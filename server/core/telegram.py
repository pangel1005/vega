"""Vega por Telegram: hablarle desde el móvil donde estés y recibir sus avisos si el teléfono no está.

Cómo funciona (y por qué es seguro de abrir):
- El PC pregunta a Telegram cada pocos segundos si hay mensajes (`getUpdates`, sondeo largo): todo es
  saliente. No se abre ningún puerto ni el servidor de Vega se expone a internet.
- Solo contesta a las cuentas de `config/telegram.json` (se rellena con
  `python -m server.tools.telegram_setup`) y solo en chats privados. Los demás mensajes se ignoran sin
  responder (el bot no revela que existe). Sin ninguna cuenta autorizada no arranca.
- Texto libre = escribirle a Vega: pasa por las habilidades y el modelo del PC igual que en la app
  (recordatorios, notas, fondo de pantalla, volumen, abrir juegos…). NO usa los botones del PC.
- Apagar la PC sí, con cuidado: «/apagar» (o «apaga la PC», o el «¿Apago la PC?» de «buenas noches»)
  pregunta con dos botones y avisa si hay un juego abierto. Con «Sí, apagar», Windows se apaga en 60 s
  y el mensaje cambia a un botón «Cancelar apagado». Cada pregunta vale una vez y caduca a los 2 minutos
  (y con un reinicio del servidor): un botón viejo no apaga nada. Reiniciar o suspender, no.
- El token del bot vive en `config/telegram.json` (fuera de git) y nunca se escribe en los registros: los
  errores solo dicen su tipo o el código HTTP, porque httpx pone la dirección (con el token) en sus mensajes.
- Los mensajes de hace más de 2 minutos (el PC estaba apagado) se descartan: una orden vieja no debe
  ejecutarse de repente.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import re
import secrets
import subprocess
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

import httpx

from server.core.text import normalize
from server.memory.personal import PersonalMemory, Reminder

log = logging.getLogger(__name__)
audit = logging.getLogger("audit")

API = "https://api.telegram.org"
POLL_SECONDS = 25
MAX_TEXT = 4000  # Telegram admite 4096 caracteres por mensaje
MAX_QUESTION = 1000
STALE_SECONDS = 120
ANSWER_TIMEOUT = 150.0
BACKOFF = (2.0, 5.0, 10.0, 30.0, 60.0)
OFF_DELAY_S = 60  # lo que tarda en apagarse: tiempo para pulsar «Cancelar apagado»
OFFER_SECONDS = 120  # lo que vale una pregunta «¿Apago la PC?»
_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)

# «apaga la pc», «apágame el ordenador»… pero no «no apagues la pc» ni «cómo se apaga la pc».
_OFF = re.compile(
    r"^(?:vega )?(?:por favor )?(?:apaga|apagame|apagar|puedes apagar)"
    r" (?:la |el |mi )?(?:pc|ordenador|computadora|compu|equipo)\b"
)
_ABORT = re.compile(r"\b(?:cancela|cancelar|para|deten) (?:el )?apagado\b|^no (?:la |lo )?apagues\b")

HELP = (
    "Soy Vega. Escríbeme como en la app:\n"
    "• «recuérdame en 10 minutos sacar la ropa»\n"
    "• «pon un fondo de paisaje», «baja Discord», «abre Fortnite»\n"
    "• cualquier pregunta: te contesto con el modelo del PC\n\n"
    "Comandos:\n"
    "/hoy — el resumen del día (entregas, la llama, el tiempo…)\n"
    "/noche — buenas noches: lo de mañana y si apago la PC\n"
    "/apagar — apagar la PC (te pregunto antes y puedes cancelarlo)\n"
    "/cancelar — cancela un apagado en marcha\n"
    "/estado — cómo está el PC y el teléfono\n"
    "/recordatorios — lo que tengo pendiente\n"
    "/ayuda — esto"
)


class TelegramError(Exception):
    """Un fallo de la API de Telegram. Nunca lleva la dirección (lleva el token)."""

    def __init__(self, status: int | None, kind: str) -> None:
        super().__init__(f"{kind} (HTTP {status})" if status else kind)
        self.status = status


class TelegramAuthError(TelegramError):
    """El token no vale (o el bot se borró): reintentar no sirve de nada."""


@dataclass(frozen=True)
class TelegramConfig:
    token: str
    chat_ids: frozenset[int]


def load_config(path: Path) -> TelegramConfig | None:
    """El token y las cuentas autorizadas, o None (y un aviso) si falta algo."""
    try:
        data = json.loads(path.read_text(encoding="utf-8-sig"))
        token = str(data.get("token") or "").strip()
        ids = frozenset(int(i) for i in data.get("chat_ids") or [])
    except FileNotFoundError:
        log.warning(
            "Telegram activado, pero falta %s: ejecuta «python -m server.tools.telegram_setup».", path.name
        )
        return None
    except (OSError, ValueError, TypeError, AttributeError):
        log.warning("Telegram: %s no se puede leer.", path.name)
        return None
    if not token or not ids:
        log.warning("Telegram: %s no tiene token o ninguna cuenta autorizada; no arranco.", path.name)
        return None
    return TelegramConfig(token, ids)


class PowerError(Exception):
    """Windows no hizo lo pedido (el mensaje se le enseña tal cual)."""


def windows_power(what: str, run: Callable[..., Any] = subprocess.run) -> str:
    """Apagar la PC en OFF_DELAY_S segundos («shutdown») o cancelarlo («cancel»)."""
    if what == "shutdown":
        cmd = ["shutdown", "/s", "/t", str(OFF_DELAY_S), "/c", "Apagado pedido desde Telegram."]
        if run(cmd, capture_output=True, creationflags=_NO_WINDOW).returncode:
            raise PowerError("Windows no dejó apagar (¿ya había un apagado en marcha?).")
        return f"Apagando la PC en {OFF_DELAY_S} s."
    if what == "cancel":
        if run(["shutdown", "/a"], capture_output=True, creationflags=_NO_WINDOW).returncode:
            raise PowerError("No había ningún apagado en marcha.")
        return "Apagado cancelado."
    raise PowerError(f"Orden desconocida: {what}")


def _audit(event: str, detail: str = "") -> None:
    audit.info(
        json.dumps(
            {"evento": event, "accion": "apagar", "cliente": "telegram", "detalle": detail},
            ensure_ascii=False,
        )
    )


class TelegramApi:
    def __init__(self, token: str, client: httpx.AsyncClient | None = None) -> None:
        self._base = f"{API}/bot{token}"
        self._client = client or httpx.AsyncClient()

    async def call(self, method: str, payload: dict[str, Any], timeout: float = 15.0) -> Any:
        try:
            response = await self._client.post(f"{self._base}/{method}", json=payload, timeout=timeout)
        except httpx.HTTPError as exc:  # sin str(exc): puede llevar la dirección con el token
            raise TelegramError(None, type(exc).__name__) from None
        if response.status_code in (401, 404):
            raise TelegramAuthError(response.status_code, "token rechazado")
        if response.status_code != 200:
            raise TelegramError(response.status_code, "error de la API")
        try:
            data = response.json()
        except ValueError:
            raise TelegramError(response.status_code, "respuesta ilegible") from None
        if not isinstance(data, dict) or not data.get("ok"):
            raise TelegramError(response.status_code, "la API dijo que no")
        return data.get("result")

    async def close(self) -> None:
        await self._client.aclose()


@dataclass(frozen=True)
class Reply:
    """Lo que contesta Vega; `ask` es el botón por el que pregunta al final (p. ej. «apagar»)."""

    text: str
    ask: str | None = None


Answer = Callable[[str], Awaitable[str | Reply]]
Text = Callable[[], Awaitable[str]]


class TelegramBridge:
    def __init__(
        self,
        api: TelegramApi,
        allowed: frozenset[int],
        answer: Answer,
        status: Text,
        pending: Text,
        clock: Callable[[], float] = time.time,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
        power: Callable[[str], str] | None = None,
        before_off: Text | None = None,
    ) -> None:
        self._power = power  # None: apagar desde Telegram no está permitido
        self._before_off = before_off  # avisos antes de apagar (un juego abierto…)
        self._offers: dict[str, float] = {}  # código de cada «¿Apago la PC?» → hasta cuándo vale
        self._api = api
        self._allowed = allowed
        self._answer = answer
        self._status = status
        self._pending = pending
        self._clock = clock
        self._sleep = sleep
        self._busy = asyncio.Semaphore(1)  # el modelo atiende de uno en uno
        self._tasks: set[asyncio.Task[None]] = set()

    # --- Envío ---

    async def send(self, chat_id: int, text: str, buttons: list[list[dict[str, str]]] | None = None) -> bool:
        payload: dict[str, Any] = {"chat_id": chat_id, "text": text[:MAX_TEXT]}
        if buttons:
            payload["reply_markup"] = {"inline_keyboard": buttons}
        try:
            await self._api.call("sendMessage", payload)
        except TelegramError as exc:
            log.warning("Telegram: no pude enviar un mensaje (%s)", exc)
            return False
        return True

    async def notify(self, text: str, retries: tuple[float, ...] = (0.0, 2.0, 5.0)) -> bool:
        """Un aviso a todas las cuentas autorizadas, con reintentos. True si llegó a alguna."""
        delivered = False
        for chat_id in sorted(self._allowed):
            for wait in retries:
                if wait:
                    await self._sleep(wait)
                if await self.send(chat_id, text):
                    delivered = True
                    break
        return delivered

    # --- Lo que llega ---

    def _accepts(self, message: dict[str, Any]) -> bool:
        chat = message.get("chat") or {}
        sender = message.get("from") or {}
        return (
            chat.get("type") == "private"
            and chat.get("id") in self._allowed
            and sender.get("id") in self._allowed
            and not sender.get("is_bot", False)
        )

    async def handle(self, update: dict[str, Any]) -> None:
        if isinstance(update.get("callback_query"), dict):
            await self._press(update["callback_query"])
            return
        message = update.get("message")
        if not isinstance(message, dict) or not isinstance(message.get("text"), str):
            return
        if not self._accepts(message):
            log.info("Telegram: mensaje de una cuenta no autorizada (ignorado)")
            return
        if self._clock() - float(message.get("date") or 0) > STALE_SECONDS:
            log.info("Telegram: mensaje antiguo descartado")
            return
        chat_id = int(message["chat"]["id"])
        text = message["text"].strip()
        if not text:
            return
        if text.startswith("/"):
            command = text.split()[0].split("@")[0].lower()
            if command in ("/start", "/ayuda", "/help"):
                await self.send(chat_id, HELP)
            elif command == "/estado":
                await self.send(chat_id, await self._status())
            elif command == "/recordatorios":
                await self.send(chat_id, await self._pending())
            elif command == "/apagar":
                await self._offer_off(chat_id)
            elif command == "/cancelar":
                await self.send(chat_id, await self._abort())
            elif command in ("/hoy", "/noche"):
                await self._ask_vega(chat_id, "resumen del dia" if command == "/hoy" else "buenas noches")
            else:
                await self.send(chat_id, "No conozco ese comando. /ayuda te dice qué puedo hacer.")
            return
        said = normalize(text)
        if _ABORT.search(said):
            await self.send(chat_id, await self._abort())
            return
        if _OFF.search(said):
            await self._offer_off(chat_id)
            return
        await self._ask_vega(chat_id, text)

    async def _ask_vega(self, chat_id: int, text: str) -> None:
        async with self._busy:
            with contextlib.suppress(TelegramError):
                await self._api.call("sendChatAction", {"chat_id": chat_id, "action": "typing"})
            try:
                reply = await asyncio.wait_for(self._answer(text[:MAX_QUESTION]), ANSWER_TIMEOUT)
            except TimeoutError:
                reply = "Estoy tardando demasiado: prueba otra vez en un rato."
            except Exception:
                log.exception("Telegram: falló al contestar")
                reply = "No he podido contestar; mira los registros del PC."
        if isinstance(reply, Reply):
            if reply.ask == "apagar" and self._power is not None:
                await self._offer_off(chat_id, lead=reply.text)
                return
            reply = reply.text
        await self.send(chat_id, reply or "No tengo nada que decir a eso.")

    # --- Apagar la PC ---

    async def _offer_off(self, chat_id: int, lead: str = "") -> None:
        """«¿Apago la PC?» con dos botones. Cada pregunta tiene su código y vale una sola vez."""
        if self._power is None:
            await self.send(chat_id, "Apagar la PC desde Telegram no está activado.")
            return
        now = self._clock()
        self._offers = {k: v for k, v in self._offers.items() if v > now}
        code = secrets.token_urlsafe(9)
        self._offers[code] = now + OFFER_SECONDS
        warning = ""
        if self._before_off is not None:
            try:
                warning = await self._before_off()
            except Exception:
                log.warning("Telegram: no pude mirar qué hay abierto", exc_info=True)
        text = "⚠️ ¿Apago la PC?"
        if warning:
            text += "\n" + warning
        text += f"\nSe apagará en {OFF_DELAY_S} s y podrás cancelarlo."
        if lead:
            text = f"{lead}\n\n{text}"
        buttons = [[
            {"text": "🔌 Sí, apagar", "callback_data": f"off:{code}"},
            {"text": "Cancelar", "callback_data": f"no:{code}"},
        ]]  # fmt: skip
        _audit("confirmación pedida")
        await self.send(chat_id, text, buttons)

    async def _abort(self) -> str:
        if self._power is None:
            return "Apagar la PC desde Telegram no está activado."
        try:
            message = await asyncio.to_thread(self._power, "cancel")
        except PowerError as exc:
            return str(exc)
        _audit("cancelada")
        return "✋ " + message

    async def _press(self, query: dict[str, Any]) -> None:
        """Un botón pulsado. Solo cuentan los de las cuentas autorizadas, en su chat privado."""
        message = query.get("message") or {}
        sender = query.get("from") or {}
        chat = message.get("chat") or {}
        if not (
            sender.get("id") in self._allowed
            and chat.get("id") in self._allowed
            and chat.get("type") == "private"
            and not sender.get("is_bot", False)
        ):
            log.info("Telegram: botón de una cuenta no autorizada (ignorado)")
            return
        with contextlib.suppress(TelegramError):
            await self._api.call("answerCallbackQuery", {"callback_query_id": query.get("id")})
        data = str(query.get("data") or "")
        kind, _, code = data.partition(":")
        buttons: list[list[dict[str, str]]] = []
        if kind == "abort":
            text = await self._abort()
        elif kind in ("off", "no"):
            valid = self._offers.pop(code, 0.0) > self._clock()
            if not valid:
                text = "Esa pregunta ya no vale. Escribe /apagar si quieres apagar la PC."
            elif kind == "no":
                text = "Vale, no la apago."
            elif self._power is None:
                text = "Apagar la PC desde Telegram no está activado."
            else:
                try:
                    text = "🔌 " + await asyncio.to_thread(self._power, "shutdown")
                    buttons = [[{"text": "✋ Cancelar apagado", "callback_data": "abort"}]]
                    _audit("ejecutada", f"en {OFF_DELAY_S} s")
                except PowerError as exc:
                    text = str(exc)
                    _audit("falló", text)
        else:
            return
        await self._edit(chat["id"], message.get("message_id"), text, buttons)

    async def _edit(
        self, chat_id: int, message_id: Any, text: str, buttons: list[list[dict[str, str]]]
    ) -> None:
        """Cambia el mensaje de la pregunta (sus botones viejos desaparecen); si no se puede, uno nuevo."""
        payload: dict[str, Any] = {"chat_id": chat_id, "message_id": message_id, "text": text[:MAX_TEXT]}
        payload["reply_markup"] = {"inline_keyboard": buttons}
        try:
            await self._api.call("editMessageText", payload)
        except TelegramError:
            await self.send(chat_id, text, buttons or None)

    def _spawn(self, update: dict[str, Any]) -> None:
        task = asyncio.create_task(self.handle(update))
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    async def run(self) -> None:
        """Sondea a Telegram hasta que se cancele. Se apaga solo si el token no vale."""
        offset: int | None = None
        failures = 0
        log.info("Telegram: puente activo (%d cuenta/s autorizada/s)", len(self._allowed))
        try:
            while True:
                try:
                    updates = await self._api.call(
                        "getUpdates",
                        {
                            "timeout": POLL_SECONDS,
                            "offset": offset,
                            "allowed_updates": ["message", "callback_query"],
                        },
                        timeout=POLL_SECONDS + 10,
                    )
                except TelegramAuthError as exc:
                    log.error("Telegram: %s. El puente se apaga; revisa config/telegram.json.", exc)
                    return
                except TelegramError as exc:
                    wait = BACKOFF[min(failures, len(BACKOFF) - 1)]
                    failures += 1
                    if failures in (1, 5):
                        log.warning("Telegram: sin conexión (%s); reintento en %.0f s", exc, wait)
                    await self._sleep(wait)
                    continue
                if failures >= 5:
                    log.info("Telegram: conexión recuperada")
                failures = 0
                for update in updates or []:
                    if isinstance(update, dict) and isinstance(update.get("update_id"), int):
                        offset = update["update_id"] + 1
                        self._spawn(update)
        finally:
            for task in list(self._tasks):
                task.cancel()
            await self._api.close()


class TelegramListener:
    """Se apunta al centro de avisos de Vega como un «cliente más»: recibe los recordatorios vencidos."""

    def __init__(self, bridge: TelegramBridge, has_phone: Callable[[], bool], mode: str) -> None:
        self._bridge = bridge
        self._has_phone = has_phone
        self._mode = mode  # away: solo si no hay teléfono conectado; always: siempre

    async def announce(self, reminder: Reminder, late: bool) -> None:
        if self._mode == "away" and self._has_phone():
            return  # el teléfono lo dice en voz alta
        if reminder.kind == "timer":
            text = f"⏰ ¡Tiempo! Terminó el {reminder.text}."
        elif late:
            text = f"🔔 Mientras no estabas conectado tenía que recordarte: {reminder.text}."
        else:
            text = f"🔔 Te recuerdo: {reminder.text}."
        if not await self._bridge.notify(text):
            log.warning("Telegram: un recordatorio no pudo entregarse (no hay conexión con Telegram)")

    # El resto de lo que el centro de avisos reparte (lecturas del PC, música, juego…) no es para Telegram.
    async def send_telemetry(self, event: object) -> None: ...
    async def send_media(self, event: object) -> None: ...
    async def send_notifications(self, event: object) -> None: ...
    async def send_event(self, event: object) -> None: ...
    async def send_actions(self) -> None: ...


# --- Textos de /estado y /recordatorios ---


def format_pending(items: list[Reminder], now: datetime) -> str:
    if not items:
        return "No tengo nada pendiente."
    lines = []
    for r in items[:15]:
        when = (
            r.due_at.strftime("%H:%M") if r.due_at.date() == now.date() else r.due_at.strftime("%d/%m %H:%M")
        )
        icon = "⏱️" if r.kind == "timer" else "🔔"
        lines.append(f"{icon} {when} — {r.text}")
    return "Pendiente:\n" + "\n".join(lines)


def make_pending(memory: PersonalMemory, now: Callable[[], datetime] = datetime.now) -> Text:
    async def pending() -> str:
        return format_pending(await asyncio.to_thread(memory.pending), now())

    return pending


def make_status(
    telemetry: Callable[[], Any],
    has_phone: Callable[[], bool],
    game: Callable[[], str | None],
) -> Text:
    """«/estado»: el PC, el juego y el teléfono en cuatro líneas."""

    async def status() -> str:
        t = await asyncio.to_thread(telemetry)
        gpu = (
            f"{t.gpu_temp:.0f} °C · {t.gpu_util:.0f} %"
            if t.gpu_temp is not None and t.gpu_util is not None
            else "sin datos"
        )
        vram = (
            f" · VRAM {t.vram_used:.1f}/{t.vram_total:.1f} GB"
            if t.vram_used is not None and t.vram_total
            else ""
        )
        playing = await asyncio.to_thread(game)
        nxt = (
            f"\n⏰ Siguiente aviso en {round(t.upcoming[0].due_in / 60)} min: {t.upcoming[0].text}"
            if t.upcoming
            else ""
        )
        return (
            f"🖥️ PC: procesador {t.cpu:.0f} % · memoria {t.ram_used:.1f}/{t.ram_total:.1f} GB\n"
            f"🎞️ Gráfica: {gpu}{vram}\n"
            f"🎮 {('Jugando a ' + playing) if playing else 'Sin juego abierto'}\n"
            f"📱 Teléfono: {'conectado' if has_phone() else 'sin conexión'}{nxt}"
        )

    return status
