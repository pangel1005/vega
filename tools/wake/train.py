"""Entrena el filtro barato de «Vega» (una red pequeña que decide si una frase EMPIEZA llamando a Vega).

    uv run --with torch --with scipy --index https://download.pytorch.org/whl/cpu python tools/wake/train.py

Para qué sirve: en el teléfono, cada frase que oye el micrófono pasa por Whisper (~2 s de procesador)
solo para ver si empieza por «Vega». Casi todas son ruido o conversación ajena. Este filtro
mira los primeros 2,4 s con unos 11 millones de operaciones (~0,1 s) y deja pasar a Whisper solo las
que pueden llevar el nombre. Se ajusta para NO perder ninguna llamada real (recall alto): un falso
positivo solo cuesta un Whisper de más.

Entrada: data/wake/clips.npz (tools/wake/generate.py). Salida:
- android/app/src/main/assets/wake_gate.bin: los pesos de la red (BatchNorm ya fundido en las
  convoluciones) y el umbral elegido.
- android/app/src/test/resources/wake_gate_fixture.bin: audios de prueba con la salida esperada, para
  que las pruebas de Kotlin comprueben que calcula lo mismo que este script.

El cálculo de rasgos (log-mel) está escrito a mano en `logmel` para poder copiarlo idéntico en Kotlin
(android/.../local/WakeGate.kt): no usar librerías que lo hagan por dentro.
"""

from __future__ import annotations

import struct
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
CLIPS = ROOT / "data" / "wake" / "clips.npz"
OUT_BIN = ROOT / "android" / "app" / "src" / "main" / "assets" / "wake_gate.bin"
OUT_FIXTURE = ROOT / "android" / "app" / "src" / "test" / "resources" / "wake_gate_fixture.bin"

RATE = 16_000
WINDOW_S = 2.4
WINDOW = int(RATE * WINDOW_S)  # 38400 muestras
FRAME = 400  # 25 ms
HOP = 160  # 10 ms
NFFT = 512
MELS = 40
FMIN, FMAX = 60.0, 7600.0
FRAMES = (WINDOW - FRAME) // HOP + 1  # 238
MAGIC = 0x57474154  # «WGAT»


# ---------- rasgos (se copian tal cual en Kotlin) ----------


def _hz_to_mel(f: np.ndarray | float) -> np.ndarray | float:
    return 2595.0 * np.log10(1.0 + np.asarray(f) / 700.0)


def _mel_to_hz(m: np.ndarray | float) -> np.ndarray | float:
    return 700.0 * (10.0 ** (np.asarray(m) / 2595.0) - 1.0)


def mel_filters() -> np.ndarray:
    """Banco de filtros triangulares (MELS, NFFT//2+1), mel de HTK, sin normalizar."""
    pts = _mel_to_hz(np.linspace(_hz_to_mel(FMIN), _hz_to_mel(FMAX), MELS + 2))
    bins = np.arange(NFFT // 2 + 1) * RATE / NFFT
    fb = np.zeros((MELS, NFFT // 2 + 1), dtype=np.float64)
    for i in range(MELS):
        lo, mid, hi = pts[i], pts[i + 1], pts[i + 2]
        up = (bins - lo) / (mid - lo)
        down = (hi - bins) / (hi - mid)
        fb[i] = np.maximum(0.0, np.minimum(up, down))
    return fb.astype(np.float32)


_FILTERS = mel_filters()
_WINDOW = (0.5 - 0.5 * np.cos(2.0 * np.pi * np.arange(FRAME) / FRAME)).astype(np.float32)


def logmel(samples: np.ndarray, warp: float = 1.0) -> np.ndarray:
    """(WINDOW,) float32 entre -1 y 1 → (MELS, FRAMES) con la media restada (no depende del volumen).

    `warp` ≠ 1 estira o encoge el eje de frecuencias (otra longitud de garganta): solo en el
    entrenamiento, para que la red no memorice las voces del generador."""
    x = np.zeros(WINDOW, dtype=np.float32)
    n = min(len(samples), WINDOW)
    x[:n] = samples[:n]
    idx = np.arange(FRAMES)[:, None] * HOP + np.arange(FRAME)[None, :]
    frames = x[idx] * _WINDOW
    spec = np.fft.rfft(frames, n=NFFT, axis=1)
    power = (spec.real**2 + spec.imag**2).astype(np.float32)
    if warp != 1.0:
        src = np.clip(np.arange(NFFT // 2 + 1) / warp, 0, NFFT // 2)
        i0 = np.floor(src).astype(int)
        i1 = np.minimum(i0 + 1, NFFT // 2)
        frac = (src - i0).astype(np.float32)
        power = power[:, i0] * (1 - frac) + power[:, i1] * frac
    mel = power @ _FILTERS.T  # (FRAMES, MELS)
    log = np.log(mel + 1e-6).T.astype(np.float32)
    return log - log.mean()


# ---------- aumento de datos ----------


def _speed(x: np.ndarray, r: np.random.Generator) -> np.ndarray:
    f = r.uniform(0.92, 1.08)
    t = np.arange(0, len(x) - 1, f)
    return np.interp(t, np.arange(len(x)), x).astype(np.float32)


def _colored_noise(n: int, r: np.random.Generator, kind: str) -> np.ndarray:
    white = r.standard_normal(n).astype(np.float32)
    if kind == "white":
        return white
    spec = np.fft.rfft(white)
    f = np.maximum(np.arange(len(spec)), 1).astype(np.float32)
    spec = spec / (np.sqrt(f) if kind == "pink" else f)  # «brown» baja más rápido: ruido de coche
    out = np.fft.irfft(spec, n=n).astype(np.float32)
    return out / (np.std(out) + 1e-9)


def _reverb(x: np.ndarray, r: np.random.Generator) -> np.ndarray:
    from scipy.signal import fftconvolve

    rt60 = r.uniform(0.1, 0.6)
    n = int(RATE * rt60)
    t = np.arange(n) / RATE
    ir = r.standard_normal(n) * np.exp(-6.9 * t / rt60)
    ir[0] = 1.0
    y = fftconvolve(x, ir.astype(np.float32))[: len(x)]
    return (y / (np.max(np.abs(y)) + 1e-9) * (np.max(np.abs(x)) + 1e-9)).astype(np.float32)


def _lowpass(x: np.ndarray, r: np.random.Generator) -> np.ndarray:
    from scipy.signal import butter, lfilter

    b, a = butter(2, r.uniform(3200, 7000) / (RATE / 2))
    return lfilter(b, a, x).astype(np.float32)


def _mix(x: np.ndarray, noise: np.ndarray, snr_db: float) -> np.ndarray:
    px = np.mean(x**2) + 1e-9
    pn = np.mean(noise**2) + 1e-9
    return x + noise * np.sqrt(px / (pn * 10 ** (snr_db / 10)))


class Augmenter:
    def __init__(self, clips: list[np.ndarray], seed: int) -> None:
        self.clips = clips  # para el «murmullo»: voces de fondo
        self.r = np.random.default_rng(seed)

    def babble(self, n: int, voices: int) -> np.ndarray:
        out = np.zeros(n, dtype=np.float32)
        for _ in range(voices):
            c = self.clips[self.r.integers(len(self.clips))]
            start = self.r.integers(0, max(1, len(c) // 2))
            seg = c[start : start + n]
            off = self.r.integers(0, max(1, n - len(seg) + 1)) if len(seg) < n else 0
            out[off : off + len(seg)] += seg[: n - off] * self.r.uniform(0.4, 1.0)
        return out

    def __call__(self, clip: np.ndarray, *, noisy: bool = True) -> np.ndarray:
        r = self.r
        x = clip.astype(np.float32)
        if noisy and r.random() < 0.6:
            x = _speed(x, r)
        # El segmentador antepone ~0,3 s de audio previo: el inicio cae entre 0,15 y 0,45 s.
        x = np.concatenate([np.zeros(int(RATE * r.uniform(0.15, 0.45)), dtype=np.float32), x])
        x = x * 10 ** (r.uniform(-15, 5) / 20)
        if noisy and r.random() < 0.4:
            x = _reverb(x, r)
        if noisy and r.random() < 0.35:
            x = _lowpass(x, r)
        n = max(len(x), WINDOW)
        if noisy:
            roll = r.random()
            if roll < 0.25:
                x = _mix(np.pad(x, (0, n - len(x))), _colored_noise(n, r, "white"), r.uniform(8, 30))
            elif roll < 0.45:
                x = _mix(np.pad(x, (0, n - len(x))), _colored_noise(n, r, "pink"), r.uniform(5, 30))
            elif roll < 0.6:
                x = _mix(np.pad(x, (0, n - len(x))), _colored_noise(n, r, "brown"), r.uniform(0, 25))
            elif roll < 0.85:
                x = _mix(np.pad(x, (0, n - len(x))), self.babble(n, int(r.integers(2, 5))), r.uniform(5, 25))
        return np.clip(x[:WINDOW], -1, 1)


def noise_only(r: np.random.Generator, aug: Augmenter) -> np.ndarray:
    kind = r.choice(["white", "pink", "brown", "babble", "quiet"])
    if kind == "babble":
        x = aug.babble(WINDOW, int(r.integers(2, 5))) * 0.3
    elif kind == "quiet":
        x = _colored_noise(WINDOW, r, "pink") * 0.002
    else:
        x = _colored_noise(WINDOW, r, kind) * 10 ** (r.uniform(-45, -15) / 20)
    return np.clip(x, -1, 1).astype(np.float32)


# ---------- red ----------

CH = (12, 24, 32)  # convoluciones 2D (frecuencia × tiempo)
TC = 48  # canales de las convoluciones en el tiempo
FREQ_AFTER = MELS // 8  # 5 filas de frecuencia tras los tres recortes


def build_model():  # type: ignore[no-untyped-def]
    import torch
    from torch import nn

    class Gate(nn.Module):
        """Tres convoluciones 2D que resumen la frecuencia y dos 1D (con salto) que dan contexto en el
        tiempo: una palabra como «Vega» dura ~0,4 s y las 2D solas veían ventanas de ~0,2 s."""

        def __init__(self) -> None:
            super().__init__()
            self.c1 = nn.Sequential(
                nn.Conv2d(1, CH[0], 3, padding=1), nn.BatchNorm2d(CH[0]), nn.ReLU(), nn.MaxPool2d(2)
            )
            self.c2 = nn.Sequential(
                nn.Conv2d(CH[0], CH[1], 3, padding=1), nn.BatchNorm2d(CH[1]), nn.ReLU(), nn.MaxPool2d(2)
            )
            self.c3 = nn.Sequential(
                nn.Conv2d(CH[1], CH[2], 3, padding=1), nn.BatchNorm2d(CH[2]), nn.ReLU(), nn.MaxPool2d((2, 1))
            )
            self.t1 = nn.Sequential(
                nn.Conv1d(CH[2] * FREQ_AFTER, TC, 5, padding=2), nn.BatchNorm1d(TC), nn.ReLU()
            )
            self.t2 = nn.Sequential(
                nn.Conv1d(TC, TC, 5, padding=4, dilation=2), nn.BatchNorm1d(TC), nn.ReLU()
            )
            self.drop = nn.Dropout(0.25)
            self.fc = nn.Linear(TC, 1)

        def forward(self, x: torch.Tensor) -> torch.Tensor:
            h = self.c3(self.c2(self.c1(x)))  # (B, 32, 5, 59)
            h = h.flatten(1, 2)  # (B, 160, 59): canal = 32 × 5 filas de frecuencia
            h = self.t2(self.t1(h))
            return self.fc(self.drop(h.amax(dim=2))).squeeze(1)

    return Gate()


def _fold_bn(layer, bn):  # type: ignore[no-untyped-def]
    import torch

    scale = bn.weight / torch.sqrt(bn.running_var + bn.eps)
    shape = (-1,) + (1,) * (layer.weight.dim() - 1)
    w = layer.weight * scale.reshape(shape)
    b = (layer.bias - bn.running_mean) * scale + bn.bias
    return w.detach().numpy().astype(np.float32), b.detach().numpy().astype(np.float32)


def fold(model) -> list[np.ndarray]:  # type: ignore[no-untyped-def]
    """Pesos con la BatchNorm fundida en cada convolución: [w1, b1, w2, b2, w3, b3, t1w, t1b, t2w, t2b, fcw, fcb]."""
    out: list[np.ndarray] = []
    for block in (model.c1, model.c2, model.c3, model.t1, model.t2):
        out += list(_fold_bn(block[0], block[1]))
    out += [
        model.fc.weight.detach().numpy().reshape(-1).astype(np.float32),
        model.fc.bias.detach().numpy().astype(np.float32),
    ]
    return out


def forward_numpy(params: list[np.ndarray], feats: np.ndarray) -> float:
    """Lo mismo que hará Kotlin, con bucles claros (para comprobar el volcado)."""
    x = feats[None, :, :]  # (1, MELS, FRAMES)
    for layer, pool in zip(range(3), ((2, 2), (2, 2), (2, 1)), strict=True):
        w, b = params[2 * layer], params[2 * layer + 1]
        cout = w.shape[0]
        h, wd = x.shape[1], x.shape[2]
        pad = np.pad(x, ((0, 0), (1, 1), (1, 1)))
        y = np.zeros((cout, h, wd), dtype=np.float32)
        for dy in range(3):
            for dx in range(3):
                y += np.tensordot(w[:, :, dy, dx], pad[:, dy : dy + h, dx : dx + wd], axes=([1], [0]))
        y = np.maximum(y + b[:, None, None], 0)
        ph, pw = h // pool[0], wd // pool[1]
        y = y[:, : ph * pool[0], : pw * pool[1]].reshape(cout, ph, pool[0], pw, pool[1]).max(axis=(2, 4))
        x = y
    x = x.reshape(-1, x.shape[2])  # (160, 59)
    for layer, (pad_n, dil) in zip((3, 4), ((2, 1), (4, 2)), strict=True):
        w, b = params[2 * layer], params[2 * layer + 1]  # (cout, cin, 5)
        t = x.shape[1]
        padded = np.pad(x, ((0, 0), (pad_n, pad_n)))
        y = np.zeros((w.shape[0], t), dtype=np.float32)
        for k in range(5):
            y += w[:, :, k] @ padded[:, k * dil : k * dil + t]
        x = np.maximum(y + b[:, None], 0)
    pooled = x.max(axis=1)
    return float(pooled @ params[10] + params[11][0])


class TrainSet:
    """Cada época vuelve a aumentar las mismas frases de forma distinta (más un relleno de solo ruido)."""

    def __init__(
        self, audio: list[np.ndarray], labels: np.ndarray, train_idx: np.ndarray, clips: list[np.ndarray]
    ) -> None:
        self.audio, self.labels, self.train_idx, self.clips = audio, labels, train_idx, clips

    def __len__(self) -> int:
        return len(self.train_idx) + len(self.train_idx) // 8

    def __getitem__(self, i: int):  # type: ignore[no-untyped-def]
        import torch

        aug = Augmenter(self.clips, seed=int(np.random.default_rng().integers(1 << 31)))
        r = aug.r
        warp = float(r.uniform(0.86, 1.14)) if r.random() < 0.75 else 1.0
        if i >= len(self.train_idx):  # relleno: solo ruido o murmullo (nunca es una llamada)
            feats, label = logmel(noise_only(r, aug), warp), 0.0
        else:
            k = self.train_idx[i]
            feats, label = logmel(aug(self.audio[k]), warp), float(self.labels[k])
        # Enmascarar a trozos (como SpecAugment): bandas de frecuencia y tramos cortos de tiempo.
        if r.random() < 0.5:
            w = int(r.integers(1, 6))
            f0 = int(r.integers(0, MELS - w))
            feats[f0 : f0 + w] = 0.0
        for _ in range(int(r.integers(0, 3))):
            w = int(r.integers(2, 13))
            t0 = int(r.integers(0, FRAMES - w))
            feats[:, t0 : t0 + w] = 0.0
        return torch.from_numpy(feats[None]), torch.tensor(label)


# ---------- entrenamiento ----------


def main() -> int:
    import torch
    from torch.utils.data import DataLoader

    torch.manual_seed(0)
    np.random.seed(0)
    data = np.load(CLIPS, allow_pickle=True)
    audio = [a.astype(np.float32) / 32768.0 for a in data["audio"]]
    labels = data["labels"].astype(np.float32)
    test = data["test"]
    train_idx = np.flatnonzero(~test)
    test_idx = np.flatnonzero(test)
    print(f"{len(train_idx)} frases de entrenamiento, {len(test_idx)} de prueba (voces que no ha visto)")
    train_clips = [audio[i] for i in train_idx if labels[i] == 0]

    dataset = TrainSet(audio, labels, train_idx, train_clips)

    model = build_model()
    opt = torch.optim.AdamW(model.parameters(), lr=3e-3, weight_decay=1e-2)
    epochs = 40
    sched = torch.optim.lr_scheduler.OneCycleLR(
        opt, max_lr=6e-3, total_steps=epochs * (len(dataset) // 64 + 1)
    )
    loader = DataLoader(dataset, batch_size=64, shuffle=True, num_workers=6, persistent_workers=True)
    loss_fn = torch.nn.BCEWithLogitsLoss()
    for epoch in range(epochs):
        model.train()
        total = 0.0
        for x, y in loader:
            opt.zero_grad()
            loss = loss_fn(model(x), y)
            loss.backward()
            opt.step()
            sched.step()
            total += float(loss) * len(y)
        print(f"época {epoch + 1}/{epochs}: pérdida {total / len(loader.dataset):.4f}", flush=True)

    model.eval()
    params = fold(model)

    # --- evaluación con voces nuevas, limpias y con ruido ---
    def scores(noisy: bool, seed: int) -> tuple[np.ndarray, np.ndarray]:
        aug = Augmenter(train_clips, seed=seed)
        feats = np.stack([logmel(aug(audio[k], noisy=noisy))[None] for k in test_idx])
        with torch.no_grad():
            return torch.sigmoid(model(torch.from_numpy(feats))).numpy(), labels[test_idx]

    results = {}
    for name, noisy in (("limpio", False), ("con ruido", True)):
        s, y = scores(noisy, 7)
        results[name] = (s, y)
    # El umbral se elige con las dos condiciones juntas: 99 % de las llamadas tienen que pasar.
    pos = np.concatenate([r[0][r[1] == 1] for r in results.values()])
    neg = np.concatenate([r[0][r[1] == 0] for r in results.values()])
    threshold = float(np.quantile(pos, 0.01))
    threshold = min(threshold, 0.5)
    print(f"\numbral {threshold:.3f}")
    for name, (s, y) in results.items():
        recall = float((s[y == 1] >= threshold).mean())
        passed = float((s[y == 0] >= threshold).mean())
        print(
            f"{name}: llamadas que pasan {recall * 100:.1f} %; no-llamadas que pasan a Whisper {passed * 100:.1f} %"
        )
    voices = data["voices"][test_idx]
    s_noisy, y_noisy = results["con ruido"]
    for v in sorted(set(voices)):
        m = voices == v
        rec = float((s_noisy[m & (y_noisy == 1)] >= threshold).mean())
        pas = float((s_noisy[m & (y_noisy == 0)] >= threshold).mean())
        print(f"  {v}: llamadas que pasan {rec * 100:.0f} %, resto que pasa {pas * 100:.0f} %")
    print(
        f"(pasan {float((neg >= threshold).mean()) * 100:.1f} % del resto: se ahorra {100 - float((neg >= threshold).mean()) * 100:.0f} % de Whisper)"
    )

    # --- volcado ---
    OUT_BIN.parent.mkdir(parents=True, exist_ok=True)
    with OUT_BIN.open("wb") as f:
        f.write(struct.pack("<I", MAGIC))
        f.write(struct.pack("<f", threshold))
        for p in params:
            f.write(p.astype("<f4").tobytes())
    print(f"Guardado {OUT_BIN} ({OUT_BIN.stat().st_size / 1024:.0f} KB)")

    # Pruebas para Kotlin: 4 frases (2 con «Vega», 2 sin) y lo que debe dar la red.
    OUT_FIXTURE.parent.mkdir(parents=True, exist_ok=True)
    pick = [int(test_idx[np.flatnonzero(labels[test_idx] == 1)[0]]), int(test_idx[np.flatnonzero(labels[test_idx] == 1)[5]]),
            int(test_idx[np.flatnonzero(labels[test_idx] == 0)[0]]), int(test_idx[np.flatnonzero(labels[test_idx] == 0)[7]])]  # fmt: skip
    with OUT_FIXTURE.open("wb") as f:
        f.write(struct.pack("<I", len(pick)))
        for k in pick:
            clip = np.zeros(WINDOW, dtype=np.float32)
            n = min(len(audio[k]), WINDOW - 4800)
            clip[4800 : 4800 + n] = audio[k][:n]  # 0,3 s de silencio delante, como el segmentador
            pcm = np.clip(clip * 32768.0, -32768, 32767).astype(np.int16)
            feats = logmel(pcm.astype(np.float32) / 32768.0)
            logit = forward_numpy(params, feats)
            with torch.no_grad():
                ref = float(model(torch.from_numpy(feats[None, None]))[0])
            print(f"  fixture clip {k}: etiqueta {int(labels[k])}, logit numpy {logit:.4f}, torch {ref:.4f}")
            assert abs(logit - ref) < 1e-2, "el volcado no coincide con la red"
            f.write(struct.pack("<I", len(pcm)))
            f.write(pcm.astype("<i2").tobytes())
            f.write(struct.pack("<f", logit))
    print(f"Guardado {OUT_FIXTURE}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
