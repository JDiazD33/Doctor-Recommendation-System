# Respuestas del asistente: diagnóstico del 16 de noviembre de 2026

La revisión encontró errores reproducibles en la lógica del chat, además de una
respuesta lenta en la web publicada. Cambiar únicamente el nombre del modelo no
corrige esos errores de lógica.

## Comprobado en el código original

- `ChatRecommendationService.orient()` omitía Gemini si una regla sumaba al menos
  tres puntos o si ya había una especialidad elegida y la etapa no era `SINTOMAS`.
  Un cambio de tema podía seguir mostrando la especialidad anterior.
- La comparación tolerante a errores confundía «cómo» con «codo». Con un médico
  activo de Traumatología, «¿Cómo estás?» terminaba recomendando Traumatología.
- Una respuesta de IA con `specialty:null` perdía su `comment`, aunque ese texto
  respondiera correctamente una pregunta o pidiera aclaración. Se sustituía por
  «No logré entender bien eso» o por una coincidencia local sin relación.
- El prompt obligaba a producir una frase corta sin preguntas y a continuar hacia
  una cita. No contemplaba respuestas informativas ni especialidades no disponibles.
- Si la petición externa fallaba, el detalle quedaba solo en los logs y el paciente
  recibía reglas internas como si la IA hubiera interpretado su mensaje.
- El README indicaba `GROQ_API_KEY`, pero el servidor actual lee `AI_API_KEY`.
- Los textos de menos de cuatro letras no consultaban la IA: incluso «tos» quedaba
  limitado a reglas locales y a las especialidades con médicos activos.

## Observado en la web publicada

Se inspeccionó `https://www.medicerca.cloud/login` y su inicio. La interfaz del
asistente requiere una cuenta de paciente. Sin crear una cuenta ni reservar citas,
se hicieron dos consultas sintéticas al servicio público
`POST /api/chat/recommendations`, con el mensaje «¿Cómo estás?».

Ambas devolvieron HTTP 200 después de aproximadamente 31 segundos y el texto
«No logré entender bien eso». El catálogo activo devuelto incluía únicamente
Cardiología, Dermatología y Pediatría. Por eso el caso local de Traumatología no
puede reproducirse con los datos activos actuales de producción.

Los 31 segundos son compatibles con el timeout de lectura configurado en 30
segundos, pero no prueban por sí solos su causa. Sin los logs de esa ejecución ni
acceso a la configuración de Render no se puede confirmar si la petición a Google
agotó el tiempo, fue rechazada o tuvo otro fallo. Tampoco se ha probado una
generación real con la clave de Gemini del despliegue.

El modelo `gemini-3.8-flash` y la URL de compatibilidad utilizados existen en la
documentación oficial: [modelo](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash),
[compatibilidad con OpenAI](https://ai.google.dev/gemini-api/docs/openai).
Eso no garantiza que la clave concreta tenga acceso, cuota ni una conexión correcta.

Después se inspeccionó en Safari la sesión de administrador que el usuario ya
tenía abierta. El panel confirmó esos tres médicos activos y el inicio, aun
autenticado como administrador, no mostró el asistente de pacientes. No se
modificaron cuentas, roles, médicos ni citas.

## Corrección local

El texto libre consulta la IA configurada aunque haya coincidencias locales o una
especialidad previa. Los botones y filtros manuales conservan su flujo local. La
respuesta informativa o de aclaración de la IA se conserva sin forzar una cita ni
mostrar médicos sin relación. Las reglas quedan como respaldo cuando la IA falla,
con un aviso visible de esa limitación. Las palabras de cuatro letras necesitan
coincidencia fonética exacta para evitar el caso «cómo»/«codo».

Se añadieron pruebas con un proveedor HTTP local controlado para los cambios de
tema, síntomas negados, preguntas informativas, saludos, fechas y fallos externos.
Las seis primeras pruebas fallaron contra el código original antes de corregirlo.
Estas pruebas verifican el flujo del programa, no la calidad de respuestas reales
de Gemini. La suite completa terminó con 75 pruebas aprobadas, sin errores ni
pruebas omitidas. También se generó correctamente el paquete ejecutable para el
servidor. La corrección se entrega como propuesta de integración; no se ha
desplegado en la web pública.

La cuenta de GitHub conectada tiene permiso de lectura sobre el repositorio
original. La entrega utiliza una copia del repositorio y una propuesta hacia
`develop`, siguiendo el flujo del equipo. Esta rama de integración estaba 20
commits detrás de `main`; se incluyó su sincronización con la versión de producción
`d5c7e67` antes de añadir la corrección del asistente.

## Comprobación pendiente en Render

1. Verificar que `AI_API_KEY` contiene la clave de Gemini API y que no se está
   usando una clave de Groq. No publicar ni pegar la clave en el repositorio.
2. Revisar si `AI_MODEL` o `AI_BASE_URL` están definidos allí: esos valores
   prevalecen sobre los nuevos valores por defecto del código.
3. Repetir una consulta y buscar el log `Asistente IA no disponible`. Conservar la
   excepción completa, el código HTTP si existe y el mensaje del proveedor,
   ocultando cualquier secreto.
4. Si es `Read timed out`, investigar latencia y conectividad desde el servidor.
   Si es HTTP 4xx/5xx, resolver la causa concreta que indique Google antes de
   volver a cambiar modelo o timeout por suposición. La
   [guía oficial de errores](https://ai.google.dev/gemini-api/docs/troubleshooting)
   distingue restricciones de proyecto, credenciales, cuotas y errores transitorios.
