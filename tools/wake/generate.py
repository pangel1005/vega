"""Genera frases habladas para entrenar el detector de «Vega» (con y sin el nombre).

    uv run --with scipy python tools/wake/generate.py

Usa las voces del PC: Kokoro (muchas voces; las que no son españolas hablan español con
acento, que da variedad) y Piper (España, México, Argentina). Unas frases empiezan llamando a
Vega («Vega, …», «Oye Vega, …»); otras no, entre ellas palabras que suenan parecido
(«llega», «pega», «venga»…). Ojo: «Bega» suena igual que «Vega» (la b y la v se pronuncian
igual), así que no es un negativo.

Guarda en data/wake/clips.npz el audio a 16 kHz de cada frase, su etiqueta y la voz (para
probar el detector con voces que no ha visto al entrenar).
"""

from __future__ import annotations

import random
import sys
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "data" / "wake" / "clips.npz"
RATE = 16_000

COMMANDS = [
    "¿qué hora es?", "pon un temporizador de cinco minutos", "apunta comprar pan", "lee mis notas",
    "abre Spotify", "pausa la música", "sube el volumen", "baja el volumen", "siguiente canción",
    "¿cómo está el PC?", "recuérdame llamar a mamá en veinte minutos", "¿qué notificaciones tengo?",
    "léeme el último mensaje", "cuéntame un chiste", "¿cuánto es veinticinco por cuatro?", "apaga el PC",
    "¿qué día es hoy?", "graba un clip", "silencia el micro", "¿qué tiempo hace?", "abre Discord",
    "cancela el temporizador", "¿qué sabes de mí?", "recuerda que mi perro se llama Toby", "gracias",
    "¿cuánta batería tienes?", "lanza una moneda", "¿cómo estás?", "pon música", "para",
]  # fmt: skip
PREFIXES = ["", "", "", "oye ", "hola ", "ey ", "eh ", "a ver, "]
NEGATIVE = [
    "Mañana llega mi hermano de viaje.", "La entrega del paquete es el lunes.", "Venga, vámonos ya.",
    "Pega el papel en la pared.", "Esa pelea fue increíble.", "La bodega está cerrada.",
    "Navega por internet un rato.", "Él juega muy bien al fútbol.", "Me gusta la comida vegetal.",
    "Mi amiga Begoña vive en Madrid.", "Enciende la vela del salón.", "¿Me pasas la sal, por favor?",
    "Vamos a jugar una partida esta noche.", "Ya casi termina el partido.", "Hay que comprar leche y huevos.",
    "¿Dónde dejaste las llaves?", "Qué calor hace hoy.", "No sé qué cenar.", "Ponte la chaqueta.",
    "El tren sale a las ocho.", "Llámame cuando llegues.", "Esa película es muy buena.", "Tengo sueño.",
    "¿Has visto mi teléfono?", "Me duele la cabeza.", "Vamos a la playa el sábado.", "Qué bien huele.",
    "La reunión se movió al jueves.", "Necesito cargar el móvil.", "Ese juego está roto.",
    "Mira lo que encontré.", "Hoy no tengo ganas de salir.", "Se me olvidó la contraseña.",
    "Apaga la luz al salir.", "La tele está muy alta.", "Me llega un mensaje.", "Pega fuerte el sol.",
    "Venga, una más y nos vamos.", "Mega rápido el internet.", "La Vela Puerca toca hoy.",
    "Ella navega todos los veranos.", "Mi abuela riega las plantas.", "Juega con nosotros.",
    "Nos vemos mañana.", "Bueno, pues ya está.", "Eso es todo por hoy.", "Pásame el mando.",
]  # fmt: skip
NEG_STARTS = ["llega", "pega", "venga", "vela", "juega", "riega", "entrega", "bodega", "pelea", "navega", "mega"]

KOKORO_TRAIN = ["ef_dora", "em_alex", "af_heart", "af_bella", "af_nicole", "am_adam", "am_michael", "bf_emma",
                "bm_george", "if_sara", "im_nicola", "pf_dora", "ff_siwis"]  # fmt: skip
KOKORO_TEST = ["em_santa", "pm_alex", "hf_alpha"]
PIPER = [("es_MX-claude-high", None), ("es_AR-daniela-high", None), ("es_ES-sharvard-medium", 0),
         ("es_ES-sharvard-medium", 1), ("es_MX-ald-medium", None)]  # fmt: skip
PIPER_TEST = {"es_MX-ald-medium"}


def texts(seed: int, n_pos: int, n_neg: int) -> list[tuple[str, int]]:
    r = random.Random(seed)
    out = []
    for _ in range(n_pos):
        pre = r.choice(PREFIXES)
        if r.random() < 0.18:
            text = f"{pre}Vega."
        else:
            text = f"{pre}Vega{r.choice([', ', ', ', '. ', ' '])}{r.choice(COMMANDS)}"
        out.append((text[0].upper() + text[1:], 1))
    for _ in range(n_neg):
        if r.random() < 0.35:
            text = f"{r.choice(NEG_STARTS)}, {r.choice(COMMANDS)}"
            text = text[0].upper() + text[1:]
        elif r.random() < 0.3:
            text = r.choice(COMMANDS)
            text = text[0].upper() + text[1:]
        else:
            text = r.choice(NEGATIVE)
        out.append((text, 0))
    return out


def resample(audio: np.ndarray, rate: int) -> np.ndarray:
    from scipy.signal import resample_poly

    g = np.gcd(rate, RATE)
    return resample_poly(audio, RATE // g, rate // g).astype(np.float32)


def kokoro_job(voice: str) -> list[tuple[np.ndarray, int, str]]:
    import onnxruntime as ort
    from kokoro_onnx import Kokoro

    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    session = ort.InferenceSession(str(ROOT / "models/kokoro/kokoro-v1.0.onnx"), options, providers=["CPUExecutionProvider"])
    engine = Kokoro.from_session(session, str(ROOT / "models/kokoro/voices-v1.0.bin"))
    r = random.Random(voice)
    out = []
    for text, label in texts(hash(voice) % 10_000, 70, 80):
        samples, rate = engine.create(text, voice=voice, speed=r.uniform(0.85, 1.25), lang=r.choice(["es", "es-419"]))
        out.append((resample(np.asarray(samples, dtype=np.float32), rate), label, f"kokoro:{voice}"))
    return out


def piper_job(spec: tuple[str, int | None]) -> list[tuple[np.ndarray, int, str]]:
    from piper import PiperVoice
    from piper.config import SynthesisConfig

    name, speaker = spec
    voice = PiperVoice.load(str(ROOT / "models/piper" / f"{name}.onnx"))
    r = random.Random(f"{name}{speaker}")
    out = []
    for text, label in texts(hash((name, speaker)) % 10_000, 110, 130):
        config = SynthesisConfig(
            speaker_id=speaker, length_scale=r.uniform(0.8, 1.25), noise_scale=r.uniform(0.5, 0.9), noise_w_scale=r.uniform(0.6, 1.0)
        )
        chunks = list(voice.synthesize(text, syn_config=config))
        audio = np.concatenate([c.audio_float_array for c in chunks]).astype(np.float32)
        out.append((resample(audio, chunks[0].sample_rate), label, f"piper:{name}:{speaker}"))
    return out


def main() -> int:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    clips: list[tuple[np.ndarray, int, str]] = []
    with ProcessPoolExecutor(max_workers=4) as pool:
        futures = [pool.submit(kokoro_job, v) for v in KOKORO_TRAIN + KOKORO_TEST]
        futures += [pool.submit(piper_job, p) for p in PIPER]
        for f in futures:
            got = f.result()
            clips += got
            print(f"{got[0][2]}: {len(got)} frases", flush=True)
    audio = np.empty(len(clips), dtype=object)
    for i, c in enumerate(clips):
        audio[i] = (np.clip(c[0], -1, 1) * 32767).astype(np.int16)
    labels = np.array([c[1] for c in clips], dtype=np.int8)
    voices = np.array([c[2] for c in clips])
    test = np.array([v.split(":")[1] in set(KOKORO_TEST) | PIPER_TEST for v in voices])
    np.savez_compressed(OUT, audio=audio, labels=labels, voices=voices, test=test)
    print(f"Guardado {OUT}: {len(clips)} frases ({labels.sum()} con «Vega»), {test.sum()} de voces de prueba")
    return 0


if __name__ == "__main__":
    sys.exit(main())
