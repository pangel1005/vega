"""Mantenimiento: liberar RAM y limpiar disco (sobre carpetas de mentira, nunca las reales)."""

from __future__ import annotations

import json
import os
import time
from pathlib import Path

import httpx
import pytest

from server.core.maintenance import SPEC_IDS, Maintenance, human

NOW = time.time()
DAY = 86_400


def make(path: Path, size: int = 10, age_days: float = 0) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"x" * size)
    t = NOW - age_days * DAY
    os.utime(path, (t, t))
    return path


@pytest.fixture
def world(tmp_path: Path) -> dict[str, Path]:
    root, temp, home = tmp_path / "vega", tmp_path / "temp", tmp_path / "home"
    for d in (root, temp, home):
        d.mkdir()
    return {"root": root, "temp": temp, "home": home}


def maint(world: dict[str, Path], handler=None) -> Maintenance:  # type: ignore[no-untyped-def]
    transport = httpx.MockTransport(handler or (lambda r: httpx.Response(200, json={"models": []})))
    return Maintenance(
        world["root"], "http://ollama.test", world["temp"], world["home"], lambda: NOW, transport
    )


def test_tamanos_en_espanol() -> None:
    assert human(380 * 2**20) == "380 MB"
    assert human(int(1.5 * 2**30)) == "1,5 GB"
    assert human(12 * 2**10) == "12 KB"


# ---------- Disco ----------


def test_el_escaneo_mide_y_lista_sin_borrar(world: dict[str, Path]) -> None:
    root = world["root"]
    make(root / "logs" / "server.log.1", 1000)
    make(root / "logs" / "server.log", 500)  # el que se está escribiendo no es «viejo»
    make(root / "logs" / "cuelgues" / "cuelgue-1.txt", 300, age_days=10)
    make(root / "logs" / "cuelgues" / "cuelgue-2.txt", 300, age_days=1)  # reciente: se queda
    make(root / "server" / "__pycache__" / "a.pyc", 200)
    make(root / ".pytest_cache" / "v" / "x", 100)
    make(world["temp"] / "viejo.tmp", 700, age_days=30)
    make(world["temp"] / "nuevo.tmp", 700, age_days=1)
    found = {c.id: c for c in maint(world).disk_scan()}
    assert found["logs"].size == 1300 and found["logs"].files == 2
    assert found["cache-codigo"].size == 300
    assert found["temporales"].size == 700 and found["temporales"].default
    assert "compilacion-android" not in found  # no hay nada que limpiar: no se ofrece
    assert (root / "logs" / "server.log.1").exists()  # solo se midió


def test_limpiar_borra_solo_lo_pedido(world: dict[str, Path]) -> None:
    root = world["root"]
    old_log = make(root / "logs" / "server.log.1", 1000)
    live_log = make(root / "logs" / "server.log", 500)
    audit = make(root / "logs" / "audit.log.1", 400)  # el rastro de las acciones no se toca
    cache = make(root / "server" / "__pycache__" / "a.pyc", 200)
    old_tmp = make(world["temp"] / "viejo.tmp", 700, age_days=30)
    result = maint(world).disk_clean(["logs"])
    assert result.freed == 1000 and result.removed == 1 and result.categories == ["logs"]
    assert not old_log.exists()
    assert live_log.exists() and audit.exists() and cache.exists() and old_tmp.exists()
    assert "Liberé" in result.message()


def test_temporales_respeta_la_edad_y_el_espacio_de_claude(world: dict[str, Path]) -> None:
    viejo = make(world["temp"] / "a" / "b.tmp", 50, age_days=40)
    nuevo = make(world["temp"] / "n.tmp", 50, age_days=1)
    en_uso = make(world["temp"] / "claude" / "sesion.txt", 50, age_days=40)
    maint(world).disk_clean(["temporales"])
    assert not viejo.exists()
    assert nuevo.exists() and en_uso.exists()


def test_la_cache_de_codigo_ignora_el_entorno_y_el_android(world: dict[str, Path]) -> None:
    root = world["root"]
    ok = make(root / "server" / "core" / "__pycache__" / "x.pyc")
    venv = make(root / ".venv" / "lib" / "__pycache__" / "y.pyc")
    android = make(root / "android" / "app" / "__pycache__" / "z.pyc")
    maint(world).disk_clean(["cache-codigo"])
    assert not ok.exists()
    assert venv.exists() and android.exists()


def test_nunca_se_sigue_un_enlace(world: dict[str, Path], tmp_path: Path) -> None:
    precious = make(tmp_path / "documentos" / "tesis.docx", 999)
    link = world["root"] / ".pytest_cache"
    try:
        link.symlink_to(precious.parent, target_is_directory=True)
    except OSError:
        pytest.skip("Windows no deja crear enlaces simbólicos sin permiso")
    result = maint(world).disk_clean(["cache-codigo"])
    assert precious.exists()
    assert result.removed == 0


def test_una_ruta_fuera_de_lo_permitido_se_rechaza(world: dict[str, Path], tmp_path: Path) -> None:
    outside = make(tmp_path / "fuera.txt")
    m = Maintenance()
    assert not m._safe(outside, world["root"])
    assert not m._safe(world["root"], world["root"])  # ni la propia carpeta
    assert not m._safe(world["root"] / ".." / "fuera.txt", world["root"])
    assert m._safe(make(world["root"] / "logs" / "a.log.1"), world["root"] / "logs")


def test_una_categoria_desconocida_no_se_acepta(world: dict[str, Path]) -> None:
    with pytest.raises(ValueError, match="desconocida"):
        maint(world).disk_clean(["../../Windows"])
    assert "logs" in SPEC_IDS


def test_lo_que_no_se_puede_borrar_se_cuenta_aparte(
    world: dict[str, Path], monkeypatch: pytest.MonkeyPatch
) -> None:
    make(world["root"] / "logs" / "server.log.1")
    monkeypatch.setattr(Maintenance, "_delete", staticmethod(lambda p: False))
    result = maint(world).disk_clean(["logs"])
    assert result.failed == 1 and result.freed == 0 and result.categories == []
    assert "No había nada" in result.message()


# ---------- RAM ----------


def ollama_handler(loaded: list[dict[str, object]], posted: list[dict[str, object]]):  # type: ignore[no-untyped-def]
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/ps":
            return httpx.Response(200, json={"models": loaded})
        posted.append(json.loads(request.content))
        return httpx.Response(200, json={})

    return handler


def test_liberar_ram_descarga_los_modelos_de_ollama(world: dict[str, Path]) -> None:
    posted: list[dict[str, object]] = []
    loaded = [{"name": "qwen3.5:4b", "size": 3 * 2**30}]
    result = maint(world, ollama_handler(loaded, posted)).free_ram()
    assert posted == [{"model": "qwen3.5:4b", "keep_alive": 0}]
    assert any("qwen3.5:4b" in s and "3,0 GB" in s for s in result.steps)
    assert result.message().startswith("Libres ahora:")


def test_liberar_ram_sin_ollama_no_falla(world: dict[str, Path]) -> None:
    def down(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("apagado")

    result = maint(world, down).free_ram()
    assert not any("Ollama" in s for s in result.steps)


def test_el_estado_de_la_ram(world: dict[str, Path]) -> None:
    status = maint(world, ollama_handler([{"name": "m", "size": 1}], [])).ram_status()
    assert status.total_mb > 0 and 0 < status.free_mb <= status.total_mb
    assert status.ollama_models == ["m"]
    assert status.top and status.top[0].mb >= status.top[-1].mb
    assert set(status.public()) == {"total_mb", "free_mb", "vega_mb", "ollama_mb", "ollama_models", "top"}
