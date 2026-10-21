import { useRef } from "react";

import type { VoiceStatus } from "./useVoice";
import "./mic.css";

/** Pulsación más corta que esto = modo «toque» (toca para empezar, toca para terminar). */
const TAP_MS = 350;

interface MicButtonProps {
  status: VoiceStatus;
  disabled: boolean;
  onStart: () => void;
  onStop: () => void;
  onCancel: () => void;
}

/**
 * Botón para hablar. Mantener pulsado: graba mientras se sostiene.
 * Toque corto: empieza a grabar y un segundo toque termina.
 */
export function MicButton({ status, disabled, onStart, onStop, onCancel }: MicButtonProps) {
  const pressedAt = useRef(0);
  const tapMode = useRef(false);
  const active = status !== "off";

  const down = (e: React.PointerEvent) => {
    if (disabled) return;
    e.currentTarget.setPointerCapture(e.pointerId);
    pressedAt.current = performance.now();
    if (active && tapMode.current) {
      // Segundo toque en modo toque: terminar (y que el «soltar» no haga nada más).
      tapMode.current = false;
      pressedAt.current = 0;
      onStop();
      return;
    }
    tapMode.current = false;
    onStart();
  };

  const up = () => {
    if (!active || pressedAt.current === 0) return;
    const held = performance.now() - pressedAt.current;
    pressedAt.current = 0;
    if (held < TAP_MS) tapMode.current = true; // sigue grabando hasta el próximo toque
    else onStop();
  };

  const keyDown = (e: React.KeyboardEvent) => {
    // Teclado (Enter/Espacio): siempre en modo toque.
    if (e.key !== "Enter" && e.key !== " ") return;
    e.preventDefault();
    if (disabled || e.repeat) return;
    if (active) onStop();
    else onStart();
  };

  const label = status === "recording" ? "Grabando" : status === "starting" ? "Abriendo micrófono" : "Hablar";

  return (
    <button
      type="button"
      className={`mic mic--${status}`}
      disabled={disabled}
      onPointerDown={down}
      onPointerUp={up}
      onPointerCancel={() => {
        pressedAt.current = 0;
        tapMode.current = false;
        onCancel();
      }}
      onKeyDown={keyDown}
      onContextMenu={(e) => e.preventDefault()}
      aria-pressed={active}
      aria-label={active ? "Terminar de hablar" : "Hablar"}
    >
      <svg className="mic__icon" viewBox="0 0 24 24" aria-hidden="true">
        <rect x="9" y="3" width="6" height="11" rx="3" />
        <path d="M5 11a7 7 0 0 0 14 0M12 18v3M8 21h8" fill="none" strokeWidth="2" />
      </svg>
      <span className="mic__text">
        <span className="jp mic__jp">{active ? "受信中" : "通話"}</span>
        <span className="mono mic__label">{label}</span>
      </span>
    </button>
  );
}
