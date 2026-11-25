# Sistema de diseño de CREA Reader

Basado en la identidad de [creanicaragua.org](https://creanicaragua.org/) (colores, tipografía y botones)
con superficies de Liquid Glass al estilo de iOS. Este archivo describe lo que la app ya usa; si algo
cambia en el código, se actualiza aquí.

## Colores

Definidos en `app/src/main/res/values/colors.xml` (claro) y `values-night/colors.xml` (oscuro). El tema
XML (`styles.xml`) y el de Compose (`utils/compose/AppTheme.kt`) leen los mismos recursos; nunca se usan
los colores dinámicos del fondo de pantalla.

| Rol | Claro | Oscuro | Uso |
|---|---|---|---|
| Primario | `#2371BD` azul CREA | `#A6CBF2` | Botones principales, títulos de pantalla, íconos de acción, deslizadores |
| Secundario | `#2E6E76` turquesa profundo | `#7EBEC5` | Botones segmentados elegidos (contenedor `#CDEBEE`), acentos suaves |
| Terciario | `#5E7700` oliva | `#C0D32E` lima | Progreso y «terminado»; el lima `#C0D32E` vive en contenedores |
| Superficie | `#F7F9FC` | `#0A192C` marino (pie de la web) | Fondo de pantallas y tarjetas |
| Texto | `#0A192C` | `#E4EAF2` | Texto principal |
| Aviso | `#A35400` | `#FFB866` | «Actualización disponible», racha de lectura |
| Favorito | `#D6246E` | `#FF8FB8` | Corazón de favoritos |

Reglas:

- El lima y el turquesa claro no se usan como color de texto sobre blanco (no llegan a 4,5:1). Sobre lima
  va texto marino: la banda «Seguir leyendo» es lima con texto `#0A192C`.
- Todo par de texto y fondo cumple WCAG AA (4,5:1). Al cambiar un color, comprobarlo.
- Las barras del lector siguen al **papel** del libro, no al tema de la app: colores `reader_glass_*`
  (sin variante nocturna), elegidos según la luminosidad de la página.

## Tipografía

Montserrat (licencia SIL OFL, `THIRD_PARTY_MONTSERRAT_LICENSE`), en `res/font`: Medium 500 como peso
normal, SemiBold 600, Bold 700 y ExtraBold 800.

- **Títulos de pantalla**: ExtraBold, mayúsculas, azul primario («MI BIBLIOTECA»), como los encabezados
  de la web (`TextAppearance.Crea.ActionBarTitle`).
- **Encabezados de sección**: Bold, mayúsculas, azul, espaciado 0,06 («APARIENCIA»).
- **Botones principales**: Bold, mayúsculas, espaciado 0,05, sobre azul sólido («AGREGAR LIBROS»,
  «LEER»), como «DONATE» en la web. Los botones con borde, tonales y de texto no van en mayúsculas.
- La escala de Material 3 (`textAppearance*`) está redefinida en Montserrat; en Compose,
  `CreaTypography`.
- El título del libro en la barra del lector conserva sus mayúsculas y minúsculas.

## Íconos

Un solo juego vectorial propio, generado por `tools/make_icons.py`:

- Caja de 24 dp, trazo de 2 dp con puntas y uniones redondeadas, misma escala óptica.
- Contorno en reposo; relleno («solid») solo para el estado elegido de la barra de navegación y para los
  controles de reproducción.
- Tinte desde el contexto (`?attr/colorControlNormal`, o el `tint` del `Icon`). Los íconos de
  notificación (`ic_stat_*`) no llevan atributo de tinte.
- No se usan emojis como íconos de interfaz. Los avatares de los lectores y las insignias de logros sí
  son emojis: son contenido, no controles.
- No se agregan PNG de íconos: un ícono nuevo se define en el script y se genera.

## Liquid Glass

Librería Backdrop de Kyant (`ui/glass/`).

- **Pantallas principales**: fondo de malla (`bg_mesh.webp`, azul → turquesa → lima) y barra de
  navegación de cristal real (desenfoque + refracción) en Android 12+.
- **Lector**: las barras superior e inferior y los controles de voz son cápsulas flotantes de cristal
  sobre una **instantánea** de la página (`PageSnapshot`), tomada al pasar de página, al cambiar el papel
  y al mostrar las barras. Nunca se copia el WebView en cada fotograma (hacía parpadear la página).
- Parámetros del lector: desenfoque 6 dp, lente 20/40 dp, sombra suave, borde de 0,5 dp; tinte blanco
  al 32 % sobre papel claro y marino al 50 % sobre papel oscuro.
- Sin efectos (ahorro de batería, Android 11 o anterior): la misma cápsula casi opaca (98 %) con borde
  visible.
- Las formas del cristal son redondeadas (cápsulas, 24 dp); la web usa esquinas rectas, pero la
  refracción necesita curvas.

## Componentes

- **Tarjetas**: cristal translúcido (`bg_glass_card`) con esquinas de 24 dp.
- **Hojas inferiores y diálogos**: `glass_sheet` (casi opaco), esquinas de 32 dp.
- **Filtros (chips)**: elegidos en azul sólido con texto blanco; el resto, cristal.
- **Volver**: chevron del juego de íconos en azul, en todas las pantallas.
- **Panel «Aa» del lector**: opaco, para que el texto del libro no se vea a través.

## Pendiente conocido

- La marca del radio en los diálogos de selección única de Material sale en el turquesa secundario y su
  título no toma Montserrat: esos diálogos no leen los atributos del tema de la app.
- Los widgets de la pantalla de inicio usan la fuente del sistema.
