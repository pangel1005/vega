"""Captura del audio de UN programa (Spotify) para dibujar su espectro, como una radio de coche.

Usa la «captura de bucle por proceso» de Windows 10 (2004) y 11: pide a Windows el audio que produce un
programa (y sus procesos hijos), sin mezclar el del resto del PC. Código propio sobre `comtypes`/`pycaw`,
sin binarios de terceros.

Privacidad: el audio se analiza en memoria y se descarta al instante; no se guarda, no se manda por la red y
no se oye por ningún sitio. Lo único que sale de aquí son unos números (cuánto suena cada banda).
Solo se captura mientras alguien lo pide (`poll`), y se apaga con el ajuste `[music] spectrum = false`.
"""

from __future__ import annotations

import ctypes
import logging
import threading
import time
from collections.abc import Callable
from ctypes import (
    POINTER,
    Structure,
    byref,
    c_long,
    c_ubyte,
    c_uint16,
    c_uint32,
    c_uint64,
    c_void_p,
    c_wchar_p,
)
from typing import Any

import numpy as np

log = logging.getLogger(__name__)

SAMPLE_RATE = 48_000
CHANNELS = 2
BYTES_PER_FRAME = CHANNELS * 2  # PCM de 16 bits, estéreo
BANDS = 24
FFT_SIZE = 4096

TICK_S = 0.04  # un análisis cada 40 ms
IDLE_S = 0.5
ACTIVE_FOR_S = 1.5  # sigue capturando este rato después de cada `poll`
RETRY_S = 3.0
PROCESS_REFRESH_S = 2.0


# ---------------------------------------------------------------------------
# El análisis (puro: se prueba sin Windows)
# ---------------------------------------------------------------------------


def band_edges(bands: int = BANDS, fmin: float = 45.0, fmax: float = 16_000.0) -> np.ndarray:
    """Bordes de frecuencia (Hz) de `bands` bandas repartidas en escala logarítmica, como en un analizador."""
    return np.geomspace(fmin, fmax, bands + 1)


class SpectrumAnalyzer:
    """Convierte audio PCM de 16 bits estéreo en `bands` números de 0 a 1 (cuánto suena cada banda)."""

    DB_FLOOR = -72.0  # por debajo de esto, la banda marca 0
    DB_RANGE = 54.0  # y 54 dB más arriba marca 1

    def __init__(self, rate: int = SAMPLE_RATE, bands: int = BANDS, size: int = FFT_SIZE) -> None:
        self._size = size
        self._window = np.hanning(size).astype(np.float32)
        self._buffer = np.zeros(size, dtype=np.float32)
        freqs = np.fft.rfftfreq(size, 1.0 / rate)
        edges = band_edges(bands)
        # Para cada banda, los índices de las frecuencias que caen dentro (al menos una).
        self._slices: list[tuple[int, int]] = []
        for lo, hi in zip(edges[:-1], edges[1:], strict=True):
            first = int(np.searchsorted(freqs, lo))
            last = max(first + 1, int(np.searchsorted(freqs, hi)))
            self._slices.append((first, last))
        centers = np.sqrt(edges[:-1] * edges[1:])
        # Los graves suenan más fuerte que los agudos: +3 dB por octava desde 200 Hz los iguala a la vista.
        self._tilt = (3.0 * np.log2(np.maximum(centers, 200.0) / 200.0)).astype(np.float32)

    def feed(self, pcm: bytes) -> None:
        """Mete audio nuevo (int16 estéreo entrelazado): se queda con los últimos `size` valores, en mono."""
        samples = np.frombuffer(pcm[: len(pcm) // BYTES_PER_FRAME * BYTES_PER_FRAME], dtype=np.int16)
        if samples.size == 0:
            return
        mono = samples.reshape(-1, CHANNELS).astype(np.float32).mean(axis=1) / 32768.0
        if mono.size >= self._size:
            self._buffer[:] = mono[-self._size :]
        else:
            self._buffer = np.concatenate((self._buffer[mono.size :], mono))

    def bands(self) -> list[float]:
        """Los valores de ahora de cada banda (0 a 1)."""
        spectrum = np.abs(np.fft.rfft(self._buffer * self._window)) / (
            self._size / 4
        )  # una senoidal a tope da ~1
        power = np.array(
            [float(np.sqrt(np.mean(spectrum[a:b] ** 2))) for a, b in self._slices], dtype=np.float32
        )
        db = 20.0 * np.log10(power + 1e-9) + self._tilt
        return [round(float(v), 3) for v in np.clip((db - self.DB_FLOOR) / self.DB_RANGE, 0.0, 1.0)]


# ---------------------------------------------------------------------------
# La captura (Windows)
# ---------------------------------------------------------------------------


class _PropVariantBlob(Structure):
    """PROPVARIANT de tipo VT_BLOB (24 bytes en 64 bits): solo lo que hace falta para pasar los parámetros."""

    _fields_ = (
        ("vt", c_uint16),
        ("r1", c_uint16),
        ("r2", c_uint16),
        ("r3", c_uint16),
        ("cbSize", c_uint32),
        ("pBlobData", c_void_p),
    )


class _ActivationParams(Structure):
    """AUDIOCLIENT_ACTIVATION_PARAMS con la captura de bucle por proceso."""

    _fields_ = (
        ("ActivationType", c_uint32),
        ("TargetProcessId", c_uint32),
        ("ProcessLoopbackMode", c_uint32),
    )


VT_BLOB = 65
ACTIVATION_PROCESS_LOOPBACK = 1
LOOPBACK_INCLUDE_TREE = 0  # el programa y sus procesos hijos
STREAM_LOOPBACK = 0x00020000
STREAM_EVENTCALLBACK = 0x00040000
BUFFER_SILENT = 0x2


def _define_com() -> dict[str, Any]:
    """Las interfaces de COM que no trae pycaw (se definen aquí para no cargar comtypes si no hace falta)."""
    from comtypes import COMMETHOD, GUID, COMObject, IUnknown  # type: ignore[import-untyped]

    class IAgileObject(IUnknown):  # type: ignore[misc]
        _iid_ = GUID("{94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90}")
        _methods_ = ()

    class IActivateAudioInterfaceAsyncOperation(IUnknown):  # type: ignore[misc]
        _iid_ = GUID("{72A22D78-CDE4-431D-B8CC-843A71199B6D}")
        _methods_ = (
            COMMETHOD(
                [],
                c_long,
                "GetActivateResult",
                (["out"], POINTER(c_long), "activateResult"),
                (["out"], POINTER(POINTER(IUnknown)), "activatedInterface"),
            ),
        )

    class IActivateAudioInterfaceCompletionHandler(IUnknown):  # type: ignore[misc]
        _iid_ = GUID("{41D949AB-9862-444A-80F6-C261334DA5EB}")
        _methods_ = (
            COMMETHOD(
                [],
                c_long,
                "ActivateCompleted",
                (["in"], POINTER(IActivateAudioInterfaceAsyncOperation), "op"),
            ),
        )

    class IAudioCaptureClient(IUnknown):  # type: ignore[misc]
        _iid_ = GUID("{C8ADBD64-E71E-48A0-A4DE-185C395CD317}")
        _methods_ = (
            COMMETHOD(
                [],
                c_long,
                "GetBuffer",
                (["out"], POINTER(POINTER(c_ubyte)), "data"),
                (["out"], POINTER(c_uint32), "frames"),
                (["out"], POINTER(c_uint32), "flags"),
                (["out"], POINTER(c_uint64), "devicePosition"),
                (["out"], POINTER(c_uint64), "qpcPosition"),
            ),
            COMMETHOD([], c_long, "ReleaseBuffer", (["in"], c_uint32, "frames")),
            COMMETHOD([], c_long, "GetNextPacketSize", (["out"], POINTER(c_uint32), "frames")),
        )

    class Handler(COMObject):  # type: ignore[misc]
        """Windows avisa aquí (desde otro hilo) de que la activación terminó."""

        _com_interfaces_ = [IActivateAudioInterfaceCompletionHandler, IAgileObject]

        def __init__(self) -> None:
            super().__init__()
            self.done = threading.Event()
            self.operation: Any = None

        def ActivateCompleted(self, this: Any, op: Any = None) -> int:  # noqa: N802
            self.operation = op
            self.done.set()
            return 0

    return {
        "IActivateAudioInterfaceCompletionHandler": IActivateAudioInterfaceCompletionHandler,
        "IAudioCaptureClient": IAudioCaptureClient,
        "Handler": Handler,
        "IUnknown": IUnknown,
    }


class ProcessLoopback:
    """El audio de un programa y sus hijos (PCM 16 bits, estéreo, 48 kHz). Todo desde un mismo hilo."""

    def __init__(self, pid: int) -> None:
        self._pid = pid
        self._client: Any = None
        self._capture: Any = None
        self._event: Any = None
        self._com: dict[str, Any] = {}
        self._keep: list[Any] = []

    def start(self) -> None:
        """Pide a Windows el audio del programa; lanza OSError si no puede (p. ej. el programa se cerró)."""
        from pycaw.api.audioclient import WAVEFORMATEX, IAudioClient  # type: ignore[import-untyped]

        com = self._com = _define_com()
        params = _ActivationParams(ACTIVATION_PROCESS_LOOPBACK, self._pid, LOOPBACK_INCLUDE_TREE)
        blob = _PropVariantBlob(VT_BLOB, 0, 0, 0, ctypes.sizeof(params), ctypes.cast(byref(params), c_void_p))
        handler = com["Handler"]()
        operation = POINTER(com["IUnknown"])()
        handler_ptr = handler.QueryInterface(com["IActivateAudioInterfaceCompletionHandler"])
        hr = ctypes.windll.Mmdevapi.ActivateAudioInterfaceAsync(
            c_wchar_p("VAD\\Process_Loopback"),
            byref(IAudioClient._iid_),
            byref(blob),
            handler_ptr,
            byref(operation),
        )
        if hr != 0:
            raise OSError(f"ActivateAudioInterfaceAsync: 0x{hr & 0xFFFFFFFF:08X}")
        if not handler.done.wait(5.0):
            raise OSError("Windows no contestó a la activación del audio")
        result, interface = handler.operation.GetActivateResult()
        if result != 0:
            raise OSError(f"No se pudo activar el audio del programa: 0x{result & 0xFFFFFFFF:08X}")
        self._keep += [handler, handler_ptr, operation, blob, params]
        client = interface.QueryInterface(IAudioClient)
        fmt = WAVEFORMATEX()
        fmt.wFormatTag, fmt.nChannels, fmt.nSamplesPerSec = 1, CHANNELS, SAMPLE_RATE
        fmt.nAvgBytesPerSec, fmt.nBlockAlign, fmt.wBitsPerSample, fmt.cbSize = (
            SAMPLE_RATE * BYTES_PER_FRAME,
            BYTES_PER_FRAME,
            16,
            0,
        )
        client.Initialize(0, STREAM_LOOPBACK | STREAM_EVENTCALLBACK, 2_000_000, 0, byref(fmt), None)
        self._event = ctypes.windll.kernel32.CreateEventW(None, False, False, None)
        client.SetEventHandle(self._event)
        self._capture = client.GetService(com["IAudioCaptureClient"]._iid_).QueryInterface(
            com["IAudioCaptureClient"]
        )
        client.Start()
        self._client = client
        self._keep.append(fmt)

    def read(self, wait_ms: int = 40) -> bytes:
        """Lo que haya capturado desde la última vez (espera como mucho `wait_ms`). Vacío si no hay nada."""
        ctypes.windll.kernel32.WaitForSingleObject(self._event, wait_ms)
        out = bytearray()
        while self._capture.GetNextPacketSize() > 0:
            data, frames, flags, _device, _qpc = self._capture.GetBuffer()
            size = frames * BYTES_PER_FRAME
            out += bytes(size) if flags & BUFFER_SILENT else ctypes.string_at(data, size)
            self._capture.ReleaseBuffer(frames)
        return bytes(out)

    def stop(self) -> None:
        try:
            if self._client is not None:
                self._client.Stop()
        finally:
            if self._event:
                ctypes.windll.kernel32.CloseHandle(self._event)
            self._client = self._capture = self._event = None
            self._keep.clear()


# ---------------------------------------------------------------------------
# Quién captura y cuándo
# ---------------------------------------------------------------------------


def find_root_pid(names: tuple[str, ...]) -> int | None:
    """El proceso «principal» de un programa (el que no es hijo de otro suyo); None si no está abierto."""
    import psutil

    wanted = {n.lower() for n in names}
    mine = {
        p.pid: p for p in psutil.process_iter(["name", "ppid"]) if (p.info["name"] or "").lower() in wanted
    }
    roots = [pid for pid, p in mine.items() if p.info["ppid"] not in mine]
    return min(roots) if roots else None


class SpotifyTap:
    """Espectro (24 bandas de 0 a 1) de lo que suena en Spotify, o None si ahora no se puede medir.

    Un hilo propio captura solo mientras alguien llama a `poll` (y unos segundos más); sin música descansa.
    """

    def __init__(
        self,
        names: tuple[str, ...] = ("spotify.exe",),
        open_capture: Callable[[int], Any] = ProcessLoopback,
        find_pid: Callable[[tuple[str, ...]], int | None] = find_root_pid,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._names = names
        self._open = open_capture
        self._find_pid = find_pid
        self._clock = clock
        self._bands: list[float] | None = None
        self._wanted_until = 0.0
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()

    def poll(self) -> list[float] | None:
        self._wanted_until = self._clock() + ACTIVE_FOR_S
        if self._thread is None:
            self._thread = threading.Thread(target=self._run, name="vega-espectro", daemon=True)
            self._thread.start()
        return self._bands

    def stop(self) -> None:
        self._stop.set()

    def _run(self) -> None:
        com: Any = None
        try:
            import comtypes

            com = comtypes
            com.CoInitialize()
        except Exception:
            com = None
        capture: Any = None
        pid: int | None = None
        analyzer = SpectrumAnalyzer()
        refreshed = float("-inf")
        try:
            while not self._stop.is_set():
                now = self._clock()
                if now >= self._wanted_until:  # nadie lo pide: se suelta el audio y se descansa
                    self._bands = None
                    if capture is not None:
                        self._close(capture)
                        capture = pid = None
                    self._stop.wait(IDLE_S)
                    continue
                try:
                    if capture is not None and now - refreshed >= PROCESS_REFRESH_S:
                        refreshed = now
                        if self._find_pid(self._names) != pid:  # Spotify se cerró o se reinició
                            self._close(capture)
                            capture = pid = None
                    if capture is None:
                        refreshed = now
                        pid = self._find_pid(self._names)
                        if pid is None:
                            self._bands = None
                            self._stop.wait(RETRY_S)
                            continue
                        capture = self._open(pid)
                        capture.start()
                    analyzer.feed(capture.read())
                    self._bands = analyzer.bands()
                except Exception as exc:
                    log.debug("No pude capturar el audio de Spotify: %s", type(exc).__name__)
                    self._bands = None
                    if capture is not None:
                        self._close(capture)
                    capture = pid = None
                    self._stop.wait(RETRY_S)
                    continue
                self._stop.wait(TICK_S)
        finally:
            if capture is not None:
                self._close(capture)
            if com is not None:
                com.CoUninitialize()

    @staticmethod
    def _close(capture: Any) -> None:
        try:
            capture.stop()
        except Exception:
            log.debug("No pude cerrar la captura de audio", exc_info=True)
