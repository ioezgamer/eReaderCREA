# CREA Reader

Lector Android local-first para EPUB y PDF con biblioteca, búsqueda, progreso de lectura y sincronización automática entre dispositivos mediante Google Drive.

La aplicación está inspirada en los flujos observables de la APK de referencia, pero es una implementación nueva. No reutiliza código, recursos ni componentes propietarios de ReadEra. El motor de lectura se basa en el proyecto abierto Readium Kotlin Toolkit 3.4.0, cuya licencia BSD se conserva en `THIRD_PARTY_READIUM_LICENSE`.

## Funciones implementadas

- Importación desde el selector de archivos de Android y desde **Abrir con/Compartir**.
- Lectura real de EPUB y PDF, índice, búsqueda, marcadores, resaltados/notas y texto a voz según el formato.
- **Catálogo en línea**: todos los libros de la carpeta «CREA Reader» de Drive (también los de subcarpetas, que actúan como categorías) con portada, título, autor, formato y tamaño, sin descargarlos. Búsqueda sin distinguir mayúsculas ni tildes, filtros (Todos, Sin descargar, Descargados, EPUB, PDF y categorías) y orden. Se guarda una copia local de los metadatos: sin conexión se sigue viendo, con un aviso de que puede estar desactualizada.
- **Comunidad**: pestaña opcional «Qué leen los demás» con recomendaciones, lecturas recientes y lo más leído entre quienes eligen compartir (solo apodo y avance por libro).
- **Descargas en segundo plano, selección múltiple, actualizaciones de libros y colecciones propias.**
- **Vistas y filtros**: lista, cuadrícula o solo portadas en tres tamaños (biblioteca y catálogo), favoritos y filtros por estado, formato, marcadores, notas, Drive y novedades.
- **Portadas y lectura por dispositivo**: los EPUB sin miniatura obtienen su portada con solicitudes parciales al archivo (sin descargarlo) y las tarjetas muestran el avance general y el de cada dispositivo de la misma cuenta.
- **Leer en línea**: abre un libro del catálogo sin agregarlo a Mi biblioteca (copia temporal en la caché privada de la app, con limpieza automática) y conserva la posición y los marcadores.
- **Descargar a Mi biblioteca**: descarga manual con progreso, cancelación, comprobación de espacio libre y de integridad (MD5 de Drive) y sin duplicados. Es la única forma de que un libro de Drive llegue al dispositivo.
- **Mi biblioteca**: solo los libros descargados o importados, disponibles sin conexión, con espacio usado, orden por título, fecha de descarga o última lectura, y eliminación que solo borra la copia local (el archivo de Drive nunca se toca y el libro sigue en el catálogo).
- Biblioteca adaptable a teléfonos y tablets con portadas, autor, formato, progreso, búsqueda y filtros.
- Los libros importados desde el dispositivo se suben a la carpeta de Drive; el progreso de lectura viaja entre dispositivos mediante un manifiesto privado.
- Detección de duplicados e integridad mediante SHA-256/MD5 del contenido.
- La sincronización del catálogo nunca descarga libros: solo lee metadatos (paginados) al abrir la app si pasaron más de 5 minutos, al deslizar para refrescar y cada seis horas en segundo plano.
- Biblioteca con tarjeta «Continuar leyendo», búsqueda, filtros (Todos, Leyendo, Sin leer, Terminados, EPUB, PDF), orden configurable, importación de varios archivos a la vez y estado de Drive siempre visible.
- Explorar con catálogos gratuitos en español (textos.info y Proyecto Gutenberg filtrado por español) y flecha de regreso dentro del libro.
- Interfaz en español con estilo «vidrio líquido» (superficies translúcidas, fondo degradado y barra de navegación flotante), tema claro/oscuro con la paleta del logo (azul, rosa, naranja y verde) y controles Material con objetivos táctiles de al menos 48 dp.

En esta primera versión los marcadores y resaltados se almacenan localmente, pero todavía no viajan en el manifiesto de sincronización.

## Preparación de Google Drive

El acceso a Drive requiere una identidad OAuth Android asociada a la firma con la que se instala la APK:

1. Crear o seleccionar un proyecto en Google Cloud Console.
2. Habilitar **Google Drive API**.
3. Configurar la pantalla de consentimiento OAuth.
4. Crear una credencial **OAuth client ID > Android**.
5. Usar el paquete `org.crea.reader` y el SHA-1 de la firma de la APK.
6. Si la pantalla está en modo de pruebas, añadir las cuentas Google que probarán la app.

La APK de depuración entregada usa el SHA-1 `AF:35:20:BD:BB:0F:59:C5:A3:9C:65:B5:13:C3:9F:E9:B4:CB:D5:1B`.

Para obtener el SHA-1 de la compilación de depuración:

```powershell
./gradlew.bat signingReport
```

Configuración ya creada en Google Cloud (cuenta ezequiel@creanicaragua.org, organización creanicaragua.org):

- Proyecto `crea-reader` ("CREA Reader") con Google Drive API habilitada.
- Google Auth Platform con audiencia **Interna**: solo cuentas `@creanicaragua.org` pueden autorizar la app, sin verificación de Google. Para abrirla a cualquier cuenta hay que cambiarla a Externa.
- Alcance `drive.appdata` (Google lo clasifica como no sensible).
- Cliente OAuth Android "CREA Reader Android (debug)" con paquete `org.crea.reader` y el SHA-1 de depuración indicado arriba. Los cambios pueden tardar de 5 minutos a unas horas en aplicarse.
- Para una APK de release hay que crear otro cliente Android con el SHA-1 del keystore de release (y, si se publica en Play Store, el de la firma de Play App Signing).

No se necesita `google-services.json`. La app pide dos alcances mediante Google Identity Services:

- `https://www.googleapis.com/auth/drive`: **necesario para ver los EPUB/PDF que se suben a mano a la carpeta «CREA Reader»**. Con `drive.file` la app solo vería archivos que ella misma creó. Es un alcance restringido de Google: funciona sin verificación mientras la audiencia del proyecto sea **Interna** (cuentas `@creanicaragua.org`); para publicarla a cualquier cuenta Google habría que pasar la verificación de seguridad de Google.
- `https://www.googleapis.com/auth/drive.appdata`: guarda en un espacio privado de la app el progreso de lectura y las eliminaciones.

Ambos deben aparecer en *Google Auth Platform > Acceso a los datos* del proyecto. La app solo lee y escribe dentro de la carpeta «CREA Reader».

## Compilar y verificar

Requisitos: JDK 21 y Android SDK con plataforma 37.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\openjdk\jdk-21.0.8'
$env:ANDROID_HOME = 'C:\Android\sdk'
./gradlew.bat testDebugUnitTest assembleDebug
```

El APK se genera en `app/build/outputs/apk/debug/app-debug.apk`. Los tests cubren conflictos de sincronización, descarga/subida, progreso más reciente, eliminaciones, reimportaciones y nombres de archivo en Drive.

APK optimizada entregada (versión final, sin depuración): `dist/CREA-Reader-0.5.0.apk` (26,635,027 bytes), SHA-256 `0C048DF3423949BB1E54DC1E798466DC404315BD58AB67757D28BCA9B9D10E8B`.

Para generarla: `./gradlew.bat assembleRelease -PsignWithDebugKey` (salida en `app/build/outputs/apk/release/app-release.apk`). Usa R8 para reducir y ofuscar el código y quitar recursos sin uso, y no es depurable. Con `-PsignWithDebugKey` se firma con la clave de depuración: se instala encima de las versiones de prueba anteriores y la credencial OAuth de Drive ya registrada sigue funcionando. Sin ese parámetro se firma con la clave de subida (ver abajo) y es el binario que corresponde a Google Play.

## Publicación en Google Play (distribución privada)

Bundle para la tienda: `dist/CREA-Reader-0.5.0.aab` (24,337,446 bytes), SHA-256 `40F31A546D316BB9297F2723450D996A3F37127639D979B8D4C4A7FF674FF974`. Se genera con `./gradlew.bat bundleRelease` (salida en `app/build/outputs/bundle/release/app-release.aab`).

**Clave de subida (upload key).** El AAB está firmado con `keystore/crea-reader-upload.jks` (alias `crea-reader-upload`), cuyos datos de acceso están en `keystore.properties`. Ambos archivos están excluidos del control de versiones.

- Huella SHA-1 de la clave de subida: `D8:D8:4A:40:5C:80:53:D9:74:3D:7B:DF:C9:1A:2D:ED:32:54:F8:0C`
- **Haz una copia de seguridad de `keystore/` y `keystore.properties` en un lugar seguro y privado.** Si se pierden, solo se puede recuperar la clave de subida pidiéndolo a Google desde Play Console.
- Aumenta `versionCode` en `app/build.gradle.kts` en cada versión que subas.

**Material de la tienda (carpeta `store/`).** `ficha-google-play.md` (textos y respuestas de los formularios), `politica-de-privacidad.md` y `politica-de-privacidad.html` (lista para publicar en una web), `icono-512.png` y `grafico-funcion-1024x500.png`. Reemplaza los marcadores `[CORREO DE CONTACTO]`, `[SITIO WEB]` y la URL de la política antes de enviarlos.

**Pasos en Play Console**

1. Crear la cuenta de desarrollador de organización y la app (nombre «CREA Reader», idioma español, gratuita, identificador `org.crea.reader`).
2. Activar *Play App Signing* al subir el primer AAB (es lo predeterminado).
3. Publicar como **app privada**: *Todas las apps > Crear app > Publicar de forma privada* (Google Play administrado de la organización Google Workspace) o usar una pista de pruebas internas o cerradas con la lista de correos `@creanicaragua.org`.
4. Completar la ficha, la política de privacidad, *Seguridad de los datos* y la clasificación de contenido.
5. En *Integridad de la app > Firma de apps* copiar la huella SHA-1 del **certificado de firma de la app** y registrarla en Google Cloud (*Google Auth Platform > Clientes*) como un cliente OAuth Android nuevo con el paquete `org.crea.reader`. Sin este paso la sincronización con Drive no funciona en la versión de la tienda, porque Google vuelve a firmar la app con su propia clave.

## Diseño de la sincronización

La carpeta «CREA Reader» de Drive es la fuente de verdad de **qué libros existen**. Hay dos conceptos separados:

- **Catálogo en línea** (tabla Room `drive_catalog`): metadatos de cada archivo de la carpeta. Se actualiza solo y nunca contiene el libro.
- **Mi biblioteca** (tabla `books`): los libros que el usuario descargó o importó. Un libro del catálogo está «descargado» cuando existe un libro local con su `remote_file_id` (o con el mismo SHA-256 si lo subió esta app).

Cada sincronización:

1. Lista la carpeta y sus subcarpetas con paginación (1000 por página) y solo los campos necesarios. Si el listado falla, no se toca nada local, así que un corte de red nunca vacía el catálogo.
2. Compara por id de Drive y escribe únicamente lo nuevo, modificado o eliminado (un solo cambio atómico en Room).
3. Descarga las miniaturas que Drive genera (normalmente PDF) para los libros nuevos o modificados que no están en el dispositivo, con un máximo por sincronización. El token solo se envía a hosts de Google y no se guarda en ninguna parte.
4. Sube los libros importados localmente que aún no están en Drive (si el mismo archivo ya está, se enlaza por MD5 en lugar de duplicarlo) y aplica el progreso de lectura más reciente desde el manifiesto JSON (`crea-reader-library-v2.json`, en el espacio privado `appDataFolder`). Solo se reescribe si cambió.

La sincronización **no descarga libros, no borra libros locales y no modifica ni elimina archivos de Drive**. Si un archivo desaparece de Drive se quita del catálogo, pero la copia ya descargada se conserva. Eliminar un libro de Mi biblioteca solo borra la copia local; su posición de lectura y sus marcadores pasan a la entrada del catálogo y vuelven si se descarga otra vez (los resaltados y notas dependen del libro local y se eliminan con él).

La descarga completa solo ocurre por una acción explícita del usuario (`OnlineBookManager`): «Descargar» la guarda en el almacenamiento privado de la app; «Leer en línea» la guarda en `cache/online-reading` (se borra tras 24 h sin usarse o al superar 400 MB, empezando por las más antiguas, y nunca mientras está abierta). Readium necesita el archivo completo para abrir un EPUB o PDF, por eso la lectura en línea usa una copia temporal en vez de solicitudes parciales. Los libros abiertos en línea usan un id de lector negativo derivado del id de Drive para guardar posición y marcadores sin crear una fila en Mi biblioteca.

Desconectar Drive no borra los libros locales ni los de Drive. Migración de base de datos 1 → 2: solo añade la tabla `drive_catalog`; los libros ya descargados permanecen en Mi biblioteca. La tabla `sync_tombstones` queda como legado sin uso (antes propagaba los borrados a Drive).

Migración desde 0.1.0: los enlaces antiguos a `appDataFolder` se descartan una sola vez y los libros se suben a la carpeta visible.

## Estado de validación

- Compilación debug: correcta.
- Tests JVM de sincronización: correctos.
- Android Lint: 0 errores; permanecen avisos no bloqueantes, principalmente actualizaciones de dependencias y código heredado del ejemplo abierto de Readium.
- Firma APK Signature Scheme v2: verificada.
- Inspección del APK: paquete, SDK, arquitecturas y actividad principal verificados.
- No había emulador ni dispositivo ADB conectado durante esta entrega, por lo que la interacción visual y OAuth real deben comprobarse en una tablet/teléfono con la credencial Google registrada.
