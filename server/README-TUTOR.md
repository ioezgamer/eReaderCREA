# Tutor de CREA Aventuras

Ruta dedicada: `POST /v1/aventuras/tutor`. Requiere `Authorization: Bearer <CREA_AVENTURAS_SERVICE_TOKEN>` y `X-Device-Id` como hash SHA-256. No utiliza ni sustituye la sesión Google de CREA Reader. Sin token configurado responde 503; con token incorrecto 401. CREA OS mantiene su propio token de tablet y hace la llamada entre servidores.

La clave `ANTHROPIC_API_KEY` y el workspace existentes permanecen en Render. Agregar el mismo secreto aleatorio de 32 bytes como `CREA_AVENTURAS_SERVICE_TOKEN` en Render y en la configuración privada de CREA OS. No escribir el secreto en Git, APK ni mensajes. Configurar esto solo al aprobar la conexión entre servidores.

La tarea `aventuras_tutor` recibe exclusivamente actividades permitidas y estadísticas agregadas, con etapa, nivel y objetivo. Rechaza ids/niveles inválidos, claves adicionales e identidad. La salida estructurada elige tres actividades distintas incluyendo lectura y matemáticas; no genera respuestas de los juegos. Se conservan los límites de uso y presupuesto de Reader. Las pruebas existentes de voz y lectura se ejecutan junto a las nuevas (`npm test`).

La activación por estudiante la gestiona CREA desde el modo facilitador de Aventuras, con autorización familiar. El usuario final puede seguir usando la ruta local ante caída del servidor, suspensión, límites o una respuesta inválida. Publicar esta versión y configurar el token es necesario: las pruebas con cliente simulado no prueban una llamada real a Claude.
