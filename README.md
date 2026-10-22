# Vega — asistente personal de voz, local

Un asistente al que le hablas («Vega, …») y que también funciona como un panel de
botones (apagar el PC, grabar, música, abrir apps). Todo corre **en tu PC**, sin
servicios de pago ni internet. Un teléfono Android conectado por **cable USB** hace
de «cuerpo»: rostro animado, micrófono, altavoz y botones.

> El nombre «Vega» sale de `config/assistant.toml`; se puede cambiar ahí.

```
 Teléfono (app Android)        cable USB         PC con Windows
 rostro · micrófono · voz  ◄── adb reverse ──►  servidor (127.0.0.1:8000)
 panal de botones                                ├─ Whisper (voz → texto, GPU)
                                                 ├─ Ollama qwen3.5:4b (respuestas, GPU)
                                                 ├─ Kokoro / Piper (texto → voz, CPU)
                                                 ├─ botones de Windows (con permisos)
                                                 └─ historial (SQLite)
```

## Estado

| Parte | Estado |
|---|---|
| Escucha continua «Vega, …», respuestas habladas, botones, historial | **Funciona y está probado**, pero es un **prototipo**: úsalo, pero espera fallos |
| Cable USB que se reconecta solo, arranque con un comando | Funciona (probado reconectando el USB) |
| Interfaz web del PC (`http://127.0.0.1:8000`) | Más antigua: chat escrito, sin panel de botones |
| Wi-Fi, coche | No hecho (decidido dejarlo para más adelante) |

## Uso diario

```powershell
powershell -ExecutionPolicy Bypass -File scripts\start.ps1 -Hidden   # arrancar
powershell -ExecutionPolicy Bypass -File scripts\stop.ps1            # parar y liberar la GPU
```

Conecta el teléfono por USB: el servidor lo enlaza y abre la app solo (tarda unos
segundos; tras reconectar el cable, hasta ~30 s).

En el teléfono:
- **Hablar**: di «Vega, …» (o toca el rostro y habla sin decir el nombre).
- **Silenciar el micrófono**: chip **● ESCUCHANDO** de arriba.
- **Botones**: el panal (6 favoritos) y **MÁS** (todos, y el cambio de **VOZ**).
- **Volver a Android**: botón **ANDROID**.
- **Actualizar la app**: cuando el PC tiene una versión nueva aparece **ACTUALIZAR**.

Arrancar con Windows (activado en el PC del autor desde el 22-10-2026):
```powershell
powershell -ExecutionPolicy Bypass -File scripts\autostart.ps1          # activar
powershell -ExecutionPolicy Bypass -File scripts\autostart.ps1 -Remove  # desactivar
```

## Configuración

| Archivo | Qué se cambia |
|---|---|
| `config/assistant.toml` | Nombre, modelo, instrucciones, voz por defecto, Whisper, USB |
| `config/actions.toml` | Botones: qué hacen, cuáles son favoritos (`quick`), frases de voz (`say`), cuáles piden confirmación |

**Seguridad de los botones:** solo existe lo que está en `config/actions.toml`; el
servidor lo comprueba siempre. Apagar, reiniciar y suspender piden confirmación (un
toque o decir «sí»), el apagado espera 15 s y se puede cancelar, y todo queda en
`logs/audit.log`. El servidor solo escucha en `127.0.0.1` y pide un token
(`config/token.txt`, fuera de git).

## Instalación en un PC con Windows

Probado en Windows 11 con una RTX 4060 Ti de 8 GB y 32 GB de RAM.

1. **Herramientas** (todas gratuitas):
   ```powershell
   winget install astral-sh.uv Ollama.Ollama Google.PlatformTools OpenJS.NodeJS.LTS Git.Git
   ```
   > Si usas la app de Claude en Windows: instala Python fuera de `AppData`
   > (`UV_PYTHON_INSTALL_DIR=%USERPROFILE%\.uv\python`), porque la app aísla lo que se
   > crea en carpetas nuevas de `AppData`.
2. **Modelo de lenguaje:** `ollama pull qwen3.5:4b`
3. **Python y dependencias:** `uv sync` (instala Python 3.12 solo para el proyecto,
   Whisper con CUDA, Piper y Kokoro).
4. **Voces** (no van en git):
   - Kokoro → `models/kokoro/`: `kokoro-v1.0.onnx` y `voices-v1.0.bin` de
     <https://github.com/thewh1teagle/kokoro-onnx/releases/tag/model-files-v1.0>
   - Piper (opcional) → `models/piper/`: archivos `.onnx` + `.onnx.json` de
     <https://huggingface.co/rhasspy/piper-voices> (p. ej. `es_MX-claude-high`).
   - Whisper se descarga solo la primera vez en `models/whisper/`.
5. **Arrancar una vez** (`scripts\start.ps1`) para crear `config/token.txt`.

### La app del teléfono

1. En el teléfono: **Ajustes → Sobre el teléfono** → toca 7 veces la versión del
   sistema → **Opciones de desarrollador → Depuración USB**. Conecta el cable y acepta
   «Permitir siempre».
2. Compilar e instalar (necesita JDK 21 en `%USERPROFILE%\.jdks` y el Android SDK en
   `%USERPROFILE%\Android\Sdk`):
   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\android-install.ps1
   ```
   En Xiaomi sin SIM no se puede instalar por cable («Instalar vía USB» exige SIM):
   copia `android\app\build\outputs\apk\release\app-release.apk` al teléfono e
   instálalo desde el gestor de archivos. Las versiones siguientes se instalan con
   el botón **ACTUALIZAR** de la app.
3. Opcional: `scripts\android-debloat.ps1` quita la publicidad de HyperOS
   (reversible con `-Restore`).

## Desarrollo

```powershell
uv run pytest            # pruebas del servidor
uv run ruff check server; uv run mypy
cd ui; npm test; npm run lint; npx tsc -b       # interfaz web
cd android; .\gradlew.bat testDebugUnitTest assembleRelease   # app (JAVA_HOME = JDK 21)
```

Herramientas de prueba: `scripts/ws_chat.py` (hablar con el servidor desde la consola,
guarda la voz en `logs/ultima_voz.wav`), `scripts/wake_test.py` (simula un micrófono
continuo con archivos WAV) y `scripts/bench_stt.py` (velocidad de Whisper).

El plan original y las decisiones están en `docs/plan-inicial.md`.
