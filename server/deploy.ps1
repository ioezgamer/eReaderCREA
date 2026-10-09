<#
  Publica el servidor de IA de CREA Reader en Google Cloud (Cloud Run) y deja la dirección lista para compilar la app.
  La clave de Anthropic se pide por teclado sin mostrarse y va directa a Secret Manager: no se escribe en ningún archivo.

  Uso:  ./server/deploy.ps1                      (valores por defecto)
        ./server/deploy.ps1 -Budget 100          (tope mensual en dólares)
#>
param(
  [string]$Project = "crea-reader",
  [string]$Region = "us-central1",
  [int]$Budget = 200,
  [string]$Domain = "creanicaragua.org"
)
# gcloud escribe avisos por stderr; se revisa el código de salida de cada paso en lugar de abortar por ellos.
$ErrorActionPreference = "Continue"

function Step($text) { Write-Host "`n==> $text" -ForegroundColor Cyan }
function Fail($text) { Write-Host "`nERROR: $text" -ForegroundColor Red; exit 1 }
function Check($what) { if ($LASTEXITCODE -ne 0) { Fail "Falló: $what" } }

if (-not (Get-Command gcloud -ErrorAction SilentlyContinue)) { Fail "No se encontró gcloud. Instálalo (winget install -e --id Google.CloudSDK) y abre una terminal nueva." }
$serverDir = $PSScriptRoot

Step "Cuenta de Google Cloud"
$account = (gcloud config get-value account 2>$null)
if (-not $account -or $account -eq "(unset)") { gcloud auth login; Check "inicio de sesión"; $account = (gcloud config get-value account 2>$null) }
Write-Host "Cuenta activa: $account"
gcloud config set project $Project 2>$null | Out-Null

Step "Facturación del proyecto (Cloud Run la exige, aunque el uso de este servicio es mínimo)"
$billing = (gcloud billing projects describe $Project --format="value(billingEnabled)" 2>$null)
if ($billing -ne "True") {
  Fail "El proyecto $Project no tiene facturación activa. Actívala en https://console.cloud.google.com/billing/linkedaccount?project=$Project y vuelve a ejecutar este script."
}

Step "Activando servicios"
gcloud services enable run.googleapis.com cloudbuild.googleapis.com secretmanager.googleapis.com firestore.googleapis.com artifactregistry.googleapis.com
Check "activar servicios"

Step "Base de datos de contadores (Firestore)"
$db = (gcloud firestore databases list --format="value(name)" 2>$null)
if (-not $db) { gcloud firestore databases create --location=nam5; Check "crear Firestore" } else { Write-Host "Ya existe." }

Step "Clave de Anthropic (Secret Manager)"
$exists = (gcloud secrets list --filter="name~anthropic-api-key" --format="value(name)" 2>$null)
if ($exists) {
  $replace = Read-Host "Ya hay una clave guardada. ¿Reemplazarla? (s/N)"
  if ($replace -ne "s") { $skipKey = $true }
}
if (-not $skipKey) {
  $secure = Read-Host "Pega la clave de API de Anthropic (no se mostrará)" -AsSecureString
  $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
  if (-not $plain -or $plain.Length -lt 20) { Fail "La clave parece vacía o incompleta." }
  if ($exists) { $plain | gcloud secrets versions add anthropic-api-key --data-file=-; Check "guardar la clave" }
  else { $plain | gcloud secrets create anthropic-api-key --data-file=-; Check "guardar la clave" }
  $plain = $null
}

Step "Permisos del servicio"
$number = (gcloud projects describe $Project --format="value(projectNumber)")
$sa = "$number-compute@developer.gserviceaccount.com"
gcloud secrets add-iam-policy-binding anthropic-api-key --member="serviceAccount:$sa" --role=roles/secretmanager.secretAccessor | Out-Null
gcloud projects add-iam-policy-binding $Project --member="serviceAccount:$sa" --role=roles/datastore.user | Out-Null
gcloud projects add-iam-policy-binding $Project --member="serviceAccount:$sa" --role=roles/cloudbuild.builds.builder | Out-Null

Step "Publicando el servicio (tarda unos minutos)"
gcloud run deploy crea-reader-ai --source $serverDir --region $Region --allow-unauthenticated `
  --set-secrets "ANTHROPIC_API_KEY=anthropic-api-key:latest" `
  --set-env-vars "ALLOWED_DOMAINS=$Domain,MONTHLY_BUDGET_USD=$Budget,USER_DAILY_LIMIT=300" `
  --max-instances 3 --memory 256Mi
Check "publicar el servicio"
$url = (gcloud run services describe crea-reader-ai --region $Region --format="value(status.url)")
if (-not $url) { Fail "No se pudo obtener la dirección del servicio." }

Step "Comprobando"
try { $health = Invoke-RestMethod "$url/healthz"; Write-Host "Servicio responde: $($health | ConvertTo-Json -Compress)" } catch { Fail "El servicio no responde en $url" }

Step "Dirección para la app"
Write-Host $url -ForegroundColor Green
$gradleUser = Join-Path $env:USERPROFILE ".gradle\gradle.properties"
$line = "aiBaseUrl=$url"
if (Test-Path $gradleUser) { (Get-Content $gradleUser) | Where-Object { $_ -notmatch '^aiBaseUrl=' } | Set-Content $gradleUser }
Add-Content $gradleUser $line
Write-Host "Guardada en $gradleUser (aiBaseUrl). Las próximas compilaciones de la app ya incluyen las funciones de IA."
Write-Host "`nListo. Apagar de emergencia:  gcloud run services update crea-reader-ai --region $Region --update-env-vars AI_ENABLED=false"
