import { useCallback, useEffect, useReducer, useRef } from "react";

import { ChatPanel } from "./chat/ChatPanel";
import { initialState, reducer } from "./chat/reducer";
import { Face } from "./face/Face";
import { AssistantSocket, defaultSocketUrl, readPageToken } from "./net/socket";
import { Boot } from "./panel/Boot";
import { HexBackground } from "./panel/HexBackground";
import { StatusBar } from "./panel/StatusBar";
import "./panel/panel.css";
import "./app.css";

export function App() {
  const [ui, dispatch] = useReducer(reducer, initialState);
  const socket = useRef<AssistantSocket | null>(null);

  useEffect(() => {
    const s = new AssistantSocket(defaultSocketUrl(), readPageToken(), {
      onEvent: (event) => dispatch({ kind: "server", event }),
      onStatus: (status, retryInMs) =>
        dispatch(retryInMs === undefined ? { kind: "link", status } : { kind: "link", status, retryInMs }),
    });
    socket.current = s;
    s.start();
    return () => s.stop();
  }, []);

  useEffect(() => {
    if (ui.assistant) document.title = ui.assistant;
  }, [ui.assistant]);

  const send = useCallback((text: string) => {
    if (socket.current?.send({ type: "chat", text })) dispatch({ kind: "sent", text });
  }, []);
  const reset = useCallback(() => {
    socket.current?.send({ type: "reset" });
  }, []);

  const canSend = ui.link === "open";
  const busy = ui.state === "thinking" || ui.state === "speaking";
  // Sin conexión, el rostro muestra error aunque el último estado recibido fuera otro.
  const faceState = ui.link === "denied" ? "error" : ui.state;

  return (
    <div className="app" data-state={faceState}>
      <HexBackground state={faceState} />
      <div className="app__layout">
        <StatusBar assistant={ui.assistant} model={ui.model} link={ui.link} retryInMs={ui.retryInMs} />
        <main className="app__stage frame">
          <Face state={faceState} pulse={ui.tokenPulse} />
          {ui.link === "denied" && (
            <p className="app__denied mono">Token incorrecto: recarga la página desde el servidor.</p>
          )}
        </main>
        <ChatPanel
          assistant={ui.assistant}
          messages={ui.messages}
          canSend={canSend}
          busy={busy}
          onSend={send}
          onReset={reset}
        />
      </div>
      <div className="fx" aria-hidden="true" />
      <Boot name={ui.assistant} />
    </div>
  );
}
