# CREA Reader para iOS (rama experimental)

Este directorio contiene una **primera app iOS nativa**, no una conversión directa del APK. Android se mantiene sin cambios.

## Funciona actualmente
- Interfaz SwiftUI adaptada a iPhone y iPad, con tema automático claro/oscuro.
- Importación de archivos PDF y EPUB desde Archivos.
- Biblioteca local persistente con eliminación de libros.
- Lectura nativa de PDF usando PDFKit.

## Pendiente antes de equivalencia con Android
- Lectura EPUB con Readium Swift (los EPUB pueden importarse pero no abrirse todavía).
- Autenticación Google iOS OAuth, acceso limitado a carpeta, catálogo remoto y caché de metadatos.
- Descargas bajo demanda, lectura online temporal, sincronización de progreso y marcadores.
- Portadas, búsquedas, comunidad, accesibilidad y pruebas reales en iPhone/iPad.

## Generar IPA sin Mac propia
La automatización `.github/workflows/ios-unsigned-ipa.yml` compila en un runner macOS de GitHub Actions. Abre **Actions** en GitHub y selecciona la rama `feature/ios-flarestore`; cada push a esta rama también intenta compilar. Descarga el artefacto `CREA-Reader-iOS-unsigned-IPA`. El archivo IPA se produce sin firma; **FlareStore necesita una firma válida compatible y puede requerir ajustes adicionales**. Un IPA no firmado no se instala directamente.

Si GitHub no permite ejecutar workflows de ramas nuevas desde el selector, revisa los intentos automáticos asociados al push de esta rama o crea un PR para que GitHub lo detecte. No se necesita equipo Mac local.

El bundle identifier provisional es `org.crea.reader.ios`. El cliente OAuth para iOS será distinto al de Android y deberá configurarse antes de sincronizar Drive.

No se incluyen credenciales ni certificados en el repositorio.
