# Evaluación del diseño de Vega 2 (4-11-2026)

Revisé las pantallas del teléfono real (captura de hoy) y las de prueba (día y noche, a 800×360 dp, el tamaño del Redmi), y
medí lo medible: contraste de colores, tamaños de letra y de botones. Las capturas están en esta misma carpeta.

## Lo que ya está bien (y no hay que tocar)

- **Tiene identidad.** La cara es el centro de todo, con color y expresión según lo que pasa (escucha, piensa, juego en
  marcha, error). Se reconoce a un metro.
- **Jerarquía clara.** La hora y los números van grandes y finos; lo secundario, pequeño y discreto. Los bloques se leen
  de un vistazo: cara, hora, PC, lo que pasa ahora, teclas.
- **Un solo lenguaje de formas.** Pastillas redondeadas, relieve en las teclas, mismos radios en todo. Día y noche con la
  misma estructura.
- **Los estados raros están pensados.** Sin conexión, la cara se apaga, los medidores muestran «–» y las teclas se
  atenúan. La confirmación de apagar el PC (cara roja, borde y cuenta atrás) no se puede pasar por alto.
- **Las pantallas grandes (música, juego, charla, dormir)** se ven cuidadas y distintas entre sí.

## Lo que medí y no pasa la prueba

### 1. Contraste de los textos (WCAG: 4,5 para texto normal y 3 para texto grande)

| Color | Día sobre tarjeta | Noche sobre tarjeta | Dónde se usa |
|---|---|---|---|
| Texto principal | 15,8 | 15,5 | bien |
| Texto secundario | 5,7 | 7,0 | bien |
| **Texto terciario** | **2,9** | **3,4** | «CPU», «GPU», «PRÓXIMO AVISO», «hace 4 días»: **no llega** |
| **Coral (cifras del temporizador)** | **2,7** | 7,0 | la cuenta «04:57» de día **no llega ni como texto grande** |
| **Amarillo, azul, verde y lila como texto** | **1,5–2,3** | 8,7–11,6 | los valores de Ajustes («Dora · natural» y «Espectro», en azul: 2,1): de día cuesta leerlos |

De noche solo falla el terciario. **De día fallan cuatro cosas.** Es el mismo patrón: los colores de acento están pensados
para dibujar, no para escribir con ellos.

### 2. Tamaño de la letra

Hay 4 textos de 10 sp, 9 de 11 sp y las etiquetas de todo el diseño van a 11 sp con espaciado. Un teléfono sobre la mesa
se mira a 50–80 cm; lo cómodo para leer de un vistazo es de 14 sp para arriba (las etiquetas de 11–12 sp obligan a
inclinarse).

### 3. Tamaño de los botones táctiles

Android recomienda **48 dp** como mínimo (unos 7,5 mm: la yema del dedo). En Vega 2:

| Botón | Tamaño |
|---|---|
| Cámara, micro y dormir (tres juntos, cara de Vega) | **34 dp**, con 6 dp entre ellos |
| Anterior/siguiente de la música (mosaico) | **30 dp** |
| Cerrar un aviso | **26 dp** |
| Teclas de abajo, pausa, «Más» | bien (más de 48 dp) |

Lo grave: **«dormir» está pegado al micro y a la cámara**, y un fallo de dedo duerme a Vega o le corta el micro.

## Lo que no se ve bien o engaña

1. **Cuando el servidor está apagado, la pantalla dice «Revisa el cable USB».** Es lo que pasó ayer: el cable estaba bien y el
   servidor parado. Y mientras tanto el mosaico dice **«Todo tranquilo · Sin avisos pendientes»**, una afirmación falsa:
   no se sabe nada, y el botón del micro sigue pareciendo activo.
2. **Hay una franja muerta a la derecha.** En el móvil real las tarjetas acaban a 47 dp del borde (por el hueco de la cámara
   frontal) y a 10 dp por la izquierda. Esa tira de unos 6 % de pantalla solo tiene un puntito verde suelto.
3. **Las respuestas largas se cortan con «…»** (dos líneas): «…con 7 de 8 GB ocup…». Para leer el resto hay que abrir la
   charla.
4. **El mosaico de CPU/GPU/vídeo ocupa un cuarto de la pantalla siempre**, aunque no pase nada. Y el reloj repite el estado
   («En línea») que ya dice la cara.
5. **Las funciones nuevas no tienen sitio.** La cámara es un icono; el mezclador de volumen por programa, los fondos y los
   subtítulos (del plan) no tienen dónde vivir. Solo hay voz o la hoja «Más».
6. **Vega 2 no usa lo que el teléfono tiene:** sensor de luz, de proximidad y acelerómetro. Cambia entre día y noche por la
   hora, no por si la habitación está a oscuras.

## Propuestas, de más a menos urgente

### A. Arreglos rápidos (1 día)
- **A1. Contraste.** Subir el texto terciario a ≥4,5 en las dos paletas; que el acento **nunca** sea texto sobre claro (la cuenta
  del temporizador, en tinta con un detalle en coral); valores de Ajustes en tinta con un puntito de color. Una **prueba
  automática** que falle si algún par de colores baja de su mínimo.
- **A2. Botones de 48 dp.** El dibujo no cambia: se agranda la zona que responde. Cámara, micro y dormir se separan, y «dormir»
  pide una pulsación un poco más larga (o confirma).
- **A3. Estados honestos.** Mensajes distintos: «Sin cable USB», «El PC no responde (¿Vega está apagada en el PC?)» y «Sin
  permiso». «Sin datos del PC» en lugar de «Todo tranquilo». Micro atenuado sin conexión. Un toque en la cara = «reintentar
  ya».
- **A4. La letra pequeña a 12 sp como mínimo** y las etiquetas a 13 sp.

### B. Mejoras de uso (2–3 días)
- **B1. Riel de estado.** En esa franja muerta, una columna con: conexión, cámara encendida, micro abierto, batería del móvil.
  Los indicadores de privacidad (cámara y micro) a la vista siempre.
- **B2. Respuestas largas legibles.** La respuesta se «lee» con la voz (la línea que suena resaltada) y se desplaza sola; un toque
  la abre entera.
- **B3. Mosaico del PC adaptable.** Con todo bien, una sola pastilla («PC bien · 21 %») con su mini gráfica; se abre en barras
  si algo sube (calor, vídeo lleno, juego en marcha). Libera sitio para B4.
- **B4. «Mesa»: el panel de lo nuevo.** Un deslizamiento hacia arriba abre una hoja con: **volumen por programa** (Discord,
  Spotify, el juego, con deslizadores grandes), **fondo de pantalla** (tres sugerencias y «otro»), **sonido a auriculares o altavoces**,
  y la **cámara** (encender, frontal/trasera). Es el mismo «Vega manda en tu PC», pero también con el dedo.
- **B5. Luz y proximidad.** Tema y brillo según la luz de la habitación (sensor de luz) y despertar al acercar la mano (sensor
  de proximidad), como un reloj de mesa.

### C. Personalidad y pantallas nuevas (1–2 semanas)
- **C1. La cara reacciona a lo que pasa fuera de ella:** bomba plantada en CS2 (alerta), ronda ganada (alegría), temporizador que
  termina (salto), música con ritmo (los ojos siguen el compás), mirar hacia donde tocas.
- **C2. Pantalla de «Intérprete»** (el plan de subtítulos): letra grande, dos líneas, original pequeño debajo, un color por
  persona, sin nada que distraiga.
- **C3. Pantalla de «Vega ve»:** visor de la cámara, el botón «¿qué es esto?», y la respuesta en una tarjeta.
- **C4. Presencia:** saludo al volver, pantalla apagada al irte (con el plan de Bluetooth).

### D. Base para que no se estropee
- **D1. Una guía de diseño de verdad:** escala de espacios (4/8/12/16), escala de letra con el mínimo de 12 sp, y todos los colores
  como elementos con nombre y su uso permitido.
- **D2. Capturas de comparación automáticas** (las de Roborazzi que ya existen, con «verificar» en vez de solo grabar): si un
  cambio mueve la pantalla sin querer, la prueba avisa.

## Cómo lo ordenaría

1. **A1–A4** (un día): lo que más se nota y lo que mide mal hoy. 2. **B1 y B3** (la franja y el mosaico adaptable), que dejan el
sitio listo. 3. **B4 «Mesa»** junto con el plan de «Vega manda en tu PC», que es lo que más lucirá. 4. **C1** (la cara viva).
5. El resto, según lo que uses más.

---

## Aplicado el 4-11-2026: arreglos rápidos A1–A4

Instalado en el Redmi. Capturas de después: `09`, `10`, `11` y `12` de esta carpeta.

| | Antes | Después |
|---|---|---|
| **A1 · texto terciario (día / noche)** | 2,9 / 3,4 | **≥ 4,5** en las dos paletas, sobre papel, papel 2 y fondo |
| **A1 · cuenta del temporizador de día** | 2,7 (coral) | **≥ 4,5** (el mismo tono, más oscuro; de noche no cambia) |
| **A1 · texto sobre mosaicos de color** | crema sobre coral, 2,6 | la tinta o la crema, la que más contraste dé (coral: tinta, 6,1) |
| **A2 · cámara, micro y dormir** | zona de 34 dp pegada | **48 dp** cada una (el dibujo sigue siendo de 34 dp) |
| **A2 · dormir** | un roce la dormía | hay que **mantener 0,45 s** (aro que se llena); si se suelta antes, avisa «Mantén para dormir» |
| **A3 · servidor apagado con el cable puesto** | «Revisa el cable USB» | «El PC no responde. ¿Está Vega encendida en el PC?» |
| **A3 · sin cable** | (igual que arriba) | «Sin cable USB al PC. Conéctalo para seguir.» |
| **A3 · mosaico de avisos sin conexión** | «Todo tranquilo» | «Sin datos del PC» |
| **A3 · micro sin conexión** | parecía activo | atenuado; **un toque en la cara reintenta ya** |
| **A4 · letra** | 10 y 11 sp | **12 sp como mínimo** (etiquetas incluidas) |

**Pruebas nuevas** (`DesignRulesTest`, 11): el contraste de todas las tintas y de todos los acentos usados como texto (si
alguien cambia un color y baja de 4,5, falla), las zonas de toque de 48 dp, dormir manteniendo, soltar antes, y los cuatro
mensajes de estado. Toda la suite de Android pasa.

**Decisiones y límites:**
- Los **ojos** de la cara conservan su color de siempre (crema sobre el mosaico); solo cambia el color del texto, que es lo que
  tenía poco contraste.
- La franja de la derecha sigue vacía: es la propuesta B1 (riel de estado).
- No he podido comprobar con el dedo el tacto de mantener para dormir (0,45 s): es un número que quizá quieras ajustar.

## Aplicado el 4-11-2026: B1 (riel de estado) y B3 (mosaico del PC adaptable)

Instalado en el Redmi. Capturas: `13` (móvil real), `14`–`16` y `17`.

**B1 · Riel de estado.** En la franja que deja el hueco de la cámara (47 dp a la derecha en el Redmi) ahora hay una columna con:
conexión con el PC (punto y «PC»), cámara (verde si está encendida), micro (tachado si está apagado) y la batería del teléfono
(pila que se vacía, rayo si carga, roja por debajo del 15 %). Va por debajo del punto verde de privacidad de Android y se corta
antes del hueco de la cámara; es solo información (no se toca) para que nadie pulse junto al hueco. Se coloca sola en el lado que
tenga el hueco (si el móvil se gira, pasa a la izquierda) y no aparece si no hay franja.

> Un hallazgo: el puntito verde suelto de la esquina **no era de Vega**: es el indicador de privacidad de Android (micrófono en uso).

**B3 · Mosaico del PC adaptable.** Con el PC tranquilo (sin juego, procesador <70 %, gráfica <75°, vídeo <90 %) el mosaico dice
**«PC bien»**, el procesador y una gráfica fina del último rato (procesador y gráfica, hasta 60 lecturas); en cuanto algo sube, o
cuando hay un juego en marcha, **vuelven las barras** y enseñan cuál (rojo y amarillo según lo cerca que esté del límite).

**Pruebas nuevas** (`RailAndPcTest`, 12): dónde cabe el riel (derecha, izquierda si se gira, nada si es fino), su contenido en
cada estado (enlace, cámara, micro, carga, sin franja), los umbrales del mosaico y el historial. Toda la suite de Android pasa.

**Límites:** la gráfica del último rato empieza vacía y se llena en un par de minutos; no la he visto con datos reales del PC
(en la captura `17` va con datos de ejemplo).

## Aplicado el 5-11-2026: B4 (la «Mesa»)

Capturas: `18` (día), `19` (noche, con el fondo en pausa) y `20` (sin nada que enseñar).

La contrapartida táctil de «Vega manda en tu PC»: una tecla nueva, **Mesa**, en la fila de teclas (justo antes de «Más») que abre una
hoja con:
- **Volumen** de cada programa que suena en el PC (Discord, Spotify, el navegador, el juego… hasta 6, los conocidos primero). Se
  toca o se arrastra la barra (zona de 48 dp), se ve el número y un botón silencia. El juego sale con su nombre.
- **Fondo de pantalla** (Wallpaper Engine): el actual, tres fondos con miniatura para elegir con un toque, «Otros» (tres nuevos),
  «Al azar» y «Pausa/Seguir».
- **Cámara**: encender/apagar y Frontal/Trasera (solo si el PC puede), la misma que en la cabecera y en «Más».

Se refresca sola cada 2 s mientras está abierta. Si todavía no ha llegado la primera lectura dice «Leyendo el PC…»; sin enlace,
«Sin conexión con el PC.»; si nada suena o no hay Wallpaper Engine, lo explica en vez de dejar un hueco.

**Servidor:** mensaje `desk` (get, volume, mute, wallpaper, random, more, pause, play) → `desk_state`; la miniatura sale de la
vista previa del fondo (JPEG ≤256×144, en caché) por `/app/wallpaper/<id>.jpg`. Solo el teléfono puede moverla; los nombres de
programa y los números de fondo se validan; una vista previa fuera de la carpeta del fondo se ignora.

**Pruebas:** 30 en el servidor (`test_desk_panel.py`) y 26 en Android (`MesaTest`: estado, protocolo, tocar y arrastrar la barra,
silencio, fondos, pausa, cámara, textos honestos y abrir desde la fila de teclas).

**Límites:** el cambio de **salida de audio** (altavoces ↔ auriculares) que estaba en la propuesta **no** está hecho: Windows no
lo documenta (hay que usar una interfaz COM sin soporte). El volumen es el de cada programa, no el general. La Mesa solo existe en
Vega 2. Nada de esto lo he probado aún con el dedo ni con tu Wallpaper Engine y tu Discord reales: el servidor tiene que
reiniciarse para que la entienda.

## B5 · Tablero de Moodle (entregas de la universidad)

Capturas: `21` (tablero de día), `22` (de noche) y `23` (la página «Moodle» del mosaico de avisos).

Dos piezas, las dos con los datos de `docs/moodle.md`:
- **Una página nueva en el mosaico de avisos** («Moodle», entre «Aviso» y «PC»; solo aparece si hay entregas): la próxima entrega con su
  cuenta atrás grande (coral si falta menos de un día), cuántas van **vencidas** (pastilla roja) y las dos siguientes. Tocarla abre el tablero.
- **El tablero** (hoja a pantalla completa, como la Mesa): cuatro columnas — **Vencidas · Hoy · Esta semana · Después** — con una tarjeta
  por entrega (título, materia, «en 3 h» / «mañana 00:00» / «mié 18 · 18:45» / «hace 1 h»). El visto de cada tarjeta la marca como
  entregada; pide un segundo toque («¿Entregada? Toca», se arrepiente solo a los 3 s) y el PC anula sus avisos. Sin enlace con el PC
  las tarjetas se ven, pero no se pueden marcar.

**Servidor:** el centro de avisos manda `tasks` (uid, título, materia, fecha en segundos) al conectar el teléfono y cada vez que
cambian; el teléfono contesta con `tasks` `done` + `uid`. Se incluyen las entregas vencidas hace menos de 7 días que no se hayan marcado
(el calendario de Moodle no dice si ya entregaste: por eso el visto).

**Pruebas:** 5 nuevas en el servidor (`test_moodle.py`) y 17 en Android (`TableroTest`: columnas y fronteras, textos de tiempo, estado,
protocolo, confirmación del visto, sin enlace, vacío, y abrir desde la página del mosaico).

**Límites:** nada probado todavía con el dedo ni en el teléfono real; las horas se muestran en la zona horaria del teléfono.

## B6 · Racha del gimnasio: la llama en la cara y Vega en el gimnasio

Capturas: `28` (la llama con el contador en la cara de Vega), `29` (Vega dormida), `30` (a mitad del salto al encenderse),
`31` (encendida), y `24`–`27` (el calendario de la racha: encendida de día y de noche, brasas, apagada).

- **En la pantalla principal**: una llama pequeña con el número de días vive en la cara de Vega (a la derecha, a media altura).
  Arde si ya fuiste hoy, está quieta si no, y dice «¡hoy!» si hoy es el último día para no perderla. Al tocarla se abre…
- **…Vega en el gimnasio** (pantalla completa). Es una escena con luz, no un dibujo plano:
  - *Dormida* (hoy aún no fuiste): de noche, azul profundo, luz de luna fría; Vega en tonos de luna con los ojos cerrados,
    las gafas de sol en la frente sobre la cinta roja, una burbuja de sueño que se infla y zetas que suben; una plataforma
    con un borde de luz tenue. Abajo: «Toca a Vega al volver del gimnasio».
  - *Al tocarla*: se agacha, salta (aplastamiento y estirón), las gafas caen a los ojos con rebote, aterriza, saca doble
    bíceps; una onda de luz sale de la plataforma y el fondo arde de abajo arriba (rojo, naranja, oro), con rayos detrás,
    una corona de fuego y chispas que suben.
  - *Encendida*: rebota al ritmo y bombea un brazo y luego el otro; tocarla otra vez le hace sacar más músculo (sin contar
    dos veces).
  - *A la derecha*: los días en grande (con resplandor de fuego cuando arde), una pastilla de estado («¡Encendida hoy!»,
    «Puedes descansar 1 día más», «¡Hoy o se apaga!»), la semana con una llamita por día, la mejor racha y el total, y
    «Calendario» (la hoja con las seis semanas). «Apagar hoy» deshace un toque sin querer.
- **Las reglas** (pensadas para el gimnasio): puedes descansar hasta **2 días seguidos** sin perder la racha; mientras
  descansas la llama se hace más pequeña y roja (brasas el último día) y, al tercer día sin ir, se apaga.
- **Por voz** (también desde Telegram): «fui al gimnasio», «ayer fui al gimnasio», «¿cómo va mi racha?», «no fui al
  gimnasio» (deshace hoy).

**Servidor:** tabla `gym` (un día por fila); mensaje `streak` (get, light, out) → evento `streak`, que el centro de avisos manda
al conectar y cada vez que cambia (también al pasar el día, porque la llama se va apagando).

**Pruebas:** 22 en el servidor (`test_streak.py`) y 25 en Android (`RachaTest`, `GymTest`).

## B7 · Transiciones

Capturas: `32` (el gimnasio abriéndose desde la llama) y `33` (la música creciendo desde su tarjeta).

- **Gimnasio**: un círculo de fuego nace de la llama de la cara de Vega y se abre hasta cubrir la pantalla (0,76 s);
  dentro, Vega entra de un salto desde la plataforma y el panel se desliza desde la derecha. Al cerrar, el círculo se
  recoge hacia la llama.
- **Música y juego**: la pantalla crece desde su tarjeta del mosaico (las esquinas se enderezan, el resto se oscurece y el
  contenido aparece) y, al cerrar, se encoge de vuelta a ella.

## B8 · Avisos del PC (todos) y tarjetas ordenadas; Vega Fit ilustrada; menos tirones

- **Pantalla «Avisos del PC»** (`34`, `35`): al tocar la tarjeta de avisos, todos los avisos en columnas, una por app (como
  el tablero de Moodle), con la barrita del color de la app, quién, qué (hasta 3 líneas) y hace cuánto. Cada uno se quita
  con su «×»; «Borrar todos» pide un segundo toque. Con más de cuatro apps, las columnas se deslizan de lado. Crece desde su
  tarjeta, como la música.
- **Tarjeta de avisos** (`36`): dos filas con aire (antes eran tres pegadas y «+12 más» encima), la cuenta en una pastilla y
  «Ver todos ›». **Tarjeta de Moodle**: la columna de la derecha empieza debajo de los puntos de página y enseña como mucho dos.
- **Vega Fit ilustrada** (`29`–`31`): estilo de mascota con sombreado de dibujo animado (color base, sombra nítida, brillo y
  contorno uniforme). Brazos y piernas son una sola forma suave cada uno (el bíceps es un abultado del contorno, no una bola).
  Dormida está sentada en el banco con las manos en las rodillas; despierta, de pie con doble bíceps.
- **Menos tirones**: el reloj de la animación ya no recompone la pantalla en cada cuadro (solo la redibuja); las transiciones
  recortan con la GPU (forma de la capa) en vez de recortar por software, y hay menos llamas y rayos por cuadro.
