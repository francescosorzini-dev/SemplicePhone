package app.semplicephone.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.semplicephone.BuildConfig
import app.semplicephone.model.PhoneConfig
import app.semplicephone.remote.SmsCommand
import app.semplicephone.ui.Palette

/** Suoneria protetta, avviso batteria, pallini e comando SMS (si applica con Salva). */
@Composable
fun PhoneEditor(initial: PhoneConfig, hasSosNumbers: Boolean, onSave: (PhoneConfig) -> Unit, onCancel: () -> Unit) {
    var p by remember { mutableStateOf(initial) }

    Column(Modifier.fillMaxSize().background(Color(0xFFECE9E2)).windowInsetsPadding(WindowInsets.safeDrawing)) {
        EditorHeader("Suoneria e avvisi", onCancel, "Fatto") { onSave(p) }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Toggle(
                "Suoneria protetta",
                "Se il telefono viene messo in silenzioso o il volume si abbassa, torna al livello scelto. Vale anche per le sveglie dei promemoria.",
                p.ringerLock,
            ) { p = p.copy(ringerLock = it) }
            if (p.ringerLock) {
                Stepper("Volume minimo", p.ringVolumePercent / 10, 3..10, { p = p.copy(ringVolumePercent = it * 10) }, format = { "${it * 10}%" })
            }

            SectionLabel("Batteria scarica")
            Stepper(
                "Avvisa sotto il", p.batteryAlertPercent / 5, 0..6,
                { p = p.copy(batteryAlertPercent = it * 5) },
                format = { if (it == 0) "mai" else "${it * 5}%" },
            )
            Text(
                when {
                    !BuildConfig.SEND_SMS -> "Compare un avviso in Home. Con la gestione da remoto arriva anche un messaggio al portale."
                    hasSosNumbers -> "Compare un avviso in Home e parte un SMS ai numeri SOS (una volta per ogni scarica)."
                    else -> "Compare un avviso in Home. Per l'SMS ai familiari imposta prima i numeri SOS."
                },
                fontSize = 15.sp, color = Color(0xFF4A4A4A),
            )

            if (BuildConfig.FULL) {
                Toggle(
                    "Pallino sui riquadri",
                    "Mostra quante chiamate perse e quanti messaggi non letti ci sono sui riquadri delle app (Telefono, Messaggi, WhatsApp…).",
                    p.badges,
                ) { p = p.copy(badges = it) }

                Toggle(
                    "Comando SMS «${SmsCommand.KEYWORD}»",
                    "Un familiare tra i numeri SOS può mandare l'SMS «${SmsCommand.KEYWORD}» per far applicare subito una configurazione pubblicata dal portale.",
                    p.smsUpdateCommand,
                ) { p = p.copy(smsUpdateCommand = it) }
            } else {
                SectionLabel("Solo nell'edizione completa")
                Text(
                    if (BuildConfig.EDITION == "play")
                        "Pallini con messaggi non letti, comando SMS «${SmsCommand.KEYWORD}» e SMS automatici non sono " +
                            "nella versione di Google Play. Ci sono nell'edizione completa (F-Droid o sito del progetto)."
                    else
                        "Pallini con messaggi non letti, comando SMS «${SmsCommand.KEYWORD}» e aggiornamento automatico " +
                        "richiedono permessi che Android blocca sulle app scaricate dal browser. Sono nell'edizione " +
                        "completa, da installare con ADB (vedi la guida nel sito del progetto).",
                    fontSize = 15.sp, color = Color(0xFF4A4A4A),
                )
            }
        }
    }
}

@Composable
private fun Toggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 18.sp, color = Palette.Ink)
            Text(detail, fontSize = 14.sp, color = Color(0xFF4A4A4A))
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
