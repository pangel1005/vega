/**
 * AudioWorklet de captura: corre en el hilo de audio del navegador (no traba la
 * interfaz). Junta muestras, las pasa a 16 kHz y PCM16, y cada ~100 ms envía un
 * bloque al hilo principal junto con su volumen.
 */

import { downsample, floatToPcm16, rms, TARGET_RATE } from "./pcm";

// Tipos mínimos del ámbito de AudioWorklet (TypeScript no los incluye en "DOM").
declare const sampleRate: number;
declare class AudioWorkletProcessor {
  readonly port: MessagePort;
}
declare function registerProcessor(name: string, ctor: new () => AudioWorkletProcessor): void;

const CHUNK_SAMPLES = TARGET_RATE / 10; // 100 ms

class CaptureProcessor extends AudioWorkletProcessor {
  private pending: number[] = [];

  process(inputs: Float32Array[][]): boolean {
    const channel = inputs[0]?.[0];
    if (!channel) return true;
    const resampled = downsample(channel, sampleRate);
    for (const v of resampled) this.pending.push(v);
    while (this.pending.length >= CHUNK_SAMPLES) {
      const block = Float32Array.from(this.pending.splice(0, CHUNK_SAMPLES));
      const pcm = floatToPcm16(block);
      this.port.postMessage({ pcm: pcm.buffer, level: rms(block) }, [pcm.buffer]);
    }
    return true;
  }
}

registerProcessor("capture-processor", CaptureProcessor);
