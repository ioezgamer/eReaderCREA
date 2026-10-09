# CREA Reader iOS — port en desarrollo

Esta rama contiene una aplicación iOS nativa SwiftUI basada en las secciones y estilo del proyecto Android original. **No es aún una reproducción funcional completa de Android.**

## Implementado
- Cinco pestañas: Mi biblioteca, Catálogo en línea, Explorar, Comunidad y Ajustes.
- Biblioteca local en cuadrícula o lista; búsqueda, filtros por estado/formato/favoritos, ordenación por título/progreso/última lectura.
- Importación manual EPUB y PDF mediante Archivos; eliminación local y favoritos.
- Lectura PDF con PDFKit y seguimiento básico de avance por página.
- Tema claro, oscuro y del sistema; tarjetas translúcidas y paleta CREA.
- Acceso a textos.info y Proyecto Gutenberg para obtener archivos.
- Compilación de IPA **sin firmar** usando GitHub Actions y un ejecutor macOS remoto, sin Mac propia.

## No implementado todavía
- Motor EPUB nativo (Readium Swift): EPUB se importa y aparece en biblioteca pero no se puede abrir.
- Google OAuth iOS y Google Drive (catálogo, descarga selectiva, lectura temporal, sincronización).
- Comunidad sincronizada y marcadores/resaltados que viajen entre dispositivos.
- Portadas reales extraídas de EPUB/PDF, lector EPUB con TTS y notas.
- Igualdad visual y funcional completa; requieren pruebas con Android e iPhone.

La app iOS **no contiene credenciales**. No se pide acceso Google Drive hasta configurar el cliente OAuth iOS separado. La app Android permanece intacta.

## IPA para FlareStore
Consultar [GitHub Actions](../../actions/workflows/ios-unsigned-ipa.yml) de la rama `feature/ios-flarestore` y descargar el artefacto `CREA-Reader-iOS-unsigned-IPA` de una ejecución correcta. El IPA sin firmar necesita un método de firma válido antes de instalarse.

La compilación de estos cambios aún debe verificarse en Actions. Que compile no implica que todas las funciones de Android estén disponibles.
