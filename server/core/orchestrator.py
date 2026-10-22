"""Orquestador de una conversación: máquina de estados + historial persistente.

Estados: idle → (listening) → thinking → speaking → idle; acting mientras se ejecuta
un botón; error si el modelo falla (el siguiente mensaje vuelve a empezar).

Voz: con el botón (listen_start/stop) o con escucha continua («Vega, …»). Una orden
de voz que coincide con un botón (`say` en config/actions.toml) lo pulsa; si no,
va al modelo de lenguaje.

El historial vive en SQLite y se lee en cada turno, así sobrevive a reinicios y
todos los clientes conectados comparten la misma conversación.
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import Awaitable, Callable, Coroutine
from typing import Any

from server.core.clock import time_context
from server.llm.base import ChatMessage, ChatModel, LlmUnavailable
from server.memory.personal import PersonalMemory, Reminder
from server.memory.store import ConversationStore
from server.skills.router import SkillRouter
from server.speech.base import BYTES_PER_SAMPLE, SAMPLE_RATE, SpeechToText, SttUnavailable
from server.speech.listener import SpeechSegmenter, parse_wake, yes_or_no
from server.speech.sentences import SentenceSplitter, clean_for_speech
from server.speech.speaker import EmitAudio, Speaker
from server.speech.tts import TextToSpeech, TtsUnavailable
from server.tools.actions import CONFIRM_SECONDS, ActionRunner, Outcome
from server.ws.protocol import (
    ActionResultEvent,
    ActionsEvent,
    ConfirmEvent,
    DoneEvent,
    ErrorEvent,
    HistoryEvent,
    HistoryItem,
    ReminderEvent,
    ServerEvent,
    State,
    StateEvent,
    TelemetryEvent,
    TokenEvent,
    TranscriptEvent,
    VoicesEvent,
)

log = logging.getLogger(__name__)

Emit = Callable[[ServerEvent], Awaitable[None]]

# Mensajes (usuario + asistente) que se envían al modelo como contexto.
MAX_HISTORY = 20
# Tras decir solo «Vega» (o tocar el rostro), tiempo para decir la orden sin repetir el nombre.
FOLLOWUP_SECONDS = 8.0
# Al detectar voz se precarga el modelo de lenguaje, como mucho una vez por este intervalo.
PRELOAD_GAP_SECONDS = 60.0


class Orchestrator:
    def __init__(
        self,
        model: ChatModel,
        system_prompt: str,
        store: ConversationStore,
        emit: Emit,
        stt: SpeechToText | None = None,
        max_audio_seconds: int = 30,
        actions: ActionRunner | None = None,
        name: str = "",
        now_context: Callable[[], str] = time_context,
        tts: TextToSpeech | None = None,
        emit_audio: EmitAudio | None = None,
        skills: SkillRouter | None = None,
        memory: PersonalMemory | None = None,
    ) -> None:
        self._skills = skills
        self._memory = memory
        self._now_context = now_context
        self._tts = tts
        self._emit_audio = emit_audio
        self._actions = actions
        self._name = name
        # Escucha continua
        self._wake_on = False
        self._segmenter = SpeechSegmenter()
        self._followup_until = 0.0
        self._reply_was_spoken = (
            False  # ¿la última orden fue por voz? (tras oírse la respuesta, sigue escuchando)
        )
        self._voice_task: asyncio.Task[None] | None = None
        self._voice_confirm: tuple[str, float] | None = None  # (código, caduca)
        self._last_preload = -PRELOAD_GAP_SECONDS
        self._tasks: set[asyncio.Task[None]] = set()
        self._model = model
        self._system = ChatMessage("system", system_prompt)
        self._store = store
        self._emit = emit
        self._stt = stt
        self._max_audio_bytes = max_audio_seconds * SAMPLE_RATE * BYTES_PER_SAMPLE
        self._audio: bytearray | None = None  # None = no se está grabando
        self._preload: asyncio.Task[None] | None = None
        self._state = State.IDLE

    @property
    def state(self) -> State:
        return self._state

    async def _set_state(self, state: State) -> None:
        if state != self._state:
            self._state = state
            await self._emit(StateEvent(state=state))

    async def send_history(self) -> None:
        conversation = await asyncio.to_thread(self._store.latest_conversation)
        stored = await asyncio.to_thread(self._store.recent, conversation, MAX_HISTORY)
        await self._emit(HistoryEvent(messages=[HistoryItem(role=m.role, content=m.content) for m in stored]))

    async def reset(self) -> None:
        """Empieza una conversación nueva; la anterior queda guardada."""
        await asyncio.to_thread(self._store.new_conversation)
        await self._emit(HistoryEvent(messages=[]))
        await self._set_state(State.IDLE)

    # --- Voz ---

    async def start_listening(self) -> None:
        if self._stt is None:
            await self._emit(ErrorEvent(message="El reconocimiento de voz no está disponible."))
            return
        self._audio = bytearray()
        # Mientras el usuario habla, se va cargando el modelo de lenguaje si estaba descargado
        # de la GPU: así la respuesta no espera a la carga al terminar la transcripción.
        self._preload = asyncio.create_task(self._model.preload())
        await self._set_state(State.LISTENING)

    def add_audio(self, chunk: bytes) -> None:
        """Audio del micrófono: va a la grabación del botón si está activa, si no a la
        escucha continua (si está activada). Se ignora lo demás."""
        if len(chunk) % BYTES_PER_SAMPLE:
            return
        if self._audio is None:
            if self._wake_on:
                self._feed_wake(chunk)
            return
        room = self._max_audio_bytes - len(self._audio)
        if room > 0:
            self._audio += chunk[:room]

    async def cancel_listening(self) -> None:
        self._audio = None
        await self._set_state(State.IDLE)

    async def stop_listening(self) -> None:
        """Transcribe lo grabado y, si se entendió algo, responde como a un mensaje escrito."""
        audio, self._audio = self._audio, None
        if audio is None or self._stt is None:
            return
        await self._set_state(State.THINKING)
        try:
            result = await asyncio.to_thread(self._stt.transcribe, bytes(audio))
        except SttUnavailable as exc:
            await self._emit(ErrorEvent(message=str(exc)))
            await self._set_state(State.ERROR)
            return
        await self._emit(
            TranscriptEvent(
                text=result.text,
                audio_seconds=round(result.audio_seconds, 2),
                elapsed_ms=round(result.elapsed_seconds * 1000),
            )
        )
        if not result.text:
            await self._set_state(State.IDLE)
            return
        await self.handle_text(result.text)

    # --- Escucha continua («Vega, …») ---

    async def set_wake_mode(self, on: bool) -> None:
        """Con la escucha continua activa, el teléfono manda el micrófono todo el rato."""
        if on and self._stt is None:
            await self._emit(ErrorEvent(message="El reconocimiento de voz no está disponible."))
            return
        self._wake_on = on
        self._segmenter.reset()
        self._followup_until = 0.0
        log.info("Escucha continua %s", "activada" if on else "desactivada")

    async def playback_done(self) -> None:
        """El teléfono terminó de decir la respuesta. Si era una conversación por voz, se sigue
        escuchando unos segundos SIN tener que decir «Vega» (antes había que repetirlo en cada
        frase: si seguías hablando, te ignoraba y parecía congelada)."""
        if not self._reply_was_spoken or not self._wake_on:
            return
        if self._voice_busy:
            return  # aún habla (el teléfono avisa entre frases si hay una pausa): se espera al final
        self._reply_was_spoken = False
        await self._open_followup()

    async def attention(self) -> None:
        """El usuario tocó el rostro: la próxima frase es una orden aunque no diga el nombre."""
        await self._open_followup()

    async def _open_followup(self) -> None:
        self._followup_until = time.monotonic() + FOLLOWUP_SECONDS
        self._preload_soon()
        await self._set_state(State.LISTENING)

        async def expire(until: float) -> None:
            await asyncio.sleep(FOLLOWUP_SECONDS)
            if self._followup_until == until and self._state == State.LISTENING and not self._voice_busy:
                self._followup_until = 0.0
                await self._set_state(State.IDLE)

        self._spawn(expire(self._followup_until))

    @property
    def _voice_busy(self) -> bool:
        return (self._voice_task is not None and not self._voice_task.done()) or self._state in (
            State.THINKING,
            State.SPEAKING,
            State.ACTING,
        )

    def _feed_wake(self, chunk: bytes) -> None:
        if self._voice_busy:
            # Mientras responde o actúa no se escucha (evita oírse a sí mismo y frases a medias).
            self._segmenter.reset()
            return
        was_speaking = self._segmenter.in_speech
        phrases = self._segmenter.feed(chunk)
        if self._segmenter.in_speech and not was_speaking:
            self._preload_soon()
        for phrase in phrases:
            self._voice_task = self._spawn(self._process_phrase(phrase))
            break  # una frase cada vez; lo que venga detrás se descarta mientras se procesa

    def _preload_soon(self) -> None:
        """Alguien empezó a hablar: si el modelo de lenguaje estaba fuera de la GPU, se va
        cargando mientras termina la frase (si no, la primera respuesta tardaba ~4 s más).
        Como mucho una vez por minuto: si ya está cargado, solo renueva su tiempo en memoria."""
        now = time.monotonic()
        if now - self._last_preload >= PRELOAD_GAP_SECONDS:
            self._last_preload = now
            self._spawn(self._model.preload())

    async def _process_phrase(self, pcm: bytes) -> None:
        if self._stt is None:
            return
        try:
            result = await asyncio.to_thread(self._stt.transcribe, pcm)
        except SttUnavailable as exc:
            await self._emit(ErrorEvent(message=str(exc)))
            return
        text = result.text.strip()
        if not text:
            return
        now = time.monotonic()

        # ¿Es la respuesta a una confirmación pedida por voz («sí» / «no»)?
        if self._voice_confirm and now < self._voice_confirm[1]:
            answer = yes_or_no(text)
            if answer is not None:
                nonce = self._voice_confirm[0]
                self._voice_confirm = None
                await self._emit(
                    TranscriptEvent(
                        text=text,
                        audio_seconds=round(result.audio_seconds, 2),
                        elapsed_ms=round(result.elapsed_seconds * 1000),
                    )
                )
                if answer:
                    await self.confirm_action(nonce, client="voz")
                else:
                    await self._emit(ActionResultEvent(id="", ok=True, message="Cancelado."))
                    await self.speak("Cancelado.")
                return

        if now < self._followup_until:
            wake = parse_wake(text, self._name)
            command = wake.command if wake.woke else text
        else:
            wake = parse_wake(text, self._name)
            if not wake.woke:
                log.info("Frase sin «%s» (descartada): «%s»", self._name, text)
                return
            command = wake.command
            if not command:
                await self._open_followup()  # solo dijo «Vega»: espera la orden
                return

        self._followup_until = 0.0
        self._reply_was_spoken = True  # al terminar de oírse la respuesta, seguirá escuchando
        await self._emit(
            TranscriptEvent(
                text=command,
                audio_seconds=round(result.audio_seconds, 2),
                elapsed_ms=round(result.elapsed_seconds * 1000),
            )
        )
        await self.handle_command(command, spoken=True)

    async def handle_command(self, command: str, spoken: bool = False) -> None:
        """Una orden (dicha o escrita): primero las habilidades (memoria, recordatorios,
        notas, estado), luego los botones (solo por voz) y, si nada encaja, el modelo."""
        if self._skills is not None:
            reply = await asyncio.to_thread(self._skills.handle, command)
            if reply is not None:
                await self._skill_reply(command, reply.text)
                return
        action = self._actions.match(command) if (spoken and self._actions) else None
        if action is not None:
            pending = await self.request_action(action.id, client="voz")
            if pending:
                self._voice_confirm = (pending, time.monotonic() + CONFIRM_SECONDS)
            return
        await self.handle_text(command)

    async def _skill_reply(self, command: str, text: str) -> None:
        """Respuesta de una habilidad: se muestra, se dice y se guarda en el historial."""
        await self._set_state(State.SPEAKING)
        await self._emit(TokenEvent(text=text))
        await self._emit(DoneEvent(text=text))
        conversation = await asyncio.to_thread(self._store.latest_conversation)
        await asyncio.to_thread(
            self._store.add_messages,
            conversation,
            [ChatMessage("user", command), ChatMessage("assistant", text)],
        )
        await self.speak(text)
        await self._set_state(State.IDLE)

    async def announce(self, reminder: Reminder, late: bool) -> None:
        """Avisa de un recordatorio o temporizador vencido (lo llama el centro de avisos)."""
        if reminder.kind == "timer":
            spoken = f"¡Tiempo! Terminó el {reminder.text}."
        elif late:
            spoken = f"Mientras no estabas conectado tenía que recordarte: {reminder.text}."
        else:
            spoken = f"Te recuerdo: {reminder.text}."
        await self._emit(ReminderEvent(kind=reminder.kind, text=spoken))
        await self.speak(spoken)

    async def send_telemetry(self, event: TelemetryEvent) -> None:
        await self._emit(event)

    def _spawn(self, coro: Coroutine[Any, Any, None]) -> asyncio.Task[None]:
        task = asyncio.create_task(coro)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)
        return task

    async def close(self) -> None:
        for task in list(self._tasks):
            task.cancel()

    # --- Botones del panel ---

    async def send_actions(self) -> None:
        if self._actions is not None:
            await self._emit(ActionsEvent(items=[a.public() for a in self._actions.actions]))

    async def request_action(self, action_id: str, client: str = "toque") -> str | None:
        """Pulsa un botón. Devuelve el código de confirmación si la acción la necesita."""
        if self._actions is None:
            return None
        outcome = await asyncio.to_thread(self._actions.request, action_id, client)
        # Si la orden fue por voz, el resultado también se dice en voz alta.
        return await self._handle_outcome(outcome, action_id, spoken=client == "voz")

    async def confirm_action(self, nonce: str, client: str = "toque") -> None:
        if self._actions is not None:
            self._voice_confirm = None
            outcome = await asyncio.to_thread(self._actions.confirm, nonce, client)
            await self._handle_outcome(outcome, "", spoken=client == "voz")

    async def _handle_outcome(self, outcome: Outcome, requested_id: str, spoken: bool = False) -> str | None:
        action_id = outcome.action.id if outcome.action else requested_id
        if outcome.kind == "ignored":
            return None
        if outcome.kind == "confirm" and outcome.nonce:
            await self._emit(
                ConfirmEvent(
                    nonce=outcome.nonce,
                    id=action_id,
                    message=outcome.message,
                    expires_in=int(CONFIRM_SECONDS),
                )
            )
            await self._set_state(State.IDLE)
            if spoken:
                await self.speak(outcome.message.replace("Pulsa de nuevo", "Di sí"))
            return outcome.nonce
        await self._set_state(State.ACTING)
        await self._emit(ActionResultEvent(id=action_id, ok=outcome.kind == "done", message=outcome.message))
        if spoken:
            await self.speak(outcome.message)
        await self._set_state(State.IDLE)
        return None

    # --- Voz de respuesta (texto a voz) ---

    def _speaker(self) -> Speaker | None:
        if self._tts is None or self._emit_audio is None:
            return None
        return Speaker(self._tts, self._emit, self._emit_audio)

    async def speak(self, text: str) -> None:
        """Dice un texto suelto (resultado de una orden de voz, presentación de una voz…)."""
        speaker = self._speaker()
        if speaker is None:
            return
        speaker.say(clean_for_speech(text))
        await speaker.finish()

    async def send_voices(self) -> None:
        if self._tts is not None:
            items = [{"id": v.id, "label": v.label} for v in self._tts.voices()]
            await self._emit(VoicesEvent(items=items, current=self._tts.voice))

    async def set_voice(self, voice_id: str) -> None:
        if self._tts is None:
            return
        try:
            await asyncio.to_thread(self._tts.set_voice, voice_id)
        except TtsUnavailable as exc:
            await self._emit(ErrorEvent(message=str(exc)))
            return
        await self.send_voices()
        await self.speak(f"Hola, esta es mi nueva voz. Soy {self._name or 'tu asistente'}.")

    # --- Texto ---

    async def handle_text(self, text: str) -> None:
        question = ChatMessage("user", text)
        conversation = await asyncio.to_thread(self._store.latest_conversation)
        previous = await asyncio.to_thread(self._store.recent, conversation, MAX_HISTORY)
        # El modelo no tiene reloj: en cada pregunta se le da la fecha y hora del PC.
        extra = [self._now_context()]
        if self._memory is not None:
            known = await asyncio.to_thread(self._memory.prompt_context)
            if known:
                extra.append(known)
        system = ChatMessage("system", "\n".join([self._system.content, *extra]))
        context = [system, *(m.as_chat() for m in previous), question]
        await self._set_state(State.THINKING)

        start = time.perf_counter()
        first_token: float | None = None
        parts: list[str] = []
        # Cada frase completa se convierte en voz mientras el modelo sigue escribiendo.
        speaker = self._speaker()
        splitter = SentenceSplitter()
        try:
            async for piece in self._model.stream(context):
                if first_token is None:
                    first_token = time.perf_counter() - start
                    await self._set_state(State.SPEAKING)
                parts.append(piece)
                await self._emit(TokenEvent(text=piece))
                if speaker is not None:
                    for sentence in splitter.feed(piece):
                        speaker.say(sentence)
        except LlmUnavailable as exc:
            log.warning("El modelo no respondió: %s", exc)
            if speaker is not None:
                speaker.cancel()
            await self._emit(ErrorEvent(message=str(exc)))
            await self._set_state(State.ERROR)
            return

        reply = "".join(parts).strip()
        if speaker is not None:
            for sentence in splitter.flush():
                speaker.say(sentence)
        # Pregunta y respuesta se guardan juntas: una pregunta sin respuesta no entra al historial.
        await asyncio.to_thread(
            self._store.add_messages, conversation, [question, ChatMessage("assistant", reply)]
        )
        log.info(
            "Respuesta de %s: primera palabra %.2f s, total %.2f s, %d caracteres",
            self._model.name,
            first_token or 0.0,
            time.perf_counter() - start,
            len(reply),
        )
        await self._emit(DoneEvent(text=reply))
        if speaker is not None:
            await speaker.finish()
            if speaker.first_audio_after is not None:
                log.info("Voz: primera frase enviada a los %.2f s de la pregunta", speaker.first_audio_after)
        await self._set_state(State.IDLE)
