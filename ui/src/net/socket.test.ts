import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { AssistantSocket, backoffDelay, BASE_DELAY_MS, type LinkStatus, MAX_DELAY_MS, type SocketLike } from "./socket";

class FakeSocket implements SocketLike {
  readyState = 0;
  sent: string[] = [];
  onopen: ((ev: Event) => void) | null = null;
  onmessage: ((ev: MessageEvent) => void) | null = null;
  onclose: ((ev: CloseEvent) => void) | null = null;
  onerror: ((ev: Event) => void) | null = null;
  send(data: string) {
    this.sent.push(data);
  }
  close() {}
  // Ayudas para la prueba
  open() {
    this.readyState = 1;
    this.onopen?.(new Event("open"));
  }
  receive(data: unknown) {
    this.onmessage?.({ data: JSON.stringify(data) } as MessageEvent);
  }
  drop(code = 1006) {
    this.readyState = 3;
    this.onclose?.({ code } as CloseEvent);
  }
}

describe("backoffDelay", () => {
  it("crece al doble y se detiene en el máximo", () => {
    const mid = () => 0.5; // sin azar
    expect(backoffDelay(0, mid)).toBe(BASE_DELAY_MS);
    expect(backoffDelay(1, mid)).toBe(BASE_DELAY_MS * 2);
    expect(backoffDelay(3, mid)).toBe(BASE_DELAY_MS * 8);
    expect(backoffDelay(20, mid)).toBe(MAX_DELAY_MS);
  });

  it("añade como mucho ±20 % de azar", () => {
    expect(backoffDelay(2, () => 0)).toBe(1600);
    expect(backoffDelay(2, () => 1)).toBe(2400);
  });
});

describe("AssistantSocket", () => {
  let sockets: FakeSocket[];
  let statuses: LinkStatus[];
  let events: unknown[];
  let client: AssistantSocket;

  const sock = (i: number): FakeSocket => {
    const s = sockets[i];
    if (!s) throw new Error(`no existe el socket ${i}`);
    return s;
  };

  beforeEach(() => {
    vi.useFakeTimers();
    sockets = [];
    statuses = [];
    events = [];
    client = new AssistantSocket(
      "ws://x/ws",
      "secreto",
      { onEvent: (e) => events.push(e), onStatus: (s) => statuses.push(s) },
      () => {
        const s = new FakeSocket();
        sockets.push(s);
        return s;
      },
    );
  });

  afterEach(() => {
    client.stop();
    vi.useRealTimers();
  });

  it("envía el token al abrir y queda abierto con ready", () => {
    client.start();
    sock(0).open();
    expect(JSON.parse(sock(0).sent[0] ?? "")).toEqual({ type: "hello", token: "secreto" });
    sock(0).receive({ type: "ready", assistant: "Vega", model: "m" });
    expect(statuses).toEqual(["connecting", "open"]);
    expect(events).toEqual([{ type: "ready", assistant: "Vega", model: "m" }]);
  });

  it("se reconecta solo con esperas crecientes y las reinicia al recuperarse", () => {
    client.start();
    sock(0).drop();
    expect(statuses.at(-1)).toBe("reconnecting");
    vi.advanceTimersByTime(BASE_DELAY_MS * 1.2);
    expect(sockets).toHaveLength(2);

    sock(1).drop();
    vi.advanceTimersByTime(BASE_DELAY_MS * 1.2); // aún no: la segunda espera es el doble
    expect(sockets).toHaveLength(2);
    vi.advanceTimersByTime(BASE_DELAY_MS * 1.2);
    expect(sockets).toHaveLength(3);

    sock(2).open();
    sock(2).receive({ type: "ready", assistant: "Vega", model: "m" });
    sock(2).drop();
    vi.advanceTimersByTime(BASE_DELAY_MS * 1.2); // vuelve a la espera corta
    expect(sockets).toHaveLength(4);
  });

  it("no reintenta si el token es incorrecto", () => {
    client.start();
    sock(0).drop(4401);
    vi.advanceTimersByTime(MAX_DELAY_MS * 2);
    expect(sockets).toHaveLength(1);
    expect(statuses.at(-1)).toBe("denied");
  });

  it("no envía nada sin conexión y deja de reintentar al parar", () => {
    client.start();
    expect(client.send({ type: "chat", text: "hola" })).toBe(false);
    sock(0).drop();
    client.stop();
    vi.advanceTimersByTime(MAX_DELAY_MS * 2);
    expect(sockets).toHaveLength(1);
  });

  it("ignora mensajes que no son eventos válidos", () => {
    client.start();
    sock(0).onmessage?.({ data: "basura" } as MessageEvent);
    sock(0).receive({ type: "desconocido" });
    expect(events).toEqual([]);
  });
});
