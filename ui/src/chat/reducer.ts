/**
 * Estado de la interfaz a partir de los eventos del servidor.
 * Función pura: fácil de probar sin navegador.
 */

import type { LinkStatus } from "../net/socket";
import type { AssistantState, ServerEvent } from "../net/protocol";

export interface Message {
  id: number;
  /** note: aviso suave del sistema (p. ej. «no te he oído»). */
  role: "user" | "assistant" | "error" | "note";
  text: string;
  /** true mientras la respuesta todavía está llegando. */
  streaming?: boolean;
  /** Dato extra que se muestra junto al autor (p. ej. «voz · 0,18 s»). */
  meta?: string;
}

export interface UiState {
  link: LinkStatus;
  retryInMs: number | null;
  assistant: string;
  model: string;
  state: AssistantState;
  messages: Message[];
  nextId: number;
  /** Cuenta de trozos recibidos; el rostro la usa para «mover la boca» (hasta tener audio). */
  tokenPulse: number;
}

export type UiAction =
  | { kind: "server"; event: ServerEvent }
  | { kind: "link"; status: LinkStatus; retryInMs?: number }
  | { kind: "sent"; text: string }
  | { kind: "local_error"; text: string };

export const NOT_HEARD = "No te he oído. Prueba a hablar un poco más cerca del micrófono.";

function seconds(ms: number): string {
  return `${(ms / 1000).toFixed(2).replace(".", ",")} s`;
}

export const initialState: UiState = {
  link: "connecting",
  retryInMs: null,
  assistant: "",
  model: "",
  state: "idle",
  messages: [],
  nextId: 1,
  tokenPulse: 0,
};

function push(s: UiState, role: Message["role"], text: string, extra: Partial<Message> = {}): UiState {
  const msg: Message = { id: s.nextId, role, text, ...extra };
  return { ...s, messages: [...s.messages, msg], nextId: s.nextId + 1 };
}

function lastStreaming(s: UiState): Message | undefined {
  const last = s.messages.at(-1);
  return last?.streaming ? last : undefined;
}

function replaceLast(s: UiState, msg: Message): UiState {
  return { ...s, messages: [...s.messages.slice(0, -1), msg] };
}

export function reducer(s: UiState, action: UiAction): UiState {
  if (action.kind === "link") {
    const next = { ...s, link: action.status, retryInMs: action.retryInMs ?? null };
    // Si se corta a mitad de una respuesta, se cierra lo que había llegado.
    const open = lastStreaming(next);
    return action.status === "open" || !open ? next : replaceLast(next, { ...open, streaming: false });
  }
  if (action.kind === "sent") {
    return push(s, "user", action.text);
  }
  if (action.kind === "local_error") {
    return push(s, "error", action.text);
  }

  const e = action.event;
  switch (e.type) {
    case "ready":
      return { ...s, assistant: e.assistant, model: e.model };
    case "history": {
      let next: UiState = { ...s, messages: [] };
      for (const m of e.messages) {
        if (m.role !== "system") next = push(next, m.role, m.content);
      }
      return next;
    }
    case "state":
      return { ...s, state: e.state };
    case "transcript":
      return e.text
        ? push(s, "user", e.text, { meta: `voz · ${seconds(e.elapsed_ms)}` })
        : push(s, "note", NOT_HEARD);
    case "token": {
      const open = lastStreaming(s);
      const next = open
        ? replaceLast(s, { ...open, text: open.text + e.text })
        : push(s, "assistant", e.text, { streaming: true });
      return { ...next, tokenPulse: s.tokenPulse + 1 };
    }
    case "done": {
      const open = lastStreaming(s);
      return open ? replaceLast(s, { id: open.id, role: "assistant", text: e.text }) : push(s, "assistant", e.text);
    }
    case "error":
      return push(s, "error", e.message);
  }
}
