# Cómo ha evolucionado Vega

Vega nació el 21-10-2026 como «un asistente pequeño que viva en un teléfono conectado al
PC, sin acaparar la tarjeta gráfica». Este documento cuenta qué se fue construyendo, en
qué orden y, sobre todo, **por qué cambió de rumbo** cada vez. El detalle de cada paso
está en el historial de git.

## 1. Los cimientos (21-10, tarde)

Se empezó por piezas pequeñas y comprobables, cada una con pruebas:

- **Servidor en el PC** (Python + FastAPI). Solo escucha en `127.0.0.1` y pide un token.
- **Chat en streaming** por WebSocket con un modelo local (Qwen 3.5 4B con Ollama).
- **Historial en SQLite**, que sobrevive a los reinicios.
- **Voz a texto** con Whisper, y el modelo recibe la fecha y la hora en cada pregunta.

La primera interfaz fue una **web en el navegador del teléfono**, con estética NERV
(Evangelion).

## 2. De web a app nativa, y de chat a botonera (21-10)

- **El usuario no quería una web**, sino algo «del sistema» y más ágil. Se hizo una
  **app nativa Android** (Kotlin + Jetpack Compose), pensada para el teléfono en
  **horizontal**.
- **Cambio de idea:** no escribir nunca. Vega pasó a ser **«asistente + Stream Deck»**:
  - se le habla;
  - tiene botones para apagar el PC, grabar, música y abrir apps;
  - los botones peligrosos piden confirmación al servidor.
- **Escucha continua «Vega, …»**, como Alexa, en lugar de un botón para hablar.
- **Trabas del teléfono:** un Xiaomi sin SIM no deja instalar por cable. Por eso la app
  **se actualiza sola** desde el PC con el botón **Actualizar**.

## 3. Vega habla (22-10, madrugada)

- **Voz:** primero Piper (robótica); después **Kokoro**, más natural, en el procesador.
- **Respuesta rápida:** la voz empieza con la primera frase mientras el modelo sigue
  escribiendo. Se recortaron esperas: Whisper sin reintentos, el modelo se precarga al oír
  voz y las respuestas son más cortas. Así se pasó a **~1,5 s hasta oírla**.
- **Funcionar sin tocar nada:**
  - **vigilante USB**, que enlaza el teléfono solo al conectarlo;
  - arranque con un comando;
  - arranque automático con Windows.

## 4. Útil de verdad (22-10)

**Habilidades** que no usan el modelo de lenguaje (rápidas y fiables):
- memoria sobre el usuario;
- recordatorios y temporizadores (con vibración);
- notas;
- estado del PC.

**Voz más natural:** acento latino, pausas entre frases y horas leídas como se dicen
(«seis de la tarde», no «dieciocho cero cero»).

Medido y **descartado:** Qwen3-TTS, una voz más humana pero más lenta que el tiempo real
en esta gráfica.

## 5. Tres diseños hasta dar con el bueno (22-10)

1. **Consola NERV con telemetría:** más datos en pantalla, pero «demasiado simple y la cara
   sin personalidad».
2. **Cara de LED** (48 × 20 puntos), elegida entre tres propuestas animadas. Tiene
   personalidad:
   - mira, parpadea, guiña y tiene manías;
   - expresiones para cada estado;
   - los recordatorios cruzan la cara en letras LED.
3. **«Vega · noche»:** el usuario quiso dejar Evangelion. Fondo negro, tarjetas
   redondeadas, un color propio (coral) y tipografía de puntos.

**Fluidez:** el teléfono iba a 60 Hz (HyperOS no deja más) y redibujaba la pantalla
entera en cada cuadro.
- Las teclas pasaron a actuar **al tocar**, no al soltar.
- La cara va en su propia capa y el resto queda en caché: de 17,7 ms a **~10 ms por
  cuadro**.
- Medido con toques reales: **7 ms** del dedo a la respuesta.

**Extras de esta etapa:**
- iconos reales de las apps;
- colores de la cara, con arcoíris;
- seguir la conversación sin repetir «Vega»;
- que no se oiga a sí misma.

## 6. Robusto (22-10)

- **Vega se duerme** cuando el PC se apaga: reloj de noche y brillo bajo. Se despierta
  sola al volver.
- **Arranque con Windows:** el acceso directo de Inicio no siempre se ejecutaba. Ahora es
  una **tarea programada**, y lo que pasa al arrancar queda en `logs/arranque.log`.
- **Vigilante:** una noche el servidor se colgó horas. Ahora una tarea lo reinicia si deja
  de responder.
- **Privacidad:** el registro ya no guarda lo que se dice cerca del micrófono.

## 7. La tarjeta gráfica, libre (22-10)

**El problema:** el usuario no podía usar el PC con normalidad: la gráfica estaba en
7,5 de 8 GB y Vega ocupaba ~4 GB.

| Prueba | Gráfica | Hasta oír su voz | Resultado |
|---|---|---|---|
| Todo en el procesador | 0 MB | 5–8 s | Demasiado lento |
| **1 capa del modelo en la gráfica** | **~700 MB** | **~3–4 s** | Elegido |

Basta una capa en la gráfica porque llama.cpp usa la tarjeta para leer la pregunta, que es
lo lento en el procesador.

## 8. Música en el teléfono (23-10)

- **Pantalla «Sonando»:** lo que suena en el PC (Spotify, el navegador…), con carátula,
  progreso y controles. Se pasa de la música al estado del PC deslizando el dedo o con un
  selector.
- **Modo letra:** letras **sincronizadas** de lrclib.net, lo único que usa internet y se
  puede apagar. La línea que suena va resaltada.
- **Transición:** al entrar o salir del modo letra, la carátula, el título y los botones
  viajan a su sitio.

## 9. Hacia la IA en el teléfono (23-10)

**Objetivo:** que Vega funcione sin el PC. Medido en el Redmi 15C (detalle en
[ia-en-el-telefono.md](ia-en-el-telefono.md)):
- modelo de 0.8B: ~9 palabras/s, pero responde pobre;
- Whisper base: ~2,2 s por frase;
- voz Piper: más rápida que el tiempo real.

## 10. La IA en el teléfono, construida (24-10)

**Por qué:** el usuario quería jugar sin que Vega ocupara la gráfica.

**Qué se hizo** («IA: teléfono», en «Más»):
- **Escucha:** el teléfono transcribe con Whisper (sherpa-onnx, dentro de la app) y
  reconoce «Vega».
- **Respuestas con el PC encendido:** el PC resuelve las habilidades y los botones sin modelo
  de lenguaje y saca el modelo de la gráfica (0 MB de vídeo).
- **Respuestas sin PC:** el teléfono tiene sus habilidades: hora, cuentas, chistes,
  recordatorios, notas y memoria. Lo que guarda pasa al PC al volver.
- **Voz:** se midió Kokoro en el Redmi y era 2,7–4 veces más lenta que hablar, así que se
  descartó allí. Con el PC encendido, el teléfono le pide al PC su voz Dora.
- **Charla sin PC:** llama.cpp compilado para Android, con un puente propio en C++ y el modelo
  Qwen 3.5 de 0.8B. Dice cada frase en cuanto la escribe.

**Lo que salió mal y se corrigió:**
- **Bucle de voces:** en los segundos de seguimiento tomaba como orden su propio eco o la tele,
  contestaba «no sé» y volvía a empezar, a veces con voces distintas. Ahora en ese momento se
  calla si no entiende, filtra su propio eco y descarta la voz del PC si llega tarde.
- **Tras «¿Sí?» ignoraba la pregunta** si no repetías «Vega». Corregido.
- **«Actualizar» no hacía nada:** con el PC muy ocupado, el APK tardaba más de 60 s en bajar.
  Ahora la app espera hasta 10 minutos.

**Lo que se aprendió probándolo de verdad:** el teléfono solo funciona, pero claramente peor
que el PC.
- Le cuesta oír «Vega»: la reconoció 1 de cada 4 veces.
- La charla tarda ~6,4 s y responde con frases muy cortas.

**Siguiente paso propuesto:** con el PC encendido, que escuche y piense el PC **solo con el
procesador** (Whisper y el modelo de 4B, sin gráfica); el teléfono solo, cuando el PC está
apagado. En marcha: un detector propio de «Vega» entrenado con frases generadas
(`tools/wake/`).

## 11. Notificaciones del PC (24-10)

- **Panel lateral:** las notificaciones de Windows (WhatsApp, Discord, correo…) aparecen en una
  barra a un lado de la pantalla, que se abre sola unos segundos cuando llega una.
- **Descartar:** se pueden quitar desde el teléfono y desaparecen también en el PC. «Borrar
  todo» las quita una a una: la orden de Windows para borrarlas todas de golpe no hace nada
  desde una app de escritorio.
- **Por voz:** «¿qué notificaciones tengo?» y «léeme el último mensaje (de Discord)».
- **Privacidad:** su contenido solo viaja por el cable y no se guarda.

## 12. Más personalidad en el diseño (24-10)

El usuario rechazó tres diseños nuevos: «no tienen personalidad». Se evolucionó el que ya
había, con una idea: **todo está hecho de luz de LED, como la cara**.
- **Iconos de las teclas:** rejillas de LED, incluidos los iconos reales de las apps, con sus
  colores.
- **Tira de luz en cada tecla:** se enciende al pulsar.
- **Vega mira lo que pasa:** las teclas al pulsarlas, los avisos cuando llegan y la música
  cuando cambia.
- **Los bordes de la pantalla toman el color de su ánimo**: escuchando, pensando o hablando.
- **Subtítulo de conversación:** lo que dijiste y su respuesta, escrita letra a letra.
- **Cuenta atrás del temporizador** en la boca de la cara.
- **Vibración ajustable** al tocar: fuerte, suave o ninguna.

**Estilo «Cristal» (prototipo):** una aurora del color de su ánimo detrás, y tarjetas y teclas
de cristal ahumado. Se midió en el Redmi hasta que fue tan fluido como el estilo de siempre:

| Versión | Tiempo por cuadro | Tirones en 10 s |
|---|---|---|
| «Noche» (referencia) | 21 ms | 0 |
| Cristal pintado en cada cuadro | 29–34 ms | 1–10 |
| Cristal repintado 4 veces por segundo | 22–23 ms | 30 |
| **Cristal pintado una vez en imagen pequeña, aurora quieta** | **18–23 ms** | **0** |

## 13. Más robusto (24-10)

- **Sin ventanas de terminal:** el vigilante abría una ventana de PowerShell cada 5 minutos;
  ahora las tareas arrancan con `conhost --headless`.
- **Chivato de cuelgues:** si el servidor deja de atender más de 20 s, escribe en
  `logs/cuelgues/` en qué línea estaba cada parte. La causa del cuelgue de aquella noche
  sigue sin conocerse; la próxima vez quedará rastro.
- **Apagado limpio:** `stop.ps1` pide al servidor que se cierre solo y solo lo fuerza si no lo
  hace. Cerrarlo a la fuerza dejó una vez el proceso medio muerto 2 minutos.

## 14. El teléfono, solo para Vega (25-10)

El usuario pidió que el Redmi viviera solo para Vega y autorizó tocarlo. Se hizo con `adb`, sin
root, y todo se puede deshacer (`tools/phone_dedicate.py --undo`):
- **99 apps quitadas** (juegos con anuncios, redes sociales, la nube de Xiaomi y de Google…),
  incluidos Google Play Services y Play Store, que reaparecían al instante y pesaban unos 480 MB.
  Las de usuario se apagan (`pm disable-user`); las de sistema, que Android no deja apagar, se
  quitan solo de su usuario (`pm uninstall -k --user 0`) y no se borran del teléfono.
- **Vega, prioridad total:** fuera del ahorro de batería, pantalla encendida cargando y **pantalla
  de inicio** del teléfono.
- **Medido:** RAM libre 1186 → 1434 MB y 21 → 17 ms por cuadro.

También: **botón de dormir** en los dos modos (y «Vega, duérmete»). Dormida, no escucha y suelta los
modelos de la memoria (~600 MB en el teléfono); tocar la pantalla la despierta. El modelo de charla
del teléfono se carga solo al primer uso y se suelta a los 3 minutos.

## 15. Vega siempre activa y la app de escritorio (25-10)

- **Servicio en primer plano:** Vega sigue escuchando con la pantalla del teléfono en segundo plano
  (y la necesita el coche). El controlador es uno solo y arranca con el primer «dueño» (pantalla,
  servicio, coche) y para con el último.
- **App de escritorio nativa** (Qt/PySide6, sin navegador) en la bandeja de Windows:
  [docs/escritorio.md](escritorio.md). Panel en vivo, conversación, edición de botones y de la
  pantalla del coche, ajustes, control del servidor y registros.
- **Sincronización con el teléfono:** el saludo lleva un rol (`phone` o `desktop`). El teléfono
  publica su estado, el escritorio lo ve, ve en espejo lo que Vega oye y responde, y le manda órdenes
  (dormir, modo de IA, estilo, micrófono). El servidor solo admite esas órdenes.
- **Una lección de las pruebas:** el cliente de pruebas de Starlette pone cada conexión en su propio
  bucle de eventos, y un envío entre dos bucles no despierta al otro; las pruebas de sincronización
  comparten un solo bucle, como el servidor real.

## 16. Vega en el coche (25-10)

[docs/coche.md](coche.md). Pantalla de **Android Auto** (categoría «IoT») con hasta 6 casillas que se
configuran desde el PC (`config/car.toml`): dormir, música, avisos del PC, estado del PC, cambiar de
IA, botones del PC con confirmación y datos del coche.
- **Límites a propósito:** Vega no escribe nada en el coche. Los datos del coche (OBD-II) son solo
  de lectura: el código solo puede enviar al lector una lista cerrada de órdenes, comprobado con pruebas.
- **Probado:** lógica, pantallas con el coche de mentira de Google y el lector OBD simulado.
  **Sin probar:** con el emulador DHU ni con un coche real; hace falta Google Play (que se había quitado)
  y la cuenta del usuario. `tools/phone_dedicate.py --car` lo repone.

## 17. El cerebro del teléfono, medido y arreglado (25-10)

Un agente midió en el Redmi el modelo de charla y salió un fallo de verdad: **`libvegallm.so` se compilaba
sin instrucciones `sdot`** (PowerShell 5.1 partía el argumento `-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod` en el
punto). Con dotprod, leer 137 tokens pasa de 11,5 s a 4–5 s. Además: las instrucciones del modelo se leen
**una sola vez** y su estado se guarda (primer token de ~4 s a ~0,7 s), 4 hilos para leer y 3 para escribir,
la frase «si no sabes algo, di que no lo sabes» (trampas bien contestadas de 4 a 8 de 15) y el modelo
**LFM2.5 1.2B Q4_0** (3,55/5 frente a 2,05/5 del Qwen 0.8B en las mismas 20 preguntas; medidas de una sola
muestra y subjetivas).

**Filtro barato de «Vega»** (`WakeGate`): una red pequeña decide en ~52 ms (medido en el Redmi) si una frase
puede llamarla, antes de gastar Whisper. En el PC, con voces sintéticas que no vio al entrenar, dejaba pasar
el 99 % de las llamadas y descartaba el 64 % del resto. **En el teléfono real descartó solo el 6 %** (698
frases en ~34 h, 653 pasan): lo ambiental real no se parece a las voces sintéticas con las que se entrenó.
Lección: sin grabaciones reales (con permiso y para borrarlas después) el filtro no sirve de mucho; por
suerte el coste real de Whisper es bajo (~20 frases por hora ambiente, ~30 s de procesador por hora).

## 18. Mantenimiento del PC y los huecos de la fila principal (25-10)

- **Liberar RAM / limpiar disco** con vista previa, categorías cerradas y confirmación antes de borrar; botones
  `Liberar RAM` y `Limpiar disco` también por voz y desde el teléfono (solo lo seguro). Medido: Vega ocupa ~0,6 GB,
  Ollama ~0,07 GB sin modelo; lo que más RAM gasta es el navegador (~12 GB) y los servicios de Gradle. Reducir lo
  que ocupa un programa **no** sube el dato de «libres» de Windows (la memoria pasa a la reserva): lo que sí lo
  sube es descargar el modelo de Ollama o detener Gradle.
- **Huecos de la fila principal del teléfono** en la página «Botones»: elegir, mover y mandar a «Más».

## 19. Más funciones (28-10)

- **Rutinas** (`kind = "routine"`): varios botones y esperas con una sola orden. Sin rutinas anidadas, máximo 8
  pasos y 60 s, y la confirmación se hereda (una sola cubre todo). Ejemplo: «Modo noche».
- **El tiempo** (Open-Meteo, opcional y apagado de serie): se baja en segundo plano cada media hora y se contesta
  desde una copia local; si no hay internet, con lo último y diciendo de cuándo es.
- **Resumen del día** («buenos días», «¿qué tengo hoy?») y **alarmas a una hora** («pon una alarma a las 7»).

## 20. Evaluación del proyecto y 21 correcciones (28-10)

Se pidió una evaluación general. Dos revisiones independientes (servidor/PC y app del móvil, de solo lectura,
con medidas reales en el Redmi) y una revisión propia de funciones y diseño. Lo más importante, corregido con su
prueba:

- **La pantalla pintaba 60 cuadros por segundo sin parar**, hasta dormida (1,2 millones de cuadros en 5,6 h; el
  hilo de dibujo, al 53 % de un núcleo; la pantalla es el 67 % de lo que gasta la app). Ahora el ritmo depende del
  ánimo: **en reposo, de 60 a 15 cuadros por segundo y el hilo de dibujo de ~53 % a ~21 %** (medido con
  `dumpsys gfxinfo` y `top`). Además, **modo ambiente**: a los 10 min sin actividad el brillo baja.
- **«Cancelar apagado» esperaba a que Vega terminase de contestar** (pedido a los 0,3 s, se ejecutaba a los 3,1 s,
  y con Ollama real hasta 120 s). Lo lento va ahora en una cola aparte y los botones se atienden al momento.
- **Fuga de ~400 MB nativos** si se cancelaba la carga de la IA del teléfono; **el micrófono podía morir en
  silencio** (ahora avisa y reintenta); **una frase que llegaba con Vega ocupada se tiraba** (ahora espera si
  sigue fresca).
- **Seguridad:** `/app/models` resolvía rutas de red (UNC) antes de comprobarlas (podía filtrar el hash NTLM);
  un recordatorio con zona horaria rompía todos los demás; el token con caracteres no ASCII lanzaba excepciones.
- **Privacidad:** el registro guardaba el texto de los recordatorios y los títulos de las pestañas del navegador
  (que además viajaban a lrclib.net); lo que se leía de las notificaciones de otras personas quedaba en el
  historial. Ahora no.
- **Robustez:** cifras enormes («999999999999 horas») tumbaban la conexión; `actions.toml` a medias impedía
  arrancar (ahora se guarda con escritura atómica y se arranca con la última copia buena).

**Lo que no se corrigió y sigue pendiente** (con el motivo):
- El **filtro de «Vega» deja pasar el 94 %** de las frases reales (653 de 698 en ~34 h) y ninguna era una llamada:
  entrenado solo con voces sintéticas no distingue la tele ni la conversación del cuarto. Hace falta recalibrarlo con
  grabaciones **reales y con permiso** (el registro ya guarda la probabilidad de cada frase, no lo dicho).
- El **token se entrega en `GET /`** y el rol lo declara el cliente (cualquiera con el token puede usar el
  mantenimiento): sin riesgo con un solo usuario; con dos tokens (uno por rol) se cerraría.
- Descarga de modelos sin reanudar, servicio en primer plano ante reinicios del sistema y llamadas WinRT en el
  hilo del bucle (posible causa de dos parones de 21 y 25 s): sospechado, sin reproducir.

## 21. Estilo «Nostromo» (28-10)

Primer rediseño completo elegido entre ocho propuestas ([docs/propuestas-diseno-2.html](propuestas-diseno-2.html)): una **terminal de
fósforo ámbar** en vez de la cara de LED, inspirada en las consolas de nave de *Alien*. Cuenta en vivo qué oye
(frases, llamadas), qué cerebro usa, el estado del PC, el próximo aviso y lo que suena, escribe la conversación
como un log (la respuesta de Vega se teclea letra a letra) y tiene teclas F1 a F6 con la misma confirmación
de siempre (`¿SEGURO? 8`) y F7 para «Más». Tocar el log equivale a tocar la cara (atención).

- Se elige en «Más → Estilo» (Noche → Cristal → Nostromo) o desde el PC (`phone_control terminal_on/off`).
- Fuente VT323 (licencia OFL), líneas de barrido y viñeta dibujadas en el momento; sin animación continua (se pinta
  cuando hay algo que contar), así que gasta menos que la cara.
- Los contadores del oído son solo cifras (frases, pasadas por el filtro, llamadas; a cero cada día), nunca lo dicho.
- 11 pruebas nuevas y 4 capturas (`29` a `32` en `app/build/capturas`).

## Lo que se mantuvo desde el principio

- Piezas pequeñas, **probadas** y explicadas.
- Nada simulado presentado como hecho.
- Todo en **español**.
- Ligero con el PC.
- Seguridad en los botones:
  - solo existen los de `config/actions.toml`;
  - lo peligroso pide confirmación;
  - todo queda registrado.

**Cifras al cierre de esta etapa (25-10):** 241 pruebas del servidor, 15 de la app de escritorio y 112 de la app del teléfono, incluidas
capturas de pantalla dibujadas en el PC para revisar el diseño sin instalarlo.

## Etapa 23 — Estilo «Orbe»

Segundo rediseño de las ocho propuestas: Vega como **una esfera de luz que respira**, sin cara ni tarjetas.

- El color dice qué hace: verde al escucharte, ámbar al pensar, crema al hablar, rojo al pedir confirmación; en
  reposo toma el color elegido o, si suena una canción en el PC, el de su carátula (con «♪ artista — título» arriba).
- Motas de luz que fluyen hacia la esfera al escuchar, un cometa que gira al pensar y ondas que salen con cada
  trozo de voz; cada aviso del PC es un satélite que orbita (los recién llegados brillan).
- Cuatro anillos finos (CPU, RAM, GPU, batería del móvil) con sus cifras debajo; los que faltan quedan vacíos.
- Se repinta a 25 fps en reposo, 8 fps dormida y a pantalla completa solo cuando actúa.
- Se elige en «Más → Estilo» (Noche → Cristal → Nostromo → Orbe) o desde el PC (`phone_control orb_on/orb_off`).
- 7 pruebas nuevas y 7 capturas (`33` a `39`).

## Etapa 24 — Noche evoluciona y el Orbe se rediseña

Tras probar el Orbe, se vuelve a **Noche** (la cara de LED original) como estilo activo y se evoluciona; el Orbe sigue
disponible en «Más → Estilo» y se rediseña a fondo.

**Noche**
- Tarjeta «Cuerpo»: con la IA en el teléfono, la tarjeta de estado cuenta cómo está el propio Redmi (cerebro, frases y
  llamadas de hoy, estado del PC, memoria y batería) y el temporizador local; la pestaña pasa a llamarse «Cuerpo».
- Osciloscopio de voz: mientras te escucha, una onda de LED bajo la cara muestra tu volumen (se ve que te oye sin leer nada).
- Tocar la respuesta abre la conversación reciente en letra grande; se cierra sola a los 10 s o al tocarla.

**Orbe**
- Esfera de plasma (tres manchas de luz del mismo tono girando por dentro, borde de luz, brillo), contorno que vibra con
  la voz, anillo rojo que se vacía en la confirmación, estrellas de fondo y teclas con el icono real de cada app.
- Medido en el Redmi: sin recortes circulares ni resplandor enorme gasta menos CPU que Noche en reposo (~18 fps).
- 24 pruebas nuevas y capturas `40`, `41` (Noche) y las del Orbe (`33` a `39`).

## Etapa 25 — Noche: carga de la IA, alerta y modo de lejos

- **Barra de LED bajo la cara** según lo que pase: verde con el porcentaje mientras baja el modelo, una luz que corre
  mientras carga, y roja con «Reintentar» (toca para volver a cargar) si falló. Se lee de la pastilla de la IA.
- **Alerta al confirmar:** el marco de toda la pantalla late en rojo y bajo la cara una barra roja se vacía en los
  8 segundos de la cuenta atrás (la cara con la X y «Confirma» ya existían).
- **Modo de lejos:** tras 10 minutos sin nadie (cuando baja el brillo) la pantalla pasa a negro con la hora enorme,
  el estado en una palabra, el temporizador y la última respuesta en letra grande; un toque la devuelve.
- 6 pruebas nuevas y capturas `42` a `45`.

## Etapa 26 — Noche: teclas vivas, agenda, música que tiñe y batería baja

- **Teclas vivas:** la tira de luz de «Play» se enciende y la tecla pasa a decir «Pausa» mientras suena algo (y «Play»
  si está en pausa); la tecla de la app que suena (p. ej. Spotify) también se enciende.
- **Agenda:** la tarjeta del PC enseña los próximos 3 avisos (el primero en grande, los otros en una línea).
- **La música tiñe la pantalla:** en reposo y con música, el halo de la cara y los bordes de las tarjetas toman el
  color de la carátula.
- **Batería baja:** con 15 % o menos y sin cargador, «En línea» pasa a «Batería N %» en rojo.
- 3 pruebas nuevas y capturas `46` a `48`.

## Etapa 27 — Próximo aviso a la vista

- Las pantallas de **lejos** y de **dormida** enseñan el próximo aviso que no es un temporizador: «Alarma 07:00»,
  «Alarma mañana 07:00» o «18:30 · llamar a mamá». Cuentan los avisos del PC y los del propio teléfono (así vale
  también con el PC apagado), y gana el más cercano.
- La pantalla de lejos avisa en rojo si la batería está baja y sin cargador.
- 4 pruebas nuevas y capturas `49` y `50`.

## Etapa 28 — La cara se hace onda con la música, avisos sin borde y una IA que recuerda

- **Onda de música:** mientras suena **Spotify** y Vega está en reposo, la cara de LED se convierte en la silueta de
  una onda de audio (forma continua con brillo, picos en cada golpe y extremos que se afinan hasta una línea), del
  color de la carátula. Sigue **solo a Spotify**: el servidor lee el pico de *su* sesión de audio de Windows
  (`server/device/audio_meter.py`, con `pycaw`), no el del PC entero, así que el navegador, Discord o los juegos no la
  mueven (y tampoco depende del volumen general de Windows). No oye ni guarda audio, solo un número. Lo manda al
  teléfono unas 20 veces por segundo y solo mientras suena Spotify. La silueta resalta lo que sobresale de la media
  reciente (los golpes) y se normaliza con el pico reciente. Medido: el medidor de Windows se actualiza cada ~50 ms,
  así que no hay más detalle real que ese; no hay adornos aleatorios. En cuanto le hablas, piensa, contesta o avisa
  vuelve la cara, y a los 4 s en reposo regresa la onda.
- **Barra de avisos:** sin el borde naranja (ahora como el resto de tarjetas), el número en blanco y el punto de
  «nuevo» también.
- **Animaciones:** los puntos de las barras de estado se encienden de uno en uno al cambiar el valor y la hora cambia
  con un deslizamiento corto.
- **IA del teléfono con memoria:** antes cada pregunta se contestaba sola y las de seguimiento («¿y cuándo nació?»,
  «¿y la de Italia?») salían en inglés o sin sentido. Ahora recuerda los dos últimos intercambios y lleva una pista
  («responde solo en español») pegada a la pregunta. Medido en el Redmi con 12 casos y el modelo LFM2.5 1.2B: los 5 que
  fallaban pasaron a español y con el dato correcto. Se probaron también unos ejemplos de respuesta en las
  instrucciones y otras instrucciones: empeoraban al modelo (respuestas sueltas, un JSON), así que se descartaron.
  El modelo sigue siendo pequeño: a veces se equivoca en datos.

## Etapa 29 — La onda, a partir de referencias: Barras, Neón y Silueta

A partir de dos imágenes de referencia, la onda de Spotify tiene tres estilos que se eligen en «Más → Onda»
(también `phone_control wave_next`):

- **Barras:** cientos de barras finas en forma de lente, simétricas, con el contorno afinándose hasta una línea en los
  extremos y brillo difuso; la mitad de abajo más clara, como un reflejo.
- **Neón:** tres curvas brillantes que se cruzan (alisadas con curvas, no quebradas), línea central con destello que
  respira con la música y detrás columnas de puntos LED a la altura de la envolvente.
- **Silueta:** la forma continua rellena de antes.

La **altura** de todo es el volumen real de Spotify. Lo único decorativo es la ondulación de las curvas del Neón: el
medidor de Windows solo da el volumen (~20 lecturas por segundo), no la forma de la onda.

**Solo audio real:** el servidor busca la sesión de audio de Spotify en **todas** las salidas de Windows (no solo la
predeterminada). Si Spotify suena pero no tiene audio en el PC (pausado, o por otro dispositivo como Spotify
Connect), no manda nada y el teléfono se queda con la cara en vez de pintar una línea plana. Se corrigió además un NaN
en el borde derecho de la onda que habrían roto el dibujo.

Nuevo en el servidor (para el vinilo): `media_control` con `command: "seek"` y `position` (segundos). Comprobado que
Spotify acepta el salto (`is_playback_position_enabled`).

## Etapa 30 — Vinilo interactivo en la pantalla de música

La pantalla grande de música (se abre tocando el mini reproductor) tiene ahora un **vinilo** en vez de la carátula
cuadrada: surcos y separaciones entre canciones, marcas estroboscópicas en el borde que muestran el giro, unos
arañazos que giran con el disco, la carátula en círculo como etiqueta y un reflejo de luz que no gira (por eso se ve
girar). Sonando, gira a 33 rpm y el **brazo baja la aguja** al disco; en pausa, el disco se para (con inercia) y el
brazo se aparta.

Es **interactivo**:
- Pon el dedo en el disco y se detiene; gíralo **hacia la derecha para adelantar** y **hacia la izquierda para
  retroceder** (una vuelta = 30 s). Mientras giras se ve cuánto (+0:12) y el tiempo y la barra siguen tu dedo; al
  soltar, Spotify salta a ese punto (`media_control` con `seek`). Si sueltas en movimiento, sigue girando por inercia.
- Un toque corto sobre el disco pausa o reanuda; tocar fuera del disco no hace nada.

Los gestos se prueban con toques simulados (`VinylTest`): girar a la derecha/izquierda, toque corto, fuera del disco y
un giro minúsculo que sigue siendo un toque. No se pudo abrir la pantalla en el Redmi por `adb` (los toques están
bloqueados): se probó con capturas y toques simulados, no a mano.

## Etapa 31 — Analizador de espectro estilo radio de coche y portada con vinilo

**Analizador de espectro.** La onda de Spotify ya es, de serie, un analizador como los de las radios de coche de los
2000: 24 columnas de segmentos de LED (graves a la izquierda, agudos a la derecha) que suben de golpe y bajan suave,
con un pico que se queda arriba un instante y cae como con gravedad, colores por zonas (el color de la canción, más
claro arriba y rojo anaranjado en la punta) y un reflejo en el «suelo». Se elige en «Más → Onda» (Espectro, Barras,
Neón, Silueta; `phone_control wave_next`).

Es **espectro real**: el servidor (`server/device/audio_capture.py`) pide a Windows el audio de Spotify solo
(«captura de bucle por proceso», código propio sobre `comtypes`, sin binarios de terceros), calcula una FFT en
memoria y manda al teléfono 24 números por instante (`music_spectrum`). El audio no se graba, no se guarda y no sale
del PC; solo se captura mientras suena Spotify, y se apaga con `[media] spectrum = false`. Comprobado en el PC con un
tono de 1 kHz (cae en su banda, el resto a 0) y con Spotify real (24 bandas, ±0,09 de movimiento en bruto: por eso el
dibujo resalta lo que sobresale del nivel habitual de cada banda, si no saldría una pared).

Si Spotify suena por otro dispositivo (sin audio en el PC) no se manda nada y el teléfono vuelve a la cara; si el
espectro no llega, cae a las barras de volumen.

**Portada hecha disco (inspirada en «Familiar»).** El proyecto de escritorio `familiar-digital` (Electron: una esfera
viva que, al sonar música, pasa a ser *la carátula redonda girando* sobre un brillo, y reacciona al audio) marcó la
idea: no un vinilo literal negro con surcos, sino **la portada como disco**. En la pantalla de música (`AlbumDisc.kt`)
la carátula es redonda y gira despacio (una vuelta cada ~9 s, con inercia al pausar), sobre un brillo del color de la
canción que late con el volumen real, y con un **anillo de barras de LED** alrededor que es el espectro real de
Spotify (24 bandas en espejo, con picos). Mejora lo del original, que oía el micrófono: aquí es el audio de Spotify.

- **Girar el disco con el dedo** adelanta (derecha) o retrasa (izquierda) la canción: 1 vuelta = 30 s, se ve «+0:12»
  y al soltar Spotify salta ahí.
- **Un toque** pausa o reanuda; **doble toque** cambia entre el disco y la **portada plana** (cuadrada, quieta, con
  sombra, brillo que late y barras de espectro debajo); se recuerda. Un toque suelto espera ~0,26 s por si llega el
  segundo. En la portada plana vale tocar en cualquier parte y no se gira.

El proyecto «Familiar» estaba en `C:/Users/pange/.gemini/antigravity/scratch/familiar-digital` (fuera de las
carpetas habituales).

## Etapa 32 — Medir cuánto entiende Vega y entender por significado

La sensación de «torpe» se midió: `tools/intent_bench` (148 frases con la intención esperada, escritas por el
desarrollador a partir de las órdenes habituales; no son grabaciones de nadie) enruta cada frase con las reglas de
siempre y dice cuántas acierta. **Solo con reglas: 58–64 %.** Con las frases como están previstas acierta el 100 %,
pero con otras palabras baja al 38–52 %, hablando como se habla de verdad al 20–30 %, y con los errores de oído
(«espotifai») al 38–57 %. No se pasa de lista: solo activa algo de más en 2 de 16 trampas. O sea: el problema no es
la inteligencia sino **entender variantes**.

**Qué se hizo** (`server/skills/semantic.py`): un modelo de embeddings pequeño (paraphrase-multilingual-MiniLM,
~120 MB, ONNX con `fastembed`, en el PC y sin red tras bajarse una vez a `models/embeddings`) compara lo dicho con
ejemplos de cada botón y habilidad. Solo actúa cuando las reglas no entendieron nada, y con cuidado:
- se parece mucho → lo ejecuta; se parece algo → **pregunta «¿Quieres que ejecute …? Di sí o no»** (y espera el sí/no
  ~12 s); no se parece → sigue como charla. Un botón peligroso (apagar) sigue pidiendo confirmación además.
- un botón no se activa con una frase que niega («no quiero apagar») o que pregunta información («qué es Spotify»);
  una habilidad (p. ej. «duérmete») tampoco aguanta una negación ni actúa si duda.
- subir/bajar el volumen lo deciden las palabras (el modelo confunde los opuestos).
- solo resuelve botones y habilidades sin datos («cómo está el pc», «qué tiempo hace»…). Las que llevan datos
  («avísame en diez minutos») siguen en manos de sus reglas, que saben sacar el tiempo.

Sirve tanto a la voz del PC como al teléfono con la IA local mientras haya enlace con el PC (el teléfono pregunta
primero al PC). **No cubre al teléfono sin PC**: ahí siguen las reglas de Kotlin. Se apaga con `[intents] semantic =
false` en `config/assistant.toml` (viene encendido; en el código viene apagado para que las pruebas no bajen nada) y
si el modelo no se puede cargar todo sigue como antes.

**Resultado** (mitad «ajuste» y mitad «prueba», esta última sin tocar al ajustar): **58–64 % → 74–81 %**, sin activar
nada de más en las trampas de la mitad de prueba. Cuidado con leer demasiado: son 74 frases por mitad, las escribió
quien también escribió los ejemplos, y se añadieron ejemplos y frases a los botones al ver fallos. La cifra real
saldrá con frases tuyas de verdad.

Lo que encontró probar con el modelo real y no el banco: «súbele a la música» se tomaba por *pausa* (por «música») y
«no quiero apagar nada» dormía a Vega; ambos corregidos. Sigue sin entender «apágame la compu», «cómo anda la
compu» ni los datos («despiértame a las seis y media»), y la regla antigua «pon el sonido» (silenciar) se adelantaba a
«pon el sonido más alto» (se añadieron frases al botón de volumen).

Siguiente: que el modelo **sepa las palabras de tus botones** (más `say` o ejemplos tuyos), mejor oído con el PC
conectado (Whisper más grande) y entender las órdenes con datos (llamada a herramientas con el modelo del PC).

## Etapa 33 — El oído rápido: el PC transcribe cuando está conectado

«Vega oye lento y responde lento». Medido en el registro del teléfono (solo cifras): Whisper base tarda **1,0–2,7 s**
por frase en el Redmi 15C (0,7–1× el tiempo real) y es lo más lento de la cadena; encima, cada frase incluye 0,5 s de
silencio de cierre y ~0,3 s de audio previo. El mismo modelo en el PC (faster-whisper, CPU int8, 8 hilos) tarda
**~0,55 s** para 3 s de audio, y `small` en la gráfica (fp16) **~0,2 s** (en CPU `small` son 1,7 s: no sirve).

**Qué se hizo:** el teléfono sigue cortando las frases y pasándolas por el filtro de «Vega» (barato), pero si hay
enlace con el PC le manda la frase (`hear`: PCM16 en base64) y el PC contesta con el texto (`heard`). Si no hay PC, el PC
no puede o tarda más de 2,5 s, oye el teléfono como siempre (`HybridEars`). El PC ya tenía el modelo cargado al arrancar.
El audio no se guarda en ningún sitio ni el texto va a los registros; solo viajan por 127.0.0.1 con el token.

Cambiar a un modelo mejor es una línea en `config/assistant.toml` (`stt_model = "small"`, `stt_device = "cuda"`,
`stt_compute = "int8_float16"`: ~0,2 s y mejor español, a cambio de ~1 GB de vídeo mientras el PC está encendido).

## Etapa 34 — La sección «Juego»

Junto a «Cuerpo/PC» y «Música» hay una tercera página, **Juego**, que aparece sola mientras tienes un juego abierto en el
PC (y pasa a ella al empezar). Es una tarjeta con la carátula, el nombre, cuánto llevas hoy y las cifras de la gráfica;
al tocarla se abre una pantalla grande con el reloj de la sesión, tus horas totales, lo que ocupa, géneros, quién lo
hizo, cuándo salió, la nota de Metacritic, la descripción y las cifras en vivo del PC (GPU, temperatura, vídeo, CPU).

**Cómo se sabe qué juego es** (`server/device/games.py`), sin instalar nada: Steam lo apunta en el registro
(`RunningAppID`); el nombre y el tamaño salen de sus `appmanifest_*.acf`, las horas de `localconfig.vdf` del usuario más
reciente y la carátula de su caché local (sin internet). Los juegos de Epic se detectan por tener un programa en marcha
dentro de su carpeta de instalación (sin carátula ni horas). La hora de inicio es la del proceso, no la de cuando Vega
lo vio. Wallpaper Engine y Steamworks no cuentan como juegos (`[games] ignore` para añadir más).

La descripción, géneros y nota vienen de la tienda pública de Steam: solo se le manda el número del juego y se guarda en
`data/games/`; con `[games] store_info = false` solo se usa lo que Steam ya tiene en el PC. Nada de lo que haces dentro
del juego se lee ni se guarda.

Probado con la biblioteca real de este PC (Marvel Rivals: 268 h, 92,1 GB, carátula, nota y descripción), pero con el
juego **cerrado**: la detección en marcha (el registro mientras se juega) solo está probada con simulaciones.

### Etapa 34b — Pestaña fija, secciones y cuenta de Steam

La pestaña **Juego** ya no desaparece: sin juego abierto enseña un mando y «Sin juegos ahora mismo», con los tres últimos
que jugaste (de los `appmanifest` de Steam). La pantalla grande tiene secciones (**Sesión · Juego · Comunidad · Logros**):
- *Sesión*: gráfica de GPU y temperatura desde que empezó (lecturas del PC en el teléfono, solo en memoria), cifras en
  vivo, GPU media, temperatura y vídeo máximos y las horas de las últimas dos semanas.
- *Comunidad*: cuánta gente juega ahora y las últimas novedades (API pública de Steam, sin clave).
- *Logros*: cuántos tiene el juego; con la cuenta conectada, cuántos llevas y los últimos desbloqueados.
- Abajo, los botones de «Más» que sirven jugando: Captura, Grabar, Discord y Silenciar.

**Cuenta de Steam** (opcional): la clave personal («Web API key», gratis en steamcommunity.com/dev/apikey) va en
`config/steam_key.txt` (una línea; fuera de git). Nunca se muestra, no entra en los registros (los fallos de red solo
registran su tipo) y solo se manda a api.steampowered.com. Con ella salen tus logros y qué amigos juegan a lo mismo
(`[games] friends = false` lo desactiva). Tu perfil debe tener «Detalles del juego» en público. **Sin probar en vivo con
una clave real**: las pruebas usan un Steam simulado. Datos de la *partida* en curso (muertes, rango, marcador) no los
da ningún juego de forma general: solo algunos tienen una API local oficial (CS2, Dota 2, League of Legends, Rocket
League) y cada uno necesitaría su propia integración.

### Etapa 34c — La partida en curso (League of Legends y Rocket League)

Sección **Partida** (la primera de la pantalla grande cuando hay datos) y marcador en vivo en la tarjeta. Solo lectura y
solo en el PC (`[games] match`), con las APIs locales oficiales (`server/device/match.py`):
- **League of Legends** — «Live Client Data API» de Riot (`https://127.0.0.1:2999/liveclientdata/allgamedata`, sin clave;
  solo existe durante una partida). Se enseñan *tus* datos (campeón, nivel, K/D/A, súbditos por minuto, oro), el marcador de
  bajas y los objetivos (dragón, barón, torres). Nada que el juego oculte: ni objetos ni hechizos del equipo rival. El
  campo de la política de Riot sobre «información que el jugador no conocía» se respeta por eso.
- **Rocket League** — «Stats API» de Psyonix: socket TCP en `127.0.0.1:49123`. Viene **desactivada**
  (`PacketSendRate=0` en `TAGame/Config/DefaultStatsAPI.ini`); `tools/rocket_league_stats.py --apply` pone 10 (hay que
  reiniciar el juego; `--undo` la deja como estaba). Marcador, tiempo restante, tus puntos, goles, asistencias, paradas,
  tiros y demoliciones, y los últimos sucesos (goles, paradas).

**Estado de la verificación:** League se probó con datos del formato oficial de Riot (documentación y JSON de ejemplo) y
Rocket League con un socket real simulado y los nombres de campo de su documentación, que no pude leer entera (403): se
prueba con una partida de verdad. El detalle de la cuenta de Steam (etapa 34b) sigue igual de sin probar en vivo.

