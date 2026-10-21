/**
 * Mensajes del WebSocket `/ws`. Deben coincidir con `server/ws/protocol.py`.
 */

export type AssistantState = "idle" | "listening" | "thinking" | "speaking" | "acting" | "error";

export interface HistoryItem {
  role: "user" | "assistant" | "system";
  content: string;
}

export type ServerEvent =
  | { type: "ready"; assistant: string; model: string }
  | { type: "history"; messages: HistoryItem[] }
  | { type: "state"; state: AssistantState }
  | { type: "token"; text: string }
  | { type: "done"; text: string }
  | { type: "error"; message: string };

export type ClientMessage =
  | { type: "hello"; token: string }
  | { type: "chat"; text: string }
  | { type: "reset" };

const SERVER_TYPES = new Set(["ready", "history", "state", "token", "done", "error"]);

/** Convierte el texto recibido en un evento; devuelve null si no es válido. */
export function parseServerEvent(raw: string): ServerEvent | null {
  try {
    const data: unknown = JSON.parse(raw);
    if (typeof data === "object" && data !== null && SERVER_TYPES.has((data as { type?: string }).type ?? "")) {
      return data as ServerEvent;
    }
  } catch {
    // texto que no es JSON: se ignora
  }
  return null;
}
