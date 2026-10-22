import { type FormEvent, useEffect, useRef, useState } from "react";

import type { Message } from "./reducer";
import "./chat.css";

interface ChatPanelProps {
  assistant: string;
  messages: Message[];
  canSend: boolean;
  busy: boolean;
  onSend: (text: string) => void;
  onReset: () => void;
}

export function ChatPanel({ assistant, messages, canSend, busy, onSend, onReset }: ChatPanelProps) {
  const [text, setText] = useState("");
  const logRef = useRef<HTMLDivElement>(null);

  // Mantiene a la vista el último mensaje mientras llega la respuesta.
  useEffect(() => {
    const log = logRef.current;
    if (log) log.scrollTop = log.scrollHeight;
  }, [messages]);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const clean = text.trim();
    if (!clean || !canSend || busy) return;
    onSend(clean);
    setText("");
  };

  return (
    <section className="chat frame" aria-label="Conversación">
      <header className="chat__head">
        <span className="mono">Registro</span>
        <span className="jp chat__head-jp">交信記録</span>
        <button type="button" className="btn btn--ghost chat__reset" onClick={onReset} disabled={!canSend || busy}>
          Nueva
        </button>
      </header>

      <div className="chat__log" ref={logRef} aria-live="polite">
        {messages.length === 0 && <p className="chat__empty mono">Sin mensajes. Escribe para empezar.</p>}
        {messages.map((m) => (
          <article key={m.id} className={`msg msg--${m.role}`}>
            <span className="msg__who mono">
              {m.role === "user"
                ? "Tú"
                : m.role === "error"
                  ? "非常事態 · Error"
                  : m.role === "note"
                    ? "Aviso"
                    : assistant || "Asistente"}
              {m.meta && <span className="msg__meta"> · {m.meta}</span>}
            </span>
            <p className="msg__text">
              {m.text}
              {m.streaming && <span className="msg__cursor" aria-hidden="true" />}
            </p>
          </article>
        ))}
      </div>

      <form className="chat__form" onSubmit={submit}>
        <input
          className="chat__input"
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder={canSend ? `Escribe a ${assistant || "tu asistente"}…` : "Sin conexión…"}
          maxLength={4000}
          enterKeyHint="send"
          aria-label="Mensaje"
        />
        <button className="btn" type="submit" disabled={!canSend || busy || !text.trim()}>
          Enviar
        </button>
      </form>
    </section>
  );
}
