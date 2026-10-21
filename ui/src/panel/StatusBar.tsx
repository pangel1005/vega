import { useEffect, useState } from "react";

import type { LinkStatus } from "../net/socket";

const LINK_TEXT: Record<LinkStatus, { jp: string; es: string }> = {
  connecting: { jp: "接続中", es: "Conectando" },
  open: { jp: "接続", es: "Enlace estable" },
  reconnecting: { jp: "再接続", es: "Reconectando" },
  denied: { jp: "拒否", es: "Acceso denegado" },
};

interface StatusBarProps {
  assistant: string;
  model: string;
  link: LinkStatus;
  retryInMs: number | null;
}

function useClock(): string {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), 15_000);
    return () => clearInterval(id);
  }, []);
  return now.toLocaleTimeString("es", { hour: "2-digit", minute: "2-digit" });
}

export function StatusBar({ assistant, model, link, retryInMs }: StatusBarProps) {
  const clock = useClock();
  const text = LINK_TEXT[link];
  const retry = link === "reconnecting" && retryInMs ? ` · ${Math.ceil(retryInMs / 1000)} s` : "";

  return (
    <header className="status">
      <div className="status__name">
        <span className="status__title">{assistant || "· · ·"}</span>
        <span className="mono status__sub">{model ? `Modelo ${model}` : "Sistema de asistencia"}</span>
      </div>
      <div className="status__right">
        <span className={`mono status__link status__link--${link}`}>
          <span className="status__dot" aria-hidden="true" />
          <span className="jp">{text.jp}</span> {text.es}
          {retry}
        </span>
        <span className="mono status__clock">{clock}</span>
      </div>
    </header>
  );
}
