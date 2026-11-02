package app.semplicephone.ui.settings

import app.semplicephone.R
import androidx.compose.ui.res.stringResource
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
import app.semplicephone.ui.Palette

/** Suoneria protetta, avviso batteria, pallini e comando SMS (si applica con Salva). */
@Composable
fun PhoneEditor(initial: PhoneConfig, hasSosNumbers: Boolean, onSave: (PhoneConfig) -> Unit, onCancel: () -> Unit) {
    var p by remember { mutableStateOf(initial) }
    val never = stringResource(R.string.phone_never)
    val keyword = stringResource(R.string.sms_keyword)

    Column(Modifier.fillMaxSize().background(Color(0xFFECE9E2)).windowInsetsPadding(WindowInsets.safeDrawing)) {
        EditorHeader(stringResource(R.string.phone_title), onCancel, stringResource(R.string.action_done)) { onSave(p) }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Toggle(
                stringResource(R.string.phone_ringer_lock),
                stringResource(R.string.phone_ringer_lock_detail),
                p.ringerLock,
            ) { p = p.copy(ringerLock = it) }
            if (p.ringerLock) {
                Stepper(stringResource(R.string.phone_min_volume), p.ringVolumePercent / 10, 3..10, { p = p.copy(ringVolumePercent = it * 10) }, format = { "${it * 10}%" })
            }

            SectionLabel(stringResource(R.string.phone_battery))
            Stepper(
                stringResource(R.string.phone_battery_below), p.batteryAlertPercent / 5, 0..6,
                { p = p.copy(batteryAlertPercent = it * 5) },
                format = { if (it == 0) never else "${it * 5}%" },
            )
            Text(
                when {
                    !BuildConfig.SEND_SMS -> stringResource(R.string.phone_battery_play)
                    hasSosNumbers -> stringResource(R.string.phone_battery_sms)
                    else -> stringResource(R.string.phone_battery_no_numbers)
                },
                fontSize = 15.sp, color = Color(0xFF4A4A4A),
            )

            if (BuildConfig.FULL) {
                Toggle(
                    stringResource(R.string.phone_badges),
                    stringResource(R.string.phone_badges_detail),
                    p.badges,
                ) { p = p.copy(badges = it) }

                Toggle(
                    stringResource(R.string.phone_sms_command, keyword),
                    stringResource(R.string.phone_sms_command_detail, keyword),
                    p.smsUpdateCommand,
                ) { p = p.copy(smsUpdateCommand = it) }
            } else {
                SectionLabel(stringResource(R.string.phone_full_only))
                Text(
                    stringResource(
                        if (BuildConfig.EDITION == "play") R.string.phone_full_only_play else R.string.phone_full_only_standard,
                        keyword,
                    ),
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
