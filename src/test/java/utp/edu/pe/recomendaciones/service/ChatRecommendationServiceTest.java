package utp.edu.pe.recomendaciones.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import utp.edu.pe.recomendaciones.dto.ChatRecommendationRequest;
import utp.edu.pe.recomendaciones.model.Especialidad;
import utp.edu.pe.recomendaciones.model.Medico;
import utp.edu.pe.recomendaciones.model.Usuario;
import utp.edu.pe.recomendaciones.model.enums.EstadoUsuario;
import utp.edu.pe.recomendaciones.repository.DistritoRepository;
import utp.edu.pe.recomendaciones.repository.MedicoRepository;

/** Prueba el flujo completo con un proveedor local: no usa claves ni llama a Google. */
class ChatRecommendationServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger llamadas = new AtomicInteger();
    private final AtomicReference<JsonNode> peticion = new AtomicReference<>();
    private final MedicoRepository medicos = mock(MedicoRepository.class);
    private final DistritoRepository distritos = mock(DistritoRepository.class);
    private final DoctorProfileService perfiles = mock(DoctorProfileService.class);
    private final AsistenteMemoriaService memoria = mock(AsistenteMemoriaService.class);
    private HttpServer proveedor;
    private String respuesta;
    private int estado = 200;

    @BeforeEach
    void preparar() throws Exception {
        when(medicos.findByUsuarioEstadoOrderByRatingPromedioDesc(EstadoUsuario.ACTIVO)).thenReturn(List.of(
                medico(1L, "Dermatología"), medico(2L, "Gastroenterología"), medico(3L, "Traumatología")));
        when(distritos.findAll()).thenReturn(List.of());
        when(memoria.cargar(null)).thenReturn(Optional.empty());
        respuesta = "{\"specialty\":null,\"alternatives\":[],\"comment\":\"Puedo ayudarte a buscar un especialista.\"}";
        proveedor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proveedor.createContext("/chat/completions", exchange -> {
            llamadas.incrementAndGet();
            peticion.set(mapper.readTree(exchange.getRequestBody()));
            byte[] body = estado == 200
                    ? mapper.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", Map.of("content", respuesta)))))
                    : "{\"error\":{\"message\":\"Proveedor temporalmente no disponible\"}}".getBytes(StandardCharsets.UTF_8);
            // Sin Content-Type: se conserva la compatibilidad con respuestas crudas del proveedor.
            exchange.sendResponseHeaders(estado, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        proveedor.start();
    }

    @AfterEach
    void cerrar() {
        if (proveedor != null) proveedor.stop(0);
    }

    @Test
    void iaInterpretaElMensajeAunqueUnaReglaCoincidaConUnSintomaNegado() {
        respuesta = "{\"specialty\":\"Gastroenterología\",\"alternatives\":[],"
                + "\"comment\":\"Por el ardor después de comer, conviene orientación digestiva.\"}";

        var resultado = servicio("clave-de-prueba").recommend(pregunta(
                "No tengo acné; siento ardor después de comer", null));

        assertThat(llamadas.get()).isEqualTo(1);
        assertThat(resultado.specialty()).isEqualTo("Gastroenterología");
        assertThat(resultado.reply()).doesNotContain("mencionaste: acne");
        assertThat(peticion.get().path("reasoning_effort").asText()).isEqualTo("low");
    }

    @Test
    void consultaIaAlCambiarDeTemaAunqueYaHayaEspecialidadElegida() {
        respuesta = "{\"specialty\":\"Gastroenterología\",\"alternatives\":[],"
                + "\"comment\":\"Te ayudo a buscar atención para ese nuevo malestar.\"}";

        var resultado = servicio("clave-de-prueba").recommend(pregunta(
                "Ahora siento ardor después de comer", "Dermatología"));

        assertThat(llamadas.get()).isEqualTo(1);
        assertThat(resultado.specialty()).isEqualTo("Gastroenterología");
    }

    @Test
    void conservaLaRespuestaDeIaCuandoNoProponeEspecialidad() {
        respuesta = "{\"specialty\":null,\"alternatives\":[],"
                + "\"comment\":\"Puedo ayudarte a elegir un especialista y buscar médicos disponibles.\"}";

        var resultado = servicio("clave-de-prueba").recommend(pregunta("¿Qué puedes hacer?", null));

        assertThat(resultado.reply()).isEqualTo("Puedo ayudarte a elegir un especialista y buscar médicos disponibles.");
        assertThat(resultado.specialty()).isNull();
        assertThat(resultado.suggestions()).isEmpty();
        assertThat(resultado.doctors()).isEmpty();
        assertThat(resultado.missing()).isEmpty();
    }

    @Test
    void sintomasCortosTambienSeConsultanAunqueNoHayaEspecialidadAdecuada() {
        respuesta = "{\"specialty\":null,\"alternatives\":[],"
                + "\"comment\":\"No hay una especialidad adecuada disponible en el catálogo actual para orientar esa consulta.\"}";

        var resultado = servicio("clave-de-prueba").recommend(pregunta("tos", null));

        assertThat(llamadas.get()).isEqualTo(1);
        assertThat(resultado.reply()).contains("No hay una especialidad adecuada");
        assertThat(resultado.specialty()).isNull();
        assertThat(resultado.doctors()).isEmpty();
    }

    @Test
    void preguntarPorUnaEspecialidadNoEquivaleAPedirUnaCita() {
        respuesta = "{\"specialty\":null,\"alternatives\":[],"
                + "\"comment\":\"Dermatología atiende problemas de la piel, el cabello y las uñas.\"}";

        var resultado = servicio("clave-de-prueba").recommend(pregunta("¿Qué atiende Dermatología?", null));

        assertThat(llamadas.get()).isEqualTo(1);
        assertThat(resultado.reply()).isEqualTo("Dermatología atiende problemas de la piel, el cabello y las uñas.");
        assertThat(resultado.specialty()).isNull();
        assertThat(resultado.doctors()).isEmpty();
    }

    @Test
    void sigueAplicandoFechasSinInventarUnaRespuestaDeDisponibilidad() {
        respuesta = "{\"specialty\":null,\"alternatives\":[],\"comment\":null}";

        var resultado = servicio("clave-de-prueba").recommend(pregunta("mañana", "Dermatología"));

        assertThat(resultado.date()).isEqualTo(LocalDate.now(ZoneId.of("America/Lima")).plusDays(1));
        assertThat(resultado.specialty()).isEqualTo("Dermatología");
        assertThat(resultado.reply()).contains("No encontré médicos", "horario libre");
    }

    @Test
    void informaDeLaFallaDelProveedorSinCulparAlPaciente() {
        estado = 503;

        var resultado = servicio("clave-de-prueba").recommend(pregunta("¿Qué puedes hacer?", null));

        assertThat(resultado.reply()).contains("No pude consultar", "volver a intentarlo");
        assertThat(resultado.reply()).doesNotContain("No logré entender");
        assertThat(resultado.specialty()).isNull();
        assertThat(resultado.doctors()).isEmpty();
    }

    @Test
    void unSaludoNoSeConvierteEnUnaConsultaPorElCodoSinIa() {
        var resultado = servicio("").recommend(pregunta("¿Cómo estás?", null));

        assertThat(resultado.specialty()).isNull();
        assertThat(resultado.suggestions()).isEmpty();
        assertThat(resultado.reply()).doesNotContain("Traumatología", "codo");
        assertThat(llamadas.get()).isZero();
    }

    @Test
    void unaRespuestaSinEspecialidadNoReutilizaUnaCoincidenciaDebil() {
        respuesta = "{\"specialty\":null,\"alternatives\":[],\"comment\":\"¡Hola! ¿En qué puedo ayudarte?\"}";

        var resultado = servicio("clave-de-prueba").recommend(pregunta("¿Cómo estás?", null));

        assertThat(resultado.reply()).isEqualTo("¡Hola! ¿En qué puedo ayudarte?");
        assertThat(resultado.specialty()).isNull();
    }

    @Test
    void siElProveedorFallaConservaLasReglasYExplicaLaLimitacion() {
        estado = 503;

        var resultado = servicio("clave-de-prueba").recommend(pregunta("Tengo acné y granos", null));

        assertThat(llamadas.get()).isEqualTo(1);
        assertThat(resultado.specialty()).isEqualTo("Dermatología");
        assertThat(resultado.reply()).contains("No pude consultar", "Dermatología");
    }

    private ChatRecommendationService servicio(String clave) {
        return new ChatRecommendationService(medicos, distritos, perfiles, memoria, clave,
                "http://127.0.0.1:" + proveedor.getAddress().getPort(), "modelo-de-prueba", "", "low", 5);
    }

    private ChatRecommendationRequest pregunta(String texto, String especialidad) {
        return new ChatRecommendationRequest(texto, especialidad, null, null, null, true, List.of(), null, null);
    }

    private Medico medico(long id, String nombreEspecialidad) {
        Especialidad especialidad = new Especialidad();
        especialidad.setNombre(nombreEspecialidad);
        Usuario usuario = new Usuario();
        usuario.setNombres("Médico");
        usuario.setApellidos("de prueba");
        Medico medico = new Medico();
        medico.setId(id);
        medico.setUsuario(usuario);
        medico.setEspecialidad(especialidad);
        return medico;
    }
}
