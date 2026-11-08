package app.semplicephone.ui.settings

import app.semplicephone.ui.Ui
import app.semplicephone.ui.TextSize
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.semplicephone.model.SosConfig
import app.semplicephone.ui.Palette

/** Numeri SOS (il primo viene chiamato, a tutti l'SMS da M4) e durata della pressione. */
@Composable
fun SosEditor(
    initial: SosConfig, onSave: (SosConfig, app.semplicephone.model.EmergencyInfo) -> Unit, onCancel: () -> Unit,
    emergency: app.semplicephone.model.EmergencyInfo = app.semplicephone.model.EmergencyInfo(),
) {
    var e by remember { mutableStateOf(emergency) }
    var numbers by remember { mutableStateOf(initial.numbers.ifEmpty { listOf("") }) }
    var hold by remember { mutableStateOf(initial.holdSeconds) }
    val clean = numbers.map { it.trim() }.filter { it.isNotEmpty() }

    Column(Modifier.fillMaxSize().background(Palette.Soft).windowInsetsPadding(WindowInsets.safeDrawing)) {
        EditorHeader("SOS", onCancel, stringResource(R.string.action_done), enabled = clean.isNotEmpty()) { onSave(initial.copy(numbers = clean, holdSeconds = hold), e) }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(if (app.semplicephone.BuildConfig.SEND_SMS) R.string.sos_editor_sms else R.string.sos_editor_compose),
                fontSize = TextSize.Small, color = Palette.Mute,
            )
            Text(stringResource(R.string.sos_editor_112), fontSize = TextSize.Small, color = Palette.Mute)
            numbers.forEachIndexed { i, n ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = n,
                        onValueChange = { v -> numbers = numbers.toMutableList().also { it[i] = v.filter { c -> c.isDigit() || c == '+' } } },
                        label = { Text(if (i == 0) stringResource(R.string.sos_editor_first) else stringResource(R.string.sos_editor_number, i + 1)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.weight(1f),
                    )
                    if (numbers.size > 1) {
                        TextButton(onClick = { numbers = numbers.filterIndexed { j, _ -> j != i } }, modifier = Modifier.heightIn(min = Ui.HeightSettings)) {
                            Text(stringResource(R.string.sos_editor_remove), color = Palette.Sos)
                        }
                    }
                }
            }
            OutlinedButton(onClick = { numbers = numbers + "" }, enabled = numbers.size < 5, modifier = Modifier.fillMaxWidth().heightIn(min = Ui.HeightSettings)) {
                Text(stringResource(R.string.sos_editor_add))
            }
            Stepper(stringResource(R.string.sos_editor_hold), hold, 1..10, { hold = it })
            // Scheda per i soccorritori: compare nella schermata SOS dopo l'invio.
            Text(stringResource(R.string.emergency_title), fontSize = TextSize.Label, color = Palette.Ink)
            Text(stringResource(R.string.emergency_editor_hint), fontSize = TextSize.Small, color = Palette.Mute)
            listOf<Triple<Int, String, (String) -> Unit>>(
                Triple(R.string.emergency_name, e.name) { v -> e = e.copy(name = v) },
                Triple(R.string.emergency_birth, e.birth) { v -> e = e.copy(birth = v) },
                Triple(R.string.emergency_conditions, e.conditions) { v -> e = e.copy(conditions = v) },
                Triple(R.string.emergency_medications, e.medications) { v -> e = e.copy(medications = v) },
                Triple(R.string.emergency_allergies, e.allergies) { v -> e = e.copy(allergies = v) },
                Triple(R.string.emergency_blood, e.bloodType) { v -> e = e.copy(bloodType = v) },
                Triple(R.string.emergency_doctor, e.doctor) { v -> e = e.copy(doctor = v) },
                Triple(R.string.emergency_notes, e.notes) { v -> e = e.copy(notes = v) },
            ).forEach { (label, value, set) ->
                OutlinedTextField(value, set, label = { Text(stringResource(label)) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
