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

## Lo que se mantuvo desde el principio

- Piezas pequeñas, **probadas** y explicadas.
- Nada simulado presentado como hecho.
- Todo en **español**.
- Ligero con el PC.
- Seguridad en los botones:
  - solo existen los de `config/actions.toml`;
  - lo peligroso pide confirmación;
  - todo queda registrado.

**Cifras al cierre de esta etapa (24-10):** 222 pruebas del servidor y 91 de la app, incluidas
capturas de pantalla dibujadas en el PC para revisar el diseño sin instalarlo.
