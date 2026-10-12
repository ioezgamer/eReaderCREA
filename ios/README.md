# CREA Reader iOS 0.9.5 beta

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

- Evaluación en dispositivo de la nueva lectura temporal en línea y vistas previas de EPUB/PDF.
- Subida a Drive de libros importados directamente en iOS y reconciliación completa del catálogo.
- Publicación voluntaria de la actividad de iOS en Comunidad, con permisos específicos y revocación.
- Sincronización de colecciones locales con Android, marcadores, resaltados, notas, búsqueda de texto y TTS.
- Funciones de IA y todos los elementos visuales de Android; pruebas de paridad pantalla por pantalla.
- Verificación real de carga de imágenes de portada con EPUB grandes, fallos de red y de permisos, y pruebas de sincronización concurrente.

## Compilar, descargar e instalar sin Mac

GitHub Actions: [iOS unsigned IPA (FlareStore)](https://github.com/ioezgamer/eReaderCREA/actions/workflows/ios-unsigned-ipa.yml). Cada push a esta rama ejecuta la compilación en macOS. Ejecuciones obsoletas se cancelan para evitar gastar minutos en versiones viejas.

En una ejecución correcta, el archivo `CREA-Reader-iOS-0.9.5-unsigned.ipa` se publica automáticamente como prerelease en [GitHub Releases](https://github.com/ioezgamer/eReaderCREA/releases). **No se instala sin una firma válida**.

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

## 0.9.4 beta: Información, muestra y lectura en línea

Al tocar la portada o **Vista previa y ficha** desde Catálogo, se abre una ficha con:
- Portada, formato, tamaño, título/autor/editorial desde metadatos reales (OPF de EPUB, PDFKit o descripción de Drive).
- Sinopsis auténtica si existe en el paquete EPUB, asunto del PDF o descripción de Drive. Si no existe, se muestra claramente «sinopsis no disponible»; no se inventan sinopsis.
- Número exacto de páginas PDF mediante PDFKit (para PDF de hasta 15 MB se carga al abrir la ficha; para mayores, se solicita al abrir la muestra). En EPUB solo se informa número de páginas cuando la edición declara un dato editorial explícito; el resto indica que la paginación depende del lector.
- Botón **Vista previa**: primeras 3 páginas para PDF, primeras secciones EPUB auténticas cuando puede extraerse texto mediante solicitudes HTTP Range.
- Botón **Leer en línea**: copia temporal autenticada desde Drive, sin agregar a Mi biblioteca; sirve en el lector nativo Readium/PDFKit. Requiere conexión inicial; el archivo temporal se borra al salir y se limpian copias huérfanas al reiniciar la app.
- Botón **Descargar**: usa el flujo existente para guardar una copia permanente en Mi biblioteca.
- Un archivo EPUB remoto con ZIP64, metadatos ausentes o extracción no compatible puede carecer de muestra o sinopsis aunque la lectura temporal continúe funcionando. Los PDF cifrados también pueden impedir la muestra. Los detalles tienen un límite de tamaño de 250 MB para lectura temporal y deben probarse en dispositivos físicos.

**Desde 0.9.5:** la lectura en línea guarda el porcentaje y, para EPUB, la posición exacta (locator) por cuenta Google e ID de Drive. Al descargar, se recupera esa posición salvo que haya una lectura local más reciente. La sincronización con Android requiere autorizar `drive.appdata` en Ajustes y probarla en dispositivos físicos.

## 0.9.4 beta: integración Claude de Android en iOS

La versión iOS utiliza **el mismo servidor de IA que Android**, definido en `server/`; la app no incluye ni solicita una clave de Anthropic. Las peticiones se envían con el token OAuth de Google y la cabecera `X-Device-Id` al servicio intermedio de CREA. Los prompts, límites diarios, presupuesto mensual y modelo elegido siguen configurados en el servidor.

Funciones portadas:
- Selecciona un fragmento EPUB y pulsa **Claude** en la barra de lectura para explicar, simplificar, traducir o definir. La app captura la selección antes de presentar el panel.
- Desde **Claude → Resumen**, obtiene el texto del archivo XHTML del capítulo actual y lo resume. Solo disponible cuando el recurso contiene texto legible de al menos 200 caracteres.
- Desde **Claude → Docentes**, genera preguntas literales, inferenciales y críticas, actividades y vocabulario a partir del capítulo actual, con grado opcional.
- En **Catálogo → Ficha de libro**, se puede pulsar **Crear sinopsis con Claude** para obtener un título sugerido, autor, categorías, nivel y resumen. La ficha generada aparece diferenciada de los metadatos reales del libro y no se sobrescriben los originales.

Para activar:
1. Publica o reutiliza el servidor Node.js de `server/` en Render o Cloud Run. Introduce `ANTHROPIC_API_KEY` **solo en las variables secretas del servidor**. La IPA nunca incorpora esa clave.
2. Verifica que `https://URL_DEL_SERVIDOR/healthz` responda y que las cuentas permitidas estén definidas en `ALLOWED_DOMAINS`.
3. En iOS, abre **Ajustes → Asistente de IA con Claude**; introduce el dominio HTTPS del servidor y activa **Asistente de Claude**. Se aceptan `ai.creanicaragua.org`, `crea-reader-ai[-sufijo].onrender.com` o `crea-reader-ai[-sufijo].run.app`. Cualquier otro dominio debe ser revisado explícitamente antes de enviar tokens Google.
4. Inicia sesión con Google y pulsa **Comprobar conexión con Claude**. El servidor debe permitir el dominio `@creanicaragua.org`. Si se configuró `ALLOWED_AUDIENCES`, añade también el cliente OAuth iOS `101673121950-e6jb7e03imhbts9dc98em5gof37ebkn8.apps.googleusercontent.com`, además del Android. De otro modo el servidor rechazará el token iOS con 401.
5. Abre un EPUB de texto y prueba las acciones de lectura o abre una ficha del catálogo.

Políticas de datos: las opciones de IA están desactivadas por defecto; no se manda texto a Claude hasta que el usuario activa la función y pulsa **Consultar Claude** o **Crear sinopsis con Claude**. La app no conserva localmente resultados por defecto y el lector continúa funcionando si el servidor no responde. El progreso, Drive y la comunidad no requieren IA. Para mostrar botones de Claude, `GET /v1/status` debe devolver `available: true`; si se agotan cuotas o créditos, los botones no se activan.

Limitaciones y pruebas pendientes:
- Los EPUB cifrados, las imágenes y los PDF escaneados no garantizan extracción de texto del capítulo.
- La navegación de capítulos utiliza el recurso XHTML actual, no adivina divisiones literarias en libros mal estructurados.
- Una compilación iOS exitosa no prueba que el servidor esté desplegado, que los permisos OAuth sean correctos ni que queden créditos de Claude. Validar con una cuenta real de CREA y el servidor configurado.

## 0.9.5 beta: continuidad de la lectura

- Los EPUB temporales guardan su locator Readium por ID del libro Drive, sin depender del nombre temporal.
- Los PDF conservan el porcentaje y recuperan aproximadamente la página.
- Tras descargar un libro leído en línea, se promueve el avance y el locator a Mi biblioteca, respetando una posición offline más reciente.
- Los avances se persisten en el dispositivo. Con permiso de Google Drive App Data pueden sincronizarse con el manifiesto Android, sin borrar los demás registros.
- La fuente FlareStore es `https://ioezgamer.github.io/eReaderCREA/source.json`.

### Prueba en dispositivo

1. Abre un EPUB de Catálogo sin descargar, avanza, cierra y vuelve a entrar: debe conservar la posición.
2. Descarga el mismo libro, ábrelo desde Mi biblioteca y verifica que continúa donde lo dejaste.
3. En Ajustes, autoriza la sincronización de progreso y comprueba el avance en Android con la misma cuenta Google.
4. Repite con PDF y verifica las opciones de Claude. La compilación no sustituye estas pruebas.
