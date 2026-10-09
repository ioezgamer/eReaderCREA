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

Sin `aiBaseUrl` la app no tiene funciones de IA (no hay nada que mostrar ni que apagar).

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
