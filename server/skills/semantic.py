"""Entender por significado: lo que dices se compara con ejemplos de cada botón y habilidad.

Las reglas (frases exactas, expresiones regulares) entienden «apaga el pc» pero no «apágame la compu». Aquí
un modelo pequeño de embeddings (paraphrase-multilingual-MiniLM, unos 120 MB, corre en CPU y sin red)
convierte cada frase en un vector; frases de igual significado quedan cerca aunque usen otras palabras.

Es un complemento, no un sustituto: solo actúa cuando las reglas no han entendido nada, y con cuidado:
- Si se parece mucho a una orden, la ejecuta. Si se parece algo, **pregunta antes** («¿Quieres …? Di sí o
  no»). Si no, la frase sigue como charla.
- Un botón nunca se activa con una frase que niega («no quiero apagar») o que pregunta por información
  («qué es Spotify»).
- Sube/baja el volumen se decide por las palabras (el modelo confunde los opuestos).
- Las habilidades solo actúan con mucha seguridad y nunca ante una negación.
- Solo resuelve botones y habilidades que no necesitan datos («cómo está el pc»). Las que sí («avísame en
  diez minutos») siguen en manos de sus reglas, que saben sacar el tiempo.

Medido con `tools/intent_bench` (148 frases, la mitad sin tocar al ajustar): de 58 % a 76 % de aciertos, sin
activar nada de más en las frases trampa.
"""

from __future__ import annotations

import logging
import re
import threading
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Literal, Protocol

import numpy as np

from server.core.text import normalize

log = logging.getLogger(__name__)

MODEL = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"

# Habilidades que se pueden resolver sin datos, y la frase con la que las entiende su regla.
CANONICAL: dict[str, str] = {
    "status": "cómo está el pc",
    "weather": "qué tiempo hace",
    "briefing": "qué tengo para hoy",
    "notifs": "qué notificaciones tengo",
    "sleep": "duérmete",
    "know": "qué sabes de mí",
}

# Ejemplos de cada habilidad. Las que llevan datos (temporizador, recordatorios, notas, recordar, olvidar)
# están aquí solo para competir: si una frase se parece más a «temporizador» que a un botón, no debe
# activar el botón.
SKILL_EXAMPLES: dict[str, list[str]] = {
    "timer": [
        "pon un temporizador de cinco minutos", "avísame cuando pasen diez minutos",
        "cuánto le queda al temporizador", "pon una alarma a las siete",
    ],
    "reminders": [
        "recuérdame llamar a alguien a las cinco", "qué recordatorios tengo", "qué tengo pendiente",
    ],
    "notes": ["apunta una nota", "qué notas tengo", "borra mis notas", "anota esto"],
    "remember": ["recuerda que tengo un perro", "quiero que sepas algo sobre mí"],
    "forget": ["olvida lo que te dije sobre eso"],
    "know": ["qué sabes de mí", "qué recuerdas sobre mí", "qué datos míos tienes guardados"],
    "notifs": [
        "qué notificaciones tengo", "tengo mensajes nuevos", "me ha llegado algo",
        "alguien me ha mandado un mensaje",
    ],
    "status": [
        "cómo está el ordenador", "cuánta memoria usa el pc", "qué temperatura tiene la gráfica",
        "cuánta batería le queda al teléfono", "qué tal va la computadora",
        "cuánto está gastando el procesador",
        "el equipo se calienta mucho", "cómo va el rendimiento",
    ],
    "weather": ["qué tiempo hace hoy", "va a llover", "hace frío o calor fuera"],
    "briefing": ["dame el resumen del día", "qué tengo para hoy", "ponme al día", "cómo está mi agenda"],
    "sleep": ["duérmete", "vete a dormir", "descansa ya", "puedes apagarte"],
}  # fmt: skip

# Charla corriente: compite con las órdenes para que «gracias» o «cuéntame algo» no activen un botón.
CHAT_EXAMPLES = [
    "hola", "gracias", "muchas gracias", "vale", "perfecto", "buenas noches",
    "cuéntame algo interesante", "explícame cómo funciona una cosa", "qué opinas de esto", "por qué pasa eso",
    "tengo hambre", "estoy cansado", "me aburro", "qué es una palabra", "quién fue un personaje histórico",
    "recomiéndame algo", "cómo se dice una palabra en inglés", "dime una curiosidad",
]  # fmt: skip

_NEGATION = re.compile(r"\b(no|nunca|tampoco|ni)\b")
_INFO_QUESTION = re.compile(
    r"^\s*(que (es|son|significa|opinas|piensas)|quien|cual es|por que|explicame|cuentame|dime que)\b"
)

# Subir o bajar el volumen: el modelo los confunde (son opuestos que se parecen), así que decide la palabra.
_UP = re.compile(r"\b(sube|subele|subir|alto|alta|fuerte|arriba|aumenta|mas volumen|mas fuerte)\b")
_DOWN = re.compile(r"\b(baja|bajale|bajar|bajo|bajito|bajita|suave|menos volumen|reduce|disminuye)\b")
VOLUME_UP, VOLUME_DOWN = "volumen-mas", "volumen-menos"


def blocks_actions(said: str) -> bool:
    """¿La frase (ya normalizada) niega o pregunta por información? Entonces no es una orden para un botón."""
    return bool(_NEGATION.search(said) or _INFO_QUESTION.search(said))


def volume_vote(said: str, known: set[str]) -> str | None:
    """El botón de volumen que piden las palabras (sube / baja), o None si no dicen una sola dirección."""
    up, down = bool(_UP.search(said)), bool(_DOWN.search(said))
    if up and not down and VOLUME_UP in known:
        return VOLUME_UP
    if down and not up and VOLUME_DOWN in known:
        return VOLUME_DOWN
    return None


def fix_volume_direction(said: str, action_id: str, known: set[str]) -> str:
    """Si el modelo eligió un botón de volumen, la dirección la dicen las palabras: sube o baja."""
    if action_id not in (VOLUME_UP, VOLUME_DOWN):
        return action_id
    return volume_vote(said, known) or action_id


class Embedder(Protocol):
    def embed(self, texts: Sequence[str]) -> np.ndarray: ...


class FastEmbedder:
    """El modelo de verdad (fastembed, ONNX). Se baja una vez (~120 MB) y después trabaja sin red."""

    def __init__(self, cache_dir: Path | None = None) -> None:
        from fastembed import TextEmbedding

        self._model = TextEmbedding(MODEL, cache_dir=str(cache_dir) if cache_dir else None)

    def embed(self, texts: Sequence[str]) -> np.ndarray:
        return np.array(list(self._model.embed(list(texts))), dtype=np.float32)


@dataclass(frozen=True)
class Match:
    kind: Literal["action", "skill"]
    id: str  # el id del botón o el nombre de la habilidad
    mode: Literal["act", "ask"]
    score: float
    canonical: str = ""  # para una habilidad: la frase que su regla entiende


class SemanticIntents:
    def __init__(
        self,
        actions: Callable[[], list[Any]],
        make_embedder: Callable[[], Embedder],
        act: float = 0.62,
        ask: float = 0.50,
        margin: float = 0.04,
    ) -> None:
        self._actions = actions
        self._make_embedder = make_embedder
        self.act, self.ask, self.margin = act, ask, margin
        self._embedder: Embedder | None = None
        self._failed = False
        self._lock = threading.Lock()
        self._built_for: tuple[tuple[str, ...], ...] | None = None
        self._labels: list[str] = []
        self._matrix = np.zeros((0, 1), dtype=np.float32)

    def warm(self) -> None:
        """Carga el modelo y arma el índice (se llama en segundo plano al arrancar)."""
        self._ensure()

    def _normed(self, texts: list[str]) -> np.ndarray:
        assert self._embedder is not None
        vectors = self._embedder.embed(texts)
        norms = np.maximum(np.linalg.norm(vectors, axis=1, keepdims=True), 1e-9)
        return np.asarray(vectors / norms, dtype=np.float32)

    def _ensure(self) -> bool:
        with self._lock:
            if self._failed:
                return False
            try:
                if self._embedder is None:
                    self._embedder = self._make_embedder()
                actions = self._actions()
                key = tuple((a.id, a.label, *a.say) for a in actions)
                if key != self._built_for:  # los botones cambiaron (o es la primera vez)
                    labels: list[str] = []
                    texts: list[str] = []
                    for action in actions:
                        for phrase in {*action.say, action.label}:
                            labels.append(f"action:{action.id}")
                            texts.append(phrase)
                    for name, examples in SKILL_EXAMPLES.items():
                        labels += [f"skill:{name}"] * len(examples)
                        texts += examples
                    labels += ["chat"] * len(CHAT_EXAMPLES)
                    texts += CHAT_EXAMPLES
                    self._matrix, self._labels, self._built_for = self._normed(texts), labels, key
                return True
            except Exception as exc:  # sin modelo (no se pudo bajar, falta la librería…): se sigue sin él
                log.warning("Sin entendimiento por significado: %s", type(exc).__name__)
                self._failed = True
                return False

    def match(self, text: str) -> Match | None:
        """La orden que más se parece a lo dicho, o None (charla, o ya la entienden las reglas)."""
        if not text.strip() or not self._ensure():
            return None
        said = normalize(text)
        sims = self._matrix @ self._normed([text])[0]
        best: dict[str, float] = {}
        for label, sim in zip(self._labels, sims, strict=True):
            best[label] = max(best.get(label, -1.0), float(sim))
        ranked = sorted(best.items(), key=lambda kv: kv[1], reverse=True)
        if blocks_actions(said):
            ranked = [(label, sim) for label, sim in ranked if not label.startswith("action:")]
        if len(ranked) < 2:
            return None
        known = {a.id for a in self._actions()}
        vote = volume_vote(said, known)
        if vote is not None and not blocks_actions(said):
            # «súbele a la música» se parece a «pausa la música»: si las palabras piden subir o bajar el
            # volumen y el modelo ve ese botón razonablemente cerca, manda la palabra.
            own = best.get(f"action:{vote}", -1.0)
            if own >= 0.35 and own >= ranked[0][1] - 0.35:
                return Match("action", vote, "act" if own >= self.act else "ask", own)
        (top, s1), (_, s2) = ranked[0], ranked[1]
        if top == "chat" or s1 < self.ask or s1 - s2 < self.margin:
            return None
        mode: Literal["act", "ask"] = "act" if s1 >= self.act else "ask"
        kind, _, name = top.partition(":")
        if kind == "action":
            return Match("action", fix_volume_direction(said, name, known), mode, s1)
        if name in CANONICAL:
            # Las habilidades no preguntan ni aguantan una negación («no quiero apagar» no es «duérmete»):
            # o se entienden con mucha seguridad, o siguen como charla.
            if mode != "act" or _NEGATION.search(said):
                return None
            return Match("skill", name, "act", s1, CANONICAL[name])
        return None  # una habilidad con datos: la entienden sus reglas, no el significado
