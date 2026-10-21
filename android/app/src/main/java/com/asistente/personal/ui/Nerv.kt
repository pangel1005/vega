package com.asistente.personal.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.asistente.personal.R

/** Tema NERV: mismos valores que ui/src/styles/theme.css. */
object Nerv {
    val Bg = Color(0xFF060606)
    val Bg2 = Color(0xFF0D0B0A)
    val Bg3 = Color(0xFF15110E)
    val Ink = Color(0xFFF3EDE4)
    val Mute = Color(0xFF8F857A)
    val Dim = Color(0xFF4A433C)

    val Orange = Color(0xFFFF7A18)
    val Amber = Color(0xFFFFB000)
    val Red = Color(0xFFFF2A3A)
    val Green = Color(0xFF3DFF7A)
    val Teal = Color(0xFF29E6B8)
    val Warn = Color(0xFFFFD400)

    val Cond = FontFamily(
        Font(R.font.barlow_condensed_regular, FontWeight.Normal),
        Font(R.font.barlow_condensed_bold, FontWeight.Bold),
    )
    val Mono = FontFamily(Font(R.font.share_tech_mono))
    val Serif = FontFamily(Font(R.font.shippori_mincho_extrabold, FontWeight.ExtraBold))
    val Jp = FontFamily(Font(R.font.noto_sans_jp_black, FontWeight.Black))
}
