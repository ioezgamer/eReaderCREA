# Historial de cambios

## 0.8.3 - 2026-10-09

- **La comunidad funciona por dispositivo**: cada instalación comparte con su propio apodo (por defecto, el nombre del dispositivo en Android) y su propio archivo, así que varias tablets con la misma cuenta de Google se ven entre sí como lectores distintos. Antes compartían un solo archivo por cuenta y se pisaban. Con otra cuenta funciona igual: el apodo siempre es del dispositivo.
- El apodo del dispositivo también es el nombre que se muestra en el avance por dispositivo de cada tarjeta del catálogo.
- El archivo antiguo por cuenta se vacía una sola vez.

## 0.8.2 - 2026-10-09

- **Corrige el cierre de la app** al tocar la tarjeta de estado de Drive (y los botones «Conectar», «Ir al Catálogo en línea» y «Ver» tras descargar): la navegación entre pestañas apuntaba a un destino que no se encontraba desde la pestaña actual. Encontrado leyendo el registro de errores de la tablet por USB.

## 0.8.1 - 2026-10-09

- **Pantalla de error con informe**: si la app se cierra por un fallo, ahora aparece una pantalla (en un proceso aparte, así funciona aunque el fallo ocurra al arrancar) con los detalles técnicos y botones para copiarlos, compartirlos o reabrir la app. El informe se queda en el dispositivo; no se envía a ningún sitio.

## 0.8.0 - 2026-10-09

- **Pestaña «Comunidad» (qué leen los demás)**, opcional y desactivada por defecto: cada persona elige un apodo y decide si comparte qué libros lee y cuánto avanza. Quien comparte ve «Recomendados para ti» (según lectores con lecturas parecidas), «Leyendo ahora» y «Lo más leído». Solo se publica el apodo y el avance por libro en un archivo de la carpeta compartida de Drive (nunca nombre, correo ni dispositivo); al dejar de compartir el archivo se vacía. La pestaña Drive pasó a Ajustes.
- **Descargas en segundo plano** con notificación de progreso y botón de cancelar: ya no se detienen al salir de la app.
- **Descargar varios libros a la vez**: mantén pulsado un libro del catálogo, elige los demás y descarga; se avisa antes si no hay espacio para todos.
- **Actualizaciones de libros descargados**: si el archivo cambió en Drive, la tarjeta lo indica y «Actualizar» reemplaza la copia conservando posición de lectura, marcadores y favorito.
- **Notas, resaltados y marcadores se conservan al eliminar un libro** y se recuperan al volver a descargar el mismo archivo.
- **Colecciones propias** en Mi biblioteca (por ejemplo «Para clase»): crea, renombra y elimina colecciones y agrega libros desde su menú; se filtran con una fila de chips.
- Base de datos: migración 3 → 4 (versión de Drive por libro, notas guardadas, colecciones).

## 0.7.0 - 2026-10-09

- **Formas de ver los libros** en Mi biblioteca y en el Catálogo en línea: lista, cuadrícula o solo portadas, con tres tamaños de portada (botón «Vista» junto a ordenar). Se recuerda la elección de cada pantalla. En el catálogo, las vistas de cuadrícula y portadas muestran la insignia de descargado y el progreso de descarga o lectura bajo la portada; tocar un libro abre la hoja con «Leer en línea» y «Descargar».
- **Más filtros en Mi biblioteca**: Favoritos, Nuevos (últimos 14 días), Con marcadores, Con notas, En Drive y Solo en este dispositivo, además de los que ya había. La búsqueda ignora tildes y mayúsculas. Nuevos criterios de orden: avance de lectura y tamaño.
- **Favoritos**: se marcan desde el menú de cada libro (mantener pulsado) o con el corazón de la vista de lista, y se ven con un corazón en la portada.
- **Más filtros en el catálogo**: Leyendo, Terminados y Nuevos (cambiados en los últimos 14 días).
- El avance de lectura se publica en Drive poco después de cerrar un libro, para que se vea enseguida en tus otros dispositivos.
- Base de datos: migración 2 → 3 (columna `favorite`).

## 0.6.1 - 2026-10-09

- **Portadas siempre visibles en el Catálogo en línea**: los EPUB sin miniatura de Drive ahora obtienen su portada leyendo solo la imagen del archivo (unos pocos KB con solicitudes parciales, sin descargar el libro). Si un libro no tiene portada, se dibuja una con su título para que ninguna tarjeta quede vacía. Se recuerda qué libros no tienen portada para no reintentarlo en cada sincronización.
- **Lectura por dispositivo**: cada instalación publica en el espacio privado de Drive de tu cuenta (junto con el nombre del dispositivo en Android) qué libros lee y cuánto. Cada tarjeta del catálogo muestra el porcentaje general (la lectura más reciente en cualquiera de tus dispositivos) y, si otros dispositivos lo leen, el avance de cada uno («Este dispositivo 40% · Galaxy Tab 12%»). Solo ven esto los dispositivos de la misma cuenta de Google.

## 0.6.0 - 2026-10-09

- Nueva pestaña **Catálogo en línea**: muestra todos los libros de la carpeta «CREA Reader» de Drive (incluidas sus subcarpetas, que funcionan como categorías) con portada, título, autor, formato y tamaño, sin descargarlos. Incluye búsqueda por título o autor (sin distinguir tildes), filtros por estado, formato y categoría, y orden por título, autor o cambios recientes. Funciona sin conexión con la última copia guardada y avisa de que puede estar desactualizada.
- **Leer en línea**: abre un libro del catálogo sin agregarlo a Mi biblioteca, con indicador de progreso. Usa una copia temporal en la caché privada que se limpia sola (24 h o 400 MB) y conserva la posición de lectura y los marcadores.
- **Descargar a Mi biblioteca**: descarga manual con progreso, botón de cancelar, comprobación de espacio libre, verificación de integridad y sin descargas duplicadas. Al descargar un libro que ya se leyó en línea se conservan su posición y sus marcadores.
- **Mi biblioteca** (antes «Biblioteca») muestra solo lo descargado o importado, con insignia de «disponible sin conexión», espacio usado, y orden por título, fecha de descarga o última lectura. Eliminar un libro solo borra la copia del dispositivo: sigue en el catálogo y el archivo de Drive no se toca.
- La sincronización con Drive ya **no descarga libros ni borra nada**: solo actualiza los metadatos del catálogo (listado paginado, sin solicitudes innecesarias). Los libros que ya estaban descargados permanecen en Mi biblioteca.
- Base de datos: migración 1 → 2 (tabla nueva `drive_catalog`).


## 0.5.0 (publicación) - 2026-10-08

- Clave de subida propia para Google Play y bundle `CREA-Reader-0.5.0.aab` firmado con ella.
- La firma se lee de `keystore.properties` (fuera del control de versiones); `-PsignWithDebugKey` genera una APK de prueba que se instala sobre las anteriores.

## 0.5.0 - 2026-10-08

- Primera compilación optimizada (release): código reducido y ofuscado con R8, recursos sin uso eliminados y sin modo de depuración. Pasa de 48,8 MB a 26,6 MB y arranca y navega más rápido.
- Firmada con la misma clave que las versiones anteriores, así que se instala encima sin perder libros y Drive sigue funcionando.

## 0.4.1 - 2026-10-08

- Cambiar de pestaña es fluido: la biblioteca y los catálogos se mantienen en memoria mientras la app está abierta y cada pantalla aparece ya pintada, sin el parpadeo de «biblioteca vacía» ni elementos que llegan uno a uno.
- Las portadas se precargan al abrir la app, usan un tamaño fijo para reutilizarse entre pantallas y ya no hacen fundido al aparecer.
- Transición de cambio de pestaña con un fundido corto en lugar de un corte brusco.

## 0.4.0 - 2026-10-08

- Toda la app está en español, también los ajustes de lectura, metadatos, voz y mensajes de error (antes varios textos estaban escritos en inglés en el código).
- Nueva pestaña **Ajustes** con selector de idioma (Español / English): cambia toda la app, incluido el lector. Por defecto siempre es español, aunque el teléfono esté en otro idioma; en Android 13+ también aparece en los ajustes del sistema.
- Ajustes incluye acceso a Drive, versión de la app y créditos.

## 0.3.3 - 2026-10-08

- El lector ya no dibuja los números de página en el margen: tapaban las primeras letras de las líneas.

## 0.3.2 - 2026-10-08

- Barra de navegación propia, compacta y centrada: la píldora de vidrio mide lo justo para su contenido y solo la pestaña activa muestra su nombre.

## 0.3.1 - 2026-10-08

- La barra de navegación inferior es más compacta: menos alto, ancho máximo en tablets y iconos más juntos.

## 0.3.0 - 2026-10-08

- Estilo «vidrio líquido» en toda la app: fondo degradado con los colores del logo, tarjetas y barras translúcidas con borde luminoso, barra de navegación flotante, hojas inferiores y botones a juego, en modo claro y oscuro.
- Nuevos iconos de navegación (Biblioteca, Explorar y Drive) de trazo fino que se rellenan al seleccionarlos.
- Flecha de regreso siempre visible en el lector para volver a la biblioteca.
- Explorar ahora ofrece 17 catálogos gratuitos en español (textos.info y Proyecto Gutenberg por temas y autores, incluido Rubén Darío) y reemplaza los de muestra en inglés; las pantallas de catálogo, detalle y errores están traducidas al español.

## 0.2.1 - 2026-10-08

- Nuevo icono de la app con el logo de CREA Reader (adaptativo para Android 8+, con variantes redonda y heredada).
- Paleta de la interfaz tomada del logo: azul como color principal, rosa y naranja de acento, verde para el progreso y azul marino para el texto, en modo claro y oscuro.
- Filtros, navegación inferior, botón de agregar y etiquetas EPUB/PDF con los colores del logo; el logo aparece en la biblioteca vacía.

## 0.2.0 - 2026-10-08

- La sincronización usa la carpeta visible «CREA Reader» de Google Drive: sube archivos desde cualquier dispositivo y aparecen en la app; los cambios de la app llegan a Drive.
- Los libros eliminados pasan a la papelera de Drive en lugar de borrarse; los eliminados en Drive se quitan de la app.
- Paginación al listar Drive, progreso de sincronización por etapas y mensajes de error comprensibles.
- Rediseño de la biblioteca: tarjeta «Continuar leyendo», estado de Drive compacto, filtros y orden, portadas 2:3, actualizar deslizando, hoja para agregar libros (varios archivos a la vez) y acciones por libro.
- Nueva pantalla Drive con cuenta conectada, acceso a la carpeta y resumen de la última sincronización.
- Sincroniza al abrir la app y tras eliminar libros. Se corrige un fallo que detenía toda la sincronización con libros agregados desde almacenamiento compartido.

## 0.1.0 - 2026-10-08

- Primera implementación funcional de CREA Reader para Android.
- Biblioteca, importación, lectura EPUB/PDF, búsqueda, progreso y anotaciones locales.
- Sincronización Google Drive de libros, metadatos, progreso y eliminaciones.
- Interfaz adaptable, tema claro/oscuro y recursos en español.
- Pruebas unitarias de la estrategia de fusión entre dispositivos.

