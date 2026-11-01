package app.semplicephone.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.semplicephone.R
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

/** Atkinson Hyperlegible (Braille Institute, OFL): progettato per chi vede poco. */
val Atkinson = FontFamily(
    Font(R.font.atkinson_regular, FontWeight.Normal),
    Font(R.font.atkinson_bold, FontWeight.Bold),
)

private fun Typography.withFamily(f: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = f), displayMedium = displayMedium.copy(fontFamily = f),
    displaySmall = displaySmall.copy(fontFamily = f), headlineLarge = headlineLarge.copy(fontFamily = f),
    headlineMedium = headlineMedium.copy(fontFamily = f), headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f), titleMedium = titleMedium.copy(fontFamily = f),
    titleSmall = titleSmall.copy(fontFamily = f), bodyLarge = bodyLarge.copy(fontFamily = f),
    bodyMedium = bodyMedium.copy(fontFamily = f), bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f), labelMedium = labelMedium.copy(fontFamily = f),
    labelSmall = labelSmall.copy(fontFamily = f),
)

@Composable
fun SempliceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            background = Palette.Ground,
            surface = Palette.Ground,
            onBackground = Palette.Ink,
            onSurface = Palette.Ink,
        ),
        typography = Typography().withFamily(Atkinson),
        content = content,
    )
}
