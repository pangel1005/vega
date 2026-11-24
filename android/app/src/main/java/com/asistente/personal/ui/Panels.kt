package com.asistente.personal.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow

/** Texto simple sin Material (más ligero). Recorta con «…» si no cabe. */
@Composable
fun Text(text: String, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, overflow: TextOverflow = TextOverflow.Ellipsis) {
    androidx.compose.foundation.text.BasicText(text, modifier, style, overflow = overflow, maxLines = maxLines)
}
