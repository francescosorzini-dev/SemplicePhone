package app.semplicephone.ui.settings

import androidx.compose.foundation.layout.heightIn
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
fun PhoneEditor(
    initial: PhoneConfig, hasSosNumbers: Boolean, onSave: (PhoneConfig) -> Unit, onCancel: () -> Unit,
    onExternalStart: () -> Unit = {},
) {
    var p by remember { mutableStateOf(initial) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val roles = remember { context.getSystemService(android.app.role.RoleManager::class.java) }
    var screening by remember { mutableStateOf(roles.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING)) }
    val askScreening = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { screening = roles.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING) }
    fun requestScreening() {
        if (!screening && roles.isRoleAvailable(android.app.role.RoleManager.ROLE_CALL_SCREENING)) {
            onExternalStart()
            askScreening.launch(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_CALL_SCREENING))
        }
    }
    val askRedirect = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { }
    /** Blocco chiamate e chiamate lunghe: SemplicePhone come app di reindirizzamento chiamate. */
    fun requestRedirect() {
        val r = android.app.role.RoleManager.ROLE_CALL_REDIRECTION
        if (roles.isRoleAvailable(r) && !roles.isRoleHeld(r)) { onExternalStart(); runCatching { askRedirect.launch(roles.createRequestRoleIntent(r)) } }
    }
    fun contactsGranted() = androidx.core.content.ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.READ_CONTACTS,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    var contacts by remember { mutableStateOf(contactsGranted()) }
    val askContacts = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { contacts = contactsGranted() }
    fun requestContacts() {
        if (!contacts) { onExternalStart(); askContacts.launch(android.Manifest.permission.READ_CONTACTS) }
    }
    var overlay by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
    val askOverlay = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { overlay = android.provider.Settings.canDrawOverlays(context) }
    fun requestOverlay() {
        if (overlay) return
        onExternalStart()
        runCatching {
            askOverlay.launch(
                android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}")),
            )
        }
    }
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

            SectionLabel(stringResource(R.string.phone_calls))
            Toggle(stringResource(R.string.phone_block_unknown), stringResource(R.string.phone_block_unknown_detail), p.blockUnknown) {
                p = p.copy(blockUnknown = it)
                if (it) { requestContacts(); requestScreening() }
            }
            if ((p.blockUnknown || p.autoAnswer || p.announceCaller || p.nightQuiet) && !screening) {
                Text(stringResource(R.string.phone_block_needs_role), fontSize = 15.sp, color = Palette.Sos)
                androidx.compose.material3.OutlinedButton(onClick = ::requestScreening, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(stringResource(R.string.phone_block_grant))
                }
            }
            if (p.blockUnknown && !contacts) {
                Text(stringResource(R.string.phone_block_needs_contacts), fontSize = 15.sp, color = Palette.Sos)
                androidx.compose.material3.OutlinedButton(onClick = ::requestContacts, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(stringResource(R.string.phone_block_contacts_grant))
                }
            }
            Toggle(stringResource(R.string.phone_speaker), stringResource(R.string.phone_speaker_detail), p.speakerCalls) { p = p.copy(speakerCalls = it) }
            Toggle(stringResource(R.string.phone_auto_answer), stringResource(R.string.phone_auto_answer_detail), p.autoAnswer) {
                p = p.copy(autoAnswer = it)
                if (it) requestScreening()
            }
            if (p.autoAnswer) {
                Stepper(stringResource(R.string.phone_auto_answer_after), (p.autoAnswerSeconds / 5).coerceIn(1, 6), 1..6, { p = p.copy(autoAnswerSeconds = it * 5) }, format = { "${it * 5} s" })
                if (!hasSosNumbers) Text(stringResource(R.string.phone_auto_answer_no_sos), fontSize = 15.sp, color = Palette.Sos)
            }
            Toggle(stringResource(R.string.phone_night), stringResource(R.string.phone_night_detail), p.nightQuiet) {
                p = p.copy(nightQuiet = it)
                if (it) requestScreening()
            }
            if (p.nightQuiet) {
                val fromH = p.nightFrom.take(2).toIntOrNull() ?: 22
                val toH = p.nightTo.take(2).toIntOrNull() ?: 7
                Stepper(stringResource(R.string.phone_night_from), fromH, 18..23, { p = p.copy(nightFrom = "%02d:00".format(it)) }, format = { "$it:00" })
                Stepper(stringResource(R.string.phone_night_to), toH, 5..10, { p = p.copy(nightTo = "%02d:00".format(it)) }, format = { "$it:00" })
            }
            Toggle(stringResource(R.string.phone_announce), stringResource(R.string.phone_announce_detail), p.announceCaller) {
                p = p.copy(announceCaller = it)
                if (it) { requestContacts(); requestScreening() }
            }

            SectionLabel(stringResource(R.string.phone_voice_screen))
            Toggle(stringResource(R.string.phone_speak_tiles), stringResource(R.string.phone_speak_tiles_detail), p.speakTiles) { p = p.copy(speakTiles = it) }
            Toggle(stringResource(R.string.phone_voice_confirm), stringResource(R.string.phone_voice_confirm_detail), p.voiceConfirm) { p = p.copy(voiceConfirm = it) }
            Toggle(stringResource(R.string.phone_bedside), stringResource(R.string.phone_bedside_detail), p.bedsideClock) { p = p.copy(bedsideClock = it) }
            Toggle(stringResource(R.string.phone_charge), stringResource(R.string.phone_charge_detail), p.chargeReminder) { p = p.copy(chargeReminder = it) }
            if (p.chargeReminder) {
                Stepper(stringResource(R.string.phone_charge_time), p.chargeTime.take(2).toIntOrNull() ?: 20, 17..23, { p = p.copy(chargeTime = "%02d:00".format(it)) }, format = { "$it:00" })
                Stepper(stringResource(R.string.phone_charge_below), p.chargeBelow / 10, 2..8, { p = p.copy(chargeBelow = it * 10) }, format = { "${it * 10}%" })
            }
            Toggle(stringResource(R.string.phone_hold), stringResource(R.string.phone_hold_detail), p.holdToOpen) { p = p.copy(holdToOpen = it) }
            val themes = linkedMapOf(
                "normal" to stringResource(R.string.phone_theme_normal),
                "contrast" to stringResource(R.string.phone_theme_contrast),
                "night" to stringResource(R.string.phone_theme_night),
            )
            OptionPicker(stringResource(R.string.phone_theme), themes.keys.toList(), p.theme, { themes[it].orEmpty() }) { p = p.copy(theme = it) }
            Toggle(stringResource(R.string.phone_home_button), stringResource(R.string.phone_home_button_detail), p.homeButton) {
                p = p.copy(homeButton = it)
                if (it) requestOverlay()
            }
            if (p.homeButton && !overlay) {
                Text(stringResource(R.string.phone_home_needs_overlay), fontSize = 15.sp, color = Palette.Sos)
                androidx.compose.material3.OutlinedButton(onClick = ::requestOverlay, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(stringResource(R.string.phone_home_grant))
                }
            }
            if (p.homeButton) {
                val sides = linkedMapOf(
                    app.semplicephone.launch.HomeButton.SIDE_RIGHT to stringResource(R.string.phone_home_side_right),
                    app.semplicephone.launch.HomeButton.SIDE_LEFT to stringResource(R.string.phone_home_side_left),
                )
                OptionPicker(stringResource(R.string.phone_home_side), sides.keys.toList(), p.homeButtonSide, { sides[it].orEmpty() }) {
                    p = p.copy(homeButtonSide = it)
                }
                Toggle(stringResource(R.string.phone_home_label), stringResource(R.string.phone_home_label_detail), p.homeButtonLabel) {
                    p = p.copy(homeButtonLabel = it)
                }
                SectionLabel(stringResource(R.string.phone_home_color))
                ColorSwatches(p.homeButtonColor) { p = p.copy(homeButtonColor = it) }
            }

            Toggle(
                stringResource(R.string.phone_fresh_start),
                stringResource(R.string.phone_fresh_start_detail),
                p.freshStart,
            ) { p = p.copy(freshStart = it) }

            SectionLabel(stringResource(R.string.phone_safety))
            Toggle(stringResource(R.string.phone_remote_apps), stringResource(R.string.phone_remote_apps_detail), p.remoteAppAlert) { p = p.copy(remoteAppAlert = it) }
            Toggle(stringResource(R.string.phone_scam), stringResource(R.string.phone_scam_detail), p.scamGuard) { p = p.copy(scamGuard = it) }
            Toggle(stringResource(R.string.phone_block_premium), stringResource(R.string.phone_block_premium_detail), p.blockPremium) { p = p.copy(blockPremium = it); if (it) requestRedirect() }
            Toggle(stringResource(R.string.phone_block_international), stringResource(R.string.phone_block_international_detail), p.blockInternational) { p = p.copy(blockInternational = it); if (it) requestRedirect() }
            Stepper(stringResource(R.string.phone_long_call), p.longCallMinutes / 5, 0..12, { p = p.copy(longCallMinutes = it * 5); if (it > 0) requestRedirect() }, format = { if (it == 0) never else "${it * 5} min" })
            Toggle(stringResource(R.string.phone_sim), stringResource(R.string.phone_sim_detail), p.simAlert) { p = p.copy(simAlert = it) }
            Stepper(stringResource(R.string.phone_inactive), p.inactivityHours / 2, 0..6, { p = p.copy(inactivityHours = it * 2) }, format = { if (it == 0) never else "${it * 2} h" })
            Toggle(stringResource(R.string.phone_fall), stringResource(R.string.phone_fall_detail), p.fallDetection) { p = p.copy(fallDetection = it) }
            Toggle(stringResource(R.string.phone_heat), stringResource(R.string.phone_heat_detail), p.heatAlert) { p = p.copy(heatAlert = it) }
            if (p.heatAlert) Stepper(stringResource(R.string.phone_heat_threshold), p.heatThreshold, 28..38, { p = p.copy(heatThreshold = it) }, format = { "$it°" })
            if (BuildConfig.FULL) Text(
                if (p.safeZoneRadius > 0) stringResource(R.string.phone_zone_on, p.safeZoneRadius) else stringResource(R.string.phone_zone_off),
                fontSize = 15.sp, color = Color(0xFF4A4A4A),
            )

            SectionLabel(stringResource(R.string.phone_comfort))
            Stepper(stringResource(R.string.phone_call_volume), p.callVolumePercent / 10, 0..10, { p = p.copy(callVolumePercent = it * 10) }, format = { if (it == 0) never else "${it * 10}%" })
            val scales = listOf(0f, 1.15f, 1.3f, 1.5f, 1.8f)
            OptionPicker(stringResource(R.string.phone_font_scale), scales, scales.minByOrNull { kotlin.math.abs(it - p.fontScale) } ?: 0f,
                { if (it == 0f) context.getString(R.string.phone_unchanged) else "${(it * 100).toInt()}%" }) { p = p.copy(fontScale = it) }
            val timeouts = listOf(0, 60, 120, 300, 600, 1800)
            OptionPicker(stringResource(R.string.phone_screen_timeout), timeouts, timeouts.minByOrNull { kotlin.math.abs(it - p.screenTimeoutSeconds) } ?: 0,
                { if (it == 0) context.getString(R.string.phone_unchanged) else if (it < 60) "$it s" else "${it / 60} min" }) { p = p.copy(screenTimeoutSeconds = it) }
            Stepper(stringResource(R.string.phone_brightness), p.brightnessPercent / 10, 0..10, { p = p.copy(brightnessPercent = it * 10) },
                format = { if (it == 0) context.getString(R.string.phone_unchanged) else "${it * 10}%" })
            Toggle(stringResource(R.string.phone_morning), stringResource(R.string.phone_morning_detail), p.morningBrief) { p = p.copy(morningBrief = it) }
            if (p.morningBrief) Stepper(stringResource(R.string.phone_morning_time), p.morningTime.take(2).toIntOrNull() ?: 9, 6..12,
                { p = p.copy(morningTime = "%02d:00".format(it)) }, format = { "$it:00" })

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

            if (BuildConfig.NOTIFICATIONS) Toggle(
                stringResource(R.string.phone_badges),
                stringResource(R.string.phone_badges_detail),
                p.badges,
            ) { p = p.copy(badges = it) }
            if (BuildConfig.FULL) {
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
internal fun Toggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 18.sp, color = Palette.Ink)
            Text(detail, fontSize = 14.sp, color = Color(0xFF4A4A4A))
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
