# CREA Reader iOS — Estanterías y navegación

Actualización: 2026-11-04. Estas reglas sustituyen las recomendaciones web del archivo maestro para la interfaz nativa de iOS.

- Mantener la tipografía de interfaz Nunito existente y estilos tipográficos semánticos que crezcan con Dynamic Type. No sustituir la fuente del contenido de los libros.
- Fondo `systemGroupedBackground`; tarjetas opacas `secondarySystemGroupedBackground`; texto primario/secundario del sistema. Reservar el material translúcido para controles flotantes del lector.
- Márgenes de 16 puntos, separación de tarjetas de 16, relleno interior de 12, esquinas de 18 y portadas con relación ancho/alto de 0.72. La portada cargada y la de sustitución ocupan exactamente el mismo rectángulo.
- En la cuadrícula: título de dos líneas reservadas, autor de una línea, formato/estado compacto, progreso y una acción principal. Información extensa en la ficha. Los títulos completos permanecen disponibles al abrir el libro y para VoiceOver.
- La lectura de otros dispositivos se consulta en un desplegable dentro de la ficha; no debe alargar las filas del catálogo.
- Catálogo y Biblioteca usan filas con tamaños de texto de accesibilidad. La ficha del libro cambia su cabecera a vertical en esos tamaños. Logros también pasa a una columna.
- Cabeceras compactas coherentes en las cinco pestañas. Contenido de estantería y Perfil limitado a 1000 puntos en iPad; ficha a 800.
- Filtros de estado, categorías y colecciones comparten cápsulas con objetivo táctil mínimo de 44 puntos y rasgo de selección para VoiceOver.

## Verificación

`BookPresentationTests` comprueba proporción de portadas con distintos contenidos, reserva de líneas para títulos/autores largos y genera imágenes nativas de los componentes compartidos a 375, 393, 812 y 834 puntos, en claro, oscuro y texto de accesibilidad. Las imágenes usan libros de prueba sin acceso a Drive; no son capturas de una sesión real con la cuenta del usuario.

El flujo `ios-parity-validation.yml` compila, ejecuta las pruebas existentes y exporta adjuntos de XCTest para inspección visual. La validación en dispositivo físico, desplazamiento con un catálogo real, VoiceOver y contraste aumentado requiere comprobación adicional; no se deduce de una imagen estática.
