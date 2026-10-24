"""Habilidades: órdenes que el asistente resuelve solo, sin el modelo de lenguaje.

Son rápidas, fiables y no gastan GPU. Se prueban en orden; la primera que reconoce
la frase responde. Si ninguna la reconoce, la frase va al modelo de lenguaje.

- Memoria:  «recuerda que…», «qué sabes de mí», «olvida que…»
- Recordatorios: «recuérdame … en 20 minutos / a las 6», «qué recordatorios tengo»,
  «cancela los recordatorios»
- Temporizadores: «pon un temporizador de 5 minutos», «cancela el temporizador»
- Notas: «apunta…», «lee mis notas», «borra mis notas»
- Estado: «cómo está el PC» (CPU, RAM, GPU, batería del teléfono)
- Notificaciones del PC: «qué notificaciones tengo», «léeme el último mensaje (de WhatsApp)»
"""

from __future__ import annotations

import re
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta

from server.core.text import normalize
from server.device.notifications import PcNotification
from server.memory.personal import PersonalMemory, to_you
from server.skills.status import pc_status
from server.skills.when import parse_duration, parse_when, spoken_time


@dataclass(frozen=True)
class SkillReply:
    text: str  # lo que se dice y se muestra


def _cut(text: str, span: tuple[int, int]) -> str:
    """Quita del texto el trozo que describía la hora y limpia lo que sobra."""
    rest = (text[: span[0]] + " " + text[span[1] :]).strip(" ,.;:¡!¿?")
    rest = re.sub(r"^(?:que|de|a)\s+", "", rest, flags=re.IGNORECASE)
    return " ".join(rest.split())


def _s(n: int) -> str:
    """Plural sencillo: «1 nota», «2 notas»."""
    return "" if n == 1 else "s"


def _list(items: list[str]) -> str:
    if len(items) == 1:
        return items[0]
    return ", ".join(items[:-1]) + " y " + items[-1]


def _ago(seconds: float) -> str:
    """Hace cuánto llegó: «ahora mismo», «hace 5 minutos», «hace 2 horas»."""
    s = max(0, int(seconds))
    if s < 60:
        return "ahora mismo"
    if s < 3600:
        m = s // 60
        return f"hace {m} minuto{_s(m)}"
    h = s // 3600
    return f"hace {h} hora{_s(h)}"


def _remaining(seconds: int) -> str:
    """125 → «2 minutos y 5 segundos»."""
    h, m, sec = seconds // 3600, seconds % 3600 // 60, seconds % 60
    parts = []
    if h:
        parts.append(f"{h} hora{_s(h)}")
    if m:
        parts.append(f"{m} minuto{_s(m)}")
    if sec and not h:
        parts.append(f"{sec} segundo{_s(sec)}")
    return _list(parts) if parts else "nada"


_REMEMBER = re.compile(r"^\s*(?:recuerda|acu[ée]rdate(?:\s+de)?|guarda|memoriza)\s+que\s+(?P<fact>.+)$", re.I)
_FORGET = re.compile(r"^\s*olvida\s+(?:que|lo\s+de|lo\s+que\s+te\s+dije\s+de)\s+(?P<about>.+)$", re.I)
_REMIND = re.compile(r"^\s*(?:recu[ée]rdame|av[íi]same|recordarme)\b[\s,:]*(?P<rest>.*)$", re.I)
_NOTE = re.compile(r"^\s*(?:apunta|anota|toma\s+nota(?:\s+de)?|nota)\b[\s:,]*(?:que\s+)?(?P<text>.+)$", re.I)
_STOPWORDS = {
    "como", "cual", "cuales", "cuando", "donde", "quien", "sabes", "recuerdas", "dime", "llama", "llaman",
    "esta", "estan", "tengo", "tiene", "gusta", "gustan", "sobre", "para", "porque",
}  # fmt: skip
_QUESTION = re.compile(r"^(como|cual|cuales|cuando|donde|quien|que|cuanto|cuantos|sabes|recuerdas|dime)\b")


def _is_question(text: str, said: str) -> bool:
    return "?" in text or bool(_QUESTION.match(said))


_TIMER = re.compile(r"\b(?:temporizador|cron[óo]metro|cuenta\s+atr[áa]s|alarma)\b", re.I)


class SkillRouter:
    def __init__(
        self,
        memory: PersonalMemory,
        now: Callable[[], datetime] = datetime.now,
        phone_battery: Callable[[], int | None] = lambda: None,
        notifications: Callable[[], list[PcNotification]] = list,
    ) -> None:
        self._mem = memory
        self._now = now
        self._battery = phone_battery
        self._notifications = notifications

    def handle(self, text: str) -> SkillReply | None:
        said = normalize(text)
        for skill in (self._forget, self._what_you_know, self._remember, self._timer, self._reminders,
                      self._notes, self._pc_notifications, self._status, self._about_me):  # fmt: skip
            reply = skill(text, said)
            if reply is not None:
                return reply
        return None

    # --- Memoria ---

    def _remember(self, text: str, said: str) -> SkillReply | None:
        m = _REMEMBER.match(text)
        if not m:
            return None
        # «A y que B» son dos datos: así se pueden olvidar por separado.
        facts = [f.strip(" .,") for f in re.split(r",?\s+y\s+que\s+", m["fact"]) if f.strip(" .,")]
        for fact in facts:
            self._mem.remember(fact)
        return SkillReply("Entendido, lo recordaré: " + _list([to_you(f) for f in facts]) + ".")

    def _about_me(self, text: str, said: str) -> SkillReply | None:
        """Preguntas sobre el usuario («¿cómo se llama mi perro?», «¿cómo me gusta el café?»):
        si la respuesta está en su memoria, la da directamente. El modelo pequeño se
        confundía de persona («me gusta…») al contestarlas."""
        if not re.search(r"\b(me|mi|mis|yo)\b", said) or not _is_question(text, said):
            return None
        words = {w for w in said.split() if len(w) > 3 and w not in _STOPWORDS}
        if not words:
            return None
        found = [f for f in self._mem.facts() if words & set(normalize(f).split())]
        if not found:
            return None
        answer = _list([to_you(f) for f in found])
        return SkillReply(answer[:1].upper() + answer[1:] + ".")  # sin tocar «Toby» ni otros nombres

    def _what_you_know(self, text: str, said: str) -> SkillReply | None:
        if not re.search(r"\bque (sabes|recuerdas) (de|sobre) mi\b", said):
            return None
        facts = self._mem.facts()
        if not facts:
            return SkillReply("Todavía no sé nada de ti. Dime «recuerda que…» y lo guardo.")
        return SkillReply("Esto es lo que sé de ti: " + "; ".join(to_you(f) for f in facts) + ".")

    def _forget(self, text: str, said: str) -> SkillReply | None:
        m = _FORGET.match(text)
        if not m:
            return None
        gone = self._mem.forget(m["about"])
        if not gone:
            return SkillReply("No tenía guardado nada sobre eso.")
        return SkillReply("Hecho, lo he olvidado: " + "; ".join(to_you(g) for g in gone) + ".")

    # --- Recordatorios y temporizadores ---

    def _timer(self, text: str, said: str) -> SkillReply | None:
        if not _TIMER.search(text):
            return None
        if re.search(r"\bcuanto (le )?(falta|queda)", said):
            timers = [r for r in self._mem.pending() if r.kind == "timer"]
            if not timers:
                return SkillReply("No hay ningún temporizador en marcha.")
            left = max(0, round((timers[0].due_at - self._now()).total_seconds()))
            return SkillReply(f"Le queda {_remaining(left)}.")
        if re.search(r"\b(cancela|quita|para|detén|deten|borra)\b", said):
            n = self._mem.cancel("timer")
            return SkillReply("Temporizador cancelado." if n else "No había ningún temporizador en marcha.")
        found = parse_duration(text)
        if found is None:
            return SkillReply("¿De cuánto tiempo? Dime, por ejemplo, «temporizador de 5 minutos».")
        seconds, span = found
        label = text[span[0] : span[1]].strip()
        label = label if label.lower().startswith("de ") else f"de {label}"
        self._mem.add_reminder("timer", f"temporizador {label}", self._now() + timedelta(seconds=seconds))
        return SkillReply(f"Temporizador {label} en marcha.")

    def _reminders(self, text: str, said: str) -> SkillReply | None:
        if re.search(r"\b(que|mis) recordatorios\b|\bque tengo pendiente\b", said) and "cancela" not in said:
            pending = [r for r in self._mem.pending() if r.kind == "reminder"]
            if not pending:
                return SkillReply("No tienes recordatorios pendientes.")
            now = self._now()
            return SkillReply(
                "Tienes pendiente: "
                + _list([f"{r.text} {spoken_time(r.due_at, now)}" for r in pending])
                + "."
            )
        if re.search(r"\b(cancela|borra|quita)\b.*\brecordatorios?\b", said):
            n = self._mem.cancel("reminder")
            return SkillReply(
                f"Cancelado{_s(n)} {n} recordatorio{_s(n)}." if n else "No había recordatorios pendientes."
            )
        m = _REMIND.match(text)
        if not m:
            return None
        rest = m["rest"]
        now = self._now()
        when = parse_when(rest, now)
        if when is None:
            return SkillReply("¿Cuándo te lo recuerdo? Dime, por ejemplo, «en 20 minutos» o «a las 6».")
        what = _cut(rest, when.span) or "lo que me pediste"
        self._mem.add_reminder("reminder", what, when.at)
        return SkillReply(f"Vale, {spoken_time(when.at, now)} te recuerdo: {what}.")

    # --- Notas ---

    def _notes(self, text: str, said: str) -> SkillReply | None:
        if re.search(r"\b(lee|leeme|dime|cuales son|que tengo en)\b.*\bnotas\b|\bque notas tengo\b", said):
            notes = self._mem.notes()
            if not notes:
                return SkillReply("No tienes notas.")
            return SkillReply(
                f"Tienes {len(notes)} nota{'s' if len(notes) != 1 else ''}: " + "; ".join(notes) + "."
            )
        if re.search(r"\b(borra|elimina|limpia)\b.*\bnotas\b", said):
            n = self._mem.clear_notes()
            return SkillReply(f"Borrada{_s(n)} {n} nota{_s(n)}." if n else "No tenías notas.")
        m = _NOTE.match(text)
        if not m:
            return None
        note = m["text"].strip(" .")
        self._mem.add_note(note)
        return SkillReply(f"Apuntado: {note}.")

    # --- Notificaciones del PC ---

    def _pc_notifications(self, text: str, said: str) -> SkillReply | None:
        asks_list = re.search(
            r"\b(que|cuantas|tengo|hay|leeme|lee|dime)\b.*\b(notificaciones|avisos|mensajes)\b", said
        )
        asks_last = re.search(
            r"\b(leeme|lee|dime|que dice)\b.*\b(ultim[oa]s?|nuev[oa]s?)\b.*\b(mensaje|notificacion|aviso)",
            said,
        )
        if not asks_list and not asks_last:
            return None
        notes = self._notifications()
        # «… de WhatsApp»: solo las de esa app (si se nombra una de las que hay).
        app = next(
            (n.app for n in notes if normalize(n.app) and f" {normalize(n.app)} " in f" {said} "), None
        )
        if app:
            notes = [n for n in notes if n.app == app]
        if not notes:
            where = f" de {app}" if app else ""
            return SkillReply(f"No tienes notificaciones{where} en el PC.")
        if asks_last:
            n = notes[0]
            ago = _ago(self._now().timestamp() - n.at)
            who = n.app if n.title == n.app else f"{n.title}, por {n.app}"
            return SkillReply(f"{who}, {ago}: {n.body or n.title}")
        parts = [n.app if n.title == n.app else f"{n.app} de {n.title}" for n in notes[:5]]
        more = f" y {len(notes) - 5} más" if len(notes) > 5 else ""
        return SkillReply(
            f"Tienes {len(notes)} notificaci{'ón' if len(notes) == 1 else 'ones'}: {_list(parts)}{more}."
        )

    # --- Estado del PC y del teléfono ---

    def _status(self, text: str, said: str) -> SkillReply | None:
        if not re.search(
            r"\b(como (esta|va) (el|mi) (pc|ordenador|computador|computadora|equipo)"
            r"|estado del (pc|ordenador|equipo|sistema)|temperatura de la (grafica|gpu)"
            r"|cuanta bateria|bateria del telefono)\b",
            said,
        ):
            return None
        return SkillReply(pc_status(self._battery()))
