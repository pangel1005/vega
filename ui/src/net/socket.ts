/**
 * Conexión con el servidor que se recupera sola.
 *
 * Si la conexión se corta (cable USB, reinicio del servidor), vuelve a intentarlo
 * con una espera que crece: 0,5 s, 1 s, 2 s, 4 s… hasta 10 s como máximo, con un
 * poco de azar para no reintentar todos a la vez. Al conectar bien, la espera
 * vuelve a empezar desde 0,5 s.
 */

import { type ClientMessage, parseServerEvent, type ServerEvent } from "./protocol";

export type LinkStatus = "connecting" | "open" | "reconnecting" | "denied";

export interface SocketCallbacks {
  onEvent: (event: ServerEvent) => void;
  onStatus: (status: LinkStatus, retryInMs?: number) => void;
}

/** Lo mínimo que usamos de WebSocket; permite probar con un doble. */
export interface SocketLike {
  readyState: number;
  onopen: ((ev: Event) => void) | null;
  onmessage: ((ev: MessageEvent) => void) | null;
  onclose: ((ev: CloseEvent) => void) | null;
  onerror: ((ev: Event) => void) | null;
  send(data: string): void;
  close(code?: number): void;
}

export const BASE_DELAY_MS = 500;
export const MAX_DELAY_MS = 10_000;
const OPEN = 1;
/** Código que usa el servidor cuando el token es incorrecto: reintentar no sirve. */
const CLOSE_UNAUTHORIZED = 4401;

export function backoffDelay(attempt: number, random: () => number = Math.random): number {
  const exp = Math.min(MAX_DELAY_MS, BASE_DELAY_MS * 2 ** attempt);
  // ±20 % de azar
  return Math.round(exp * (0.8 + random() * 0.4));
}

export class AssistantSocket {
  private ws: SocketLike | null = null;
  private attempt = 0;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private stopped = false;

  constructor(
    private readonly url: string,
    private readonly token: string,
    private readonly callbacks: SocketCallbacks,
    private readonly factory: (url: string) => SocketLike = (u) => new WebSocket(u),
  ) {}

  start(): void {
    this.stopped = false;
    this.connect();
  }

  stop(): void {
    this.stopped = true;
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    this.ws?.close();
    this.ws = null;
  }

  /** Envía un mensaje si hay conexión; devuelve false si no se pudo. */
  send(message: ClientMessage): boolean {
    if (!this.ws || this.ws.readyState !== OPEN) return false;
    this.ws.send(JSON.stringify(message));
    return true;
  }

  private connect(): void {
    this.callbacks.onStatus(this.attempt === 0 ? "connecting" : "reconnecting");
    const ws = this.factory(this.url);
    this.ws = ws;

    ws.onopen = () => {
      ws.send(JSON.stringify({ type: "hello", token: this.token } satisfies ClientMessage));
    };
    ws.onmessage = (ev) => {
      const event = parseServerEvent(String(ev.data));
      if (!event) return;
      if (event.type === "ready") {
        this.attempt = 0;
        this.callbacks.onStatus("open");
      }
      this.callbacks.onEvent(event);
    };
    ws.onerror = () => {
      // El cierre llega justo después por onclose; ahí se decide qué hacer.
    };
    ws.onclose = (ev) => {
      if (this.ws !== ws) return; // cierre de una conexión vieja
      this.ws = null;
      if (this.stopped) return;
      if (ev.code === CLOSE_UNAUTHORIZED) {
        this.callbacks.onStatus("denied");
        return;
      }
      const delay = backoffDelay(this.attempt);
      this.attempt += 1;
      this.callbacks.onStatus("reconnecting", delay);
      this.timer = setTimeout(() => {
        this.timer = null;
        this.connect();
      }, delay);
    };
  }
}

/** Dirección del WebSocket en el mismo servidor que entregó la página. */
export function defaultSocketUrl(loc: Location = window.location): string {
  const scheme = loc.protocol === "https:" ? "wss" : "ws";
  return `${scheme}://${loc.host}/ws`;
}

export function readPageToken(doc: Document = document): string {
  return doc.querySelector<HTMLMetaElement>('meta[name="assistant-token"]')?.content ?? "";
}
