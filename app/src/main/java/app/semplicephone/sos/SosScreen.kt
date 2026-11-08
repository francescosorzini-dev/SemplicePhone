package app.semplicephone.sos

import app.semplicephone.ui.Ui
import app.semplicephone.ui.TextSize
import androidx.compose.foundation.layout.heightIn
import app.semplicephone.R
import androidx.compose.ui.res.stringResource
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
fun SosScreen(
    cfg: SosConfig, launcher: TileLauncher, contactNames: (String) -> String, onDone: () -> Unit,
    emergency: app.semplicephone.model.EmergencyInfo = app.semplicephone.model.EmergencyInfo(), onEmergency: () -> Unit = {},
) {
    val context = LocalContext.current
    val done by rememberUpdatedState(onDone)
    var remaining by remember { mutableIntStateOf(cfg.countdownSeconds.coerceIn(3, 15)) }
    var status by remember { mutableStateOf<String?>(null) }
    // SMS non inviabile in automatico (permesso negato o bloccato da Android): testo pronto per l'app Messaggi.
    var manualSms by remember { mutableStateOf<String?>(null) }
    val fetcher = remember { LocationFetcher(context) }

    LaunchedEffect(Unit) {
        coroutineScope {
            val fix = async { fetcher.current(timeoutMs = remaining * 1000L + 3000) }
            while (remaining > 0) {
                delay(1000)
                remaining--
            }
            status = context.getString(R.string.sos_sending)
            // Al massimo 3 s oltre il conto alla rovescia per il fix, poi l'ultima posizione nota.
            val location: Location? = withContext(Dispatchers.Default) {
                kotlinx.coroutines.withTimeoutOrNull(3000) { fix.await() } ?: fetcher.lastKnown()
            }
            fix.cancel()
            val smsText = SosSms.text(context, cfg, location)
            val sent = withContext(Dispatchers.IO) { SosSms.send(context, cfg.numbers, smsText) }
            app.semplicephone.remote.RemoteEvents.enqueue(
                context, "sos", smsText,
                mapOf("smsSent" to sent, "lat" to location?.latitude, "lon" to location?.longitude, "acc" to location?.accuracy, "fixAt" to location?.time, "msg" to cfg.message.trim()),
            )
            cfg.numbers.firstOrNull()?.let { launcher.call(it) }
            if (sent > 0) {
                status = context.resources.getQuantityString(R.plurals.sos_sms_sent, sent, sent)
                delay(4000)
                done()
            } else {
                // La chiamata è partita; l'SMS con la posizione lo si manda con un tocco dall'app Messaggi.
                status = context.getString(R.string.sos_call_started)
                manualSms = smsText
            }
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
        Text(stringResource(R.string.sos_title), color = Color.White, fontSize = TextSize.Big, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        // Dopo l'invio, chi arriva ad aiutare trova qui farmaci, allergie e contatti.
        if (status != null && !emergency.isEmpty) app.semplicephone.ui.BigButton(
            stringResource(R.string.emergency_open), app.semplicephone.ui.Tone.Light, symbol = "heart", height = app.semplicephone.ui.Ui.HeightRow,
            textSize = app.semplicephone.ui.TextSize.Label, onClick = onEmergency,
        )
        if (status == null) {
            Text(stringResource(R.string.sos_starts_in), color = Color.White, fontSize = TextSize.Label)
            Box(
                Modifier.size(220.dp).border(10.dp, Color.White, CircleShape).semantics { liveRegion = LiveRegionMode.Assertive },
                contentAlignment = Alignment.Center,
            ) { Text("$remaining", color = Color.White, fontSize = 130.sp, fontWeight = FontWeight.Bold) }
            Text(stringResource(R.string.sos_calling, contactNames(first)), color = Color.White, fontSize = TextSize.Label, textAlign = TextAlign.Center)
            if (cfg.numbers.isNotEmpty()) {
                Text(
                    stringResource(
                        if (app.semplicephone.BuildConfig.SEND_SMS) R.string.sos_will_sms else R.string.sos_will_compose,
                        cfg.numbers.joinToString(", ") { contactNames(it) },
                    ),
                    color = Color.White, fontSize = TextSize.Label, textAlign = TextAlign.Center,
                )
            }
        } else {
            Text(status!!, color = Color.White, fontSize = TextSize.Title, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.weight(1f))
        manualSms?.let { text ->
            app.semplicephone.ui.BigButton(stringResource(R.string.sos_send_location), app.semplicephone.ui.Tone.Light, symbol = "pin") {
                SosSms.openComposer(context, cfg.numbers, text)
            }
            app.semplicephone.ui.CloseButton(onDone, onDark = true)
        }
        if (status == null) {
            app.semplicephone.ui.BigButton(
                stringResource(R.string.sos_cancel), app.semplicephone.ui.Tone.Light, symbol = "close",
                height = app.semplicephone.ui.Ui.HeightHuge, textSize = app.semplicephone.ui.TextSize.Huge, onClick = onDone,
            )
        }
    }
}
