# CREA Reader iOS 0.11.0: paridad con Android 0.9.4

Referencia: Android `release/0.9.3` commit `6681144` (versión 0.9.4), iOS basada en `feature/ios-flarestore` commit `1294f40` (0.10.1). Esta tabla distingue implementación de validación física.

| Función Android | Implementación iOS | Verificación necesaria |
| --- | --- | --- |
| Biblioteca EPUB/PDF, búsqueda, filtros, favoritos, colecciones, orden y continuar | SwiftUI, persistencia local, colecciones múltiples con creación/renombrado/eliminación, filtros Android y locator Readium | Reinicio, modo avión, iPad |
| Importar desde Archivos / compartir | Importación múltiple, iCloud, hash SHA-256 para evitar duplicados, exportación con ShareLink | Archivos iCloud y documentos grandes |
| EPUB | Readium 3.8, posición exacta, índice jerárquico y salto porcentual | EPUB reflow y fixed layout |
| Buscar en el libro | Servicio de búsqueda Readium; resultados navegables; PDFKit en PDF | Acentos, errores, EPUB grande |
| Marcadores | Locator EPUB o página PDF persistente por hash del contenido | Reimportación y reinicio |
| Resaltados, subrayados y notas | Decoraciones Readium; selección PDF guardada como rectángulos por línea; edición y eliminación | Selección y rotación, accesibilidad |
| Lectura en voz alta | Sintetizador Readium con seguimiento y controles anterior/pausa/siguiente; PDFKit + AVSpeech para página o selección | Voces instaladas, interrupciones y pronunciación |
| Preferencias EPUB | Tamaño, fuente serif/sans, interlineado, márgenes, sepia, oscuro, scroll | Dark mode y distintas pantallas |
| PDF | PDFKit, zoom, índice, búsqueda, salto, progreso, modos continuo/individual | PDF cifrado y escaneado |
| Catálogo Drive | Categorías, filtros, portadas, descarga explícita, ficha, muestra y lectura temporal | Cuenta Workspace, red y permisos |
| Subida de libros importados | Subida resumable explícita, carpeta CREA Reader, hash y enlace al catálogo | Permiso Drive completo, red interrumpida |
| Progreso entre dispositivos | Manifiesto Android v2, registro de libros subidos, EPUB/PDF y lista de dispositivos | Android + iPhone con la misma cuenta |
| Conflictos de progreso | If-Match con ETag cuando Google lo proporciona; conflicto conserva avance local | Drive real; Android todavía escribe sin esta protección |
| Explorar OPDS | Fuentes y temas/autores Android, navegación, paginación, ficha textual y descarga directa EPUB/PDF | Disponibilidad y variantes reales de feeds |
| Comunidad | Lectura, popularidad, publicación voluntaria y revocación existentes | Permisos y cuenta real |
| Claude | Selección, resumen, guía docente y ficha inteligente existentes | Servidor CREA y cuotas reales |
| Ajustes y apariencia | Tema sistema/claro/oscuro, idioma español/inglés y organización de biblioteca | Dynamic Type y VoiceOver; algunos diagnósticos permanecen en español |
| Audio e imagen/CBZ | AudioNavigator: pistas, avance, pausa, saltos de 15 s, velocidad; imágenes paginadas con zoom | Audiolibros y cómics reales |
| Dispositivos | Lecturas y avances del manifiesto privado por dispositivo | Múltiples tablets/iPhone |

## Alcance y validación pendiente

- Las fichas compartidas `crea-ai-catalog.json` se leen con el esquema Android y se muestran diferenciadas de los metadatos editoriales.
- Las anotaciones son locales. Android actualmente guarda marcadores, resaltados y colecciones en Room y no los transporta en el manifiesto v2. Añadir campos iOS a ese manifiesto no habilitaría intercambio con Android: requiere un contrato común y cambios en ambos clientes.
- Audio e imagen/CBZ se importan y abren con Readium/UIImage. El usuario confirmó el 12 de octubre de 2026 que solo utilizan libros sin DRM; LCP queda fuera del alcance de esta entrega.
- El TTS EPUB recorre la publicación; el PDF lee página o selección. No se implementa OCR. La voz instalada, velocidad y tono se configuran desde Ajustes.
- OPDS admite Atom/OPDS 1 y JSON/OPDS 2, paginación y catálogos personalizados persistentes; los proveedores que requieren autenticación necesitan un flujo adicional.
- La creación simultánea del primer manifiesto no es atómica entre clientes. Se vuelve a consultar antes de crear; no constituye una transacción distribuida.
- La sincronización automática es opcional al volver a primer plano y usa BGAppRefreshTask. iOS decide la frecuencia y disponibilidad del trabajo en segundo plano.

## Validación automatizada

`ios-parity-validation.yml` ejecuta sintaxis Swift, generación XcodeGen, XCTest en simulador iPhone y compilación Release para dispositivo; produce logs, `.xcresult` e IPA sin firmar. Las pruebas cubren SHA-256 con múltiples bloques, OPDS y URLs relativas, feeds malformados, deduplicación que conserva progreso/favoritos/colección y conflictos de anotaciones.

## Validación física

1. Importar EPUB/PDF dos veces y comprobar una sola copia y conservación del avance.
2. EPUB: guardar marcador, buscar, abrir resultado, resaltar con nota, reiniciar y comprobar índice/resaltado/nota.
3. EPUB: iniciar voz desde una posición intermedia; pausar, continuar, anterior, siguiente y salir.
4. PDF: buscar, ir a página, seleccionar varias líneas, guardar nota, reiniciar y comprobar rectángulos y progreso.
5. Descargar desde textos.info y Gutenberg sin abrir el navegador; comprobar paginación y funcionamiento offline del archivo guardado.
6. Autorizar subida, subir un libro iOS y sincronizar en Android. Confirmar aparición en catálogo y posición en ambos sentidos para EPUB/PDF.
7. Cambiar cuenta, revocar permisos y desconectar red. Comprobar errores, conservación local y ausencia de publicación automática.
8. Probar modo oscuro, texto grande, VoiceOver, horizontal e iPad.

Una compilación correcta no sustituye estas pruebas ni demuestra paridad completa.

## Nuevos flujos portados en 0.11.0

| Android | iOS | Límite de verificación |
| --- | --- | --- |
| Categorías/subcategorías | Jerarquía completa, categorías intermedias y filtro por descendientes; categoría de libro local y de Drive | Probar movimientos/renombrado en Drive con la cuenta real |
| Importación sin duplicados | SHA-256, metadatos Readium/PDF, identificador o título/autor en el mismo formato; confirmación de otra edición | Probar iCloud, compartir desde CREA Home y varias importaciones |
| Subir a carpeta y reutilizar libros | Carpeta elegida por el lector; coincidencias MD5/SHA-256 y título/autor de nombres creados por la app | Google Workspace y permisos de escritura |
| Repositorios gratuitos | Directorio Android de 23 fuentes, OPDS y web; alta/baja persistente, descarga directa e importación real | La disponibilidad y licencias dependen de cada fuente |
| TTS de calidad | Voces instaladas de iOS, calidad real, idioma, preferencia latinoamericana y elección manual | Instalar/probar voces en iPhone; iOS no expone el indicador Android de red |
| Tres vistas/tamaños | Opciones independientes y persistentes de biblioteca y catálogo | iPhone/iPad, texto grande y horizontal |
| Comunidad personalizada | Lectores similares, recomendaciones, actividad reciente y lectores que terminaron | Datos reales compartidos voluntariamente |
| Descargas/actualizaciones | Servicio de fondo de iOS, diario persistente, bytes reales, cancelación, selección múltiple, espacio, MD5 y reemplazo conservando datos | iOS puede interrumpir tras cierre forzado; reintentar. Las importaciones que requieren aprobación esperan el primer plano |
| Fichas IA compartidas | Contrato JSON Android con valores omitidos, versión por MD5/fecha, caché, búsqueda/autor/categorías y generación optativa de hasta 6 por actualización | Cuenta de CREA, cuota y permiso Drive. El control ETag no es una transacción entre Android/iOS |

Prueba física adicional: actualizar un EPUB cambiado mientras su lector conserva una nota; cerrar/reabrir y verificar notas, marcadores y posición. Repetir descarga con pérdida de conexión, cancelación, falta de espacio, cambio de cuenta y cierre forzado. Confirmar que no se pierde la copia anterior si el checksum falla.

## Sesión «Rediseño Liquid Glass» (iOS 0.12.0)

| Android (PR #8) | iOS | Diferencias / verificación |
| --- | --- | --- |
| Estadísticas, racha y widgets | `CREAReadingStats`, `CREAStatsView`, extensión `CREAWidgets` | Widgets compartidos por App Group; el servidor de firma debe permitirlo. Probar en dispositivo. |
| Panel Aspecto, brillo, barras | `CREAAppearancePanel`, `CREAReaderLook` | Brillo con `UIScreen.brightness` (Auto = valor del sistema). |
| Índice, páginas, marcadores | `EPUBToolsSheet` | Las páginas siempre se generan desde las posiciones de Readium. PDF conserva su panel actual. |
| Exportar notas | `CREANotesExport` | Markdown vía hoja de compartir. |
| IA: interruptores y usos | `CREAAISettingsView` | iOS mantiene la lista de servidores autorizados (se envía el token de Google): «servidor propio» solo https **y** autorizado. |
| Nunito, Liquid Glass | `CREAGlass`, `Fonts/Nunito.ttf` | Nunito es variable: comprobar peso en dispositivo. Los iconos Icons8 PNG de Android no se portaron: iOS usa SF Symbols. |
| Voz de lectura | `CREAVoiceSettings` (ya en la app) | iOS no abre los ajustes de voz del sistema; se eligen dentro de la app. |
