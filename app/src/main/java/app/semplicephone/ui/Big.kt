package app.semplicephone.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.semplicephone.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Significato del colore di un pulsante (vedi [Palette]). */
enum class Tone(val bg: Color) {
    Ok(Palette.Ok), Accent(Palette.Accent), Warn(Palette.Warn), Sos(Palette.Sos), Close(Palette.Ink), Amber(Palette.Amber), Light(Color.White);

    val fg: Color get() = contentColorFor(bg)
}

/**
 * Testo che si rimpicciolisce da solo (fino a [min]) invece di uscire dal pulsante: le traduzioni
 * tedesche e francesi sono spesso più lunghe dell'italiano.
 */
@Composable
fun FitText(
    text: String, color: Color, max: TextUnit, modifier: Modifier = Modifier, min: TextUnit = 14.sp,
    bold: Boolean = true, align: TextAlign = TextAlign.Center, maxLines: Int = 2,
) {
    BasicText(
        text, modifier,
        style = TextStyle(color = color, fontFamily = Atkinson, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, textAlign = align, lineHeight = 1.15.em),
        maxLines = maxLines,
        autoSize = TextAutoSize.StepBased(minFontSize = min, maxFontSize = max, stepSize = 1.sp),
    )
}

/** Pulsante principale delle schermate grandi: colore con significato, simbolo facoltativo, testo che non esce. */
@Composable
fun BigButton(
    text: String, tone: Tone, modifier: Modifier = Modifier.fillMaxWidth(), symbol: String? = null,
    height: Dp = Ui.HeightBig, textSize: TextUnit = TextSize.Title, onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = tone.bg, contentColor = tone.fg),
        shape = Ui.ShapeBig, modifier = modifier.heightIn(min = height),
        border = if (tone == Tone.Light) androidx.compose.foundation.BorderStroke(2.dp, Palette.Ink) else null,
    ) {
        if (symbol != null) {
            Icon(Symbols.get(symbol), null, Modifier.size(36.dp), tint = tone.fg)
            Spacer(Modifier.width(10.dp))
        }
        FitText(text, tone.fg, textSize)
    }
}

/** Pulsante secondario (bordo, sfondo chiaro) delle schermate grandi. */
@Composable
fun BigOutlined(text: String, modifier: Modifier = Modifier.fillMaxWidth(), symbol: String? = null, height: Dp = Ui.HeightRow, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, shape = Ui.ShapeBig, modifier = modifier.heightIn(min = height),
        border = androidx.compose.foundation.BorderStroke(2.dp, Palette.Ink),
    ) {
        if (symbol != null) {
            Icon(Symbols.get(symbol), null, Modifier.size(30.dp), tint = Palette.Ink)
            Spacer(Modifier.width(8.dp))
        }
        FitText(text, Palette.Ink, TextSize.Label)
    }
}

/** «CHIUDI»: sempre in basso, sempre uguale. Su sfondi scuri ([onDark]) è bianco. */
@Composable
fun CloseButton(onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), onDark: Boolean = false, label: String? = null) {
    BigButton(label ?: stringResource(R.string.action_close_big), if (onDark) Tone.Light else Tone.Close, modifier, symbol = "close", onClick = onClick)
}

/**
 * Cornice delle schermate grandi (strumenti, video, messaggi, radio…): simbolo e titolo in alto,
 * contenuto al centro, «CHIUDI» in basso. Così ogni funzione si usa allo stesso modo.
 */
@Composable
fun BigScreen(
    title: String, symbol: String?, onClose: () -> Unit,
    background: Color = Palette.Ground, onDark: Boolean = false, showClose: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val fg = if (onDark) Color.White else Palette.Ink
    Column(
        Modifier.fillMaxSize().background(background).windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (title.isNotBlank() || symbol != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (symbol != null) {
                Icon(Symbols.get(symbol), null, Modifier.size(40.dp), tint = fg)
                Spacer(Modifier.width(12.dp))
            }
            FitText(title, fg, TextSize.Title, Modifier.weight(1f), align = TextAlign.Start)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            content = content,
        )
        if (showClose) CloseButton(onClose, onDark = onDark)
    }
}

/** Niente da mostrare o errore: simbolo grande, una frase, al massimo un pulsante. */
@Composable
fun EmptyState(symbol: String, text: String, modifier: Modifier = Modifier, color: Color = Palette.Ink, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(Symbols.get(symbol), null, Modifier.size(96.dp), tint = color)
        Text(text, fontSize = TextSize.Label, lineHeight = 30.sp, fontWeight = FontWeight.Bold, color = color, textAlign = TextAlign.Center)
        if (action != null && onAction != null) BigOutlined(action, onClick = onAction)
    }
}

/**
 * Finestra di conferma delle impostazioni, uguale ovunque: titolo, testo, pulsanti grandi in colonna
 * (quello principale in alto). Sostituisce le finestre standard di Android, con testo piccolo.
 */
@Composable
fun ConfirmDialog(
    title: String, text: String?, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit,
    dismiss: String? = null, tone: Tone = Tone.Ok, extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(20.dp).fillMaxWidth().background(Palette.Ground, Ui.ShapeBig).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, fontSize = TextSize.Title, lineHeight = 34.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
            if (!text.isNullOrBlank()) Text(text, fontSize = TextSize.Body, lineHeight = 25.sp, color = Palette.Ink)
            extra?.invoke(this)
            BigButton(confirm, tone, height = Ui.HeightSettings, textSize = TextSize.Label, onClick = onConfirm)
            BigOutlined(dismiss ?: stringResource(R.string.action_cancel), height = Ui.HeightSettings, onClick = onDismiss)
        }
    }
}

/**
 * Avvisi brevi («tieni premuto», «app non disponibile»): fascia grande in basso nella Home, letta a voce,
 * al posto dei toast di Android (piccoli e spariscono in due secondi). Fuori dalla Home resta il toast.
 */
object Hint {
    private val main = Handler(Looper.getMainLooper())
    private val _text = MutableStateFlow<String?>(null)
    val text = _text.asStateFlow()
    private var hosts = 0
    private val clear = Runnable { _text.value = null }

    fun show(context: Context, message: String, speak: Boolean = true) {
        main.post {
            if (hosts == 0) { Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show(); return@post }
            _text.value = message
            main.removeCallbacks(clear); main.postDelayed(clear, 5000)
            if (speak) Voice.speak(context, message)
        }
    }

    fun hide() { main.removeCallbacks(clear); _text.value = null }

    /** Da mettere in fondo alla Home: mostra la fascia quando c'è un avviso. */
    @Composable
    fun Host(modifier: Modifier = Modifier) {
        DisposableEffect(Unit) { hosts++; onDispose { hosts--; hide() } }
        val t by text.collectAsState()
        val msg = t ?: return
        Box(
            modifier.fillMaxWidth().padding(12.dp).background(Palette.Ink, Ui.ShapeSmall)
                .clickable { hide() }.padding(horizontal = 20.dp, vertical = 18.dp),
            contentAlignment = Alignment.Center,
        ) { FitText(msg, Color.White, TextSize.Label, maxLines = 3) }
    }
}

