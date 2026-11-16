package utp.edu.pe.recomendaciones.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import utp.edu.pe.recomendaciones.exception.NegocioException;
import utp.edu.pe.recomendaciones.service.CorreoService.Verificacion;

/**
 * Pruebas sin red: comprueban la validación de configuración (clave de Resend, remitente) y que no
 * se filtren secretos en los mensajes de log. Enviar un correo real o verificar la clave contra la
 * API de Resend requiere red, así que eso no se cubre aquí.
 */
class CorreoServiceTest {

    @Test
    void sinClaveDeApiNoEstaDisponible() {
        CorreoService c = new CorreoService("", "avisos@medicerca.cloud", "MediCerca");
        assertThat(c.disponible()).isFalse();
        assertThat(c.motivoNoDisponible()).contains("RESEND_API_KEY");
        assertThat(c.verificarConexion().estado()).isEqualTo(Verificacion.Estado.NO_CONFIGURADO);
        assertThatThrownBy(() -> c.enviarTexto("a@b.com", "Hola", "cuerpo")).isInstanceOf(NegocioException.class);
    }

    @Test
    void sinRemitenteNoEstaDisponible() {
        CorreoService c = new CorreoService("re_123456789", "", "MediCerca");
        assertThat(c.disponible()).isFalse();
        assertThat(c.motivoNoDisponible()).contains("MAIL_FROM");
    }

    @Test
    void conClaveYRemitenteSiEstaDisponible() {
        CorreoService c = new CorreoService("re_123456789", "avisos@medicerca.cloud", "MediCerca");
        assertThat(c.disponible()).isTrue();
        assertThat(c.motivoNoDisponible()).isNull();
    }

    @Test
    void laDescripcionNoRevelaLaClaveDeApi() {
        CorreoService c = new CorreoService("re_123456789_secreta", "avisos@medicerca.cloud", "MediCerca");
        assertThat(c.descripcion()).contains("avisos@medicerca.cloud").doesNotContain("re_123456789_secreta");
    }

    @Test
    void enmascararOcultaElUsuario() {
        assertThat(CorreoService.enmascarar("ana.perez@gmail.com")).isEqualTo("a***@gmail.com");
        assertThat(CorreoService.enmascarar("")).isEqualTo("(sin definir)");
    }
}
