# Plan inicial del asistente personal

> Documento acordado antes de empezar a programar. El nombre del asistente se
> define en `config/assistant.toml`; nunca se escribe fijo en el código.

## 1. Objetivo

Un asistente de voz al estilo de JARVIS (con otro nombre) que corre **entero en
el PC** del usuario:

- Windows 11, RTX 4060 Ti, 32 GB de RAM.
- Todo local con Ollama, sin servicios de pago. Presupuesto extra: menos de US$20.
- Un Xiaomi Redmi 15C conectado por cable USB hace de «cuerpo»: rostro animado,
  micrófono y altavoz.
- Hoy el teléfono está bloqueado por la protección de la cuenta (FRP) tras un
  restablecimiento de fábrica. Por eso **todo el MVP se hace y se prueba en el
  navegador del PC**.

## 2. Arquitectura

```
 Teléfono (o navegador del PC)              PC con Windows 11
 ┌──────────────────────────┐   USB    ┌───────────────────────────────────┐
 │ PWA React (rostro, chat, │  adb     │ FastAPI en 127.0.0.1:8000 (token)  │
 │ micrófono, altavoz)      │ reverse  │  └─ WebSocket /ws                  │
 │ http://127.0.0.1:8000    │◄────────►│      ├─ Orquestador (estados)      │
 └──────────────────────────┘          │      ├─ ChatModel   → Ollama       │
                                       │      ├─ SpeechToText→ faster-whisper│
                                       │      ├─ TextToSpeech→ Piper         │
                                       │      ├─ Herramientas (con permisos) │
                                       │      └─ Memoria (SQLite)            │
                                       └───────────────────────────────────┘
```

- **El celular es solo la interfaz**: una PWA en React servida por el PC. Se
  conecta por USB con `adb reverse tcp:8000 tcp:8000`, así la página es
  `http://127.0.0.1:8000`. Ese es un origen seguro para el navegador, y el
  micrófono funciona sin HTTPS.
- **Servidor FastAPI que escucha solo en 127.0.0.1**, protegido con token. Nada
  queda expuesto a la red Wi-Fi.
- **Un único WebSocket `/ws`**: eventos JSON tipados (`state`, `token`,
  `transcript`, `tool_request`, `confirm`) y el audio en binario por el mismo canal.
- **Orquestador = máquina de estados**:
  `idle → listening → thinking → speaking → acting → error`.
- **Interfaces intercambiables**: `ChatModel` (Ollama), `SpeechToText`
  (faster-whisper), `TextToSpeech` (Piper). Cambiar de motor no obliga a tocar
  el resto.
- **Todo en streaming**: las palabras del modelo se cortan en frases y Piper
  sintetiza cada frase sin esperar al resto. Meta: **primera palabra hablada en
  ~1,5–2,5 s**.
- **Herramientas de Windows con permisos que aplica el código, nunca el modelo**:
  - lista de apps y carpetas permitidas (`config/permissions.toml`);
  - confirmación en la pantalla del teléfono para lo sensible;
  - papelera (send2trash) en vez de borrar;
  - auditoría de todo lo que se ejecuta;
  - ninguna terminal libre;
  - lo que se lee (archivos, webs) son **datos, no órdenes**.
- **Vigilante USB** (`adb track-devices`): vuelve a aplicar `adb reverse` solo
  cuando el teléfono se reconecta, y la interfaz se reconecta con espera creciente.

## 3. Tecnologías

| Área | Elección |
|---|---|
| Lenguaje servidor | Python 3.12 + uv |
| Web | FastAPI / Uvicorn, Pydantic v2 |
| Modelo de lenguaje | Ollama; Qwen3 8B en Q4 para empezar, `think: false` en voz (revisar qué modelos hay en ese momento) |
| Voz a texto | faster-whisper en CUDA (`int8_float16`) + Silero VAD |
| Texto a voz | Piper (voz es_ES o es_MX) |
| Datos | SQLite con SQLAlchemy 2 + Alembic, FTS5; más adelante embeddings + sqlite-vec |
| Sistema | psutil, nvidia-ml-py |
| Interfaz | React 19 + TypeScript + Vite; AudioWorklet con PCM16 a 16 kHz |
| Pruebas | pytest, Vitest |
| Calidad | ruff + mypy; ESLint + tsc estricto |

## 4. Estructura de carpetas

```
server/
  ws/        WebSocket y protocolo de eventos
  core/      orquestador, configuración, registros
  llm/       ChatModel (Ollama)
  speech/    SpeechToText y TextToSpeech
  tools/     herramientas de Windows y permisos
  memory/    SQLite, historial, búsqueda
  device/    ADB y vigilante USB
  tests/
ui/src/
  face/      rostro animado
  chat/      conversación
  panel/     estado y ajustes
  audio/     captura (AudioWorklet) y reproducción
  net/       cliente WebSocket con reconexión
config/      assistant.toml, permissions.toml
docs/
scripts/     start.ps1, adb-setup.ps1
```

## 5. MVP (en el navegador del PC, sin teléfono ni herramientas)

Un paso a la vez; cada uno con su prueba de aceptación.

| Paso | Qué | Prueba de aceptación |
|---|---|---|
| 0 | Entorno instalado | Ollama responde en español |
| 1 | Esqueleto FastAPI | `/health` responde; se carga la configuración; hay registros |
| 2 | Chat en streaming por WebSocket | Las palabras llegan una a una; con Ollama apagado sale un error claro |
| 3 | Historial en SQLite | La conversación sobrevive a un reinicio del servidor |
| 4 | Interfaz | Chat y rostro con sus estados; se reconecta sola |
| 5 | Voz a texto | Botón para hablar; transcripción en menos de 1 s |
| 6 | Texto a voz por frases | Se mide la latencia de punta a punta |
| 7 | Rostro sincronizado | Sigue la amplitud del audio a ~60 FPS |
| 8 | Arranque y documentación | `start.ps1` y README de instalación en Windows |

### Después del MVP

- **Fase 3**: USB/ADB con el teléfono (cuando se resuelva el FRP).
- **Fase 4**: herramientas de Windows con el sistema de permisos.
- **Fase 5**: memoria a largo plazo y personalidad.
- **Fase 6**: optimización, app nativa en Kotlin, cámara.

## 6. Riesgos y cómo se manejan

| Riesgo | Mitigación |
|---|---|
| El teléfono sigue bloqueado por FRP | El MVP entero funciona en el navegador del PC; el teléfono es solo otra pantalla |
| VRAM justa (8 GB) para modelo + Whisper | Modelo en Q4, Whisper en `int8_float16`, medir uso real con nvidia-ml-py; si no alcanza, Whisper `small` o modelo más pequeño |
| Latencia de voz alta | Streaming en todas las etapas, síntesis por frases, `think: false` |
| El modelo intenta acciones peligrosas | Los permisos los aplica el código, confirmación en pantalla, papelera, auditoría, sin terminal libre |
| Inyección de órdenes desde archivos o webs | Todo lo leído se trata como datos, nunca como instrucciones |
| El cable USB se desconecta | Vigilante `adb track-devices` + reconexión con espera creciente en la interfaz |
| Calidad de voz en español | Probar voces Piper es_ES y es_MX y elegir la que mejor suene |

## 7. Reglas de trabajo

- Componentes pequeños y verificables.
- Nada simulado presentado como si funcionara.
- Explicar las decisiones importantes.
- Pruebas en lo crítico.
- Marcar qué es **prototipo** y qué está **listo para el uso diario**.
