import { useEffect, useState } from "react";

const SEEN_KEY = "boot-seen";
const DURATION_MS = 1600;

function alreadySeen(): boolean {
  try {
    return sessionStorage.getItem(SEEN_KEY) === "1";
  } catch {
    return false;
  }
}

/** Arranque corto estilo NERV. Una vez por sesión; se salta tocando la pantalla. */
export function Boot({ name }: { name: string }) {
  const [visible, setVisible] = useState(() => !alreadySeen());
  const [leaving, setLeaving] = useState(false);

  useEffect(() => {
    if (!visible) return;
    const leave = setTimeout(() => setLeaving(true), DURATION_MS);
    return () => clearTimeout(leave);
  }, [visible]);

  useEffect(() => {
    if (!leaving) return;
    try {
      sessionStorage.setItem(SEEN_KEY, "1");
    } catch {
      // sin almacenamiento: se verá de nuevo, no pasa nada
    }
    const hide = setTimeout(() => setVisible(false), 500);
    return () => clearTimeout(hide);
  }, [leaving]);

  if (!visible) return null;
  return (
    <div className={`boot${leaving ? " boot--leave" : ""}`} onClick={() => setLeaving(true)} role="presentation">
      <div className="boot__band stripes" />
      <div className="boot__core">
        <span className="jp boot__jp">起動</span>
        <span className="boot__name">{name || "SISTEMA"}</span>
        <span className="mono boot__sub">Iniciando enlace · toca para saltar</span>
        <span className="boot__bar">
          <span className="boot__fill" />
        </span>
      </div>
      <div className="boot__band stripes" />
    </div>
  );
}
