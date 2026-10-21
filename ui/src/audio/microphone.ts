/**
 * Micrófono → bloques PCM16 a 16 kHz.
 *
 * El micrófono solo se abre mientras se graba (el indicador del sistema se apaga
 * al soltar el botón). Funciona en http://127.0.0.1 porque el navegador lo
 * considera un origen seguro.
 */

import workletUrl from "./capture-worklet.ts?worker&url";
import { TARGET_RATE } from "./pcm";

export interface AudioChunk {
  pcm: ArrayBuffer;
  /** Volumen del bloque, 0–1. */
  level: number;
}

export class MicrophoneError extends Error {}

export class Microphone {
  private stream: MediaStream | null = null;
  private ctx: AudioContext | null = null;

  get active(): boolean {
    return this.stream !== null;
  }

  async start(onChunk: (chunk: AudioChunk) => void): Promise<void> {
    if (!navigator.mediaDevices?.getUserMedia) {
      throw new MicrophoneError("Este navegador no permite usar el micrófono aquí.");
    }
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({
        audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
      });
    } catch (err) {
      const denied = err instanceof DOMException && err.name === "NotAllowedError";
      throw new MicrophoneError(
        denied ? "No tengo permiso para usar el micrófono. Actívalo en el candado de la barra de direcciones." : "No encuentro ningún micrófono.",
      );
    }

    // Pedimos 16 kHz directamente: el navegador remuestrea con buena calidad.
    // Si no lo admite, el worklet reduce la frecuencia él mismo.
    try {
      this.ctx = new AudioContext({ sampleRate: TARGET_RATE });
    } catch {
      this.ctx = new AudioContext();
    }
    await this.ctx.audioWorklet.addModule(workletUrl);
    const source = this.ctx.createMediaStreamSource(this.stream);
    const node = new AudioWorkletNode(this.ctx, "capture-processor");
    node.port.onmessage = (ev: MessageEvent<AudioChunk>) => onChunk(ev.data);
    // Conectado a la salida con volumen 0: algunos navegadores no procesan nodos «sueltos».
    const mute = this.ctx.createGain();
    mute.gain.value = 0;
    source.connect(node).connect(mute).connect(this.ctx.destination);
  }

  stop(): void {
    this.stream?.getTracks().forEach((t) => t.stop());
    this.stream = null;
    void this.ctx?.close();
    this.ctx = null;
  }
}
