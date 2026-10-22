/**
 * Utilidades de audio puras (sin navegador): se usan dentro del AudioWorklet y se
 * prueban con Vitest.
 */

export const TARGET_RATE = 16_000;

/** Reduce la frecuencia de muestreo promediando bloques (sirve de filtro paso bajo sencillo). */
export function downsample(input: Float32Array, inputRate: number, outputRate = TARGET_RATE): Float32Array {
  if (inputRate === outputRate) return input;
  if (inputRate < outputRate) throw new Error(`No se puede subir de ${inputRate} a ${outputRate} Hz`);
  const ratio = inputRate / outputRate;
  const length = Math.floor(input.length / ratio);
  const out = new Float32Array(length);
  for (let i = 0; i < length; i++) {
    const start = Math.floor(i * ratio);
    const end = Math.min(input.length, Math.floor((i + 1) * ratio));
    let sum = 0;
    for (let j = start; j < end; j++) sum += input[j] ?? 0;
    out[i] = sum / Math.max(1, end - start);
  }
  return out;
}

/** Float32 [-1, 1] → PCM de 16 bits con signo (little-endian al enviarlo). */
export function floatToPcm16(input: Float32Array): Int16Array {
  const out = new Int16Array(input.length);
  for (let i = 0; i < input.length; i++) {
    const s = Math.max(-1, Math.min(1, input[i] ?? 0));
    out[i] = s < 0 ? Math.round(s * 0x8000) : Math.round(s * 0x7fff);
  }
  return out;
}

/** Volumen eficaz (RMS) entre 0 y 1. */
export function rms(input: Float32Array): number {
  if (input.length === 0) return 0;
  let sum = 0;
  for (let i = 0; i < input.length; i++) {
    const v = input[i] ?? 0;
    sum += v * v;
  }
  return Math.sqrt(sum / input.length);
}
