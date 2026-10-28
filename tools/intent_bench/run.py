"""Mide cuántas órdenes entiende Vega y dónde falla: `uv run python tools/intent_bench/run.py [--matcher actual|semantico]`.

Usa el mismo enrutado que el servidor: primero las habilidades (por reglas), luego los botones por voz y, si
nada encaja, la charla con el modelo. Cada frase del banco tiene la intención esperada; aquí se compara.

Solo mide el ENRUTADO (qué habilidad o botón se activa), no el oído (Whisper) ni la calidad de lo que contesta
el modelo de charla. Las habilidades del teléfono (Kotlin) son aparte; esto mide las del PC.
"""

from __future__ import annotations

import argparse
import sys
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from server.memory.personal import PersonalMemory  # noqa: E402
from server.memory.store import ConversationStore  # noqa: E402
from server.skills.router import SkillRouter  # noqa: E402
from server.tools.actions import ActionRunner, load_actions  # noqa: E402

CASES = Path(__file__).with_name("cases.tsv")

# Nombre corto de cada habilidad, en el orden en que las prueba el servidor.
SKILLS = [
    ("forget", "_forget"), ("know", "_what_you_know"), ("remember", "_remember"), ("timer", "_timer"),
    ("reminders", "_reminders"), ("notes", "_notes"), ("notifs", "_pc_notifications"), ("status", "_status"),
    ("weather", "_weather_skill"), ("briefing", "_briefing"), ("sleep", "_sleep"), ("aboutme", "_about_me"),
]  # fmt: skip


class _NoExecutor:
    def execute(self, *args, **kwargs):  # type: ignore[no-untyped-def]
        raise RuntimeError("el banco no ejecuta nada")


def load_cases() -> list[tuple[str, str, str]]:
    out = []
    for line in CASES.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        kind, intent, phrase = line.split("\t")
        out.append((kind, intent, phrase))
    return out


def build() -> tuple[SkillRouter, ActionRunner]:
    memory = PersonalMemory(ConversationStore(":memory:"))
    for fact in ("mi perro se llama Toby", "a mi mamá le gusta el té"):
        memory.remember(fact)
    router = SkillRouter(memory, now=lambda: datetime(2026, 10, 28, 12, 0), weather_ready=False)
    return router, ActionRunner(load_actions(), _NoExecutor())  # type: ignore[arg-type]


def route_current(router: SkillRouter, actions: ActionRunner, phrase: str) -> str:
    """Lo que hace hoy el servidor con una frase hablada."""
    said = phrase
    from server.core.text import normalize  # noqa: PLC0415

    norm = normalize(said)
    for name, method in SKILLS:
        if getattr(router, method)(said, norm) is not None:
            return f"skill:{name}"
    action = actions.match(said)
    return f"action:{action.id}" if action else "chat"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--matcher", default="actual", choices=["actual", "semantico"])
    parser.add_argument("-v", "--verbose", action="store_true", help="enseña cada fallo")
    args = parser.parse_args()

    router, actions = build()
    fresh = build  # cada frase parte de una memoria limpia: «olvida…» y «recuerda…» no deben afectar a las demás
    if args.matcher == "semantico":
        from tools.intent_bench.semantic import SemanticRouter  # noqa: PLC0415

        semantic = SemanticRouter(actions.actions)

        def route(phrase: str) -> str:
            first = route_current(fresh()[0], actions, phrase)
            return first if first != "chat" else semantic.route(phrase)
    else:

        def route(phrase: str) -> str:
            return route_current(fresh()[0], actions, phrase)

    cases = load_cases()
    # Las impares sirven para ajustar umbrales; las pares, para medir de verdad (no se tocan al ajustar).
    splits = {"ajuste": cases[1::2], "prueba": cases[0::2]}
    for split, subset in splits.items():
        print(f"== {split} ({len(subset)} frases) ==")
        report(args, subset, route, semantic if args.matcher == "semantico" else None, router, actions)
    return 0


def report(args, cases, route, semantic, router, actions) -> None:  # type: ignore[no-untyped-def]
    by_kind: dict[str, list[bool]] = defaultdict(list)
    fails: list[tuple[str, str, str, str]] = []
    confusion: Counter[tuple[str, str]] = Counter()
    asked_ok = asked_bad = harmful = 0
    for kind, expected, phrase in cases:
        got = route(phrase)
        ok = got == expected
        by_kind[kind].append(ok)
        if semantic is not None and got == "chat" and expected != "chat":
            label, mode, _ = semantic.decide(phrase)
            if mode == "ask":
                asked_ok += label == expected
                asked_bad += label != expected
        if not ok:
            fails.append((kind, expected, got, phrase))
            confusion[(expected.split(":")[0], got.split(":")[0])] += 1
            if expected == "chat" or (got != "chat" and got != expected):
                harmful += 1  # activó algo que no tocaba
    total = sum(len(v) for v in by_kind.values())
    good = sum(sum(v) for v in by_kind.values())
    print(f"Enrutado «{args.matcher}»: {good}/{total} bien ({100 * good / total:.0f} %); activó algo que no tocaba: {harmful}")
    if semantic is not None:
        print(f"  preguntaría «¿quieres…?» en {asked_ok + asked_bad} frases (de ellas, con la intención correcta: {asked_ok})")
    for kind, results in by_kind.items():
        print(f"  {kind:10s} {sum(results):3d}/{len(results):3d}  ({100 * sum(results) / len(results):.0f} %)")
    if args.verbose:
        for kind, expected, got, phrase in fails:
            print(f"  [{kind}] «{phrase}» → {got}   (esperado {expected})")


if __name__ == "__main__":
    raise SystemExit(main())
