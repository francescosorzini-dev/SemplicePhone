package app.semplicephone.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.semplicephone.R
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.luminance

/**
 * Colori dell'interfaccia (gli stessi del portale: --bg, --ink, --mute, --line, --ok, --warn, --bad, --accent).
 * Ogni colore ha un significato: rosso solo per SOS ed emergenze, verde per le azioni «sì» (fatto, chiama,
 * avvia), arancio per fermare o fare attenzione, blu per le azioni neutre, nero per chiudere.
 * I colori dei riquadri scelti dall'utente sono un'altra cosa (tavolozza dei riquadri).
 */
object Palette {
    val Ground = Color(0xFFF7F5F0)
    val Ink = Color(0xFF1A1A1A)
    val Mute = Color(0xFF4F4B45)
    val Line = Color(0xFFD9D3C7)
    val Soft = Color(0xFFECE9E2)
    val Ok = Color(0xFF1B6E34)
    val Warn = Color(0xFFA84300)
    val Sos = Color(0xFFB71C1C)
    val Accent = Color(0xFF1F4FA8)
    val Amber = Color(0xFFFFA726)
    /** Sfondi tenui: avviso (arancio), nota (giallo), informazione (azzurro). */
    val WarnSoft = Color(0xFFFFE0B2)
    val NoteSoft = Color(0xFFFFF8E1)
    val InfoSoft = Color(0xFFE8F1FB)
}

/** Sei grandezze di testo per tutta l'app (i riquadri della Home hanno le loro, legate alla misura). */
object TextSize {
    val Small = 15.sp
    val Body = 18.sp
    val Label = 22.sp
    val Title = 28.sp
    val Big = 36.sp
    val Huge = 44.sp
}

/** Due raggi e quattro altezze di pulsante. */
object Ui {
    val ShapeBig = RoundedCornerShape(24.dp)
    val ShapeSmall = RoundedCornerShape(12.dp)
    /** Impostazioni e righe semplici. */
    val HeightSettings = 52.dp
    /** Righe grandi, pulsanti secondari nelle schermate grandi. */
    val HeightRow = 72.dp
    /** Pulsanti principali delle schermate grandi. */
    val HeightBig = 96.dp
    /** Pulsanti enormi (SOS, «STO BENE»). */
    val HeightHuge = 140.dp
}

/** "#RRGGBB" → Color; valore non valido → blu di default. */
fun parseColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (e: IllegalArgumentException) {
    Palette.Accent
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
