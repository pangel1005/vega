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

## 9. Hacia la IA en el teléfono (en estudio)

**Objetivo:** que Vega funcione sin el PC. Medido en el Redmi 15C (detalle en
[ia-en-el-telefono.md](ia-en-el-telefono.md)):
- modelo de 0.8B: ~9 palabras/s, pero responde pobre;
- Whisper base: ~2,2 s por frase;
- voz Piper: más rápida que el tiempo real.

**Plan:** habilidades, escucha y voz en el teléfono, y el modelo pequeño como extra.

## Lo que se mantuvo desde el principio

- Piezas pequeñas, **probadas** y explicadas.
- Nada simulado presentado como hecho.
- Todo en **español**.
- Ligero con el PC.
- Seguridad en los botones:
  - solo existen los de `config/actions.toml`;
  - lo peligroso pide confirmación;
  - todo queda registrado.

**Cifras al cierre de esta etapa:** 201 pruebas del servidor y 50 de la app, incluidas
capturas de pantalla dibujadas en el PC para revisar el diseño sin instalarlo.
