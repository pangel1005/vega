import { describe, expect, it } from "vitest";

import type { ServerEvent } from "../net/protocol";
import { initialState, reducer, type UiAction, type UiState } from "./reducer";

const server = (event: ServerEvent): UiAction => ({ kind: "server", event });
const run = (actions: UiAction[], start: UiState = initialState) => actions.reduce(reducer, start);

describe("reducer", () => {
  it("arma la respuesta trozo a trozo y la cierra con done", () => {
    const s = run([
      { kind: "sent", text: "hola" },
      server({ type: "state", state: "thinking" }),
      server({ type: "token", text: "Ho" }),
      server({ type: "token", text: "la" }),
    ]);
    expect(s.messages.map((m) => [m.role, m.text, !!m.streaming])).toEqual([
      ["user", "hola", false],
      ["assistant", "Hola", true],
    ]);
    expect(s.tokenPulse).toBe(2);

    const done = reducer(s, server({ type: "done", text: "Hola." }));
    expect(done.messages.at(-1)).toEqual({ id: 2, role: "assistant", text: "Hola." });
  });

  it("carga el historial al conectar, sin mensajes de sistema", () => {
    const s = run([
      { kind: "sent", text: "viejo local" },
      server({
        type: "history",
        messages: [
          { role: "system", content: "x" },
          { role: "user", content: "a" },
          { role: "assistant", content: "b" },
        ],
      }),
    ]);
    expect(s.messages.map((m) => m.text)).toEqual(["a", "b"]);
  });

  it("muestra los errores como mensajes", () => {
    const s = reducer(initialState, server({ type: "error", message: "Ollama apagado" }));
    expect(s.messages).toEqual([{ id: 1, role: "error", text: "Ollama apagado" }]);
  });

  it("cierra la respuesta a medias si se corta la conexión", () => {
    const s = run([server({ type: "token", text: "Medio" }), { kind: "link", status: "reconnecting", retryInMs: 500 }]);
    expect(s.messages.at(-1)?.streaming).toBeFalsy();
    expect(s.link).toBe("reconnecting");
    expect(s.retryInMs).toBe(500);
  });

  it("guarda nombre y modelo al recibir ready", () => {
    const s = reducer(initialState, server({ type: "ready", assistant: "Vega", model: "qwen3.5:4b" }));
    expect([s.assistant, s.model]).toEqual(["Vega", "qwen3.5:4b"]);
  });
});
