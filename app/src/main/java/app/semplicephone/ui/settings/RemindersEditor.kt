package app.semplicephone.ui.settings

import app.semplicephone.ui.Ui
import app.semplicephone.ui.TextSize
import app.semplicephone.ui.Symbols
import androidx.compose.material3.Icon
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import app.semplicephone.R
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.semplicephone.model.Reminder
import app.semplicephone.reminders.ReminderScheduler
import app.semplicephone.reminders.ReminderTime
import app.semplicephone.ui.Palette

/** Elenco promemoria + modifica del singolo, tutto sulla bozza (si applica con Salva). */
@Composable
fun RemindersEditor(
    initial: List<Reminder>,
    scheduler: ReminderScheduler,
    onSave: (List<Reminder>) -> Unit,
    onCancel: () -> Unit,
    onExternalStart: () -> Unit,
) {
    val context = LocalContext.current
    var list by remember { mutableStateOf(initial) }
    var editing by remember { mutableStateOf<Reminder?>(null) }

    editing?.let { r ->
        ReminderForm(
            onExternalStart = onExternalStart,
            initial = r,
            isNew = list.none { it.id == r.id },
            onDone = { updated -> list = if (list.any { it.id == updated.id }) list.map { if (it.id == updated.id) updated else it } else list + updated; editing = null },
            onDelete = { list = list.filterNot { it.id == r.id }; editing = null },
            onCancel = { editing = null },
        )
        return
    }

    Column(Modifier.fillMaxSize().background(Palette.Soft).windowInsetsPadding(WindowInsets.safeDrawing)) {
        EditorHeader(stringResource(R.string.reminders_title), onCancel, stringResource(R.string.action_done)) { onSave(list.sortedBy { it.time }) }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!scheduler.canScheduleExact() && Build.VERSION.SDK_INT >= 31) {
                Warning(stringResource(R.string.reminders_exact_warning)) {
                    onExternalStart()
                    app.semplicephone.launch.Permissions.openSystemPage(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                }
            }
            if (!scheduler.canFullScreen() && Build.VERSION.SDK_INT >= 34) {
                Warning(stringResource(R.string.reminders_fullscreen_warning)) {
                    onExternalStart()
                    app.semplicephone.launch.Permissions.openSystemPage(context, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")))
                }
            }
            if (list.isEmpty()) Text(stringResource(R.string.reminders_none), fontSize = TextSize.Body, color = Palette.Mute)
            list.sortedBy { it.time }.forEach { r ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(Ui.ShapeSmall)
                        .background(Color.White)
                        .clickable { editing = r }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Symbols.get(r.icon.takeIf { it in Symbols.names } ?: "pill"), null, Modifier.size(30.dp).padding(end = 6.dp), tint = Palette.Ink)
                    Text(r.time, fontSize = TextSize.Title, fontWeight = FontWeight.Bold, color = if (r.enabled) Palette.Ink else Palette.Mute)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(r.title, fontSize = TextSize.Body, fontWeight = FontWeight.Bold)
                        Text(
                            listOfNotNull(r.detail.takeIf { it.isNotBlank() }, if (r.date.isNotBlank()) r.date else daysSummary(r.days), if (!r.enabled) stringResource(R.string.reminder_disabled) else null).joinToString(" · "),
                            fontSize = TextSize.Small, color = Palette.Mute,
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = { editing = Reminder(id = "r" + System.currentTimeMillis().toString(36), time = "08:00", title = context.getString(R.string.default_reminder_title)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = Ui.HeightSettings),
            ) { Text(stringResource(R.string.reminders_add)) }
        }
    }
}

@Composable
private fun ReminderForm(
    initial: Reminder, isNew: Boolean, onDone: (Reminder) -> Unit, onDelete: () -> Unit, onCancel: () -> Unit,
    onExternalStart: () -> Unit,
) {
    val context = LocalContext.current
    var r by remember { mutableStateOf(initial) }
    val t = ReminderTime.parse(r.time) ?: java.time.LocalTime.of(8, 0)
    fun setTime(h: Int, m: Int) { r = r.copy(time = "%02d:%02d".format(h, m)) }

    Column(Modifier.fillMaxSize().background(Palette.Soft).windowInsetsPadding(WindowInsets.safeDrawing)) {
        EditorHeader(stringResource(if (isNew) R.string.reminder_new else R.string.reminders_title), onCancel, enabled = r.title.isNotBlank()) { onDone(r) }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Modelli rapidi: titolo e simbolo in un tocco (farmaco, doccia, spesa, visita…)
            SectionLabel(stringResource(R.string.reminder_presets))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReminderPresets.forEach { (icon, label) ->
                    val text = stringResource(label)
                    OutlinedButton(
                        onClick = { r = r.copy(title = text, icon = icon) },
                        modifier = Modifier.heightIn(min = Ui.HeightSettings),
                    ) {
                        Icon(Symbols.get(icon), null, Modifier.size(22.dp), tint = Palette.Ink)
                        Text(" $text", color = Palette.Ink)
                    }
                }
            }
            Text(r.time, fontSize = 56.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Stepper(stringResource(R.string.reminder_hour), t.hour, 0..23, { setTime(it, t.minute) }, format = { "%02d".format(it) })
                Stepper(stringResource(R.string.reminder_minutes), t.minute / 5, 0..11, { setTime(t.hour, it * 5) }, format = { "%02d".format(it * 5) })
            }
            Toggle(
                stringResource(R.string.reminder_one_day), stringResource(R.string.reminder_one_day_detail), r.date.isNotBlank(),
            ) { on ->
                r = r.copy(date = if (on) java.time.LocalDate.now().plusDays(1).toString() else "")
            }
            if (r.date.isNotBlank()) {
                val d = ReminderTime.parseDate(r.date) ?: java.time.LocalDate.now()
                val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
                OutlinedButton(
                    onClick = {
                        android.app.DatePickerDialog(context, { _, y, m, day ->
                            r = r.copy(date = java.time.LocalDate.of(y, m + 1, day).toString())
                        }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = Ui.HeightSettings),
                ) {
                    Text(
                        d.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL).withLocale(locale)),
                        fontSize = TextSize.Body, color = Palette.Ink,
                    )
                }
            }
            if (r.date.isBlank()) SectionLabel(stringResource(R.string.reminder_days))
            if (r.date.isBlank()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
                (1..7).forEach { day ->
                    val label = ReminderTime.dayInitial(day, locale)
                    val dayName = java.time.DayOfWeek.of(day).getDisplayName(java.time.format.TextStyle.FULL, locale)
                    val on = r.days.isEmpty() || day in r.days
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(Ui.ShapeSmall)
                            .background(if (on) Palette.Ink else Color.White)
                            .border(2.dp, Palette.Ink, Ui.ShapeSmall)
                            .clickable {
                                val current = if (r.days.isEmpty()) (1..7).toSet() else r.days.toSet()
                                val next = if (day in current) current - day else current + day
                                r = r.copy(days = if (next.size == 7 || next.isEmpty()) emptyList() else next.sorted())
                            }
                            .semantics { contentDescription = dayName; this.selected = on },
                        contentAlignment = Alignment.Center,
                    ) { Text(label, color = if (on) Color.White else Palette.Ink, fontWeight = FontWeight.Bold, fontSize = TextSize.Body) }
                }
            }
            if (r.date.isBlank()) Text(daysSummary(r.days), fontSize = TextSize.Small, color = Palette.Mute)
            OutlinedTextField(r.title, { r = r.copy(title = it) }, label = { Text(stringResource(R.string.reminder_title)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(r.detail, { r = r.copy(detail = it) }, label = { Text(stringResource(R.string.reminder_detail)) }, modifier = Modifier.fillMaxWidth())
            SectionLabel(stringResource(R.string.tile_icon))
            SymbolGrid(r.icon) { r = r.copy(icon = it) }
            SectionLabel(stringResource(R.string.reminder_photo))
            Text(stringResource(R.string.reminder_photo_hint), fontSize = TextSize.Small, color = Palette.Mute)
            SinglePhotoField(r.photo, { r = r.copy(photo = it) }, onExternalStart)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Stepper(stringResource(R.string.reminder_snooze), r.snoozeMinutes, 5..60, { r = r.copy(snoozeMinutes = it) })
                Stepper(stringResource(R.string.reminder_repeat), r.repeatMinutes, 1..30, { r = r.copy(repeatMinutes = it) })
            }
            Stepper(stringResource(R.string.reminder_max_repeats), r.maxRepeats, 0..20, { r = r.copy(maxRepeats = it) })
            // Scorta: pastiglie nella scatola oggi; il telefono conta le «Presa» e avvisa quando finiscono.
            Stepper(stringResource(R.string.reminder_stock), r.stock.coerceAtLeast(0), 0..200,
                { r = r.copy(stock = if (it == 0) -1 else it, stockSetAt = System.currentTimeMillis()) },
                format = { if (it == 0) context.getString(R.string.reminder_stock_off) else "$it" })
            if (r.stock > 0) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Stepper(stringResource(R.string.reminder_per_dose), r.perDose, 1..6, { r = r.copy(perDose = it) })
                Stepper(stringResource(R.string.reminder_low_at), r.lowAt, 1..30, { r = r.copy(lowAt = it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.reminder_active), fontSize = TextSize.Body, modifier = Modifier.weight(1f))
                Switch(checked = r.enabled, onCheckedChange = { r = r.copy(enabled = it) })
            }
            if (!isNew) {
                OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth().heightIn(min = Ui.HeightSettings)) {
                    Text(stringResource(R.string.reminder_delete), color = Palette.Sos, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun Warning(text: String, onFix: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(Ui.ShapeSmall).background(Palette.WarnSoft).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, fontSize = TextSize.Small, color = Palette.Ink)
        OutlinedButton(onClick = onFix, modifier = Modifier.heightIn(min = Ui.HeightSettings)) { Text(stringResource(R.string.action_open_settings)) }
    }
}

@Composable
private fun daysSummary(days: List<Int>): String = ReminderTime.daysSummary(
    days, stringResource(R.string.days_every), stringResource(R.string.days_weekdays),
    androidx.compose.ui.platform.LocalConfiguration.current.locales[0],
)

/** Modelli rapidi: simbolo e titolo (tradotto). */
private val ReminderPresets = listOf(
    "pill" to R.string.preset_medicine,
    "shower" to R.string.preset_shower,
    "cart" to R.string.preset_shopping,
    "doctor" to R.string.preset_visit,
    "cards" to R.string.preset_cards,
    "walk" to R.string.preset_walk,
    "water" to R.string.preset_water,
    "food" to R.string.preset_meal,
    "thermometer" to R.string.preset_measure,
    "syringe" to R.string.preset_insulin,
    "trash" to R.string.preset_trash,
    "plant" to R.string.preset_plants,
    "moon" to R.string.preset_bed,
)
