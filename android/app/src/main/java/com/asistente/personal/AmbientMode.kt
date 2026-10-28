package com.asistente.personal

/** Minutos sin que pase nada tras los que la pantalla baja al brillo de «ambiente». */
const val AMBIENT_AFTER_MS = 10 * 60_000L

/** Brillo de la pantalla en modo ambiente (0 a 1): lo justo para ver la hora de lejos sin alumbrar el cuarto. */
const val AMBIENT_BRIGHTNESS = 0.04f

/**
 * ¿Toca bajar el brillo? Sí si lleva `afterMs` sin actividad (un toque, un aviso, Vega escuchando,
 * pensando o hablando). La pantalla es el 67 % de lo que gasta la app (medido: 116 de 173 mAh) y vive
 * encendida todo el día con el brillo fijo; de noche o sin nadie delante no hace falta.
 */
fun ambientDue(lastActiveMs: Long, nowMs: Long, afterMs: Long = AMBIENT_AFTER_MS): Boolean =
    nowMs - lastActiveMs >= afterMs
