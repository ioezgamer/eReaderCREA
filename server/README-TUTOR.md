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

## Activación por código (versión global de Aventuras, ADR-058)

La versión de Google Play **no lleva llave ni PIN**: cualquiera puede bajar el APK. El tutor directo se enciende
en cada tablet con un **código que emite CREA**, que da un **token de instalación** atado a esa tablet y revocable.
Plan completo: `CREA-Aventuras/docs/PLAN_VERSION_GLOBAL.md`.

- **Activarla en el servidor:** pon en Render `AVENTURAS_TOKEN_SECRET` (un valor aleatorio de 32 caracteres o más;
  firma los tokens y **no va en ningún APK**). Sin él, la activación queda apagada y el servidor lo avisa al arrancar.
  Rotarlo invalida todos los tokens (las tablets tendrán que activar otra vez).
- **`POST /v1/aventuras/activate`** (sin sesión ni llave): `{"code":"ABCD-EFGH-JKMN","device":"<sha256 del ID de instalación>"}`.
  - `200 {"ok":true,"result":{"token":"v1.…","expiresAt":<segundos Unix>,"label":"Escuela X"}}`
  - `400` formato de código o de `device`; `404 code_unknown` (no existe, revocado o vencido: mismo error para no dar
    pistas); `409 code_exhausted` (ya se usó en todas las tablets permitidas); `429 rate_limited`; `503 activation_disabled`.
  - Frena los intentos por hora: por tablet (`AVENTURAS_ACTIVATE_DEVICE_HOURLY`, 10), por red
    (`AVENTURAS_ACTIVATE_IP_HOURLY`, 30; solo se cuenta una huella de la IP) y en total (`AVENTURAS_ACTIVATE_HOURLY`, 600).
  - Reactivar desde la misma tablet no gasta cupo. El token dura `AVENTURAS_TOKEN_DAYS` (90) o lo que falte al código.
- **`/tutor` y `/tutor/report`** aceptan el token (`Authorization: Bearer v1.…`) además de las llaves anteriores.
  Se comprueba la firma, que `X-Device-Id` sea el de la activación, que no haya vencido y que el código siga activo
  (se relee cada 30 s como mucho). Tope diario **por código** (todas sus tablets) con `AVENTURAS_CODE_DAILY_LIMIT` (600),
  además de 30 por tablet. Identidad propia en los contadores: `aventuras-code-<id>`.
- **Gestión de códigos** (con la sesión de Google de CREA, como los reportes):
  - `POST /v1/aventuras/codes` `{"label":"Escuela X","maxDevices":20,"days":180}` → `{"code":"ABCD-EFGH-JKMN","id":"…","expiresAt":…}`.
    **El código en claro solo se muestra aquí**: el servidor guarda un identificador derivado (HMAC), nunca el código.
  - `GET /v1/aventuras/codes` → lista con etiqueta, tablets usadas, vencimiento, estado y consultas de hoy.
  - `POST /v1/aventuras/codes/revoke` `{"id":"…"}` → los tokens de ese código dejan de valer de inmediato (como mucho 30 s).
- **Qué se guarda:** por código, etiqueta, máximo de tablets, vencimiento, estado y quién lo creó; por tablet, la
  huella de su ID de instalación y la fecha. **No** se guarda el código en claro, la IP ni nada de lo que envía el tutor.
- **Transición:** la llave antigua de la app (`CREA_AVENTURAS_APP_TOKEN`) sigue valiendo para el APK `1.12.0-directo`
  ya repartido por CREA; cuando no quede ninguno, quítala de Render.
- **Pendiente:** pantalla en el panel (`/admin/`) para crear y revocar códigos sin usar la API (fase 3 del plan).

## Reportar una sugerencia de la IA

Google Play pide que las apps con contenido generado con IA permitan reportarlo a los desarrolladores sin salir de la app. La ruta del tutor incluye texto escrito por Claude (un resumen y tres motivos), así que CREA Aventuras tiene «Reportar esta sugerencia».

- **`POST /v1/aventuras/tutor/report`**: mismas llaves y mismo `X-Device-Id` que el tutor. Cuerpo estricto: `version`, `reason` (`ofensivo`, `inadecuado_edad` o `confuso_o_erroneo`), `summary` (el resumen reportado), `choices` (1 a 3, con `id` y `reason`) y `app` (versión). Cualquier otro campo se rechaza.
- **Qué se guarda:** el texto reportado, el motivo, la versión de la app, la fecha y el canal (`app` o `service`). **No** se guarda el código de la tablet con el reporte (solo cuenta para el límite), ni perfil, apodo o resultados.
- **Límites:** 5 reportes por tablet y día (`AVENTURAS_REPORT_DEVICE_LIMIT`) y 200 al día en total (`AVENTURAS_REPORT_DAILY_LIMIT`). No gasta consultas del tutor ni presupuesto: no llama a Claude. Los cupos se reservan con un incremento atómico, así que peticiones simultáneas no pueden pasar del límite. Una reserva rechazada no se devuelve: los intentos por encima del límite siguen contando para esa tablet ese día, y un intento rechazado por el tope global también gasta un cupo de la tablet.
- **Conservación:** los reportes y el índice de cada día, 90 días en Redis; el contador por tablet, 40 días. Después se borran solos. (En Firestore no hay caducidad automática; el despliegue actual usa Redis.)
- **Cómo se revisan:** cada reporte deja una línea en el registro del servidor («Reporte de contenido del tutor de Aventuras»), y `GET /v1/aventuras/tutor/reports?day=AAAA-MM-DD` devuelve los de ese día a quien entre con la sesión de Google de CREA (la misma autenticación de CREA Reader). No hay aviso por correo: alguien tiene que mirarlos.
- **Para CREA Home:** si CREA OS reenvía los reportes de las tablets con CREA Home, llama a esta misma ruta con la llave de servicio.
