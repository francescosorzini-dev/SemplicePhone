package app.semplicephone.sos

import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.semplicephone.launch.TileLauncher
import app.semplicephone.model.SosConfig
import app.semplicephone.ui.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Conto alla rovescia annullabile, poi: SMS con posizione a tutti i numeri, chiamata al primo.
 * La posizione si cerca durante il conto alla rovescia, così non ritarda la chiamata.
 */
@Composable
fun SosScreen(cfg: SosConfig, launcher: TileLauncher, contactNames: (String) -> String, onDone: () -> Unit) {
    val context = LocalContext.current
    val done by rememberUpdatedState(onDone)
    var remaining by remember { mutableIntStateOf(cfg.countdownSeconds.coerceIn(3, 15)) }
    var status by remember { mutableStateOf<String?>(null) }
    val fetcher = remember { LocationFetcher(context) }

    LaunchedEffect(Unit) {
        coroutineScope {
            val fix = async { fetcher.current(timeoutMs = remaining * 1000L + 3000) }
            while (remaining > 0) {
                delay(1000)
                remaining--
            }
            status = "Invio in corso…"
            // Al massimo 3 s oltre il conto alla rovescia per il fix, poi l'ultima posizione nota.
            val location: Location? = withContext(Dispatchers.Default) {
                kotlinx.coroutines.withTimeoutOrNull(3000) { fix.await() } ?: fetcher.lastKnown()
            }
            fix.cancel()
            val smsText = SosSms.text(cfg, location)
            val sent = withContext(Dispatchers.IO) { SosSms.send(context, cfg.numbers, smsText) }
            app.semplicephone.remote.RemoteEvents.enqueue(
                context, "sos", smsText,
                mapOf("smsSent" to sent, "lat" to location?.latitude, "lon" to location?.longitude),
            )
            cfg.numbers.firstOrNull()?.let { launcher.call(it) }
            status = when {
                sent > 0 -> "SMS inviato a $sent ${if (sent == 1) "persona" else "persone"}"
                else -> "SMS non inviato"
            }
            delay(4000)
            done()
        }
    }

    val first = cfg.numbers.firstOrNull().orEmpty()
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Sos)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Chiamata di emergenza", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (status == null) {
            Text("parte tra", color = Color.White, fontSize = 24.sp)
            Box(
                Modifier.size(220.dp).border(10.dp, Color.White, CircleShape).semantics { liveRegion = LiveRegionMode.Assertive },
                contentAlignment = Alignment.Center,
            ) { Text("$remaining", color = Color.White, fontSize = 130.sp, fontWeight = FontWeight.Bold) }
            Text("Chiamo ${contactNames(first)}", color = Color.White, fontSize = 22.sp, textAlign = TextAlign.Center)
            if (cfg.numbers.isNotEmpty()) {
                Text(
                    "Invio SMS con la posizione a ${cfg.numbers.joinToString(", ") { contactNames(it) }}",
                    color = Color.White, fontSize = 20.sp, textAlign = TextAlign.Center,
                )
            }
        } else {
            Text(status!!, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.weight(1f))
        if (status == null) {
            Button(
                onClick = onDone,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Palette.Sos),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().height(110.dp),
            ) { Text("ANNULLA", fontSize = 40.sp, fontWeight = FontWeight.Bold) }
        }
    }
}
