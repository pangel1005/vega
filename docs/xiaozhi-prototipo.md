# Satélites de voz ESP32 con el protocolo «xiaozhi» (prototipo)

Fecha: 11-11-2026 · Estado: **prototipo en el servidor, desactivado por defecto, sin probar con una placa real**.

## La idea

`78/xiaozhi-esp32` es un firmware abierto (MIT) para placas ESP32 con micrófono, altavoz y pantalla. La placa detecta la
palabra de activación por sí sola (ESP-SR) y habla con un servidor por WebSocket; el servidor oficial está en la nube.
Aquí el servidor es **Vega**: Whisper para entender, el modelo del PC y las habilidades para contestar, Kokoro para hablar.
Así una placa de pocos euros en otra habitación sería un altavoz más de Vega, sin tocar el teléfono.

## Qué hay hecho

- `server/ws/xiaozhi.py`: el punto de conexión `ws://127.0.0.1:8000/xiaozhi/v1/` (con el mismo token de Vega,
  `Authorization: Bearer …`).
  - Saludo (`hello` ⇄ `hello`), `listen` (`start` en modo auto, manual o realtime; `stop`; `detect`), `abort`.
  - Contesta con `stt` (lo que oyó) y `tts` (`start`, `sentence_start` con el texto, `stop`) más la voz en Opus.
  - Los tres marcos de audio del protocolo (versión 1: Opus sin más; 2: cabecera de 16 bytes; 3: cabecera de 4).
  - En modo auto, el servidor corta las frases por silencio (el mismo detector de la escucha continua).
  - La voz sale por una cola a ritmo de reproducción (5 paquetes de golpe y luego uno cada 60 ms) para no desbordar la placa.
  - Un aborto tira lo que quedaba de la respuesta, también lo que llegue tarde.
  - Cada pregunta usa las habilidades, botones por voz, significado y modelo, igual que hablarle al teléfono.
- `server/speech/opus.py`: Opus mono de 16 kHz en paquetes de 60 ms ⇄ PCM, con PyAV (ya instalado con Whisper).
- `[xiaozhi] enabled = false` en `config/assistant.toml`.
- `server/tools/xiaozhi_demo.py`: una «placa» de mentira para probarlo sin hardware:
  `.venv\Scripts\python.exe -m server.tools.xiaozhi_demo --text "¿qué hora es?"` (con el servidor en marcha y el ajuste activado).
- Pruebas: `server/tests/test_xiaozhi.py` (21: Opus, marcos, acceso, turnos manual y auto, versiones 2 y 3, sin voz, sin
  Whisper, abortos, basura y dos preguntas seguidas). La suite entera pasa (535).

## Lo que NO se ha comprobado

- **Con una placa real.** Los detalles del protocolo (campos y tamaños de las cabeceras 2 y 3, nombres de emociones, el ritmo
  de envío) salen de la documentación del repositorio, que su propio autor avisa que puede no coincidir con el código.
- **Con el servidor en vivo y Whisper/modelo reales**: solo con dobles de prueba. Pendiente cuando no haya un juego abierto.
- Español: ESP-SR sabe palabras de activación en chino e inglés; una en español («Vega») no está confirmada.
- Mensajes `mcp` (la placa ofrece herramientas): se ignoran.
- Cancelación de eco en el servidor y reconocer quién habla.

## Seguridad: por qué solo escucha en este PC

Vega solo atiende a `127.0.0.1` (y rechaza otros nombres de host). Una placa en la red de casa no llegaría. Abrirlo a la red
local es una decisión aparte y seria: cifrado (`wss://`), un token propio por placa y límites de uso. No está hecho.

## Siguientes pasos, por orden

1. Probarlo en vivo con la «placa» de mentira (cuando no haya juego abierto).
2. Comprar una placa (por ejemplo una ESP32-S3-BOX-3 o un kit M5Stack Atom con Echo; precios sin verificar) y probar con ella
   en el propio PC, compilando el firmware con la dirección de Vega.
3. Decidir cómo abrirlo a la red de casa con cifrado y token por placa.
4. Ideas del protocolo que pueden servir al teléfono: verificar la palabra de activación en el servidor con el audio previo
   (`listen detect`), y el mensaje `llm` con la emoción para la cara.
