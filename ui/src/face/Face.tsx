import { useEffect, useRef } from "react";

import type { AssistantState } from "../net/protocol";
import { STATE_LABELS } from "./states";
import "./face.css";

const BARS = 11;
/** Perfil de la boca: las barras del centro se mueven más que las de los lados. */
const PROFILE = Array.from({ length: BARS }, (_, i) => 1 - Math.abs(i - (BARS - 1) / 2) / BARS);

interface FaceProps {
  state: AssistantState;
  /** Aumenta con cada trozo de respuesta recibido. */
  pulse: number;
}

/**
 * Rostro del asistente. Todo lo animado usa solo `transform` y `opacity`
 * (lo más barato para el navegador). La boca se mueve con un bucle de
 * requestAnimationFrame que escribe directamente en el DOM, sin re-renderizar React.
 *
 * PROTOTIPO: la boca reacciona a la llegada de palabras; en el paso 7 seguirá
 * la amplitud real del audio.
 */
export function Face({ state, pulse }: FaceProps) {
  const barsRef = useRef<(SVGRectElement | null)[]>([]);
  const level = useRef(0);
  const raf = useRef(0);

  useEffect(() => {
    if (pulse === 0) return;
    level.current = Math.min(1, level.current + 0.55);
    // El bucle solo corre mientras la boca se mueve; en reposo no gasta nada.
    if (raf.current) return;
    const tick = (now: number) => {
      level.current *= 0.9;
      const t = now / 1000;
      const done = level.current < 0.01;
      barsRef.current.forEach((bar, i) => {
        if (!bar) return;
        const wobble = 0.55 + 0.45 * Math.sin(t * 17 + i * 1.7) * Math.sin(t * 5.3 + i);
        const s = done ? 0.08 : 0.08 + level.current * (PROFILE[i] ?? 0) * Math.abs(wobble);
        bar.style.transform = `scaleY(${s.toFixed(3)})`;
      });
      raf.current = done ? 0 : requestAnimationFrame(tick);
    };
    raf.current = requestAnimationFrame(tick);
  }, [pulse]);

  useEffect(() => () => cancelAnimationFrame(raf.current), []);

  const label = STATE_LABELS[state];

  return (
    <div className="face" data-face={state}>
      <div className="face__ring" aria-hidden="true" />
      <svg className="face__svg" viewBox="0 0 300 300" role="img" aria-label={`Estado: ${label.es}`}>
        {/* Anillo de segmentos que gira (más rápido al pensar). */}
        <g className="face__spin">
          <circle cx="150" cy="150" r="128" className="face__seg" />
        </g>
        <g className="face__spin face__spin--rev">
          <circle cx="150" cy="150" r="114" className="face__seg face__seg--thin" />
        </g>

        {/* Marco hexagonal */}
        <polygon className="face__hex" points="150,40 245,95 245,205 150,260 55,205 55,95" />
        <polygon className="face__hex face__hex--inner" points="150,52 234,101 234,199 150,248 66,199 66,101" />

        {/* Ojos */}
        <g className="face__eyes">
          <polygon className="face__eye" points="92,122 136,122 132,138 96,138" />
          <polygon className="face__eye" points="164,122 208,122 204,138 168,138" />
        </g>
        {/* Barrido de escáner sobre los ojos (al pensar) */}
        <rect className="face__scan" x="88" y="116" width="124" height="2" />

        {/* Boca: barras tipo osciloscopio */}
        <g className="face__mouth">
          {PROFILE.map((_, i) => (
            <rect
              key={i}
              ref={(el) => {
                barsRef.current[i] = el;
              }}
              className="face__bar"
              x={102 + i * 9}
              y={170}
              width={5}
              height={44}
            />
          ))}
        </g>
      </svg>

      <div className="face__label">
        <span className="jp face__jp">{label.jp}</span>
        <span className="mono face__es">{label.es}</span>
      </div>
      {state === "error" && <div className="face__alert stripes stripes--red" aria-hidden="true" />}
    </div>
  );
}
