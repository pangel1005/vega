import { describe, expect, it } from "vitest";

import { downsample, floatToPcm16, rms } from "./pcm";

describe("downsample", () => {
  it("48 kHz → 16 kHz deja un tercio de las muestras, promediadas", () => {
    const input = new Float32Array([0, 0.3, 0.6, 1, 1, 1]);
    expect(Array.from(downsample(input, 48_000))).toEqual([expect.closeTo(0.3), 1]);
  });

  it("no toca el audio si ya está a 16 kHz", () => {
    const input = new Float32Array([0.1, 0.2]);
    expect(downsample(input, 16_000)).toBe(input);
  });

  it("44,1 kHz (ratio no entero) produce la duración correcta", () => {
    const oneSecond = new Float32Array(44_100).fill(0.5);
    const out = downsample(oneSecond, 44_100);
    expect(out.length).toBe(16_000);
    expect(out.every((v) => Math.abs(v - 0.5) < 1e-6)).toBe(true);
  });
});

describe("floatToPcm16", () => {
  it("convierte y recorta al rango de 16 bits", () => {
    expect(Array.from(floatToPcm16(new Float32Array([0, 1, -1, 2, -2, 0.5])))).toEqual([
      0, 32767, -32768, 32767, -32768, 16384,
    ]);
  });
});

describe("rms", () => {
  it("vale 0 en silencio y 1 en señal máxima", () => {
    expect(rms(new Float32Array(10))).toBe(0);
    expect(rms(new Float32Array([1, -1, 1, -1]))).toBe(1);
    expect(rms(new Float32Array())).toBe(0);
  });
});
