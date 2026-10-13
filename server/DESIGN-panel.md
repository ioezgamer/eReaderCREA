# Panel CREA Reader: plan de diseño

Sujeto: el equipo de CREA (educación en Nicaragua) revisa cómo leen las personas lectoras de sus tabletas.
Trabajo principal: ver de un vistazo quién avanza, quién se quedó atrás y qué libros funcionan.

## Idea
El logo de CREA es un mosaico de piezas irregulares separadas por una línea blanca. El panel usa esa idea como
dato, no como adorno: **cada lector es una pieza del mosaico**. El color de la pieza es su avance medio y el tono
apagado indica que lleva más de 30 días sin leer. Es lo único atrevido de la página; todo lo demás es calmado.

## Color
- Fondo `#FFFFFF` (en oscuro `#0D1424`), sin tarjetas: las secciones se separan con espacio y una línea fina.
- Tinta `#10223E`, texto secundario `#5B6B83`, línea `#E3E7EE`.
- Colores de la marca, solo con significado: rosa `#E84481` (poco avance), cian `#00ADD8` (a mitad), lima `#C3D600` (casi),
  verde `#3BAF2A` (terminado), azul `#0972CE` (acciones y actividad), gris para sin avance.

## Tipografía
- Titulares y cifras: Bricolage Grotesque (con carácter, ancho variable), en peso 700.
- Texto: Atkinson Hyperlegible (diseñada para legibilidad; el público incluye docentes y estudiantes con todo tipo de pantallas).
- Escala: 15 / 17 / 28 / 44 px. Todo en minúsculas con mayúscula inicial: sin etiquetas en mayúsculas.

## Composición (alineada a la izquierda)
```
[logo]  Resumen  Lectores  Libros                     Actualizar  CSV  Tema  Salir
─────────────────────────────────────────────────────────────────────────────────
31 personas lectoras comparten su avance          Avance medio        8 %
y 12 leyeron en los últimos 7 días.               Libros terminados   0
                                                  En curso            77
┌───────────────── mosaico: una pieza por lector ──────────────────────────────┐
└──────────────────────────────────────────────────────────────────────────────┘
 leyenda de colores · nombre de la pieza al pasar el cursor
─────────────────────────────────────────────────────────────────────────────────
Actividad (30 días)                         Cuánto llevan leído (barra segmentada)
Libros más leídos                           Más constantes
```

## Qué evitamos
Tarjetas iguales con sombra, KPIs con ícono y acento, numeración decorativa, mayúsculas espaciadas,
animaciones en cada sección. Hay un solo movimiento: las piezas del mosaico aparecen una vez al cargar.
