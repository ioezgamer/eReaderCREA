# CREA Reader iOS 0.10.1

Aplicación nativa para iPhone/iPad con iOS 17 o posterior, basada en `feature/ios-flarestore`. Esta entrega amplía la versión iOS con las herramientas y flujos de Android. La comparación detallada y las pruebas físicas están en [PARITY.md](PARITY.md).

## Funciones

- Biblioteca local: importación múltiple desde Archivos/iCloud, EPUB/PDF, audio e imágenes/CBZ; deduplicación SHA-256, metadatos reales, favoritos, colecciones múltiples con creación/renombrado/eliminación, filtros Android, búsqueda por título/autor, orden y continuar leyendo.
- EPUB: Readium 3.8, locator exacto, índice jerárquico, búsqueda con resultados navegables, salto porcentual, marcadores, resaltados/subrayados de selección, notas editables y eliminación.
- PDF: PDFKit, zoom, índice, búsqueda, salto a página, modos continuo/individual, posición persistente, marcadores, resaltados por línea y notas.
- Voz: Readium recorre el EPUB desde la posición actual; controles anterior/pausa/continuar/siguiente. PDF lee página o selección. Voz instalada, velocidad y tono configurables.
- Audio: AudioNavigator, pistas, posición, reproducción/pausa, saltos de 15 segundos y velocidad. Imagen/CBZ: páginas, progreso y zoom.
- Preferencias: fuente serif/sans, tamaño con la unidad de Readium corregida, interlineado, márgenes, sepia, oscuro y scroll. Interfaz español/inglés; algunos mensajes de diagnóstico siguen en español.
- Explorar: catálogos textos.info/Gutenberg con los temas y autores de Android; OPDS 1/2, navegación, paginación, búsqueda de la página, descarga directa y catálogos personalizados guardados.
- Drive: catálogo, categorías, filtros, portadas, fichas/muestras y lectura temporal; descarga manual y subida explícita resumable de EPUB/PDF importados. La biblioteca local permanece tras un fallo.
- Progreso: manifiesto Android v2, registro de los libros subidos desde iOS, posiciones EPUB/PDF y lecturas por dispositivo. Condición If-Match cuando Drive devuelve ETag; un conflicto conserva el avance local para reintentar.
- Comunidad: participantes, popularidad, publicación voluntaria con apodo y revocación, sin publicar correo o libros no vinculados por defecto.
- Claude: selección, resumen del capítulo, guía docente y ficha; reutiliza el servidor CREA de Android. Lee también las fichas compartidas `crea-ai-catalog.json`.
- Sincronización automática opcional al abrir la app y mediante BGAppRefreshTask; iOS decide cuándo ejecutar las tareas en segundo plano.

Las anotaciones se guardan por SHA-256, así sobreviven al cambio de nombre, lectura temporal y reimportación. Android guarda sus anotaciones y colecciones localmente y su manifiesto v2 no las transporta. Esta entrega no afirma sincronizarlas entre Android e iOS.

## Google Drive

`project.yml` contiene el cliente OAuth iOS y el esquema URL públicos, con bundle ID `org.crea.reader.ios`. Google Sign-In obtiene y renueva los tokens; no se guardan claves privadas en el repositorio.

- `drive.readonly`: consultar catálogo y descargar archivos elegidos.
- `drive.appdata`: progreso privado compartido entre dispositivos.
- `drive`: permiso adicional solicitado desde Ajustes para subir libros o publicar Comunidad. La subida se hace por acción explícita.

El cliente iOS debe pertenecer al mismo proyecto Google Cloud que Android para acceder a su `appDataFolder`. El catálogo y los metadatos de progreso se aíslan por cuenta; cerrar sesión no elimina los archivos locales.

Desde Ajustes, iniciar sesión, autorizar progreso y pulsar **Sincronizar progreso Android y iOS**. Para subir, autorizar escritura y usar el menú del libro o **Subir libros locales a Drive**. Para Comunidad, autorizar y activar compartir voluntariamente.

## Claude

Se utiliza el servidor `server/` de Android, sin clave Anthropic en la IPA. Configurar el dominio HTTPS del servidor en **Ajustes → Asistente de IA con Claude**, activar y comprobar conexión. El servidor debe permitir el dominio de la cuenta y, si usa `ALLOWED_AUDIENCES`, el cliente OAuth iOS además del Android.

Solo se envía contenido cuando el lector solicita una acción de Claude. La lectura, Drive y Comunidad siguen funcionando sin IA. El índice compartido se muestra separado de los metadatos originales del libro.

## Compilación y pruebas

El workflow [iOS parity validation](https://github.com/ioezgamer/eReaderCREA/actions/workflows/ios-parity-validation.yml) ejecuta sintaxis Swift, genera el proyecto Xcode, prueba en simulador y compila Release para dispositivo. Publica logs, resultados XCTest e IPA sin firmar como artefacto, sin cambiar la fuente pública de FlareStore.

Las pruebas incluyen apertura/búsqueda con EPUB/PDF reales, apertura de audio/CBZ, OPDS 1/2, SHA-256 por bloques, deduplicación que conserva datos y conflictos de anotaciones. Consulta el resultado de la ejecución sobre el commit actual; no confundas implementación con compilación validada.

El workflow de distribución existente sigue generando la IPA y la fuente FlareStore al integrar cambios en `feature/ios-flarestore`. Una IPA sin firmar requiere firma válida antes de instalarse.

## Límites

Alcance confirmado por el usuario: libros sin DRM; Readium LCP queda excluido de esta entrega. Los catálogos OPDS autenticados necesitan su flujo de credenciales. Los PDFs escaneados no tienen OCR; la voz requiere texto.

La creación inicial del manifiesto no constituye una transacción entre plataformas y Android aún puede escribir sin If-Match. Se necesitan pruebas físicas con la misma cuenta, permisos Workspace, fallos de red, iPad y accesibilidad antes de declarar igualdad funcional completa.
