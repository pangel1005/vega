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
- Entregas de la universidad (Moodle): «qué tareas tengo», «ya entregué el ensayo»
- Racha del gimnasio: «fui al gimnasio», «ayer fui al gimnasio», «¿cómo va mi racha?»
- El día: «buenos días» / «resumen del día» (recordatorios, entregas, la llama, el tiempo) y
  «buenas noches» (pausa la música, lo de mañana, la llama y pregunta si apaga la PC)
"""

from __future__ import annotations

import re
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta

from server.core.text import normalize
from server.device.notifications import PcNotification
from server.memory.personal import PersonalMemory, Reminder, to_you
from server.skills.desk import Desk
from server.skills.moodle import TaskBook, clock, describe
from server.skills.status import pc_status
from server.skills.streak import GymLog
from server.skills.streak import handle as streak_handle
from server.skills.streak import morning as streak_morning
from server.skills.streak import night as streak_night
from server.skills.weather import Forecast
from server.skills.weather import answer as weather_answer
from server.skills.weather import brief as weather_brief
from server.skills.when import parse_duration, parse_when, spoken_time


@dataclass(frozen=True)
class SkillReply:
    text: str  # lo que se dice y se muestra
    sleep: bool = False  # después de decirlo, el teléfono pasa a dormir
    persist: bool = (
        True  # False: no se guarda en el historial (p. ej. los mensajes que otras personas te mandan)
    )
    # Una pregunta al final que, con un «sí», pulsa un botón: (id del botón, pregunta). Si hay pregunta, el
    # teléfono no se duerme hasta que se conteste (con `sleep`, se duerme después del «no»).
    ask: tuple[str, str] | None = None


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


_BRIEFING = re.compile(
    r"\b(el )?(resumen|repaso|parte) (del dia|de hoy)\b|\bque (tengo|hay) (hoy|para hoy)\b"
    r"|\bcomo va (el|mi) dia\b"
    r"|\bbuenos dias\b"
)
_NIGHT = re.compile(
    r"^(?:vega )?(?:bueno |pues )?buenas noches\b|\bme voy a (?:dormir|acostar)\b|\bhasta manana\b"
)
_DAYS = ("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")
_MONTHS = (
    "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre",
    "noviembre", "diciembre",
)  # fmt: skip
_WEATHER = re.compile(
    r"\b(que|como) tiempo (hace|hara|va a hacer|tendremos)\b"
    r"|\bel tiempo (de |para )?(hoy|manana|ahora|fuera)\b"
    r"|\b(el|la) (clima|prevision)\b|\bva a (llover|nevar)\b|\b(llover|llueve|llovera|paraguas)\b"
    r"|\bhace (frio|calor)\b|\bhara (frio|calor)\b|\bque temperatura (hace|hara)\b"
    r"|\bcuantos grados (hay|hace)\b"
)
_TIMER = re.compile(r"\b(?:temporizador|cron[óo]metro|cuenta\s+atr[áa]s|alarma)\b", re.I)
_TASKS = re.compile(
    r"\b(que|cuales|cuantas|cuantos) (tareas|entregas|deberes|trabajos) (tengo|me quedan|hay|debo|faltan)\b"
    r"|\b(mis|las|los) (tareas|entregas|deberes) (pendientes|de hoy|de esta semana)\b"
    r"|\b(tareas|entregas|deberes) pendientes\b|\bproxima (tarea|entrega)\b|\bque (tengo que|debo) entregar\b"
    r"|\bmoodle\b"
)
_TASK_DONE = re.compile(
    r"^(?:vega )?(?:ya )?(?:entregue|termine|envie|subi|complete|hice)\s+(?:la |el |mi |las |los )?"
    r"(?:tarea |entrega |trabajo |practica |actividad )?(?P<what>.+)$"
    r"|^(?:vega )?marca (?:la |el )?(?:tarea |entrega )?(?P<what2>.+?)"
    r" como (?:hecha|entregada|terminada|lista)$"
)


class SkillRouter:
    def __init__(
        self,
        memory: PersonalMemory,
        now: Callable[[], datetime] = datetime.now,
        phone_battery: Callable[[], int | None] = lambda: None,
        notifications: Callable[[], list[PcNotification]] = list,
        weather: Callable[[], Forecast | None] | None = None,
        weather_ready: bool = False,
    ) -> None:
        self._weather = weather
        self._weather_ready = weather_ready  # ¿está activado y con ciudad? (si no, se explica cómo)
        self._mem = memory
        self._now = now
        self._battery = phone_battery
        self._notifications = notifications
        self._desk: Desk | None = None
        self._moodle: TaskBook | None = None
        self._gym: GymLog | None = None
        self._pause_music: Callable[[], bool] | None = None

    def attach_music(self, pause: Callable[[], bool] | None) -> None:
        """Pausar lo que suena en el PC (para «buenas noches»). Devuelve True si había algo sonando."""
        self._pause_music = pause

    def attach_gym(self, gym: GymLog | None) -> None:
        """La racha del gimnasio (la llama)."""
        self._gym = gym

    def attach_moodle(self, tasks: TaskBook | None) -> None:
        """Las entregas de la universidad (Moodle)."""
        self._moodle = tasks

    def attach_desk(self, desk: Desk | None) -> None:
        """Las órdenes de «Vega manda en tu PC» (fondos, volumen por programa, abrir juegos)."""
        self._desk = desk

    def handle(self, text: str) -> SkillReply | None:
        said = normalize(text)
        for skill in (self._forget, self._what_you_know, self._remember, self._timer, self._reminders,
                      self._notes, self._desk_skill, self._gym_skill, self._tasks, self._pc_notifications,
                      self._status,
                      self._weather_skill, self._night, self._briefing, self._sleep,
                      self._about_me):  # fmt: skip
            reply = skill(text, said)
            if reply is not None:
                return reply
        return None

    # --- El escritorio: fondos, volumen por programa, juegos ---

    def _desk_skill(self, text: str, said: str) -> SkillReply | None:
        reply = self._desk.handle(text, said) if self._desk is not None else None
        return SkillReply(reply) if reply else None

    # --- La racha del gimnasio ---

    def _gym_skill(self, text: str, said: str) -> SkillReply | None:
        reply = streak_handle(self._gym, said) if self._gym is not None else None
        return SkillReply(reply) if reply else None

    # --- Entregas de la universidad ---

    def _tasks(self, text: str, said: str) -> SkillReply | None:
        done = _TASK_DONE.match(said)
        if done and self._moodle is not None:
            return self._task_done(done["what"] or done["what2"])
        if not _TASKS.search(said):
            return None
        if self._moodle is None:
            return SkillReply(
                "Aún no tengo Moodle conectado: copia el enlace de exportación de tu calendario "
                "y guárdalo con python -m server.tools.moodle_setup."
            )
        now = self._now()
        return SkillReply(describe(self._moodle.upcoming(now), now))

    def _task_done(self, about: str) -> SkillReply | None:
        assert self._moodle is not None
        found = self._moodle.find(about, self._now())
        if not found:
            return None  # no es una entrega que conozca: «ya hice la cena» sigue su camino
        if len(found) > 1:
            names = _list([f"«{t.title}»" for t in found[:4]])
            return SkillReply(f"¿Cuál de ellas: {names}? Dime el nombre completo.")
        self._moodle.mark_done(found[0])
        return SkillReply(f"Hecho: «{found[0].title}» entregada, ya no te avisaré de ella.")

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
        if found is None and "alarma" in said:
            # «Pon una alarma a las 7»: es un aviso a una hora, no una cuenta atrás.
            now = self._now()
            when = parse_when(text, now)
            if when is None:
                return SkillReply("¿A qué hora? Dime, por ejemplo, «pon una alarma a las 7».")
            self._mem.add_reminder("reminder", "es la hora de la alarma", when.at)
            return SkillReply(f"Hecho, {spoken_time(when.at, now)} suena la alarma.")
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

    # --- Dormir ---

    def _sleep(self, text: str, said: str) -> SkillReply | None:
        if not re.search(r"\b(duermete|vete a dormir|a dormir|modo (de )?(sueno|descanso))\b", said):
            return None
        return SkillReply("Hasta luego. Toca la pantalla cuando quieras despertarme.", sleep=True)

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
            return SkillReply(f"{who}, {ago}: {n.body or n.title}", persist=False)
        parts = [n.app if n.title == n.app else f"{n.app} de {n.title}" for n in notes[:5]]
        more = f" y {len(notes) - 5} más" if len(notes) > 5 else ""
        return SkillReply(
            f"Tienes {len(notes)} notificaci{'ón' if len(notes) == 1 else 'ones'}: {_list(parts)}{more}.",
            persist=False,  # lo que otros te escriben no se guarda en tu historial
        )

    # --- Resumen del día ---

    def _own_reminders(self) -> list[Reminder]:
        """Tus recordatorios por hora, sin temporizadores ni los avisos de Moodle (se dicen aparte)."""
        skip = self._moodle.alert_ids() if self._moodle is not None else set()
        mine = (r for r in self._mem.pending() if r.kind == "reminder" and r.id not in skip)
        return sorted(mine, key=lambda r: r.due_at)

    def _briefing(self, text: str, said: str) -> SkillReply | None:
        if not _BRIEFING.search(said):
            return None
        now = self._now()
        greet = "Buenos días. " if "buenos dias" in said else ""
        parts = [f"Hoy es {_DAYS[now.weekday()]} {now.day} de {_MONTHS[now.month - 1]}."]
        today = [r for r in self._own_reminders() if r.due_at.date() == now.date()]
        if today:
            parts.append(
                "Hoy tienes: " + _list([f"{r.text} {spoken_time(r.due_at, now)}" for r in today]) + "."
            )
        else:
            parts.append("No tienes recordatorios para hoy.")
        if self._moodle is not None and (due := self._moodle.upcoming(now, timedelta(days=2))):
            parts.append("Entregas próximas: " + describe(due, now, limit=3).split(": ", 1)[1])
        if self._gym is not None and (flame := streak_morning(self._gym.state())):
            parts.append(flame)
        if self._weather_ready and self._weather is not None and (forecast := self._weather()) is not None:
            parts.append(weather_brief(forecast, now.timestamp()))
        notes = self._mem.notes()
        if notes:
            parts.append(f"Tienes {len(notes)} nota{_s(len(notes))} apuntada{_s(len(notes))}.")
        alerts = len(self._notifications())
        if alerts:
            parts.append(f"Y {alerts} aviso{_s(alerts)} sin leer en el PC.")
        return SkillReply(greet + " ".join(parts))

    # --- Buenas noches ---

    def _night(self, text: str, said: str) -> SkillReply | None:
        if not _NIGHT.search(said):
            return None
        now = self._now()
        tomorrow = now.date() + timedelta(days=1)
        parts = ["Buenas noches."]
        if self._pause_music is not None and self._pause_music():
            parts.append("He pausado la música.")
        later = [r for r in self._own_reminders() if r.due_at.date() <= tomorrow]
        if later:
            parts.append(
                "Te queda: " + _list([f"{r.text} {spoken_time(r.due_at, now)}" for r in later[:4]]) + "."
            )
        if self._moodle is not None:
            until = datetime.combine(tomorrow, datetime.max.time()) - now
            due = self._moodle.upcoming(now, until)
            if due:
                names = [f"«{t.title}», {clock(t.due, now)}" for t in due[:3]]
                more = f" y {len(due) - 3} más" if len(due) > 3 else ""
                parts.append(f"Entrega{_s(len(due))} para mañana: " + "; ".join(names) + more + ".")
            elif not later:
                parts.append("Mañana no tienes entregas ni recordatorios.")
        elif not later:
            parts.append("Mañana no tienes recordatorios.")
        if self._gym is not None and (flame := streak_night(self._gym.state())):
            parts.append(flame)
        return SkillReply(" ".join(parts), sleep=True, ask=("apagar", "¿Apago la PC?"))

    # --- El tiempo ---

    def _weather_skill(self, text: str, said: str) -> SkillReply | None:
        if not _WEATHER.search(said):
            return None
        if not self._weather_ready or self._weather is None:
            return SkillReply("Aún no tengo el tiempo: actívalo en Ajustes de la app del PC, con tu ciudad.")
        forecast = self._weather()
        if forecast is None:
            return SkillReply("Todavía no he podido bajar el tiempo. Lo intento cada pocos minutos.")
        return SkillReply(weather_answer(said, forecast, self._now().timestamp()))

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
