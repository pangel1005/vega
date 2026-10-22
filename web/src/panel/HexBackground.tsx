import { useEffect, useRef } from "react";

import type { AssistantState } from "../net/protocol";

const R = 28; // radio del hexágono
const FPS_MS = 33; // ~30 cuadros por segundo: de sobra para un fondo y ahorra batería
const PULSES = 7;

/** Rejilla de hexágonos con celdas que se encienden al azar (estilo NERV). */
export function HexBackground({ state }: { state: AssistantState }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const stateRef = useRef(state);

  useEffect(() => {
    stateRef.current = state;
  }, [state]);

  useEffect(() => {
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext("2d");
    if (!canvas || !ctx) return;

    const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const w = R * Math.sqrt(3);
    const h = R * 1.5;
    let grid: HTMLCanvasElement | null = null;
    let cells: [number, number][] = [];
    let width = 0;
    let height = 0;
    let dpr = 1;
    let color = "#ff7a18";
    const pulses = Array.from({ length: PULSES }, () => ({ cell: 0, t: Math.random() }));

    const hexPath = (c: CanvasRenderingContext2D, x: number, y: number, r: number) => {
      c.beginPath();
      for (let i = 0; i < 6; i++) {
        const a = (Math.PI / 3) * i + Math.PI / 6;
        c.lineTo(x + r * Math.cos(a), y + r * Math.sin(a));
      }
      c.closePath();
    };

    const readColor = () => {
      color = getComputedStyle(canvas).getPropertyValue("--acc").trim() || "#ff7a18";
    };

    // La rejilla fija se dibuja una vez en un canvas aparte; cada cuadro solo la copia.
    const build = () => {
      dpr = Math.min(window.devicePixelRatio || 1, 2);
      width = window.innerWidth;
      height = window.innerHeight;
      canvas.width = width * dpr;
      canvas.height = height * dpr;
      readColor();
      // Ventana sin tamaño (pestaña oculta o minimizada): no hay nada que dibujar.
      if (width === 0 || height === 0) {
        grid = null;
        return;
      }
      grid = document.createElement("canvas");
      grid.width = canvas.width;
      grid.height = canvas.height;
      const g = grid.getContext("2d");
      if (!g) return;
      g.scale(dpr, dpr);
      g.strokeStyle = color;
      g.globalAlpha = 0.07;
      g.lineWidth = 1;
      cells = [];
      for (let row = -1; row * h < height + R; row++) {
        for (let col = -1; col * w < width + w; col++) {
          const x = col * w + (row % 2 ? w / 2 : 0);
          const y = row * h;
          cells.push([x, y]);
          hexPath(g, x, y, R - 1);
          g.stroke();
        }
      }
      for (const p of pulses) p.cell = Math.floor(Math.random() * cells.length);
    };

    let raf = 0;
    let last = 0;
    let lastState = stateRef.current;
    const frame = (now: number) => {
      raf = requestAnimationFrame(frame);
      if (now - last < FPS_MS) return;
      const dt = last ? (now - last) / 1000 : 0;
      last = now;
      if (stateRef.current !== lastState) {
        lastState = stateRef.current;
        build(); // el color de acento cambió con el estado
      }
      if (!grid) return;
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      ctx.drawImage(grid, 0, 0);
      ctx.scale(dpr, dpr);
      ctx.fillStyle = color;
      // Pensando: las celdas laten más rápido.
      const speed = lastState === "thinking" ? 0.9 : lastState === "speaking" ? 0.6 : 0.3;
      for (const p of pulses) {
        p.t += dt * speed;
        if (p.t >= 1) {
          p.t = 0;
          p.cell = Math.floor(Math.random() * cells.length);
        }
        const cell = cells[p.cell];
        if (!cell) continue;
        ctx.globalAlpha = Math.sin(p.t * Math.PI) * 0.16;
        hexPath(ctx, cell[0], cell[1], R - 3);
        ctx.fill();
      }
      ctx.globalAlpha = 1;
    };

    const start = () => {
      cancelAnimationFrame(raf);
      last = 0;
      if (reduced) {
        if (grid) ctx.drawImage(grid, 0, 0); // movimiento reducido: rejilla fija, sin animar
      } else {
        raf = requestAnimationFrame(frame);
      }
    };
    const onVisibility = () => (document.hidden ? cancelAnimationFrame(raf) : start());
    let resizeTimer: ReturnType<typeof setTimeout> | undefined;
    const onResize = () => {
      clearTimeout(resizeTimer);
      resizeTimer = setTimeout(() => {
        build();
        start();
      }, 150);
    };

    build();
    start();
    document.addEventListener("visibilitychange", onVisibility);
    window.addEventListener("resize", onResize);
    return () => {
      cancelAnimationFrame(raf);
      clearTimeout(resizeTimer);
      document.removeEventListener("visibilitychange", onVisibility);
      window.removeEventListener("resize", onResize);
    };
  }, []);

  return <canvas ref={canvasRef} className="hex-bg" aria-hidden="true" />;
}
