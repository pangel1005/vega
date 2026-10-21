"""Nombres de teclas permitidos en `config/actions.toml` → códigos de tecla virtual de Windows."""

from __future__ import annotations

VK: dict[str, int] = {
    "win": 0x5B, "ctrl": 0x11, "alt": 0x12, "shift": 0x10, "tab": 0x09, "enter": 0x0D, "esc": 0x1B,
    "space": 0x20, "print_screen": 0x2C,
    "volume_mute": 0xAD, "volume_down": 0xAE, "volume_up": 0xAF,
    "media_next_track": 0xB0, "media_prev_track": 0xB1, "media_stop": 0xB2, "media_play_pause": 0xB3,
    **{f"f{i}": 0x6F + i for i in range(1, 13)},
    **{chr(c): c - 32 for c in range(ord("a"), ord("z") + 1)},
    **{str(d): 0x30 + d for d in range(10)},
}  # fmt: skip

# Teclas que Windows trata como «extendidas» (necesitan una bandera especial al simularlas).
EXTENDED = {0x5B, 0x2C, 0xAD, 0xAE, 0xAF, 0xB0, 0xB1, 0xB2, 0xB3}


def unknown_keys(combo: str) -> list[str]:
    return [k for k in combo.split("+") if k not in VK]
