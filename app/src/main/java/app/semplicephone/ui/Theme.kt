package app.semplicephone.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

object Palette {
    val Ground = Color(0xFFF7F5F0)
    val Ink = Color(0xFF1A1A1A)
    val Sos = Color(0xFFB71C1C)
}

/** "#RRGGBB" → Color; valore non valido → blu di default. */
fun parseColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (e: IllegalArgumentException) {
    Color(0xFF1F4FA8)
}

/** Testo nero su sfondi chiari, bianco su scuri: la palette resta ad alto contrasto. */
fun contentColorFor(bg: Color): Color = if (bg.luminance() > 0.45f) Palette.Ink else Color.White

@Composable
fun SempliceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            background = Palette.Ground,
            surface = Palette.Ground,
            onBackground = Palette.Ink,
            onSurface = Palette.Ink,
        ),
        content = content,
    )
}
