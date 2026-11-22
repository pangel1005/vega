# Plan de mejoras de Vega (3-11-2026)

Qué se puede hacer a continuación, ordenado por lo que más se nota en el día a día. Sale de tres cosas: lo que
ya mide y hace Vega, lo que pasó esta semana, y una investigación en la red hecha el 3-11-2026.

**Cómo leerlo.** Cada idea dice qué es, por qué encaja, cuánto cuesta (S = horas, M = 1–3 días, L = una semana o
más), el riesgo y qué necesito de ti. Lo medido por mí aparece como «medido»; lo que sale de artículos, como
«según…» (casi todo son blogs y fichas de modelos, no pruebas independientes). **Ninguna cifra de la red está
probada en tu PC ni en el Redmi** salvo donde diga «medido».

## Las apuestas que impresionan (segunda versión, 3-11-2026)

La primera versión de este plan era sensata pero pequeña: mejoras de base. Esta parte pone lo llamativo primero y deja
lo demás (más abajo) como cimientos. Antes de proponer miré qué tienes de verdad, para no inventar:

| Lo que medí hoy | Resultado | Qué significa |
|---|---|---|
| Razer Chroma (luces) | Tienes un ratón DeathAdder V3 y los auriculares Barracuda X, pero la API de luces no responde (puerto 54235 cerrado) y esos aparatos casi no tienen luces | **Descartado** el «teclado que reacciona a Vega» |
| Wallpaper Engine | **239 fondos** instalados, todos con título y etiquetas (Anime 88, Juegos 61, Paisaje 20, Pixel art 16, Ciberpunk, Relajante…); admite órdenes por línea de comandos | Control real y muy vistoso |
| Redmi 15C | Sin infrarrojos, sin NFC, sin SIM; Bluetooth LE, Wi-Fi, acelerómetro, luz y proximidad. **Wi-Fi a –87 dBm** donde está | El cable es lo correcto; nada de «mando de la tele» con este móvil |
| Programas del PC | Discord, Spotify, Steam, Epic (Fortnite), Riot, OBS (instalado por la cámara), Wallpaper Engine, WhatsApp, Phone Link | Hay mucho con lo que conectar |

**Las apuestas, de más a menos espectacular para lo que cuestan:**

### A. «Vega manda en tu PC» — todo por voz (coste M en total, cada pieza es S)
- **Fondos de pantalla por voz, ánimo y música.** «Vega, pon un fondo de Fortnite», «uno tranquilo», «sorpréndeme».
  Vega lee los títulos y etiquetas de tus 239 fondos y entiende por significado qué quieres; cambia solo al ritmo del
  día (amanecer, noche) o con el color de la carátula que suena. Fuente: la página oficial de
  [control por línea de comandos](https://help.wallpaperengine.io/en/functionality/cli.html) (`-control openWallpaper`).
- **Mezclador de volumen por voz.** «Baja Discord», «silencia el juego», «pasa el sonido a los auriculares».
  Windows lo permite por su servicio de audio; hoy Vega solo sube y baja el volumen general.
- **«Abre Fortnite».** Vega ya sabe qué juegos tienes (Steam y Epic); falta lanzarlos por voz.
- **Modo juego automático.** Al abrir un juego: pasa el sonido a los auriculares, activa «no molestar», guarda a Vega en
  la gráfica (ya lo hace), y el teléfono pone la página del juego. Al cerrarlo, todo vuelve.

### B. «Intérprete en vivo»: subtítulos traducidos de cualquier sonido del PC, en el teléfono (M)
Juegas con gente que habla inglés, ves un vídeo sin subtítulos o entras a una llamada: Vega escucha lo que suena en el
PC, lo transcribe, lo traduce al español con su modelo y lo muestra como subtítulos en el Redmi. **Sin ninguna capa
encima del juego**, así que el sistema antitrampas (Vanguard) no tiene nada que objetar. Hay programas de código
abierto que hacen justo esto en Windows con la captura de audio del sistema
([whisper-burner](https://github.com/nkmnhan/whisper-burner): recomienda gráfica NVIDIA y, en procesador, el modelo
`small` para ir casi en directo). *Para ti:* jugando, usaría el procesador (`base`/`small`), con un tope de consumo.
*Privacidad:* nada se guarda; solo las apps que elijas; las voces de otros se transcriben al vuelo y se olvidan.

### C. «Vega ve» (M) — la cámara del Redmi como ojos
«¿Qué es esto?», «léeme esto», «**tradúceme esto**» (apuntas a una caja o un cartel y te lo dice en español),
«escanea esto» (el texto aparece en el portapapeles del PC o se abre el enlace). El modelo que ya usas tiene visión y
lo medí: una imagen en 3,6 s. Nada se guarda.

### D. «Copiloto de partida» y «tu día» (M)
- Un clip automático al ganar una ronda o hacer una racha (OBS puede guardar los últimos 30 s con su
  [interfaz WebSocket](https://www.powershellgallery.com/packages/obs-powershell/0.1.2/Content/Commands%5CRequests%5CSave-OBSReplayBuffer.ps1)),
  y «Vega, guarda eso» por voz; el teléfono lo celebra y lee el resumen: «ganaste 13–9, tu mejor ronda fue la 7».
- **Tu día / tu semana**, como el resumen anual de Spotify pero tuyo: horas por juego, canciones, cuántas veces te
  avisó el PC y de qué, lo que dejaste pendiente. Todo con datos que Vega ya tiene; nada nuevo que grabar.

### E. Conectividad: tres ideas de verdad distintas
1. **Presencia por Bluetooth (M).** El Redmi detecta tu reloj, auriculares o tu móvil principal: bloquea el PC y apaga
   la pantalla cuando te alejas y te saluda al volver. Hay proyectos de código abierto que lo hacen
   ([go-bt-lock](https://www.github.com/sercanarga/go-bt-lock), [NearLock](https://github.com/baptisteba/NearLock)),
   con un aviso honesto: los móviles no emiten siempre, y se pierde fiabilidad. *Necesito saber:* ¿tienes reloj o
   auriculares Bluetooth que lleves encima?
2. **Vega en tu móvil principal, fuera de casa (L).** Por un túnel privado cifrado, desde cualquier sitio: «¿terminó la
   descarga?», «dime cómo va el PC», «mándame el PDF de…», «avísame cuando acabe el renderizado». Hoy el servidor solo
   escucha en el propio PC y por cable; abrirlo exige una puerta bien cerrada (clave por dispositivo, solo cifrado,
   apagado por defecto, nunca en el coche). **Es la más ambiciosa y la que más cuidado pide.**
3. **El portapapeles y los archivos entre el PC y Vega (S–M).** «Mándame esto al teléfono»; lo que apuntas con la cámara
   aparece en el PC. (El móvil no tiene SIM: esto es un puente solo con el PC.)

### F. Reuniones y clases con resumen (M, solo si lo quieres)
«Vega, escucha esta clase»: transcribe (con quién habla), resume, saca las tareas y las pone en tus recordatorios, y
luego puedes preguntar «¿qué dijo del examen?». **Siempre con tu orden explícita, con aviso en pantalla, y el audio se
borra al transcribirlo.**

### G. Lo que hace fiable todo lo anterior (la parte de abajo)
Voz del dueño, palabra «Vega» propia, el servidor que se levanta solo, el medidor de turnos y un oído más ligero: son la
Fase 0 y la Fase 1 de más abajo. No lucen, pero sin ellas lo llamativo falla de formas molestas.

---

## Lo que no se negocia (viene de lo que has pedido)

- Ligera con la gráfica: el vigilante ya aparta a Vega cuando abres un juego (hoy, con Fortnite, bajó solo a 1 capa).
- El teléfono es el cuerpo; el PC, el cerebro. Equipos modestos como objetivo.
- Todo local; ni grabar audio ni guardar frases sin tu permiso explícito; nada de APK de terceros en el Redmi.
- En el coche, nada de datos del PC.
- Diseño: nada del aspecto viejo (Vega 2).

## Punto de partida (medido)

| Qué | Hoy |
|---|---|
| Entender lo que dices (Whisper small, gráfica) | 0,27 s de mediana por frase de ~2 s |
| Respuesta del modelo del PC (4B) | 0,6–2,9 s con 24 capas en la gráfica; 3,5 s con 1 capa (modo juego) |
| Voz (Kokoro, procesador) | primer sonido a los 0,8–1,5 s |
| Calidad en español (20 preguntas) | 4 de 12 limpias antes del cambio de instrucciones; 11 de 12 después |
| Memoria del PC libre ahora mismo | 7,5 de 31,9 GB (el escritorio y el juego ocupan mucho) |
| `qwen3:8b` ya instalado, con 20 capas en la gráfica | 5–7 palabras/s (5–6 s por respuesta corta): demasiado lento |
| Visión del modelo 4B | lee una imagen en 3,6 s |

## Fase 0 — Esta semana: que no se rompa y se pueda medir

**0.1 Que el servidor se levante solo y la app diga la verdad.** *(S)*
Hoy la app se quedó en «reconectando» porque el servidor llevaba parado desde que terminó la sesión anterior, y el
vigilante no lo levanta: si el puerto 8000 no escucha, cree que lo paraste tú. Propuesta: `stop.ps1` deja una
marca «parado a propósito»; sin marca, el vigilante lo arranca. En la app, «reconectando» pasa a decir qué falta:
«sin cable», «el PC no responde» o «servidor apagado». *Riesgo bajo. Necesito: nada.*

**0.2 Medidor de turnos.** *(S)* Un registro por turno con las tres esperas (oír, pensar, hablar) y un resumen
semanal en la app del PC. Sin guardar lo que dices. Sirve para decidir con números el resto del plan.

**0.3 Banco de pruebas de la IA.** *(S)* Convertir las 20 preguntas que usé en un script de la carpeta `tools/` que
se pasa antes de tocar instrucciones o modelos y puntúa: idioma, preguntas de relleno, cuentas, datos tuyos fuera
de lugar. Hoy está solo en mi carpeta temporal.

**0.4 Orden en el repositorio.** *(S)* Hay trabajo de todo esta semana sin guardar en dos carpetas. Propuesta:
commits pequeños por tema y decidir cuándo la rama `rediseno` (Vega 2) pasa a ser la app principal. Sin subir a
GitHub salvo que lo pidas. *Necesito: tu «sí» para los commits.*

## Fase 1 — Dos semanas: lo que más cambia cómo se siente

**1.1 Que solo te obedezca a ti (voz del dueño).** *(M)*
*El problema real:* el Redmi oye todo lo que suena en la habitación. Los registros del servidor muestran entre 350 y
1700 frases transcritas al día esta semana. El análisis del 28-10 (698 frases) no encontró ni una que fuera una
llamada a Vega: era ambiente (tele, vídeos, gente). *La idea:* un modelo pequeño de «huella de voz» comprueba que quien habla eres
tú antes de gastar el reconocimiento de voz y, sobre todo, antes de ejecutar nada delicado (apagar, reiniciar).
sherpa-onnx, que Vega ya usa en el teléfono, trae extractores de huella de voz (3D-Speaker, WeSpeaker) y un gestor
de personas registradas con umbral
([documentación](https://k2-fsa.github.io/sherpa/onnx/javascript-api/examples/speaker_identification.html));
una app de terceros ya los corre en Android
([ejemplo](https://huggingface.co/Aivo/speaker-diarization-models)). *Privacidad:* se guardan solo los números de
la huella, nunca el audio. *Riesgo medio:* ajustar el umbral (que no te rechace con catarro ni deje pasar a
otros); no encontré un ejemplo de «solo el dueño» en tiempo real, así que habría que medirlo en el Redmi.
*Necesito:* un registro de ~10 frases tuyas, con tu permiso.

**1.2 Una palabra de activación propia.** *(M)*
Hoy cada frase pasa por un filtro y por Whisper para ver si dice «Vega» (94 % de las frases pasan el filtro).
Un detector específico de «Vega» (openWakeWord o microWakeWord) gasta casi nada y se puede entrenar con voces
sintéticas: ya existe un esbozo en `tools/wake/`. Un entrenador comunitario incluye español
([herramienta](https://community.home-assistant.io/t/tool-openwakeword-train-train-your-own-wake-words-locally-tts-examples-real-mic-negatives-onnx-tflite-export/1026289)),
con la advertencia de que su español no está validado en condiciones reales; microWakeWord publica 2 % de
rechazos y 0,12 falsas activaciones por hora en su prueba
([proyecto](https://github.com/kahrendt/microWakeWord)). Con 1.1, las dos juntas casi eliminan las activaciones
falsas. *Riesgo:* «Vega» suena a otras palabras; se mide con audio real de tu casa. *Necesito:* grabaciones de
prueba con tu permiso (locales y borradas después).

**1.3 Automatizaciones: «cuando pase X, haz Y».** *(M)*
Vega ya sabe cuándo abres un juego, cuándo sube el calor, cuándo llega un aviso o suena música, y ya tiene
rutinas (`modo-noche`). Falta unirlos: un archivo con reglas como «al abrir un juego → silenciar notificaciones del
PC, poner la cámara de Vega en modo juego, guardar un clip al ganar una ronda». Sin IA, solo reglas: rápidas y
fiables. Reutiliza los botones y rutinas que ya existen, con sus confirmaciones. *Riesgo bajo.*

**1.4 Vega ve (visión bajo demanda).** *(M)*
El modelo que ya usas (Qwen3.5) tiene visión y lo medí: una imagen en 3,6 s, leyendo bien el texto. Casos:
«Vega, ¿qué es esto?» con la cámara del Redmi (una foto, sin guardarla), «léeme esto» (texto en papel o pantalla),
«¿qué hay en mi pantalla?» (una captura al pedirlo, nunca vigilando). No necesita instalar nada: lo hace la app.
*Riesgo bajo; privacidad:* solo cuando lo pides y nada se guarda. Los agentes que manejan el escritorio por visión
(UI-TARS y similares) piden modelos mucho mayores; no los recomiendo en esta gráfica
([proyecto](https://github.com/bytedance/UI-TARS-desktop)).

**1.5 Un oído mejor y más ligero (a medir).** *(M)*
Parakeet TDT 0.6B v3 cubre 25 idiomas europeos con español. Según una tabla de un blog que cita a NVIDIA, en español
rinde casi como Whisper large-v3 (3,5 % frente a 3,1 % de errores) y es varias veces más rápido
([datos](https://whispernotes.app/en/blog/parakeet-v3-default-mac-model)); `sherpa-onnx` lo ofrece cuantizado.
Si se confirma con tus frases, el oído pasaría al procesador y se liberarían ~1 GB de vídeo, que es lo que más
falta cuando juegas. *Riesgo:* son cifras de un blog; hay que compararlo con tus propias frases. *Necesito:* unas 30
frases dictadas con tu permiso, que se borran al terminar.

**1.6 Una voz decente también en el teléfono.** *(S–M)*
Kokoro no corre a tiempo real en el Redmi (2,7–4 veces más lento) y Piper suena robótica. Supertonic 2 (español, el
portugués y el francés, ~99 M de parámetros, licencia OpenRAIL) publica velocidades de hasta 167 veces el tiempo
real en un procesador de portátil
([ficha](https://huggingface.co/Supertone/supertonic-2)); no hay cifras de Android. Si en el Redmi sale más rápido
que hablar, el teléfono tendría su propia voz natural, sin depender del PC. *Riesgo:* hay que medirlo en el Redmi.

## Fase 2 — Un mes: cosas que impresionan

**2.1 Clips automáticos de tus partidas.** *(M)* Vega ya recibe eventos del juego (CS2 por «Game State
Integration», League, Rocket League, Forza). OBS, que ya está instalado por la cámara, puede mantener un búfer de
los últimos 30 s y guardarlo cuando se le pida por su interfaz WebSocket (`SaveReplayBuffer`,
[referencia](https://www.powershellgallery.com/packages/obs-powershell/0.1.2/Content/Commands%5CRequests%5CSave-OBSReplayBuffer.ps1)).
Entonces: «Vega, guarda eso» o clip solo al ganar la ronda o al dar una racha, y el teléfono lo cuenta.
*Cuidado:* no leer la memoria del juego (tu regla) y comprobar que el sistema antitrampas (Vanguard está
instalado) no se queja de OBS. *Coste:* el búfer gasta algo de gráfica (codificación por hardware) mientras juegas;
se mediría antes de dejarlo por defecto.

**2.2 «¿Dónde dejé…?»: buscar tus archivos por significado.** *(M–L)* Un índice local, con las carpetas que tú
elijas, de nombres y texto de documentos con los modelos de significado que Vega ya usa para las órdenes. «Vega,
¿dónde está la factura de la luz de octubre?». Opciones de memoria ya hechas en SQLite con búsqueda vectorial
([ejemplo](https://pypi.org/project/memoirs/)). *Privacidad:* solo carpetas elegidas, todo local.

**2.3 Memoria de Vega más lista.** *(M)* Hoy guarda hechos sueltos. Mejoras: resúmenes de lo hablado a lo largo
de los días, caducidad de datos viejos, y una pantalla para ver y borrar lo que sabe de ti (hoy solo por voz).

**2.4 Pensar más cuando hace falta.** *(M)* El 4B falla en cuentas y lógica (por ejemplo, contó 66 patas a un
ciempiés de 30 pares). Dos caminos: (a) una calculadora y herramientas para lo exacto, que es lo más seguro y rápido;
(b) un modo «piénsalo bien» con razonamiento, que cuesta 3–6 s más y solo se usaría al pedirlo. Probé `qwen3:8b`
con 20 capas en la gráfica: 5–7 palabras/s, no sirve para charlar. Los modelos de «expertos» (MoE) son la
promesa: según varios artículos, uno de 30B con solo 3B activos corre a ~30 palabras/s en una gráfica de 8 GB
sacando los expertos al procesador
([guía](https://github.com/GenerelSchwerz/llama.cpp/wiki/8GB-VRAM-Setup),
[prueba](https://dev.to/upayanghosh/from-oom-to-262k-context-running-qwen3-coder-30b-locally-on-8gb-vram-1ej1)).
**Pero** necesitan ~18–20 GB de memoria del PC y hoy tienes 7,5 GB libres con el escritorio: solo sería
viable con el PC en reposo, o con `gpt-oss-20b` (~12 GB, según su guía de llama.cpp,
[aquí](https://github.com/ggml-org/llama.cpp/discussions/15396)), que no es tan bueno en español. *Riesgo alto de
decepción:* se prueba cuando no estés jugando.

**2.5 Más velocidad con predicción de varias palabras (MTP).** *(M, experimento)* llama.cpp incorporó en mayo de
2026 la predicción de varias palabras para Qwen3.5/3.6, y según artículos da de 1,3 a 2 veces más velocidad sin
cambiar el modelo
([guía](https://mer.vin/2026/05/run-qwen-3-6-mtp-in-llama-cpp-faster-local-inference-with-built-in-speculative-decoding/),
[Qwen3.5-4B](https://alphasignal.ai/news/unsloth-s-qwen3-5-4b-doubles-token-speed-without-changing-a-single-weight)).
Pero no es compatible aún con la visión en llama.cpp, y habría que dejar Ollama por el servidor de llama.cpp. Un
artículo dice que Ollama ya lo trae desde la versión 0.32.6; tienes la 0.40.7, más nueva, así que quizá ya lo tiene:
hay que mirar sus notas de versión antes de cambiar nada. *Mi consejo:* no antes de la 1.x.

## Fase 3 — Ambicioso (cuando lo demás esté estable)

**3.1 Un segundo cuerpo.** *(L)* Cualquier móvil Android viejo con la app de Vega sería «otra habitación». El
servidor hoy mezcla el estado de dos teléfonos (lo vi esta semana: la app vieja y Vega 2 se pisaban). Primero habría
que repartir el estado por teléfono. Alternativa de mercado: satélites de voz de Home Assistant con ESP32-S3
([protocolo Wyoming](https://www.home-assistant.io/integrations/wyoming/)), pero no encaja con tu idea de que el
teléfono sea el cuerpo.

**3.2 Vega que usa herramientas (MCP).** *(L)* Un estándar para dar «manos» a los modelos: ya hay servidores para
controlar Windows por su árbol de accesibilidad
([Windows-MCP y otros](https://dev.to/saptadev27/let-ai-control-your-desktop-the-best-computer-use-mcp-servers-in-2026-49k4)),
OBS ([mcp-server-obs](https://github.com/sbroenne/mcp-server-obs)), Spotify y Discord. Potente pero arriesgado: el
modelo pequeño se equivoca, así que solo con una lista corta de herramientas, confirmación por voz del dueño (1.1) y
nada de actuar solo. El propio artículo avisa de no dejar nunca un control del escritorio sin clave.

**3.3 Teléfono con modelo propio mejor.** *(M)* Gemma 4 E2B (audio y llamadas a herramientas en el propio modelo;
47 palabras/s en el procesador de un móvil de gama alta, según
[Google](https://developers.google.com/edge/litert-lm/models/gemma-4)) es la opción realista para móviles. En un
Helio G81 con 4 GB sería mucho más lento y no hay pruebas. Con el modo mixto el PC ya contesta cuando está libre, así
que **esto sube o baja según lo que ocurra en 1.x**.

## Lo investigado y descartado (y por qué)

| Idea | Por qué no ahora |
|---|---|
| **NemotronLabs VoiceChat** (voz a voz, llamadas a herramientas, agosto de 2026) | Solo inglés y pide GPUs de 80 GB ([ficha](https://www.gradually.ai/en/ai-models/nvidia-nemotron-voicechat/)) |
| **Moshi / Unmute** (voz a voz) | Modelos pensados para inglés y francés; no encontré español |
| **Voxtral Mini 4B Realtime** (reconocimiento en directo) | Pide ≥16 GB de vídeo ([guía](https://localaimaster.com/blog/voxtral-local-guide)). El Voxtral Mini 3B sí cabe (~2,5 GB cuantizado) y sabe español, pero no mejora a lo que ya tienes |
| **Chatterbox / Orpheus** (voces clonadas) | Piden 8–12 GB de gráfica ([comparativa](https://localaimaster.com/blog/best-local-tts-models)): chocan con jugar |
| **Qwen3.6-35B-A3B** completo | ~20 GB de memoria: no cabe con tu escritorio (ver 2.4) |
| **Agentes de escritorio por visión (UI-TARS…)** | Piden modelos grandes de visión |

## Orden que recomiendo

1. **0.1** (arranque y «reconectando»), **0.2** y **0.3** (medir) y **0.4** (commits): una tarde.
2. **1.1 + 1.2** (voz del dueño y palabra propia): es lo que más cambiaría el uso diario porque cada frase suelta de
   la tele deja de costar y de arriesgar.
3. **1.3** (automatizaciones, con modo juego) y **1.4** (visión).
4. **1.5 y 1.6** si las medidas salen bien. Luego la Fase 2 según lo que más uses.

## Qué necesito de ti para empezar cada cosa

| Idea | Permiso o dato |
|---|---|
| 0.4 | Tu «sí» para guardar los commits |
| 1.1, 1.2, 1.5 | Grabaciones de prueba tuyas, con permiso expreso (locales y borradas al acabar) |
| 1.4 | Que se pida permiso de cámara en la app |
| 2.1 | Que OBS tenga activado su WebSocket y el búfer de repetición |
| 2.2 | Qué carpetas puede indexar |

> **11-11-2026:** hay un prototipo de servidor compatible con los altavoces ESP32 «xiaozhi» (satélites de voz): ver `docs/xiaozhi-prototipo.md`.
> **11-11-2026:** el puente con Telegram («Vega fuera de casa») está hecho y desactivado: ver `docs/telegram.md`.
