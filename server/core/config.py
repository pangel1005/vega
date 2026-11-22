"""Carga y valida `config/assistant.toml`.

Los valores sensibles para la seguridad (como la dirección en la que escucha el
servidor) se validan aquí, en el código, y no dependen de que el archivo esté bien.
"""

from __future__ import annotations

import os
import tomllib
from pathlib import Path
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONFIG = ROOT / "config" / "assistant.toml"
CONFIG_ENV_VAR = "ASISTENTE_CONFIG"

# El teléfono llega por `adb reverse`, así que el servidor nunca necesita escuchar en la red.
ALLOWED_HOST = "127.0.0.1"


class ConfigError(Exception):
    """La configuración no existe o no es válida."""


class _Section(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class AssistantSettings(_Section):
    name: str = Field(min_length=1, max_length=40)
    language: str = "es"


class ServerSettings(_Section):
    host: str = ALLOWED_HOST
    port: int = Field(default=8000, ge=1024, le=65535)

    @field_validator("host")
    @classmethod
    def _only_loopback(cls, value: str) -> str:
        if value != ALLOWED_HOST:
            raise ValueError(f"el servidor solo puede escuchar en {ALLOWED_HOST}, no en {value!r}")
        return value


class LlmSettings(_Section):
    url: str = "http://127.0.0.1:11434"
    model: str = Field(min_length=1)
    keep_alive: str = Field(default="10m", pattern=r"^\d+[smh]$")
    # Capas del modelo en la gráfica: 1 (por defecto, ~650 MB), 0 (ninguna) o -1 (todas, ~3,7 GB).
    gpu_layers: int = Field(default=1, ge=-1, le=200)
    # Con `follow_game`, el modelo usa `gpu_layers_free` (-1 = todo en la gráfica: ~0,7 s por respuesta)
    # mientras no hay juego ni otro programa pesado, y vuelve a `gpu_layers` en cuanto se abre uno.
    # `gpu_free_mib`: vídeo libre que hace falta (sin contar el de Vega) para ponerlo todo en la gráfica.
    follow_game: bool = True
    # Con la IA en el teléfono, la charla libre (lo que no es botón ni habilidad) la contesta el modelo grande
    # del PC mientras la gráfica esté libre; jugando o sin PC, el pequeño del teléfono.
    phone_chat: bool = True
    gpu_layers_free: int = Field(default=-1, ge=-1, le=200)
    gpu_free_mib: int = Field(default=4000, ge=0, le=100_000)
    threads: int = Field(default=8, ge=1, le=64)  # hilos del procesador para lo que no va en la gráfica
    context: int = Field(default=2048, ge=512, le=32768)
    # Más bajo = respuestas más seguras (medido: con 0,8 mezclaba francés y se inventaba datos).
    temperature: float = Field(default=0.5, ge=0.0, le=2.0)
    system_prompt: str = "Eres {name}, un asistente personal. Responde en español, breve y claro."


class DeskSettings(_Section):
    """«Vega manda en tu PC»: fondos de Wallpaper Engine, volumen por programa y abrir juegos por voz."""

    enabled: bool = True
    wallpaper: bool = True
    volume: bool = True
    games: bool = True


class XiaozhiSettings(_Section):
    """Altavoces ESP32 con el protocolo «xiaozhi» (PROTOTIPO), en `/xiaozhi/v1/`. Va desactivado."""

    enabled: bool = False


class TelegramSettings(_Section):
    """Vega por Telegram (fuera de casa): hablarle desde el móvil y recibir avisos si el teléfono no está."""

    enabled: bool = False
    # away: los recordatorios llegan por Telegram solo si no hay teléfono conectado; always: siempre; never.
    reminders: Literal["away", "always", "never"] = "away"


class MoodleSettings(_Section):
    """Entregas de la universidad (Moodle), leídas del calendario exportado. Solo habla con tu Moodle."""

    enabled: bool = False
    # Minutos antes de la entrega en que avisa; con varios, varios avisos ([1440, 60] = un día y una hora).
    alerts: list[Annotated[int, Field(ge=1, le=10080)]] = [60]
    refresh_minutes: int = Field(default=20, ge=5, le=240)
    # Solo los eventos que vencen o cierran (no «se abre»); con false entran todos los del calendario.
    only_deadlines: bool = True


class CameraSettings(_Section):
    """La cámara del teléfono como cámara de Windows (scrcpy + OBS Studio instalados con winget)."""

    enabled: bool = True
    facing: Literal["front", "back"] = "front"
    # Giro de la imagen en grados (sentido de las agujas del reloj): con el teléfono tumbado sale al revés.
    rotate: Literal[0, 90, 180, 270] = 180
    size: str = Field(default="1280x720", pattern=r"^\d{3,4}x\d{3,4}$")
    # Al apagar la cámara, OBS se queda en la bandeja (unos 180 MB): Discord y compañía no pierden la cámara
    # y al encenderla otra vez la imagen vuelve sola. Con false se cierra todo, pero esos programas se quedan
    # con el logotipo de OBS hasta que vuelvas a elegir la cámara en ellos.
    keep_obs: bool = True


class LoggingSettings(_Section):
    level: Literal["DEBUG", "INFO", "WARNING", "ERROR"] = "INFO"
    dir: Path = Path("logs")

    @property
    def path(self) -> Path:
        """Carpeta de registros; si es relativa, se toma desde la raíz del proyecto."""
        return self.dir if self.dir.is_absolute() else ROOT / self.dir


class SpeechSettings(_Section):
    stt_model: str = "small"
    stt_device: Literal["cuda", "cpu"] = "cuda"
    stt_compute: str = "int8_float16"
    stt_game_model: str = "base"  # el modelo que se usa en el procesador mientras hay un juego abierto
    max_seconds: int = Field(default=30, ge=1, le=120)
    models_dir: Path = Path("models/whisper")
    tts_enabled: bool = True
    tts_voice: str = "es_MX-claude-high"
    tts_speed: float = Field(default=1.0, ge=0.7, le=1.5)
    tts_accent: Literal["es-419", "es"] = "es-419"
    voices_dir: Path = Path("models/piper")

    @property
    def models_path(self) -> Path:
        return self.models_dir if self.models_dir.is_absolute() else ROOT / self.models_dir

    @property
    def voices_path(self) -> Path:
        return self.voices_dir if self.voices_dir.is_absolute() else ROOT / self.voices_dir


class DeviceSettings(_Section):
    usb_watcher: bool = True
    adb_path: str = ""
    open_app_on_connect: bool = True


class MemorySettings(_Section):
    file: Path = Path("data/history.sqlite")

    @property
    def path(self) -> Path:
        """Archivo de historial; si es relativo, se toma desde la raíz del proyecto."""
        return self.file if self.file.is_absolute() else ROOT / self.file


class MediaSettings(_Section):
    # Letras de canciones desde lrclib.net (lo único que usa internet: título, artista,
    # álbum y duración de la canción). False = sin modo letra.
    lyrics: bool = True
    # Analizador de espectro estilo radio de coche en el teléfono: analiza el audio de Spotify (solo el de
    # Spotify) en memoria para saber cuánto suenan graves, medios y agudos. No se guarda ni se manda audio:
    # solo ~24 números.
    # False = sin espectro (la onda sigue usando solo el volumen).
    spectrum: bool = True


class GameSettings(_Section):
    """La sección «Juego» del teléfono: qué juego está abierto en el PC y su información."""

    enabled: bool = True
    # Descripción, géneros y nota desde la tienda de Steam (pública). Solo se le manda el número del juego;
    # se guarda en data/games para no volver a pedirla. False = solo lo que Steam ya tiene en este PC.
    store_info: bool = True
    # Números de Steam que no son juegos y no deben salir (Wallpaper Engine y Steamworks ya están ignorados).
    ignore: list[int] = []
    # Con tu cuenta conectada (config/steam_key.txt): enseñar qué amigos de Steam juegan a lo mismo ahora.
    friends: bool = True
    # La partida en curso desde las APIs locales oficiales de League of Legends y Rocket League (solo
    # lectura, sin salir del PC). false = sin sección «Partida».
    match: bool = True
    rocket_port: int = Field(default=49123, ge=1, le=65535)
    # Puerto UDP donde escucha la telemetría «Data Out» de Forza Horizon (el mismo que pones en el juego).
    forza_port: int = Field(default=5300, ge=1024, le=65535)


class IntentSettings(_Section):
    """Entender por significado: complementa a las reglas con un modelo pequeño en el PC, sin red."""

    # False = solo las reglas de siempre. El modelo (~120 MB) se baja una vez y después no usa internet.
    # (En el código viene apagado, para que las pruebas no bajen nada; config/assistant.toml lo enciende.)
    semantic: bool = False
    act: float = Field(default=0.62, ge=0, le=1)  # parecido desde el que ejecuta la orden
    ask: float = Field(default=0.50, ge=0, le=1)  # parecido desde el que pregunta «¿quieres…?» antes
    margin: float = Field(default=0.04, ge=0, le=1)  # ventaja mínima sobre la segunda opción


class NotificationSettings(_Section):
    # Ver en el teléfono las notificaciones del PC (solo viajan por el cable; no se guardan).
    enabled: bool = True


class WeatherSettings(_Section):
    """El tiempo (Open-Meteo). Apagado de serie: sale a internet con las coordenadas de la ciudad."""

    enabled: bool = False
    city: str = ""
    latitude: float | None = Field(default=None, ge=-90, le=90)
    longitude: float | None = Field(default=None, ge=-180, le=180)

    @property
    def ready(self) -> bool:
        return self.enabled and self.latitude is not None and self.longitude is not None


class Settings(_Section):
    assistant: AssistantSettings
    server: ServerSettings = ServerSettings()
    llm: LlmSettings
    logging: LoggingSettings = LoggingSettings()
    memory: MemorySettings = MemorySettings()
    speech: SpeechSettings = SpeechSettings()
    device: DeviceSettings = DeviceSettings()
    media: MediaSettings = MediaSettings()
    games: GameSettings = GameSettings()
    notifications: NotificationSettings = NotificationSettings()
    weather: WeatherSettings = WeatherSettings()
    camera: CameraSettings = CameraSettings()
    desk: DeskSettings = DeskSettings()
    xiaozhi: XiaozhiSettings = XiaozhiSettings()
    telegram: TelegramSettings = TelegramSettings()
    moodle: MoodleSettings = MoodleSettings()
    intents: IntentSettings = IntentSettings()

    @property
    def system_prompt(self) -> str:
        return self.llm.system_prompt.strip().replace("{name}", self.assistant.name)


def load_settings(path: Path | None = None) -> Settings:
    """Lee la configuración de `path`, de la variable ASISTENTE_CONFIG o del archivo por defecto."""
    if path is None:
        path = Path(os.environ.get(CONFIG_ENV_VAR, DEFAULT_CONFIG))
    try:
        # utf-8-sig: algunos editores de Windows guardan el archivo con BOM y tomllib no lo acepta.
        data = tomllib.loads(path.read_text(encoding="utf-8-sig"))
    except FileNotFoundError as exc:
        raise ConfigError(f"No encuentro el archivo de configuración: {path}") from exc
    except tomllib.TOMLDecodeError as exc:
        raise ConfigError(f"El archivo {path} tiene un error de formato: {exc}") from exc
    try:
        return Settings.model_validate(data)
    except ValidationError as exc:
        problems = "; ".join(f"{'.'.join(str(p) for p in err['loc'])}: {err['msg']}" for err in exc.errors())
        raise ConfigError(f"Configuración no válida en {path}: {problems}") from exc
