# CREA Reader iOS 0.9.1 beta

Aplicación nativa para iPhone y iPad (iOS 17 o superior). Código Android intacto. La aplicación iOS se compila mediante GitHub Actions en macOS y se distribuye como **IPA sin firmar** para instalar después con un firmador compatible como FlareStore.

## Funciones implementadas en código

- SwiftUI: Biblioteca, Catálogo en línea, Explorar, Comunidad y Ajustes.
- Archivos: importación local EPUB/PDF desde Archivos e iCloud Drive, eliminación y favoritos.
- Lectores: EPUB con Readium Swift 3.8; PDF con PDFKit; lectura local sin conexión.
- Portadas: extracción del EPUB, miniatura primera página del PDF, miniatura Google Drive cuando esté disponible.
- EPUB: porcentaje real, locator Readium guardado/restaurado, controles persistentes de tamaño de fuente, modo oscuro y desplazamiento vertical.
- Biblioteca: cuadrícula/lista, filtros, búsqueda, progreso, continuar leyendo y colecciones locales creadas desde el menú de cada libro.
- Google: inicio/cierre de sesión con OAuth iOS; catálogo Drive por subcarpetas/categorías, búsqueda, caché de metadatos, descarga individual y distinción descargado/en línea. **No descarga automáticamente todos los libros.**
- Comunidad: lectura de perfiles voluntariamente publicados por Android (`crea-lector-*.json`). No publica lecturas de iOS por defecto.
- Progreso Android/iOS: lectura del manifiesto privado `crea-reader-library-v2.json` mediante `drive.appdata`; vincula libros por Drive file ID o SHA-256 idéntico, aplica el timestamp más reciente y recupera el locator EPUB. Al cerrar un EPUB envía posiciones más recientes a ese mismo manifiesto, conservando los demás registros JSON y la lista de dispositivos.
- Catálogo muestra si un libro está descargado, su avance conocido y permite abrirlo sin descargar duplicados cuando se vincula con Drive.

**Estado de calidad:** implementación en desarrollo; los cambios recientes aún necesitan superar una compilación completa de Actions y pruebas en un iPhone y una tablet Android. Que el proyecto compile no demuestra la sincronización completa.

## Configuración de Google

El cliente OAuth para iOS y su esquema URL ya están registrados en `project.yml`, con bundle ID `org.crea.reader.ios`. Son identificadores públicos, no secretos de autenticación. La app obtiene tokens mediante Google Sign-In y no los coloca en el repositorio.

Permisos solicitados:
- `drive.readonly`: listar catálogo y descargar archivos seleccionados.
- `drive.appdata`: leer/escribir el progreso privado de esta aplicación.

Las sesiones antiguas podrían tener solo `drive.readonly`. Desde **Ajustes > Autorizar sincronización de progreso**, conceder el nuevo permiso. Google puede requerir consentimiento adicional por política de Workspace.

**Advertencia:** Google solo permite leer `appDataFolder` de la misma aplicación OAuth. El cliente iOS debe pertenecer al proyecto de Google Cloud de la app Android que creó ese manifiesto. Si son aplicaciones/proyectos diferentes, el manifiesto no será visible aunque el catálogo sí funcione.

## Comprobar sincronización de progreso

1. Android: abre y avanza un EPUB, realiza su sincronización normal y confirma el porcentaje.
2. iOS: inicia sesión con la misma cuenta, autoriza progreso privado en Ajustes y pulsa **Sincronizar progreso Android y iOS**.
3. Abre el mismo EPUB descargado, confirma el porcentaje y la ubicación.
4. Avanza el EPUB y regresa a la biblioteca. iOS guarda la posición y, con autorización, envía sus cambios.
5. Android: sincroniza y verifica la nueva posición.
6. Comprueba que no desaparecieron otros libros, dispositivos ni lecturas. Prueba también el modo sin conexión.

Si no existe manifiesto de Android, iOS **no crea una copia vacía** que pueda ocultar el progreso del otro dispositivo. Los libros solo se relacionan automáticamente cuando coinciden el identificador de Drive o el SHA-256.

**Riesgo pendiente:** la actualización del manifiesto compartido usa lectura, mezcla y escritura, pero no incluye una transacción atómica entre Android e iOS. No se recomienda editar el progreso en ambos dispositivos exactamente al mismo tiempo hasta añadir control de versiones/concurrencia y pruebas de conflicto.

## Funciones Android aún pendientes de portabilidad completa

- Lectura EPUB remota en línea con caché temporal sin añadir el libro a la biblioteca.
- Subida a Drive de libros importados directamente en iOS y reconciliación completa del catálogo.
- Publicación voluntaria de la actividad de iOS en Comunidad, con permisos específicos y revocación.
- Sincronización de colecciones locales con Android, marcadores, resaltados, notas, búsqueda de texto y TTS.
- Funciones de IA y todos los elementos visuales de Android; pruebas de paridad pantalla por pantalla.
- Verificación real de carga de imágenes de portada con EPUB grandes, fallos de red y de permisos, y pruebas de sincronización concurrente.

## Compilar, descargar e instalar sin Mac

GitHub Actions: [iOS unsigned IPA (FlareStore)](https://github.com/ioezgamer/eReaderCREA/actions/workflows/ios-unsigned-ipa.yml). Cada push a esta rama ejecuta la compilación en macOS. Ejecuciones obsoletas se cancelan para evitar gastar minutos en versiones viejas.

En una ejecución correcta, el archivo `CREA-Reader-iOS-0.9.1-unsigned.ipa` se publica automáticamente como prerelease en [GitHub Releases](https://github.com/ioezgamer/eReaderCREA/releases). **No se instala sin una firma válida**.

Una X roja significa que ese commit no generó una IPA válida. Los logs de compilación también se adjuntan como `ios-build-errors` al fallar el compilador.

## Diagnóstico de compilación
El workflow ejecuta una comprobación de sintaxis Swift antes de Xcode y publica un estado `crea-ios-ipa` sobre el commit. Esto permite consultar el resultado real desde GitHub, sin confundir cambios de código con una IPA verificada. Si falla, revisar el artefacto `ios-build-errors` o el registro del paso Swift/Xcode del workflow.

## 0.9.1 beta: Catálogo visual y Comunidad

- El catálogo busca miniaturas de Google Drive y las almacena en caché privada. Si Drive no incluye miniatura, para EPUB intenta leer solamente los rangos HTTP del ZIP necesarios para la portada (contenedor, OPF e imagen). No añade el EPUB completo a Mi biblioteca. Algunos EPUB sin portada reconocible, ZIP64 o con servidor que no acepte Range pueden seguir mostrando el icono genérico.
- Filtros de catálogo: Todos, Sin descargar, Descargados, Leyendo, Terminados, categorías y búsqueda. Solo se descargan libros por una acción explícita del lector.
- Comunidad muestra los libros más leídos por participantes y permite **compartir voluntariamente** desde iOS, con un apodo de 2-40 caracteres. Compartir está desactivado por defecto.
- Para publicar archivos de comunidad se requiere **un permiso adicional y sensible de Drive** (`https://www.googleapis.com/auth/drive`). La app pide autorización solo cuando se pulsa «Autorizar compartir con Comunidad». Se conserva el catálogo de solo lectura sin él.
- Al activar, el perfil `crea-lector-{deviceId}.json` incluye apodo, ID de instalación, los libros vinculados a Drive y sus porcentajes. Cada cambio de posición EPUB actualiza las lecturas compartidas mientras esté habilitado. Al dejar de compartir se sobrescribe el perfil con `active=false` y sin lecturas. Si Drive falla, se muestra un error y no se afirma haber dejado de compartir.
- No se publica automáticamente correo electrónico, ubicación ni libros sin identificar en Drive.
- La tabla de popularidad representa solo lectores voluntarios de esa carpeta, no toda la comunidad de CREA.

**Limitaciones:** la subida de perfiles depende de que el administrador de Google Workspace permita el nuevo alcance OAuth; el algoritmo remoto de portadas y el flujo de revocación necesitan pruebas reales. El permiso Drive completo es sensible y puede requerir verificación para proyectos con usuarios externos.
