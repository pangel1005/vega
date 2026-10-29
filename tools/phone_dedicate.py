"""Deja el teléfono para Vega: apaga lo que sobra y le da prioridad a la app.

    uv run python tools/phone_dedicate.py            # aplica y apunta qué cambió
    uv run python tools/phone_dedicate.py --undo     # lo deja como estaba
    uv run python tools/phone_dedicate.py --status   # mide RAM y procesos
    uv run python tools/phone_dedicate.py --car      # repone Play Services/Play Store para Android Auto
    uv run python tools/phone_dedicate.py --no-car   # los vuelve a quitar (recupera ~480 MB de RAM)

Todo se hace con adb, sin root y sin tocar nada que el teléfono necesite para funcionar
(pantalla, llamadas, ajustes, seguridad, teclado, instalador de apps):

- Apps de usuario que sobran (juegos, redes sociales…): `pm disable-user`.
- Apps de sistema que sobran (la nube y los anuncios de Xiaomi y Google…): Android no deja
  apagarlas desde adb, pero sí quitarlas solo para tu usuario (`pm uninstall -k --user 0`).
  No se borran del teléfono: `--undo` las repone con `install-existing`.
- Google Play Services y Play Store también se quitan (reaparecían al instante y pesaban ~480 MB).
- Vega: fuera del ahorro de batería, siempre activa, y la pantalla se queda encendida mientras
  carga (el teléfono es el cuerpo de Vega).
- Vega pasa a ser la pantalla de inicio (el botón Inicio la abre a ella, no a la de Xiaomi).
- Animaciones de Android a la mitad y batería adaptativa apagada.

Lo que cambió queda en data/telefono_antes.json, para poder deshacerlo.
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / "data" / "telefono_antes.json"
VEGA = "com.asistente.personal"

# Se apagan (pm disable-user). Los que no estén o no se dejen, se saltan.
DISABLE = ["cn.wps.moffice_eng", "cn.wps.xiaomi.abroad.lite", "com.block.juggle", "com.einnovation.temu", "com.facebook.katana", "com.linkedin.android", "com.mi.global.bbs", "com.microsoft.skydrive", "com.miui.huanji", "com.netease.newspike", "com.netflix.mediaclient", "com.nf.snake", "com.oakever.arrows", "com.oakever.tiletrip", "com.ordinaryjoy.woodblast", "com.soulcompany.bubbleshooter.relaxing", "com.spotify.music", "com.sukhavati.gotoplaying.bubble.BubbleShooter.mint", "com.vitastudio.mahjong", "com.zhiliaoapp.musically", "com.xiaomi.midrop", "com.xiaomi.smarthome", "com.xiaomi.calendar", "com.xiaomi.scanner", "com.miui.weather2", "com.miui.mediaeditor", "com.google.android.apps.chromecast.app", "com.google.android.apps.walletnfcrel", "com.google.android.apps.bard", "com.google.android.apps.docs", "com.google.android.apps.maps", "com.google.android.apps.photos", "com.google.android.apps.messaging", "com.google.android.apps.tachyon", "com.google.android.apps.wellbeing", "com.google.android.apps.safetyhub", "com.google.android.apps.subscriptions.red", "com.google.android.apps.youtube.music", "com.google.android.gm", "com.google.android.youtube", "com.google.android.videos", "com.google.android.projection.gearhead", "com.google.android.apps.restore", "com.google.android.ondevicepersonalization.services", "com.google.android.federatedcompute", "com.google.android.feedback", "com.google.android.partnersetup", "com.google.android.googlequicksearchbox", "com.google.android.healthconnect.controller", "com.google.android.health.connect.backuprestore", "com.google.ambient.streaming", "com.google.android.adservices.api", "com.google.mainline.adservices", "com.google.mainline.telemetry", "com.google.android.printservice.recommendation", "com.android.chrome", "com.android.bips", "com.miui.bugreport", "com.miui.cloudbackup", "com.miui.cloudservice", "com.miui.micloudsync", "com.xiaomi.micloud.sdk", "com.miui.backup", "com.miui.gallery", "com.miui.player", "com.miui.videoplayer", "com.mi.globalbrowser", "com.mi.appfinder", "com.mi.android.globalFileexplorer", "com.miui.fm", "com.miui.fmservice", "com.miui.extraphoto", "com.miui.miservice", "com.miui.mishare.connectivity", "com.xiaomi.mi_connect_service", "com.xiaomi.aiasst.vision", "com.xiaomi.aicr", "com.xiaomi.discover", "com.xiaomi.barrage", "com.xiaomi.cameramind", "com.xiaomi.finddevice", "com.xiaomi.simactivate.service", "com.xiaomi.ugd", "com.miui.thirdappassistant", "com.miui.phrase", "com.miui.cleaner", "com.miui.mediaviewer", "com.miui.analytics", "com.xiaomi.joyose", "com.miui.msa.global", "com.xiaomi.mipicks", "com.miui.hybrid", "com.miui.systemAdSolution", "com.mi.globalminusscreen", "com.mediatek.callrecorder", "com.mediatek.magtapp", "com.mediatek.mdmlsample", "com.mediatek.engineermode", "com.android.vending", "com.google.android.gms.location.history", "com.google.android.syncadapters.calendar", "com.google.android.gms", "com.google.android.gsf"]

# Android Auto necesita Google Play Services y la tienda (el Android Auto del teléfono es solo un
# marcador de sistema: la app de verdad se instala desde Google Play, con tu cuenta).
CAR_PACKAGES = [
    "com.google.android.gms",
    "com.google.android.gsf",
    "com.android.vending",
    "com.google.android.projection.gearhead",
    "com.google.android.gms.location.history",
    # Lo que se usa EN el coche: Google Maps, la app de Google («Hey Google» en Android Auto) y YouTube Music.
    "com.google.android.apps.maps",
    "com.google.android.googlequicksearchbox",
    "com.google.android.apps.youtube.music",
]
# Estas se apagaron (no se quitaron): basta con encenderlas. Waze no viene en el teléfono: se instala desde Play.
CAR_ENABLE = ["com.spotify.music"]

# Se dejan, pero casi sin segundo plano. (Play Services y Play Store reaparecían al instante
# aunque estuvieran restringidos y se llevaban ~480 MB: se quitan; ver DISABLE.)
RESTRICT = [
    "com.xiaomi.xmsf",
    "com.google.android.apps.nbu.files",
    "com.google.android.contacts",
    "com.google.android.dialer",
]

# Ajustes del sistema: (espacio, clave, valor nuevo).
SETTINGS = [
    ("global", "stay_on_while_plugged_in", "7"),  # pantalla encendida con USB, enchufe o carga sin cable
    ("global", "window_animation_scale", "0.5"),
    ("global", "transition_animation_scale", "0.5"),
    ("global", "animator_duration_scale", "0.5"),
    ("global", "adaptive_battery_management_enabled", "0"),
]


def adb(*args: str, check: bool = False) -> str:
    out = subprocess.run(
        ["adb", "shell", *args], capture_output=True, text=True, encoding="utf-8", errors="replace"
    )
    text = (out.stdout + out.stderr).strip()
    if check and out.returncode:
        raise SystemExit(f"adb {' '.join(args)}: {text}")
    return text


def packages(flags: str) -> set[str]:
    return {
        line.removeprefix("package:")
        for line in adb("pm", "list", "packages", *flags.split()).splitlines()
        if line
    }


def status() -> None:
    mem = {
        k: int(v.split()[0])
        for k, v in (line.split(":") for line in adb("cat", "/proc/meminfo").splitlines() if ":" in line)
    }
    print(f"RAM libre: {mem['MemAvailable'] / 1024:.0f} MB de {mem['MemTotal'] / 1024:.0f} MB")
    swap_used = (mem["SwapTotal"] - mem["SwapFree"]) / 1024
    print(f"Swap en uso: {swap_used:.0f} MB")
    procs = adb("sh", "-c", "ps -A -o NAME | grep -c .")
    print(f"Procesos: {procs}")
    print(f"Apps activas: {len(packages('-e'))}  ·  apagadas: {len(packages('-d'))}")


def apply() -> None:
    STATE.parent.mkdir(parents=True, exist_ok=True)
    saved = (
        json.loads(STATE.read_text(encoding="utf-8"))
        if STATE.exists()
        else {"disabled": [], "settings": {}, "buckets": {}}
    )
    enabled = packages("-e")

    print("— Apagando apps —")
    done, removed, kept = [], [], []
    for pkg in DISABLE:
        if pkg not in enabled:
            continue
        if saved.get("car") and (pkg in CAR_PACKAGES or pkg in CAR_ENABLE):
            continue  # en modo coche se dejan
        out = adb("pm", "disable-user", "--user", "0", pkg)
        if "new state: disabled" in out:
            done.append(pkg)
        elif "Cannot disable system packages" in out:
            ok = "Success" in adb("pm", "uninstall", "-k", "--user", "0", pkg)
            (removed if ok else kept).append(pkg)
        else:
            kept.append(pkg)
    saved["disabled"] = sorted(set(saved["disabled"]) | set(done))
    saved["removed"] = sorted(set(saved.get("removed", [])) | set(removed))
    print(f"Apagadas: {len(done)}  ·  quitadas de tu usuario: {len(removed)}  ·  se quedan: {len(kept)}")
    for pkg in kept:
        print(f"   se queda: {pkg}")

    print("— Segundo plano restringido —")
    for pkg in RESTRICT:
        if pkg in enabled:
            before = adb("am", "get-standby-bucket", pkg)
            saved["buckets"].setdefault(pkg, before)
            adb("am", "set-standby-bucket", pkg, "restricted")
            adb("cmd", "appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", "ignore")

    print("— Prioridad para Vega —")
    adb("cmd", "deviceidle", "whitelist", f"+{VEGA}")
    adb("am", "set-standby-bucket", VEGA, "active")
    for op in ("RUN_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND", "START_FOREGROUND", "WAKE_LOCK"):
        adb("cmd", "appops", "set", VEGA, op, "allow")

    print("— Pantalla de inicio —")
    current = adb(
        "cmd",
        "package",
        "resolve-activity",
        "--brief",
        "-c",
        "android.intent.category.HOME",
        "-a",
        "android.intent.action.MAIN",
    ).splitlines()
    before = current[-1].strip() if current else ""
    if before and before != f"{VEGA}/.MainActivity":
        saved.setdefault("home", before)
    print("   ", adb("cmd", "package", "set-home-activity", f"{VEGA}/.MainActivity"))

    print("— Ajustes —")
    for space, key, value in SETTINGS:
        saved["settings"].setdefault(f"{space}/{key}", adb("settings", "get", space, key))
        adb("settings", "put", space, key, value)
        print(f"   {key} = {value}")

    STATE.write_text(json.dumps(saved, indent=2), encoding="utf-8")
    print(f"\nGuardado en {STATE.relative_to(ROOT)} (para --undo).")


def car_on() -> None:
    """Repone lo que Android Auto necesita. Después: abre Google Play en el teléfono, instala «Android Auto»
    con tu cuenta y sigue docs/coche.md."""
    STATE.parent.mkdir(parents=True, exist_ok=True)
    saved = (
        json.loads(STATE.read_text(encoding="utf-8"))
        if STATE.exists()
        else {"disabled": [], "settings": {}, "buckets": {}}
    )
    for pkg in CAR_PACKAGES:
        print(f"   {pkg}: {adb('cmd', 'package', 'install-existing', '--user', '0', pkg)}")
    for pkg in CAR_ENABLE:
        print(f"   {pkg}: {adb('pm', 'enable', '--user', '0', pkg)}")
    saved["car"] = True
    STATE.write_text(json.dumps(saved, indent=2), encoding="utf-8")
    print(
        "\nListo. Abre Google Play en el teléfono, inicia sesión e instala «Android Auto» (y Waze, si lo quieres)."
    )


def car_off() -> None:
    saved = (
        json.loads(STATE.read_text(encoding="utf-8"))
        if STATE.exists()
        else {"disabled": [], "settings": {}, "buckets": {}}
    )
    removed = set(saved.get("removed", []))
    for pkg in CAR_PACKAGES:
        if "Success" in adb("pm", "uninstall", "-k", "--user", "0", pkg):
            removed.add(pkg)
    for pkg in CAR_ENABLE:
        adb("pm", "disable-user", "--user", "0", pkg)
    saved["removed"] = sorted(removed)
    saved["car"] = False
    STATE.write_text(json.dumps(saved, indent=2), encoding="utf-8")
    print("Vuelto a quitar Play Services y Play Store.")


def undo() -> None:
    if not STATE.exists():
        raise SystemExit("No hay nada que deshacer (falta data/telefono_antes.json).")
    saved = json.loads(STATE.read_text(encoding="utf-8"))
    for pkg in saved["disabled"]:
        adb("pm", "enable", "--user", "0", pkg)
    for pkg in saved.get("removed", []):
        adb("cmd", "package", "install-existing", "--user", "0", pkg)
    for pkg, bucket in saved["buckets"].items():
        adb("am", "set-standby-bucket", pkg, bucket.split()[0] if bucket.split() else "active")
        adb("cmd", "appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", "default")
    adb("cmd", "deviceidle", "whitelist", f"-{VEGA}")
    if saved.get("home"):
        adb("cmd", "package", "set-home-activity", saved["home"])
    for key, value in saved["settings"].items():
        space, name = key.split("/")
        if value in ("", "null"):
            adb("settings", "delete", space, name)
        else:
            adb("settings", "put", space, name, value)
    STATE.unlink()
    print(
        f"Vuelto a como estaba: {len(saved['disabled']) + len(saved.get('removed', []))} apps repuestas y los ajustes restaurados."
    )


if __name__ == "__main__":
    {"--undo": undo, "--status": status, "--car": car_on, "--no-car": car_off}.get(
        sys.argv[1] if len(sys.argv) > 1 else "", apply
    )()
