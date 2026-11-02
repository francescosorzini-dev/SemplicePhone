package app.semplicephone.ui.settings

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
fun SosEditor(initial: SosConfig, onSave: (SosConfig) -> Unit, onCancel: () -> Unit) {
    var numbers by remember { mutableStateOf(initial.numbers.ifEmpty { listOf("") }) }
    var hold by remember { mutableStateOf(initial.holdSeconds) }
    val clean = numbers.map { it.trim() }.filter { it.isNotEmpty() }

    Column(Modifier.fillMaxSize().background(Color(0xFFECE9E2)).windowInsetsPadding(WindowInsets.safeDrawing)) {
        EditorHeader("SOS", onCancel, "Fatto", enabled = clean.isNotEmpty()) { onSave(initial.copy(numbers = clean, holdSeconds = hold)) }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                if (app.semplicephone.BuildConfig.SEND_SMS) "Il primo numero viene chiamato. Tutti riceveranno un SMS con la posizione."
                else "Il primo numero viene chiamato. Poi lo schermo mostra «MANDA LA POSIZIONE», che apre Messaggi già pronto per tutti i numeri.",
                fontSize = 15.sp, color = Color(0xFF4A4A4A),
            )
            Text("Usa numeri di familiari o vicini: Android non permette alle app di chiamare direttamente " +
                "il 112 e il servizio SMS del 112 è riservato a chi è registrato.", fontSize = 15.sp, color = Color(0xFF4A4A4A))
            numbers.forEachIndexed { i, n ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = n,
                        onValueChange = { v -> numbers = numbers.toMutableList().also { it[i] = v.filter { c -> c.isDigit() || c == '+' } } },
                        label = { Text(if (i == 0) "Numero da chiamare" else "Numero ${i + 1}") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.weight(1f),
                    )
                    if (numbers.size > 1) {
                        TextButton(onClick = { numbers = numbers.filterIndexed { j, _ -> j != i } }, modifier = Modifier.height(48.dp)) {
                            Text("Togli", color = Palette.Sos)
                        }
                    }
                }
            }
            OutlinedButton(onClick = { numbers = numbers + "" }, enabled = numbers.size < 5, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("+ Aggiungi numero")
            }
            Stepper("Secondi di pressione", hold, 1..10, { hold = it })
        }
    }
}
