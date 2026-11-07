package app.semplicephone.sos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.semplicephone.R
import app.semplicephone.model.EmergencyInfo
import app.semplicephone.ui.Voice

/** Righe (titolo, valore) della scheda, solo quelle compilate. */
fun emergencyRows(context: android.content.Context, e: EmergencyInfo): List<Pair<String, String>> = listOf(
    R.string.emergency_name to e.name, R.string.emergency_birth to e.birth, R.string.emergency_conditions to e.conditions,
    R.string.emergency_medications to e.medications, R.string.emergency_allergies to e.allergies,
    R.string.emergency_blood to e.bloodType, R.string.emergency_doctor to e.doctor, R.string.emergency_notes to e.notes,
).filter { it.second.isNotBlank() }.map { context.getString(it.first) to it.second.trim() }

/**
 * Scheda per i soccorritori: testo nero su bianco, molto grande, leggibile da chi arriva ad aiutare;
 * «LEGGI» la dice ad alta voce (utile al telefono con il 112).
 */
@Composable
fun EmergencyScreen(info: EmergencyInfo, onClose: () -> Unit) {
    val context = LocalContext.current
    val rows = emergencyRows(context, info)
    Column(
        Modifier.fillMaxSize().background(Color.White).windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.emergency_title), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color(0xFFC62828))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (rows.isEmpty()) Text(stringResource(R.string.emergency_empty), fontSize = 22.sp, color = Color.Black)
            rows.forEach { (k, v) ->
                Column {
                    Text(k, fontSize = 18.sp, color = Color(0xFF555555))
                    Text(v, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { Voice.speak(context, rows.joinToString(". ") { "${it.first}: ${it.second}" }) },
                modifier = Modifier.weight(1f).heightIn(min = 88.dp), shape = RoundedCornerShape(20.dp),
            ) { Text(stringResource(R.string.emergency_read), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.Black) }
            Button(
                onClick = onClose, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F4FA8)),
                modifier = Modifier.weight(1f).heightIn(min = 88.dp), shape = RoundedCornerShape(20.dp),
            ) { Text(stringResource(R.string.video_close), fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        }
    }
}
