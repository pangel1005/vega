package com.asistente.personal.local

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Decide si una frase puede ser una llamada a Vega, antes de gastar Whisper en ella. */
fun interface Gate {
    /** `window`: el principio de la frase (hasta 2,4 s) en muestras entre −1 y 1. */
    fun passes(window: FloatArray): Boolean

    /** Probabilidad de la última frase (NaN si no se sabe). Solo para el registro. */
    val lastScore: Float get() = Float.NaN
}

/**
 * Filtro barato de «Vega»: una red convolucional pequeña (~60 000 pesos, unos 14 millones de
 * operaciones, ~0,15 s en el Redmi) que mira los primeros 2,4 s de una frase y dice si empieza
 * llamando a Vega. Sin ella, cada frase que oye el micrófono (la tele, una conversación) pasaba
 * por Whisper (~2 s de procesador a 4 hilos) solo para descartarla.
 *
 * Se entrena en el PC con `tools/wake/train.py`, que escribe `assets/wake_gate.bin`. Los rasgos
 * (log-mel) y la red están copiados de ese script: una prueba comprueba que dan lo mismo.
 * Está ajustada para dejar pasar casi todas las llamadas reales; un falso positivo solo cuesta un
 * Whisper de más.
 */
class WakeGate(weights: ByteArray) : Gate {
    val threshold: Float
    private val w1: FloatArray
    private val b1: FloatArray
    private val w2: FloatArray
    private val b2: FloatArray
    private val w3: FloatArray
    private val b3: FloatArray
    private val t1w: FloatArray
    private val t1b: FloatArray
    private val t2w: FloatArray
    private val t2b: FloatArray
    private val fcW: FloatArray
    private val fcB: Float

    init {
        val buf = ByteBuffer.wrap(weights).order(ByteOrder.LITTLE_ENDIAN)
        require(buf.int == MAGIC) { "wake_gate.bin no es un filtro de Vega" }
        threshold = buf.float
        fun take(n: Int) = FloatArray(n).also { buf.asFloatBuffer().get(it); buf.position(buf.position() + n * 4) }
        w1 = take(CH1 * 1 * 9)
        b1 = take(CH1)
        w2 = take(CH2 * CH1 * 9)
        b2 = take(CH2)
        w3 = take(CH3 * CH2 * 9)
        b3 = take(CH3)
        t1w = take(TC * CH3 * ROWS * 5)
        t1b = take(TC)
        t2w = take(TC * TC * 5)
        t2b = take(TC)
        fcW = take(TC)
        fcB = take(1)[0]
        require(!buf.hasRemaining()) { "wake_gate.bin tiene datos de más" }
    }

    @Volatile override var lastScore: Float = Float.NaN
        private set

    override fun passes(window: FloatArray): Boolean {
        val p = probability(window)
        lastScore = p
        return p >= threshold
    }

    fun probability(window: FloatArray): Float = (1.0 / (1.0 + exp(-logit(window).toDouble()))).toFloat()

    /** La salida de la red antes de la sigmoide. */
    fun logit(window: FloatArray): Float {
        val a = layer(logMel(window), 1, MELS, FRAMES, w1, b1, CH1, 2, 2) // 12 × 20 × 119
        val b = layer(a, CH1, MELS / 2, FRAMES / 2, w2, b2, CH2, 2, 2) // 24 × 10 × 59
        val c = layer(b, CH2, MELS / 4, FRAMES / 4, w3, b3, CH3, 2, 1) // 32 × 5 × 59 = 160 filas × 59 instantes
        val t = FRAMES / 4
        val d = conv1d(c, CH3 * ROWS, t, t1w, t1b, TC, pad = 2, dilation = 1)
        val e = conv1d(d, TC, t, t2w, t2b, TC, pad = 4, dilation = 2)
        var out = fcB
        for (ch in 0 until TC) {
            // el máximo de cada canal a lo largo del tiempo: «¿sonó “Vega” en algún momento?»
            var m = -Float.MAX_VALUE
            for (i in 0 until t) m = max(m, e[ch * t + i])
            out += m * fcW[ch]
        }
        return out
    }

    /** Convolución 3×3 con relleno, ReLU y máximo `ph`×`pw` (se descarta lo que sobra al final). */
    private fun layer(inp: FloatArray, cin: Int, h: Int, w: Int, wt: FloatArray, bias: FloatArray, cout: Int, ph: Int, pw: Int): FloatArray {
        val conv = FloatArray(cout * h * w)
        for (co in 0 until cout) {
            val base = co * h * w
            java.util.Arrays.fill(conv, base, base + h * w, bias[co])
            for (ci in 0 until cin) {
                for (dy in 0..2) {
                    for (dx in 0..2) {
                        val wv = wt[((co * cin + ci) * 3 + dy) * 3 + dx]
                        if (wv == 0f) continue
                        val x0 = max(0, 1 - dx)
                        val x1 = min(w, w + 1 - dx)
                        for (y in max(0, 1 - dy) until min(h, h + 1 - dy)) {
                            val src = (ci * h + y + dy - 1) * w + dx - 1
                            val dst = base + y * w
                            for (x in x0 until x1) conv[dst + x] += wv * inp[src + x]
                        }
                    }
                }
            }
        }
        val oh = h / ph
        val ow = w / pw
        val out = FloatArray(cout * oh * ow)
        for (c in 0 until cout) {
            for (y in 0 until oh) {
                for (x in 0 until ow) {
                    var m = 0f // ReLU y máximo se pueden intercambiar: empezar en 0 hace las dos cosas
                    for (j in 0 until ph) {
                        for (i in 0 until pw) m = max(m, conv[(c * h + y * ph + j) * w + x * pw + i])
                    }
                    out[(c * oh + y) * ow + x] = m
                }
            }
        }
        return out
    }

    /** Convolución en el tiempo (núcleo de 5, con relleno y salto) y ReLU. */
    private fun conv1d(inp: FloatArray, cin: Int, t: Int, wt: FloatArray, bias: FloatArray, cout: Int, pad: Int, dilation: Int): FloatArray {
        val out = FloatArray(cout * t)
        for (co in 0 until cout) {
            val base = co * t
            java.util.Arrays.fill(out, base, base + t, bias[co])
            for (ci in 0 until cin) {
                for (k in 0 until 5) {
                    val wv = wt[(co * cin + ci) * 5 + k]
                    if (wv == 0f) continue
                    val shift = k * dilation - pad
                    for (x in max(0, -shift) until min(t, t - shift)) out[base + x] += wv * inp[ci * t + x + shift]
                }
            }
            for (x in 0 until t) if (out[base + x] < 0f) out[base + x] = 0f
        }
        return out
    }

    companion object {
        const val MAGIC = 0x57474154
        const val RATE = 16_000
        const val WINDOW = 38_400 // 2,4 s
        private const val FRAME = 400
        private const val HOP = 160
        private const val NFFT = 512
        private const val MELS = 40
        private const val FRAMES = (WINDOW - FRAME) / HOP + 1 // 238
        private const val CH1 = 12
        private const val CH2 = 24
        private const val CH3 = 32
        private const val ROWS = MELS / 8 // filas de frecuencia que quedan tras los tres recortes
        private const val TC = 48

        private val hann = FloatArray(FRAME) { (0.5 - 0.5 * cos(2.0 * PI * it / FRAME)).toFloat() }
        private val filters: Array<FloatArray> = melFilters()
        private val cosT = DoubleArray(NFFT / 2) { cos(2.0 * PI * it / NFFT) }
        private val sinT = DoubleArray(NFFT / 2) { -sin(2.0 * PI * it / NFFT) }

        private fun hzToMel(f: Double) = 2595.0 * log10(1.0 + f / 700.0)
        private fun melToHz(m: Double) = 700.0 * (Math.pow(10.0, m / 2595.0) - 1.0)

        private fun melFilters(): Array<FloatArray> {
            val lo = hzToMel(60.0)
            val hi = hzToMel(7600.0)
            val pts = DoubleArray(MELS + 2) { melToHz(lo + (hi - lo) * it / (MELS + 1)) }
            return Array(MELS) { i ->
                FloatArray(NFFT / 2 + 1) { k ->
                    val f = k * RATE.toDouble() / NFFT
                    val up = (f - pts[i]) / (pts[i + 1] - pts[i])
                    val down = (pts[i + 2] - f) / (pts[i + 2] - pts[i + 1])
                    max(0.0, min(up, down)).toFloat()
                }
            }
        }

        /** Rasgos log-mel (40 × 238), con la media restada: no dependen del volumen. */
        fun logMel(samples: FloatArray): FloatArray {
            val x = FloatArray(WINDOW)
            System.arraycopy(samples, 0, x, 0, min(samples.size, WINDOW))
            val out = FloatArray(MELS * FRAMES)
            val re = DoubleArray(NFFT)
            val im = DoubleArray(NFFT)
            val power = DoubleArray(NFFT / 2 + 1)
            var sum = 0.0
            for (f in 0 until FRAMES) {
                for (i in 0 until NFFT) {
                    re[i] = if (i < FRAME) (x[f * HOP + i] * hann[i]).toDouble() else 0.0
                    im[i] = 0.0
                }
                fft(re, im)
                for (k in 0..NFFT / 2) power[k] = re[k] * re[k] + im[k] * im[k]
                for (m in 0 until MELS) {
                    var acc = 0.0
                    val row = filters[m]
                    for (k in 0..NFFT / 2) if (row[k] != 0f) acc += row[k] * power[k]
                    val v = ln(acc + 1e-6).toFloat()
                    out[m * FRAMES + f] = v
                    sum += v
                }
            }
            val mean = (sum / out.size).toFloat()
            for (i in out.indices) out[i] -= mean
            return out
        }

        /** FFT compleja en el sitio (potencia de 2), sin librerías. */
        private fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val step = n / len
                for (start in 0 until n step len) {
                    for (k in 0 until len / 2) {
                        val wr = cosT[k * step]
                        val wi = sinT[k * step]
                        val a = start + k
                        val b = a + len / 2
                        val tr = re[b] * wr - im[b] * wi
                        val ti = re[b] * wi + im[b] * wr
                        re[b] = re[a] - tr
                        im[b] = im[a] - ti
                        re[a] += tr
                        im[a] += ti
                    }
                }
                len = len shl 1
            }
        }
    }
}
