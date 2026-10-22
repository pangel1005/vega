import { useCallback, useEffect, useRef, useState } from "react";

import type { AssistantSocket } from "../net/socket";
import { Microphone, MicrophoneError } from "./microphone";

export type VoiceStatus = "off" | "starting" | "recording";

interface VoiceOptions {
  socket: React.RefObject<AssistantSocket | null>;
  onLevel: (level: number) => void;
  onError: (message: string) => void;
}

/** Une micrófono y WebSocket: listen_start → audio binario → listen_stop. */
export function useVoice({ socket, onLevel, onError }: VoiceOptions) {
  const [status, setStatus] = useState<VoiceStatus>("off");
  const mic = useRef<Microphone | null>(null);
  // Si se suelta el botón mientras el micrófono aún se abre, se para en cuanto esté listo.
  const stopRequested = useRef(false);

  const finish = useCallback(
    (kind: "listen_stop" | "listen_cancel") => {
      mic.current?.stop();
      mic.current = null;
      socket.current?.send({ type: kind });
      setStatus("off");
    },
    [socket],
  );

  const start = useCallback(async () => {
    if (mic.current || !socket.current?.send({ type: "listen_start" })) return;
    stopRequested.current = false;
    setStatus("starting");
    const m = new Microphone();
    mic.current = m;
    try {
      await m.start((chunk) => {
        socket.current?.sendAudio(chunk.pcm);
        onLevel(chunk.level);
      });
    } catch (err) {
      mic.current = null;
      socket.current?.send({ type: "listen_cancel" });
      setStatus("off");
      onError(err instanceof MicrophoneError ? err.message : "No pude abrir el micrófono.");
      return;
    }
    if (stopRequested.current) finish("listen_stop");
    else setStatus("recording");
  }, [socket, onLevel, onError, finish]);

  const stop = useCallback(() => {
    if (!mic.current) return;
    if (!mic.current.active) {
      stopRequested.current = true; // todavía abriéndose
      return;
    }
    finish("listen_stop");
  }, [finish]);

  const cancel = useCallback(() => {
    if (mic.current) finish("listen_cancel");
  }, [finish]);

  // Al desmontar, nunca dejar el micrófono abierto.
  useEffect(() => () => mic.current?.stop(), []);

  return { status, start, stop, cancel };
}
