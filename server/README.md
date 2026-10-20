# Servidor de IA de CREA Reader

Pequeño servicio intermedio entre la app y la API de Claude. **La clave de Anthropic vive solo aquí**, nunca dentro de la app (cualquier APK se puede abrir y extraer una clave).

Lo que hace:

- Acepta solo cuentas de Google de tu organización (valida el token de Drive que la app ya tiene; por defecto `@creanicaragua.org`).
- Tiene las instrucciones (prompts) fijas. La app solo envía contenido (un pasaje, un capítulo, un nombre de archivo), así que nadie puede usar tu clave para otra cosa.
- Limita el uso por dispositivo y por día, y tiene un **tope mensual en dólares**.
- **Se apaga solo** si Anthropic responde que no hay créditos, si se llega al tope mensual o si pones `AI_ENABLED=false`. La app lo detecta y oculta las funciones de IA; todo lo demás sigue funcionando igual.

Funciones (modelo `claude-haiku-5-5`, la más barata; ~0,10 US$ por millón de tokens de entrada y ~0,50 US$ de salida):

| Tarea | Qué hace | Límite diario por dispositivo |
|---|---|---|
| `book_info` | Autor, título limpio, resumen, categorías y nivel de un libro del catálogo (se genera una vez por libro y se comparte con todos los dispositivos) | 40 |
| `explain` | Explicar, simplificar, definir o traducir un pasaje seleccionado | 80 |
| `chapter_summary` | Resumen del capítulo que se está leyendo | 25 |
| `teacher_questions` | Preguntas de comprensión, actividades y vocabulario para docentes | 15 |

## Probarlo en tu PC (sin gastar créditos)

```bash
cd server
npm install
npm test          # pruebas automáticas (11)
npm run mock      # servidor de prueba en http://localhost:8787 con respuestas de ejemplo
```

Con la tablet conectada por USB: `adb reverse tcp:8787 tcp:8787` y compila la app con
`./gradlew assembleDebug -PaiBaseUrl=http://localhost:8787`. Las respuestas de ejemplo empiezan con «(Ejemplo)»; la app nunca las comparte ni las muestra en versiones de lanzamiento.

## Publicarlo gratis, sin tarjeta (Render)

Google Cloud exige vincular una cuenta de facturación (tarjeta) aunque este servicio quepa en el nivel gratuito. Render, en su plan gratuito, no. Lo único que se paga es el uso de Claude, y eso sale de tus créditos de Anthropic.

1. Crea una cuenta en <https://render.com> (puedes entrar con GitHub).
2. **New → Blueprint**, elige el repositorio `eReaderCREA`; Render lee `render.yaml` de la raíz del repositorio.
3. Cuando lo pida, pega la clave de Anthropic en `ANTHROPIC_API_KEY` (solo en el panel de Render).
4. Al terminar te da una dirección `https://crea-reader-ai-xxxx.onrender.com`; compila la app con `-PaiBaseUrl=` esa dirección.

Si Anthropic responde «This API key is not scoped to a workspace», añade en Render la variable `ANTHROPIC_WORKSPACE_ID` con el ID del workspace (consola de Anthropic → Workspaces), o crea la clave dentro de un workspace.

Limitaciones del plan gratuito: el servicio «duerme» tras ~15 min sin uso (la primera petición tarda ~30–60 s y la app lo trata como no disponible un momento) y los contadores viven en memoria, así que se reinician al dormir. Por eso el **tope real** son los créditos de Anthropic: fija además un límite de gasto en el workspace de la consola de Anthropic.

## Publicarlo en Google Cloud (proyecto `crea-reader`)

Necesitas `gcloud` instalado y una **clave de API de Anthropic** (consola de Anthropic → API keys; lo ideal es una clave de un *workspace* propio con límite de gasto).

```bash
gcloud config set project crea-reader
gcloud services enable run.googleapis.com cloudbuild.googleapis.com secretmanager.googleapis.com firestore.googleapis.com
gcloud firestore databases create --location=nam5          # una sola vez (guarda los contadores)

# 1) La clave, en Secret Manager (te la pedirá por teclado; no la pegues en archivos)
printf "PEGA_AQUI_LA_CLAVE" | gcloud secrets create anthropic-api-key --data-file=-

# 2) Permiso para que el servicio lea el secreto y use Firestore
PROJECT_NUMBER=$(gcloud projects describe crea-reader --format='value(projectNumber)')
SA="$PROJECT_NUMBER-compute@developer.gserviceaccount.com"
gcloud secrets add-iam-policy-binding anthropic-api-key --member="serviceAccount:$SA" --role=roles/secretmanager.secretAccessor
gcloud projects add-iam-policy-binding crea-reader --member="serviceAccount:$SA" --role=roles/datastore.user

# 3) Publicar (la autenticación la hace el propio código, por eso --allow-unauthenticated)
gcloud run deploy crea-reader-ai --source server --region us-central1 --allow-unauthenticated \
  --set-secrets ANTHROPIC_API_KEY=anthropic-api-key:latest \
  --set-env-vars ALLOWED_DOMAINS=creanicaragua.org,MONTHLY_BUDGET_USD=200,USER_DAILY_LIMIT=300
```

Al terminar muestra una dirección `https://crea-reader-ai-xxxx.run.app`. Compila la app con ella:

```powershell
./gradlew.bat assembleRelease -PsignWithDebugKey -PaiBaseUrl=https://crea-reader-ai-xxxx.run.app
```

Sin `aiBaseUrl` la app usa `https://crea-reader-ai.onrender.com`; para una versión sin IA compila con `-PaiBaseUrl=`.

## Controles

| Quiero… | Cómo |
|---|---|
| Apagar todo ya | `gcloud run services update crea-reader-ai --region us-central1 --update-env-vars AI_ENABLED=false` |
| Volver a encender | `... --update-env-vars AI_ENABLED=true` |
| Cambiar el tope mensual | `... --update-env-vars MONTHLY_BUDGET_USD=100` |
| Restringir a un cliente OAuth de la app | `... --update-env-vars ALLOWED_AUDIENCES=<ID_DE_CLIENTE_ANDROID>.apps.googleusercontent.com` |
| Usar otro modelo para docentes | `... --update-env-vars MODEL_TEACHER=claude-sonnet-5-5` (con ajuste de precios en `PRICES`) |

El gasto se calcula con los tokens que informa Anthropic y los precios de `src/service.js` (se pueden sustituir con la variable `PRICES`, un JSON `{"modelo":[entrada,salida]}` en US$ por millón de tokens). Mira también el gasto real en la consola de Anthropic y fija allí un límite del workspace como segunda barrera.

## Privacidad

Se envía al servidor solo lo que la persona pide analizar (un pasaje seleccionado, un capítulo, o el nombre y el inicio de un libro del catálogo) junto con su token de Google para comprobar la organización. El servidor no guarda ese contenido: solo contadores de uso. Anthropic procesa el texto según sus condiciones de API.

## Panel de administración (`/admin`)

Página web con el avance de lectura de las personas inscritas: resumen (lectores activos, avance medio, libros terminados, actividad de 30 días), tabla de lectores con detalle por persona, libros más leídos y exportación a CSV. Está en `public/admin/index.html` y la sirve este mismo servicio en `https://<tu-servicio>.onrender.com/admin/`.

**De dónde salen los datos.** No hay base de datos central: el panel entra con la cuenta de Google de quien lo abre y lee la carpeta compartida **«CREA Reader»** de Drive (la persona debe tener acceso a ella). Ahí cada lector que activó «Compartir mi lectura» publica su apodo y su avance por libro, así que **solo aparecen quienes comparten** (sin correo ni nombre de dispositivo). El servidor no guarda ni ve esos datos; el navegador habla directo con Drive (solo lectura).

**Configuración (una vez):**

1. En Google Cloud (proyecto `crea-reader`) → *APIs y servicios → Credenciales → Crear credenciales → ID de cliente de OAuth*, tipo **Aplicación web**. En *Orígenes autorizados de JavaScript* pon la dirección de Render (y `http://localhost:8787` para pruebas). No requiere facturación.
2. En la pantalla de consentimiento, tipo **Interno** (solo la organización) y el alcance `drive.readonly`.
3. En Render, variable `GOOGLE_CLIENT_ID` = el ID de cliente. Sin ella el panel solo ofrece los datos de ejemplo (`/admin/?demo`).

## Voz natural (ElevenLabs)

La lectura en voz alta puede usar una voz natural de ElevenLabs. Igual que con Claude, **la clave vive solo en el servidor**: la app pide el audio de cada frase a `POST /v1/voice` y, si el servidor no puede (sin clave, sin créditos, tope alcanzado, sin conexión), la app sigue leyendo esa frase con la voz del dispositivo, sin cortarse.

Variables (en el panel de Render):

| Variable | Para qué | Por defecto |
|---|---|---|
| `ELEVENLABS_API_KEY` | Clave de ElevenLabs. Sin ella la voz natural está apagada. | (vacía) |
| `ELEVENLABS_VOICE_ID` | Voz a usar (cópiala de la biblioteca de voces de ElevenLabs; elige una en español). | voz prediseñada multilingüe |
| `ELEVENLABS_MODEL` | Modelo. Flash v2.5 es el más rápido y gasta la mitad de créditos por carácter. | `eleven_flash_v2_5` |
| `VOICE_MONTHLY_CHARS` | Tope de caracteres al mes para todo el servidor. | `400000` |
| `VOICE_DEVICE_DAILY_CHARS` | Tope diario por tablet (~60 000 ≈ 1 hora de lectura). | `60000` |
| `VOICE_ENABLED` | `false` apaga la voz natural sin quitar la clave. | `true` |

Si ElevenLabs responde que no quedan créditos, el servidor deja de pedir audio durante `SUSPEND_HOURS` (6 h) y todas las tablets usan la voz del dispositivo. La app guarda el audio de cada frase (hasta 60 MB), así que repetir un pasaje no gasta créditos.
