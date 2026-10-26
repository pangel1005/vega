"""Mantenimiento del PC: liberar RAM y limpiar disco.

Pensado para jugar con la IA local sin que el PC vaya justo de memoria. Reglas de seguridad:

- **Disco: vista previa antes de borrar.** `disk_scan` solo mide y lista; `disk_clean` borra
  únicamente las categorías pedidas, y dentro de cada una solo carpetas permitidas (la lista está
  aquí, en el código: ni el teléfono ni la app pueden pedir otra ruta). No sigue enlaces ni uniones.
- **Nada de documentos, descargas ni la papelera.** Vaciar la papelera es borrar para siempre cosas
  que el usuario aún puede querer: eso lo hace él.
- **RAM: no cierra programas ajenos.** Descarga el modelo de Ollama, reduce el espacio en RAM que
  ocupan los procesos de Vega y, solo si se pide «a fondo», reduce el de las demás aplicaciones
  (Windows se lo devuelve cuando las usas) y detiene los servicios de Gradle y Kotlin, que se
  vuelven a arrancar solos.
"""

from __future__ import annotations

import contextlib
import ctypes
import logging
import os
import shutil
import stat
import sys
import tempfile
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path

import httpx
import psutil

from server.core.config import ROOT

log = logging.getLogger(__name__)

SCAN_BUDGET_S = 25.0  # una categoría enorme no debe bloquear la petición
TEMP_DAYS = 7
LOG_DAYS = 3

# Procesos del sistema que nunca se tocan al reducir la memoria de las demás aplicaciones.
_PROTECTED = {
    "system", "registry", "memory compression", "csrss.exe", "winlogon.exe", "services.exe", "lsass.exe",
    "smss.exe", "wininit.exe", "dwm.exe", "fontdrvhost.exe", "svchost.exe", "audiodg.exe", "explorer.exe",
}  # fmt: skip


def mb(n: int) -> int:
    return round(n / 2**20)


def human(n: int) -> str:
    """1536 MB → «1,5 GB»; 380 MB → «380 MB»; 12 KB → «12 KB»."""
    if n >= 2**30:
        return f"{n / 2**30:.1f}".replace(".", ",") + " GB"
    if n >= 2**20:
        return f"{n / 2**20:.0f} MB"
    return f"{max(n, 0) / 2**10:.0f} KB"


# ---------- RAM ----------


@dataclass(frozen=True)
class _Proc:
    pid: int
    name: str
    rss: int
    vega: bool  # ¿es el servidor o la app del PC de Vega?
    user: str | None
    cmd: list[str]


@dataclass(frozen=True)
class RamProc:
    name: str
    mb: int


@dataclass(frozen=True)
class RamStatus:
    total_mb: int
    free_mb: int
    vega_mb: int
    ollama_mb: int
    ollama_models: list[str]
    top: list[RamProc]

    def public(self) -> dict[str, object]:
        return {
            "total_mb": self.total_mb,
            "free_mb": self.free_mb,
            "vega_mb": self.vega_mb,
            "ollama_mb": self.ollama_mb,
            "ollama_models": self.ollama_models,
            "top": [{"name": p.name, "mb": p.mb} for p in self.top],
        }


@dataclass
class FreeResult:
    before_mb: int
    after_mb: int
    steps: list[str] = field(default_factory=list)

    @property
    def freed_mb(self) -> int:
        return max(self.after_mb - self.before_mb, 0)

    def message(self) -> str:
        head = f"Libres ahora: {human(self.after_mb * 2**20)} (había {human(self.before_mb * 2**20)})."
        return head + (" " + " ".join(self.steps) if self.steps else " No había nada que descargar.")

    def public(self) -> dict[str, object]:
        return {
            "before_mb": self.before_mb,
            "after_mb": self.after_mb,
            "freed_mb": self.freed_mb,
            "steps": self.steps,
        }


# ---------- Disco ----------


@dataclass(frozen=True)
class DiskCategory:
    id: str
    label: str
    size: int
    files: int
    note: str  # qué pasa después de borrarlo
    default: bool  # ¿va marcada de serie? (solo lo que se regenera al momento)
    examples: list[str]

    def public(self) -> dict[str, object]:
        return {
            "id": self.id, "label": self.label, "size": self.size, "files": self.files,
            "note": self.note, "default": self.default, "examples": self.examples,
        }  # fmt: skip


@dataclass
class CleanResult:
    freed: int = 0
    removed: int = 0
    failed: int = 0
    categories: list[str] = field(default_factory=list)

    def message(self) -> str:
        if not self.categories:
            return "No había nada que limpiar."
        text = f"Liberé {human(self.freed)} en {self.removed} elementos."
        return text + (f" No pude borrar {self.failed} (están en uso)." if self.failed else "")

    def public(self) -> dict[str, object]:
        return {
            "freed": self.freed,
            "removed": self.removed,
            "failed": self.failed,
            "categories": self.categories,
        }


@dataclass(frozen=True)
class _Spec:
    id: str
    label: str
    note: str
    default: bool


_SPECS = [
    _Spec("logs", "Registros viejos de Vega", "Se vuelven a crear solos.", True),
    _Spec("cache-codigo", "Cachés de programación (pytest, ruff, mypy, __pycache__)", "Se regeneran al ejecutar.", True),  # noqa: E501
    _Spec("temporales", f"Archivos temporales de Windows (de más de {TEMP_DAYS} días)", "Los programas los vuelven a crear si hacen falta.", True),  # noqa: E501
    _Spec("compilacion-android", "Compilaciones de la app del teléfono", "La próxima compilación tarda unos minutos más.", False),  # noqa: E501
    _Spec("cache-gradle", "Caché de Gradle (~/.gradle)", "Se vuelve a bajar de internet en la próxima compilación (varios minutos).", False),  # noqa: E501
]  # fmt: skip
SPEC_IDS = tuple(s.id for s in _SPECS)


def _is_link(path: Path) -> bool:
    """Enlaces simbólicos y uniones de Windows: nunca se siguen ni se borran por dentro."""
    try:
        st = path.lstat()
    except OSError:
        return True
    return stat.S_ISLNK(st.st_mode) or bool(getattr(st, "st_file_attributes", 0) & 0x400)  # REPARSE_POINT


def _size_of(path: Path, deadline: float) -> tuple[int, int]:
    """(bytes, archivos) de un archivo o carpeta, sin seguir enlaces; se corta al pasar `deadline`."""
    if _is_link(path):
        return 0, 0
    try:
        if path.is_file():
            return path.stat().st_size, 1
    except OSError:
        return 0, 0
    total = count = 0
    stack = [path]
    while stack and time.monotonic() < deadline:
        current = stack.pop()
        try:
            with os.scandir(current) as it:
                for entry in it:
                    try:
                        if entry.is_symlink() or _is_link(Path(entry.path)):
                            continue
                        if entry.is_dir(follow_symlinks=False):
                            stack.append(Path(entry.path))
                        else:
                            total += entry.stat(follow_symlinks=False).st_size
                            count += 1
                    except OSError:
                        continue
        except OSError:
            continue
    return total, count


class Maintenance:
    """Liberar RAM y limpiar disco. Todo es síncrono: se llama desde un hilo (`asyncio.to_thread`)."""

    def __init__(
        self,
        root: Path = ROOT,
        ollama_url: str = "http://127.0.0.1:11434",
        temp: Path | None = None,
        home: Path | None = None,
        now: Callable[[], float] = time.time,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        self._root = root
        self._ollama = ollama_url.rstrip("/")
        self._temp = temp if temp is not None else Path(tempfile.gettempdir())
        self._home = home if home is not None else Path.home()
        self._now = now
        self._transport = transport

    # ---------- RAM ----------

    def ram_status(self, top_n: int = 5) -> RamStatus:
        mem = psutil.virtual_memory()
        vega = ollama = 0
        per: dict[str, int] = {}
        for proc in self._processes():
            per[proc.name] = per.get(proc.name, 0) + proc.rss
            if proc.vega:
                vega += proc.rss
            if proc.name.lower().startswith("ollama"):
                ollama += proc.rss
        top = sorted(per.items(), key=lambda kv: kv[1], reverse=True)[:top_n]
        return RamStatus(
            mb(mem.total), mb(mem.available), mb(vega), mb(ollama), [m for m, _ in self._ollama_loaded()],
            [RamProc(n, mb(v)) for n, v in top],
        )  # fmt: skip

    def free_ram(self, deep: bool = False) -> FreeResult:
        before = mb(psutil.virtual_memory().available)
        steps: list[str] = []
        steps += self._unload_ollama()
        steps += self._trim_vega()
        if deep:
            steps += self._stop_dev_daemons()
            steps += self._trim_others()
        time.sleep(0.4)  # Windows tarda un instante en contar las páginas devueltas
        return FreeResult(before, mb(psutil.virtual_memory().available), steps)

    @staticmethod
    def _processes() -> list[_Proc]:
        out: list[_Proc] = []
        me = os.getpid()
        for p in psutil.process_iter(["pid", "name", "cmdline", "memory_info", "username"]):
            info = p.info
            mem = info.get("memory_info")
            if mem is None:
                continue
            name = info["name"] or "?"
            cmd = list(info.get("cmdline") or [])
            is_vega = info["pid"] == me or (
                "python" in name.lower() and "-m" in cmd and any(a in ("server", "desktop") for a in cmd[-4:])
            )
            out.append(_Proc(info["pid"], name, mem.rss, is_vega, info.get("username"), cmd))
        return out

    def _ollama_loaded(self) -> list[tuple[str, int]]:
        try:
            with httpx.Client(timeout=3, transport=self._transport) as client:
                models = client.get(f"{self._ollama}/api/ps").json().get("models", [])
            return [(m["name"], int(m.get("size", 0))) for m in models]
        except (httpx.HTTPError, ValueError, KeyError):
            return []

    def _unload_ollama(self) -> list[str]:
        steps = []
        for name, size in self._ollama_loaded():
            try:
                with httpx.Client(timeout=10, transport=self._transport) as client:
                    client.post(f"{self._ollama}/api/generate", json={"model": name, "keep_alive": 0})
                steps.append(f"Ollama soltó {name} ({human(size)}).")
            except httpx.HTTPError as exc:
                log.debug("No pude descargar %s: %s", name, exc)
        return steps

    def _trim_vega(self) -> list[str]:
        saved = sum(self._trim(p.pid) for p in self._processes() if p.vega)
        return [f"Vega dejó {human(saved)} en la memoria de reserva de Windows."] if saved >= 2**20 else []

    def _trim_others(self) -> list[str]:
        foreground = _foreground_pid()
        me = psutil.Process().username()
        saved = count = 0
        for p in self._processes():
            if (
                p.vega
                or p.pid == foreground
                or p.name.lower() in _PROTECTED
                or p.user != me
                or p.rss < 100 * 2**20
            ):
                continue
            got = self._trim(p.pid)
            if got:
                saved += got
                count += 1
        return (
            [
                f"Reduje {count} aplicaciones en segundo plano ({human(saved)}; pasan a la reserva y se vuelven a llenar si las usas)."  # noqa: E501
            ]
            if count
            else []
        )

    def _stop_dev_daemons(self) -> list[str]:
        """Gradle y el compilador de Kotlin se quedan en segundo plano GB de RAM; se arrancan solos."""
        stopped = freed = 0
        for p in self._processes():
            cmd = " ".join(p.cmd)
            if p.name.lower().startswith("java") and ("GradleDaemon" in cmd or "KotlinCompileDaemon" in cmd):
                try:
                    psutil.Process(p.pid).terminate()
                except psutil.Error:
                    continue
                stopped += 1
                freed += p.rss
        return [f"Detuve {stopped} servicios de Gradle/Kotlin ({human(freed)})."] if stopped else []

    @staticmethod
    def _trim(pid: int) -> int:
        """Devuelve a Windows las páginas que un proceso no está usando. Bytes recuperados (0 si no pudo)."""
        if sys.platform != "win32":
            return 0
        try:
            before: int = psutil.Process(pid).memory_info().rss
        except psutil.Error:
            return 0
        kernel32 = ctypes.windll.kernel32
        kernel32.OpenProcess.restype = ctypes.c_void_p
        handle = kernel32.OpenProcess(0x0100 | 0x1000, False, pid)  # SET_QUOTA | QUERY_LIMITED_INFORMATION
        if not handle:
            return 0
        try:
            ctypes.windll.psapi.EmptyWorkingSet(ctypes.c_void_p(handle))
        finally:
            kernel32.CloseHandle(ctypes.c_void_p(handle))
        try:
            after: int = psutil.Process(pid).memory_info().rss
            return max(before - after, 0)
        except psutil.Error:
            return 0

    # ---------- Disco ----------

    def _targets(self, category: str) -> list[tuple[Path, Path]]:
        """(ruta a borrar, carpeta permitida que la contiene) de una categoría. Aquí está TODA la lista."""
        root = self._root
        cutoff = self._now()
        out: list[tuple[Path, Path]] = []

        def old(path: Path, days: int) -> bool:
            """Más viejo que `days`: una carpeta cuenta por lo más nuevo que tenga dentro."""
            limit = cutoff - days * 86_400
            try:
                if not path.is_dir() or _is_link(path):
                    return path.lstat().st_mtime < limit
                for dirpath, dirnames, filenames in os.walk(path):
                    dirnames[:] = [d for d in dirnames if not _is_link(Path(dirpath, d))]
                    for name in filenames:
                        if os.lstat(os.path.join(dirpath, name)).st_mtime >= limit:
                            return False
                return True
            except OSError:
                return False

        if category == "logs":
            logs = root / "logs"
            for p in logs.glob("*.log.*"):  # server.log.1…: lo que ya rotó
                if not p.name.startswith("audit"):  # el rastro de las acciones ejecutadas no se borra
                    out.append((p, logs))
            for p in (logs / "cuelgues").glob("*"):
                if old(p, LOG_DAYS):
                    out.append((p, logs))
            if (logs / "ultima_voz.wav").is_file():
                out.append((logs / "ultima_voz.wav", logs))  # audio de depuración: mejor no guardarlo
        elif category == "cache-codigo":
            for name in (".pytest_cache", ".ruff_cache", ".mypy_cache"):
                if (root / name).is_dir():
                    out.append((root / name, root))
            skip = {".venv", "node_modules", ".git", "android"}
            for dirpath, dirnames, _ in os.walk(root):
                dirnames[:] = [d for d in dirnames if d not in skip and not _is_link(Path(dirpath, d))]
                if "__pycache__" in dirnames:
                    out.append((Path(dirpath, "__pycache__"), root))
                    dirnames.remove("__pycache__")
        elif category == "temporales":
            if self._temp.is_dir():
                for p in self._temp.iterdir():
                    # «claude…» es el espacio de trabajo de la app de Claude: puede estar en uso ahora mismo.
                    if p.name.lower().startswith("claude") or not old(p, TEMP_DAYS):
                        continue
                    out.append((p, self._temp))
        elif category == "compilacion-android":
            for rel in ("android/app/build", "android/build", "android/.gradle"):
                if (root / rel).is_dir():
                    out.append((root / rel, root))
        elif category == "cache-gradle":
            caches = self._home / ".gradle" / "caches"
            if caches.is_dir():
                out.append((caches, self._home / ".gradle"))
        return out

    def disk_scan(self) -> list[DiskCategory]:
        deadline = time.monotonic() + SCAN_BUDGET_S
        found = []
        for spec in _SPECS:
            size = files = 0
            examples: list[str] = []
            for path, _ in self._targets(spec.id):
                s, f = _size_of(path, deadline)
                size += s
                files += f
                if len(examples) < 3:
                    examples.append(path.name)
            if files:
                found.append(
                    DiskCategory(spec.id, spec.label, size, files, spec.note, spec.default, examples)
                )
        return found

    def disk_clean(self, ids: list[str]) -> CleanResult:
        result = CleanResult()
        deadline = time.monotonic() + 3600
        for category in dict.fromkeys(ids):
            if category not in SPEC_IDS:
                raise ValueError(f"Categoría desconocida: {category}")
            done = False
            for path, allowed in self._targets(category):
                if not self._safe(path, allowed):
                    continue
                size, _ = _size_of(path, deadline)
                if self._delete(path):
                    result.freed += size
                    result.removed += 1
                    done = True
                else:
                    result.failed += 1
            if done:
                result.categories.append(category)
        return result

    @staticmethod
    def _safe(path: Path, allowed: Path) -> bool:
        """Solo dentro de la carpeta permitida, nunca ella misma y nunca por un enlace."""
        try:
            resolved = path.resolve()
            return (
                resolved != allowed.resolve()
                and resolved.is_relative_to(allowed.resolve())
                and not _is_link(path)
            )
        except OSError:
            return False

    @staticmethod
    def _delete(path: Path) -> bool:
        def retry(func: Callable[..., object], p: str, _exc: object) -> None:
            with contextlib.suppress(OSError):  # solo lectura: se quita la marca y se reintenta
                os.chmod(p, stat.S_IWRITE)
                func(p)

        try:
            if path.is_dir():
                shutil.rmtree(path, onexc=retry)
                return not path.exists()
            path.unlink()
            return True
        except OSError:
            return False


def _foreground_pid() -> int | None:
    """El proceso de la ventana en primer plano (no se reduce: es lo que se está usando)."""
    if sys.platform != "win32":
        return None
    user32 = ctypes.windll.user32
    pid = ctypes.c_ulong()
    user32.GetWindowThreadProcessId(user32.GetForegroundWindow(), ctypes.byref(pid))
    return int(pid.value) or None
