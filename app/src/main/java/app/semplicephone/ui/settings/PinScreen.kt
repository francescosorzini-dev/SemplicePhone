package app.semplicephone.ui.settings

import androidx.compose.foundation.layout.heightIn
import app.semplicephone.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.semplicephone.security.PinStore
import app.semplicephone.ui.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class PinMode { ENTER, CREATE }

/**
 * ENTER: verifica il PIN e chiama onSuccess.
 * CREATE: chiede il nuovo PIN due volte, lo salva e chiama onSuccess.
 */
@Composable
fun PinScreen(store: PinStore, mode: PinMode, onSuccess: () -> Unit, onCancel: () -> Unit) {
    val scope = rememberCoroutineScope()
    var digits by remember { mutableStateOf("") }
    var firstEntry by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lockMs by remember { mutableLongStateOf(store.lockRemainingMs()) }

    LaunchedEffect(lockMs > 0) {
        while (lockMs > 0) {
            delay(1000)
            lockMs = store.lockRemainingMs()
        }
    }

    val title = when {
        mode == PinMode.ENTER -> stringResource(R.string.pin_settings)
        firstEntry == null -> stringResource(R.string.pin_choose)
        else -> stringResource(R.string.pin_repeat)
    }
    val subtitle = when {
        lockMs > 0 -> stringResource(R.string.pin_locked, ((lockMs + 999) / 1000).toInt())
        message != null -> stringResource(message!!)
        mode == PinMode.ENTER -> stringResource(R.string.pin_reserved)
        else -> stringResource(R.string.pin_length, PinStore.PIN_LENGTH.first, PinStore.PIN_LENGTH.last)
    }

    fun submit() {
        if (busy || digits.length !in PinStore.PIN_LENGTH) return
        busy = true
        scope.launch {
            when (mode) {
                PinMode.ENTER -> {
                    if (store.check(digits)) onSuccess()
                    else {
                        message = R.string.pin_wrong
                        lockMs = store.lockRemainingMs()
                    }
                }
                PinMode.CREATE -> {
                    val first = firstEntry
                    if (first == null) {
                        firstEntry = digits
                        message = null
                    } else if (first == digits) {
                        store.set(digits)
                        onSuccess()
                    } else {
                        firstEntry = null
                        message = R.string.pin_mismatch
                    }
                }
            }
            digits = ""
            busy = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Ground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        Text(subtitle, fontSize = 18.sp, color = Color(0xFF4A4A4A), textAlign = TextAlign.Center)

        val digitsDesc = stringResource(R.string.pin_digits_entered, digits.length)
        val deleteDesc = stringResource(R.string.pin_delete)
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .padding(vertical = 8.dp)
                .semantics { contentDescription = digitsDesc },
        ) {
            val shown = maxOf(PinStore.PIN_LENGTH.first, digits.length)
            repeat(shown) { i ->
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .then(if (i < digits.length) Modifier.background(Palette.Ink) else Modifier.border(3.dp, Palette.Ink, CircleShape)),
                )
            }
        }

        val enabled = lockMs == 0L && !busy
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")
        keys.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { k ->
                    val mod = Modifier.weight(1f).heightIn(min = 72.dp)
                    when (k) {
                        "" -> Spacer(mod)
                        "⌫" -> TextButton(
                            onClick = { digits = digits.dropLast(1) },
                            enabled = enabled,
                            modifier = mod.semantics { contentDescription = deleteDesc },
                        ) { Text("⌫", fontSize = 28.sp, color = Palette.Ink) }
                        else -> OutlinedButton(
                            onClick = { if (digits.length < PinStore.PIN_LENGTH.last) digits += k },
                            enabled = enabled,
                            shape = RoundedCornerShape(16.dp),
                            modifier = mod,
                        ) { Text(k, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Palette.Ink) }
                    }
                }
            }
        }

        Button(
            onClick = ::submit,
            enabled = enabled && digits.length in PinStore.PIN_LENGTH,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Ink),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        ) { Text(stringResource(R.string.action_confirm), fontSize = 22.sp, fontWeight = FontWeight.Bold) }

        Spacer(Modifier.weight(1f))
        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 56.dp)) {
            Text(stringResource(R.string.pin_exit), fontSize = 20.sp, color = Palette.Ink)
        }
    }
}
