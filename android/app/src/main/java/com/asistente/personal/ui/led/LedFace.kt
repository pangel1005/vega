package com.asistente.personal.ui.led

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/*
 * Cara de Vega en una matriz de LED (48 × 20 puntos). Aquí solo hay lógica: qué puntos
 * se encienden para cada expresión y cómo se animan. Dibujarlos es cosa de LedMatrix.kt.
 */

const val FACE_COLS = 48
const val FACE_ROWS = 20
/** Los avisos pasan por la cara con letras de 6 × 10 puntos, a 30 puntos por segundo. */
const val TEXT_SCALE = 2
const val TEXT_SPEED = 30f

/** Estado de ánimo que se ve en la cara. Lo decide la pantalla a partir del estado real. */
enum class Mood { IDLE, LISTEN, THINK, TALK, HAPPY, SLEEP, ERROR, ALERT, OFFLINE }

/** Brillo (0–1) de cada punto. */
class Frame(val cols: Int = FACE_COLS, val rows: Int = FACE_ROWS) {
    val v = FloatArray(cols * rows)

    fun clear() = v.fill(0f)

    operator fun get(x: Int, y: Int): Float = if (x in 0 until cols && y in 0 until rows) v[y * cols + x] else 0f

    fun set(x: Int, y: Int, a: Float = 1f) {
        if (x in 0 until cols && y in 0 until rows) {
            val i = y * cols + x
            v[i] = max(v[i], a)
        }
    }

    fun rect(x: Int, y: Int, w: Int, h: Int, a: Float = 1f) {
        for (i in x until x + w) for (j in y until y + h) set(i, j, a)
    }

    /** Puntos encendidos (brillo > 0,5), útil en pruebas. */
    fun lit(): Int = v.count { it > 0.5f }
}

/** Dibuja las expresiones. Todas las medidas están en puntos de la matriz. */
object LedFace {
    private const val EYE_W = 8
    private const val LEFT_X = 12 // ojo izquierdo: columnas 12–19
    private const val RIGHT_X = 28 // ojo derecho: columnas 28–35

    /**
     * @param look dónde mira (−1…1 en cada eje)
     * @param blinkL cierre del ojo izquierdo (0 abierto, 1 cerrado); igual el derecho
     * @param level volumen de la voz (0–1): mueve la boca y las ondas
     */
    fun draw(
        f: Frame, mood: Mood, t: Float,
        lookX: Float = 0f, lookY: Float = 0f,
        blinkL: Float = 0f, blinkR: Float = 0f,
        level: Float = 0f,
        countdown: String? = null,
    ) {
        f.clear()
        val dx = (lookX * 3).roundToInt()
        val dy = (lookY * 2).roundToInt()
        when (mood) {
            Mood.IDLE -> {
                // Respira: el brillo sube y baja muy despacio.
                val a = 0.86f + 0.14f * sin(t * 1.3f)
                eyes(f, dx, dy, h = 10, top = 3, blinkL = blinkL, blinkR = blinkR, a = a)
                // Con un temporizador en marcha, la boca es la cuenta atrás («4:59»).
                if (countdown != null) LedFont.draw(f, countdown, (f.cols - LedFont.width(countdown)) / 2, 14)
                else smile(f, 20 + dx / 2, 15, 8, depth = 1, a = a * 0.9f)
            }
            Mood.LISTEN -> {
                eyes(f, dx / 2, 0, h = 12, top = 2, blinkL = blinkL * 0.5f, blinkR = blinkR * 0.5f, w = EYE_W + 1)
                ring(f, 22, 15, 4, 3)
                waves(f, t, level)
            }
            Mood.THINK -> {
                // Mira arriba a un lado, entorna un ojo y «carga».
                eyes(f, 2, -1, h = 6, top = 5, blinkL = 0f, blinkR = 0f, onlyLeft = true)
                eyes(f, 2, -1, h = 9, top = 3, blinkL = 0f, blinkR = blinkR, onlyRight = true)
                val step = floor(t * 3f).toInt() % 3
                for (i in 0..2) f.rect(20 + i * 3 + 1, 16, 2, 2, if (i == step) 1f else 0.25f)
            }
            Mood.TALK -> {
                val bounce = if (level > 0.5f) 1 else 0
                eyes(f, dx / 2, -bounce, h = 10, top = 3, blinkL = blinkL, blinkR = blinkR)
                for (i in 0 until 10) {
                    val n = abs(sin(t * 13f + i * 1.7f) * sin(t * 4.1f + i * 0.6f))
                    val h = 1 + (level * 3.4f * (0.35f + 0.65f * n)).roundToInt()
                    f.rect(19 + i, 18 - h, 1, h)
                }
            }
            Mood.HAPPY -> {
                happyEye(f, LEFT_X + dx, 8 + dy)
                happyEye(f, RIGHT_X + dx, 8 + dy)
                smile(f, 18, 14, 12, depth = 2)
                // Mejillas: dos puntos tenues.
                f.rect(LEFT_X - 3, 12, 2, 1, 0.45f)
                f.rect(RIGHT_X + EYE_W + 1, 12, 2, 1, 0.45f)
            }
            Mood.SLEEP -> {
                for (x0 in listOf(LEFT_X, RIGHT_X)) {
                    for (i in 0 until EYE_W) f.set(x0 + i, if (i == 0 || i == EYE_W - 1) 9 else 10, 0.8f)
                }
                f.rect(22, 16, 4, 1, 0.45f)
                // Zetas que suben y se apagan.
                for (k in 0..1) {
                    val p = ((t * 0.35f + k * 0.5f) % 1f)
                    zeta(f, 39 + k * 3, (8 - p * 7).roundToInt(), 1f - p)
                }
            }
            Mood.ERROR -> {
                for (x0 in listOf(LEFT_X, RIGHT_X)) {
                    for (i in 0 until 7) {
                        f.set(x0 + i, 4 + i)
                        f.set(x0 + 6 - i, 4 + i)
                    }
                }
                for (i in 0 until 10) f.set(19 + i, 16 + i % 2)
            }
            Mood.ALERT -> {
                // Ojos muy abiertos (huecos) con pupila: «¿seguro?».
                for (x0 in listOf(LEFT_X - 1, RIGHT_X - 1)) {
                    ring(f, x0, 2, EYE_W + 2, 12)
                    f.rect(x0 + 4 + dx.coerceIn(-2, 2), 6 + dy.coerceIn(-2, 2), 3, 3)
                }
                ring(f, 22, 15, 4, 4)
            }
            Mood.OFFLINE -> {
                for (x0 in listOf(LEFT_X, RIGHT_X)) f.rect(x0, 8, EYE_W, 2, 0.35f)
                val x = (t * 14f % (f.cols + 8)).toInt() - 4
                for (i in 0 until 4) f.set(x - i, f.rows - 2, 0.7f - i * 0.15f)
            }
        }
    }

    private fun eyes(
        f: Frame, dx: Int, dy: Int, h: Int, top: Int,
        blinkL: Float, blinkR: Float, a: Float = 1f, w: Int = EYE_W,
        onlyLeft: Boolean = false, onlyRight: Boolean = false,
    ) {
        val off = (w - EYE_W) / 2
        if (!onlyRight) eye(f, LEFT_X - off + dx, top + dy, w, h, blinkL, a)
        if (!onlyLeft) eye(f, RIGHT_X - off + dx, top + dy, w, h, blinkR, a)
    }

    /** Ojo rectangular con las esquinas recortadas; al parpadear se aplasta hacia su centro. */
    private fun eye(f: Frame, x: Int, top: Int, w: Int, h: Int, blink: Float, a: Float) {
        val hh = max(1, (h * (1f - blink)).roundToInt())
        val y = top + (h - hh) / 2
        f.rect(x, y, w, hh, a)
        // Esquinas redondeadas: quita la esquina y, si el ojo es grande, también sus vecinas.
        if (hh >= 4) {
            val big = hh >= 7 && w >= 7
            for ((cx, cy, sx, sy) in listOf(
                Corner(x, y, 1, 1), Corner(x + w - 1, y, -1, 1),
                Corner(x, y + hh - 1, 1, -1), Corner(x + w - 1, y + hh - 1, -1, -1),
            )) {
                clear(f, cx, cy)
                if (big) {
                    clear(f, cx + sx, cy)
                    clear(f, cx, cy + sy)
                }
            }
        }
    }

    private data class Corner(val x: Int, val y: Int, val sx: Int, val sy: Int)

    private fun clear(f: Frame, x: Int, y: Int) {
        if (x in 0 until f.cols && y in 0 until f.rows) f.v[y * f.cols + x] = 0f
    }

    /** Ojo contento: un arco «^». */
    private fun happyEye(f: Frame, x0: Int, base: Int) {
        for (i in 0 until EYE_W) {
            val h = (3 - abs(i - 3.5f) * 0.85f).roundToInt()
            f.set(x0 + i, base - h)
            f.set(x0 + i, base - h + 1)
        }
    }

    /** Sonrisa en «U» de `w` puntos; `depth` = cuánto baja el centro. */
    private fun smile(f: Frame, x0: Int, y0: Int, w: Int, depth: Int, a: Float = 1f) {
        val mid = (w - 1) / 2f
        for (i in 0 until w) {
            val k = (i - mid) / mid
            f.set(x0 + i, y0 + (depth * (1 - k * k)).roundToInt(), a)
        }
    }

    private fun ring(f: Frame, x: Int, y: Int, w: Int, h: Int) {
        for (i in 0 until w) {
            f.set(x + i, y)
            f.set(x + i, y + h - 1)
        }
        for (j in 0 until h) {
            f.set(x, y + j)
            f.set(x + w - 1, y + j)
        }
    }

    /** Ondas de sonido a los lados mientras escucha (siguen el volumen del micrófono). */
    private fun waves(f: Frame, t: Float, level: Float) {
        for (k in 0..2) {
            val h = (2 + (level * 6f + 1.2f) * (0.5f + 0.5f * sin(t * 6f - k * 1.1f))).roundToInt().coerceAtMost(10)
            val a = 1f - k * 0.28f
            val y = 10 - h / 2
            f.rect(5 - k * 2, y, 1, h, a)
            f.rect(f.cols - 6 + k * 2, y, 1, h, a)
        }
    }

    private fun zeta(f: Frame, x: Int, y: Int, a: Float) {
        f.rect(x, y, 3, 1, a)
        f.set(x + 1, y + 1, a)
        f.rect(x, y + 2, 3, 1, a)
    }
}

/** Letras de 3 × 5 puntos para escribir en la matriz (mayúsculas, sin tildes). */
/** Segundos que faltan → «4:59» (o «1:05:00» si pasa de una hora). */
fun countdownText(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

object LedFont {
    private val glyphs: Map<Char, Array<String>> = mapOf(
        'A' to arrayOf("###", "#.#", "###", "#.#", "#.#"), 'B' to arrayOf("##.", "#.#", "##.", "#.#", "##."),
        'C' to arrayOf("###", "#..", "#..", "#..", "###"), 'D' to arrayOf("##.", "#.#", "#.#", "#.#", "##."),
        'E' to arrayOf("###", "#..", "##.", "#..", "###"), 'F' to arrayOf("###", "#..", "##.", "#..", "#.."),
        'G' to arrayOf("###", "#..", "#.#", "#.#", "###"), 'H' to arrayOf("#.#", "#.#", "###", "#.#", "#.#"),
        'I' to arrayOf("###", ".#.", ".#.", ".#.", "###"), 'J' to arrayOf("..#", "..#", "..#", "#.#", "###"),
        'K' to arrayOf("#.#", "#.#", "##.", "#.#", "#.#"), 'L' to arrayOf("#..", "#..", "#..", "#..", "###"),
        'M' to arrayOf("#.#", "###", "###", "#.#", "#.#"), 'N' to arrayOf("##.", "#.#", "#.#", "#.#", "#.#"),
        'Ñ' to arrayOf("###", "...", "##.", "#.#", "#.#"),
        'O' to arrayOf("###", "#.#", "#.#", "#.#", "###"), 'P' to arrayOf("###", "#.#", "###", "#..", "#.."),
        'Q' to arrayOf("###", "#.#", "#.#", "###", "..#"), 'R' to arrayOf("##.", "#.#", "##.", "#.#", "#.#"),
        'S' to arrayOf("###", "#..", "###", "..#", "###"), 'T' to arrayOf("###", ".#.", ".#.", ".#.", ".#."),
        'U' to arrayOf("#.#", "#.#", "#.#", "#.#", "###"), 'V' to arrayOf("#.#", "#.#", "#.#", "#.#", ".#."),
        'W' to arrayOf("#.#", "#.#", "###", "###", "#.#"), 'X' to arrayOf("#.#", "#.#", ".#.", "#.#", "#.#"),
        'Y' to arrayOf("#.#", "#.#", ".#.", ".#.", ".#."), 'Z' to arrayOf("###", "..#", ".#.", "#..", "###"),
        '0' to arrayOf("###", "#.#", "#.#", "#.#", "###"), '1' to arrayOf(".#.", "##.", ".#.", ".#.", "###"),
        '2' to arrayOf("###", "..#", "###", "#..", "###"), '3' to arrayOf("###", "..#", ".##", "..#", "###"),
        '4' to arrayOf("#.#", "#.#", "###", "..#", "..#"), '5' to arrayOf("###", "#..", "###", "..#", "###"),
        '6' to arrayOf("###", "#..", "###", "#.#", "###"), '7' to arrayOf("###", "..#", ".#.", ".#.", ".#."),
        '8' to arrayOf("###", "#.#", "###", "#.#", "###"), '9' to arrayOf("###", "#.#", "###", "..#", "###"),
        ':' to arrayOf("...", ".#.", "...", ".#.", "..."), '.' to arrayOf("...", "...", "...", "...", ".#."),
        ',' to arrayOf("...", "...", "...", ".#.", "#.."), '!' to arrayOf(".#.", ".#.", ".#.", "...", ".#."),
        '?' to arrayOf("###", "..#", ".##", "...", ".#."), '-' to arrayOf("...", "...", "###", "...", "..."),
        '%' to arrayOf("#.#", "..#", ".#.", "#..", "#.#"), '/' to arrayOf("..#", "..#", ".#.", "#..", "#.."),
        '«' to arrayOf("...", ".#.", "#..", ".#.", "..."), '»' to arrayOf("...", ".#.", "..#", ".#.", "..."),
        ' ' to arrayOf("...", "...", "...", "...", "..."),
    )

    /** «¡Te recuerdo: llamar a mamá!» → «TE RECUERDO: LLAMAR A MAMA!» (sin tildes ni signos de apertura). */
    fun normalize(text: String): String {
        val upper = text.uppercase().replace("¡", "").replace("¿", "")
        val keepÑ = upper.replace('Ñ', '\u0001')
        val plain = Normalizer.normalize(keepÑ, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return plain.replace('\u0001', 'Ñ').map { if (it in glyphs) it else ' ' }.joinToString("")
            .replace(Regex(" +"), " ").trim()
    }

    /** Ancho en puntos (cada letra ocupa 3 + 1 de separación). */
    fun width(text: String): Int = max(0, text.length * 4 - 1)

    /** Escribe `text` desde (x, y); `scale` 2 = letras de 6 × 10 puntos. */
    fun draw(f: Frame, text: String, x: Int, y: Int, a: Float = 1f, scale: Int = 1) {
        var cx = x
        val advance = 4 * scale
        for (ch in text) {
            val g = glyphs[ch] ?: glyphs.getValue(' ')
            if (cx > -advance && cx < f.cols) {
                for (row in 0 until 5) for (col in 0 until 3) {
                    if (g[row][col] == '#') f.rect(cx + col * scale, y + row * scale, scale, scale, a)
                }
            }
            cx += advance
        }
    }

    /** Segundos que tarda un aviso en cruzar la cara entera (a la velocidad de [LedFaceAnimator]). */
    fun crossSeconds(message: String): Float =
        (width(normalize(message)) * TEXT_SCALE + FACE_COLS) / TEXT_SPEED
}

/**
 * Anima la cara: mirada que se pasea, parpadeos (a veces un guiño), y el brillo de cada
 * punto que sube rápido y se apaga despacio, como un LED de verdad.
 */
class LedFaceAnimator(private val random: Random = Random.Default) {
    val target = Frame()
    val shown = FloatArray(FACE_COLS * FACE_ROWS)

    private var lookX = 0f
    private var lookY = 0f
    private var goalX = 0f
    private var goalY = 0f
    private var lookTimer = 1f
    private var blinkL = 0f
    private var blinkR = 0f
    private var nextBlink = 2.5f
    private var text: String? = null
    private var textX = 0f

    // Manías en reposo: de vez en cuando entorna los ojos contenta o mira a los lados.
    private var quirkTimer = 12f
    private var quirk = Quirk.NONE
    private var quirkLeft = 0f

    private enum class Quirk { NONE, SMILE, LOOK_AROUND }

    /** Pasa un texto de derecha a izquierda (un aviso); luego vuelve la cara. */
    fun showText(message: String) {
        text = LedFont.normalize(message).ifEmpty { null }
        textX = FACE_COLS.toFloat()
    }

    val showingText: Boolean get() = text != null

    /**
     * Mira hacia algo un momento (x, y entre −1 y 1): las teclas al pulsar una, el lado de los
     * avisos cuando llega uno, la música cuando cambia la canción. Luego vuelve a pasear la vista.
     */
    fun glance(x: Float, y: Float, hold: Float = 1.3f) {
        goalX = x.coerceIn(-1f, 1f)
        goalY = y.coerceIn(-1f, 1f)
        lookTimer = hold
    }

    /** Cuenta atrás del temporizador («4:59»), o null si no hay ninguno en marcha. */
    var countdown: String? = null

    /** Avanza `dt` segundos. Devuelve true si algún punto cambió lo bastante como para redibujar. */
    fun step(dt: Float, t: Float, mood: Mood, level: Float): Boolean {
        val msg = text
        if (msg != null) {
            target.clear()
            textX -= dt * TEXT_SPEED
            LedFont.draw(target, msg, textX.roundToInt(), 5, scale = TEXT_SCALE)
            if (textX < -LedFont.width(msg) * TEXT_SCALE) text = null
        } else {
            val shownMood = idleQuirk(dt, mood)
            moveEyes(dt, shownMood)
            LedFace.draw(target, shownMood, t, lookX, lookY, blinkL, blinkR, level, countdown)
        }
        val fall = if (msg != null) 22f else 7f // el texto no deja estela (se leería borroso)
        var changed = false
        for (i in shown.indices) {
            val goal = target.v[i]
            val now = shown[i]
            if (now == goal) continue
            // Enciende rápido (~40 ms) y se apaga con estela (~150 ms).
            val k = if (goal > now) min(1f, dt * 25f) else min(1f, dt * fall)
            var next = now + (goal - now) * k
            if (abs(next - goal) < 0.01f) next = goal
            if (abs(next - now) > 0.004f) changed = true
            shown[i] = next
        }
        return changed
    }

    private fun idleQuirk(dt: Float, mood: Mood): Mood {
        if (mood != Mood.IDLE) {
            quirk = Quirk.NONE
            quirkTimer = 10f + random.nextFloat() * 15f
            return mood
        }
        if (quirk != Quirk.NONE) {
            quirkLeft -= dt
            if (quirkLeft <= 0f) {
                quirk = Quirk.NONE
                quirkTimer = 12f + random.nextFloat() * 25f
            }
        } else {
            quirkTimer -= dt
            if (quirkTimer <= 0f) {
                quirk = if (random.nextBoolean()) Quirk.SMILE else Quirk.LOOK_AROUND
                quirkLeft = if (quirk == Quirk.SMILE) 1.4f else 2.4f
                lookTimer = 0f
            }
        }
        if (quirk == Quirk.LOOK_AROUND) {
            // Izquierda, derecha, y vuelta al centro.
            goalX = if (quirkLeft > 1.6f) -1f else if (quirkLeft > 0.8f) 1f else 0f
            goalY = 0f
            lookTimer = 1f
        }
        return if (quirk == Quirk.SMILE) Mood.HAPPY else mood
    }

    private fun moveEyes(dt: Float, mood: Mood) {
        lookTimer -= dt
        if (lookTimer <= 0f) {
            lookTimer = 0.8f + random.nextFloat() * 2.6f
            val wander = mood == Mood.IDLE || mood == Mood.HAPPY || mood == Mood.TALK || mood == Mood.ALERT
            goalX = if (wander && random.nextFloat() < 0.7f) random.nextFloat() * 2 - 1 else 0f
            goalY = if (wander && random.nextFloat() < 0.5f) random.nextFloat() - 0.5f else 0f
        }
        // La mirada salta (como los ojos de verdad), con un poco de suavidad.
        lookX += (goalX - lookX) * min(1f, dt * 14f)
        lookY += (goalY - lookY) * min(1f, dt * 14f)

        nextBlink -= dt
        if (nextBlink <= 0f && mood != Mood.SLEEP && mood != Mood.OFFLINE) {
            nextBlink = 2.2f + random.nextFloat() * 3.8f
            val wink = mood == Mood.IDLE && random.nextFloat() < 0.12f
            blinkR = 1f
            if (!wink) blinkL = 1f
        }
        blinkL = max(0f, blinkL - dt * 6f)
        blinkR = max(0f, blinkR - dt * 6f)
    }
}
