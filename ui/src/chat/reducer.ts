/**
 * Estado de la interfaz a partir de los eventos del servidor.
 * Función pura: fácil de probar sin navegador.
 */

import type { LinkStatus } from "../net/socket";
import type { AssistantState, ServerEvent } from "../net/protocol";

export interface Message {
  id: number;
  role: "user" | "assistant" | "error";
  text: string;
  /** true mientras la respuesta todavía está llegando. */
  streaming?: boolean;
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
  | { kind: "sent"; text: string };

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

function push(s: UiState, role: Message["role"], text: string, streaming = false): UiState {
  const msg: Message = streaming ? { id: s.nextId, role, text, streaming } : { id: s.nextId, role, text };
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
    case "token": {
      const open = lastStreaming(s);
      const next = open ? replaceLast(s, { ...open, text: open.text + e.text }) : push(s, "assistant", e.text, true);
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
