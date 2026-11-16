package utp.edu.pe.recomendaciones.service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.ObjectMapper;

import utp.edu.pe.recomendaciones.exception.NegocioException;

/**
 * Infraestructura de envío de correo via la API HTTP de Resend (https://resend.com). La usan
 * NotificacionMedicoService (aprobación/rechazo de médicos), CredencialesMedicoService y
 * RecuperacionPasswordService (códigos de seguridad).
 *
 * Por qué HTTP y no SMTP: Render (y varios PaaS gratuitos) bloquean las conexiones salientes a los
 * puertos de SMTP (25/465/587); un correo por SMTP directo nunca llega ahí aunque las credenciales
 * estén bien. La API de Resend va por HTTPS (puerto 443), que nunca se bloquea.
 *
 * Reglas: la aplicación arranca aunque no haya clave de API (disponible() == false y enviar* falla
 * con un mensaje claro); nunca se registra la clave; el destinatario y el asunto se limpian de
 * saltos de línea.
 */
@Service
public class CorreoService {

    private static final Logger log = LoggerFactory.getLogger(CorreoService.class);
    private static final String BASE_URL = "https://api.resend.com";

    /** Resultado de probar la clave de API de Resend (sin enviar ningún correo). */
    public record Verificacion(Estado estado, String detalle) {
        public enum Estado { NO_CONFIGURADO, CONEXION_OK, ERROR }

        public boolean ok() {
            return estado == Estado.CONEXION_OK;
        }
    }

    private final RestClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final String from;
    private final String fromName;

    public CorreoService(@Value("${medicerca.resend.api-key:}") String apiKey,
                         @Value("${medicerca.mail.from:}") String from,
                         @Value("${medicerca.mail.from-name:MediCerca}") String fromName) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.from = from == null ? "" : from.trim();
        this.fromName = fromName;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().baseUrl(BASE_URL).requestFactory(factory).build();
    }

    /** true si hay clave de API y remitente: es decir, si enviar() puede intentarse. */
    public boolean disponible() {
        return motivoNoDisponible() == null;
    }

    /** Resumen sin secretos para el log: remitente configurado. */
    public String descripcion() {
        return "Resend API, remitente " + (vacio(from) ? "(sin definir)" : from);
    }

    /** Qué falta para poder enviar, o null si todo está listo. */
    public String motivoNoDisponible() {
        if (vacio(apiKey)) return "Falta la clave de la API de Resend (variable RESEND_API_KEY).";
        if (vacio(from)) return "Falta la dirección del remitente (variable MAIL_FROM).";
        return null;
    }

    /**
     * Pide la lista de dominios de la cuenta de Resend (no envía ningún correo). Sirve solo para
     * comprobar que la clave de API es válida.
     */
    public Verificacion verificarConexion() {
        String falta = motivoNoDisponible();
        if (falta != null) return new Verificacion(Verificacion.Estado.NO_CONFIGURADO, falta);
        try {
            http.get().uri("/domains")
                    .header("Authorization", "Bearer " + apiKey)
                    .retrieve().body(String.class);
            return new Verificacion(Verificacion.Estado.CONEXION_OK, "Clave de Resend válida (" + descripcion() + ").");
        } catch (RestClientResponseException e) {
            return new Verificacion(Verificacion.Estado.ERROR,
                    "Resend rechazó la clave de API (HTTP " + e.getStatusCode().value() + "): " + resumen(e));
        } catch (Exception e) {
            return new Verificacion(Verificacion.Estado.ERROR, "No se pudo contactar a Resend: " + e.getMessage());
        }
    }

    /** Envía un correo de texto plano. Lanza NegocioException si el correo no está configurado o falla el envío. */
    public void enviarTexto(String para, String asunto, String cuerpo) {
        enviar(para, asunto, cuerpo, false);
    }

    /** Envía un correo HTML. Lanza NegocioException si el correo no está configurado o falla el envío. */
    public void enviarHtml(String para, String asunto, String html) {
        enviar(para, asunto, html, true);
    }

    private void enviar(String para, String asunto, String cuerpo, boolean html) {
        String falta = motivoNoDisponible();
        if (falta != null) {
            log.warn("Correo no enviado: {}", falta);
            throw new NegocioException("El envío de correos no está disponible en este momento.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", fromName + " <" + from + ">");
        body.put("to", List.of(sinSaltos(para)));
        body.put("subject", sinSaltos(asunto));
        body.put(html ? "html" : "text", cuerpo == null ? "" : cuerpo);
        try {
            http.post().uri("/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(mapper.writeValueAsString(body))
                    .retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            log.error("No se pudo enviar el correo a {} (HTTP {}): {}", enmascarar(para), e.getStatusCode().value(), resumen(e));
            throw new NegocioException("No se pudo enviar el correo. Inténtalo de nuevo en unos minutos.");
        } catch (Exception e) {
            log.error("No se pudo enviar el correo a {}: {}", enmascarar(para), e.getMessage());
            throw new NegocioException("No se pudo enviar el correo. Inténtalo de nuevo en unos minutos.");
        }
    }

    /** El cuerpo de la respuesta de error de Resend (JSON con "message"), o el texto crudo si no se puede leer. */
    private String resumen(RestClientResponseException e) {
        try {
            return mapper.readTree(e.getResponseBodyAsString()).path("message").asText(e.getResponseBodyAsString());
        } catch (Exception ignored) {
            return e.getResponseBodyAsString();
        }
    }

    private static String sinSaltos(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n]+", " ").trim();
    }

    private static boolean vacio(String s) {
        return s == null || s.isBlank();
    }

    /** ana.perez@gmail.com → a***@gmail.com */
    static String enmascarar(String correo) {
        if (vacio(correo)) return "(sin definir)";
        int at = correo.indexOf('@');
        return at <= 0 ? "***" : correo.charAt(0) + "***" + correo.substring(at);
    }
}
