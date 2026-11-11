# Tutor de CREA Aventuras

Ruta dedicada: `POST /v1/aventuras/tutor`. Requiere `Authorization: Bearer <CREA_AVENTURAS_SERVICE_TOKEN>` y `X-Device-Id` como hash SHA-256. No utiliza ni sustituye la sesión Google de CREA Reader. Sin token configurado responde 503; con token incorrecto 401. CREA OS mantiene su propio token de tablet y hace la llamada entre servidores.

La clave `ANTHROPIC_API_KEY` y el workspace existentes permanecen en Render. Agregar el mismo secreto aleatorio de 32 bytes como `CREA_AVENTURAS_SERVICE_TOKEN` en Render y en la configuración privada de CREA OS. No escribir el secreto en Git, APK ni mensajes. Configurar esto solo al aprobar la conexión entre servidores.

La tarea `aventuras_tutor` recibe exclusivamente actividades permitidas y estadísticas agregadas, con etapa, nivel y objetivo. Rechaza ids/niveles inválidos, claves adicionales e identidad. La salida estructurada elige tres actividades distintas incluyendo lectura y matemáticas; no genera respuestas de los juegos. Se conservan los límites de uso y presupuesto de Reader. Las pruebas existentes de voz y lectura se ejecutan junto a las nuevas (`npm test`).

La activación por estudiante la gestiona CREA desde el modo facilitador de Aventuras, con autorización familiar. El usuario final puede seguir usando la ruta local ante caída del servidor, suspensión, límites o una respuesta inválida. Publicar esta versión y configurar el token es necesario: las pruebas con cliente simulado no prueban una llamada real a Claude.

## Tablets sin CREA Home (conexión directa)

Algunas tablets no tienen CREA Home. Para ellas, CREA Aventuras puede llamar a esta misma ruta directamente, con una llave distinta: `CREA_AVENTURAS_APP_TOKEN`.

- **Es una llave de bajo privilegio.** Viaja dentro del APK y se puede extraer, así que no protege nada por sí sola: lo que limita el uso es el contrato estricto de entrada y salida (solo actividades permitidas y resultados agregados, sin identidad), el límite de 30 consultas por tablet y día, el tope diario `AVENTURAS_APP_DAILY_LIMIT` (1500 por defecto) y el presupuesto mensual. Nunca uses aquí `CREA_AVENTURAS_SERVICE_TOKEN`.
- **Identidad propia** (`aventuras-app@creanicaragua.org`): sus consultas no gastan el tope de CREA OS ni al revés, y los contadores por tablet van aparte (`aventuras-app-<hash>`).
- **Activarla:** genera un valor aleatorio de 32 bytes, ponlo en Render como `CREA_AVENTURAS_APP_TOKEN` y en el archivo de configuración con el que se compila el APK de conexión directa (ver el README de CREA Aventuras). No lo escribas en Git ni en mensajes.
- **Apagarla o rotarla:** quita o cambia la variable en Render. Las tablets con la llave anterior reciben 401 y siguen con la ruta local; CREA OS no se ve afectado.
- `X-Device-Id` es el SHA-256 de un identificador al azar de la instalación (no del dispositivo ni de la persona).
