package app.semplicephone.reminders

import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.res.stringResource
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.semplicephone.R
import app.semplicephone.data.ConfigRepository
import app.semplicephone.ui.Palette
import app.semplicephone.ui.SempliceTheme
import app.semplicephone.ui.Symbols

/** Allarme a schermo intero, anche a telefono bloccato: un solo gesto ovvio, "PRESO". */
class ReminderActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(ReminderReceiver.EXTRA_ID) ?: return finish()
        val r = app.semplicephone.data.LocalizedDefaults.apply(this, ConfigRepository.readCurrent(this)).reminders.firstOrNull { it.id == id } ?: return finish()

        setContent {
            SempliceTheme {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFFFFF8E1))
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Spacer(Modifier.height(24.dp))
                    Text(r.time, fontSize = 72.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                    Text(r.title, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, textAlign = TextAlign.Center)
                    val photo = androidx.compose.runtime.remember(r.photo) {
                        app.semplicephone.data.Media.bitmap(this@ReminderActivity, r.photo, 900)?.asImageBitmap()
                    }
                    if (photo != null) {
                        androidx.compose.foundation.Image(
                            photo, contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).aspectRatio(4f / 3f)
                                .clip(RoundedCornerShape(20.dp)),
                        )
                    } else {
                        Icon(Symbols.get(r.icon.takeIf { it in Symbols.names } ?: "pill"), null, Modifier.size(120.dp), tint = Palette.Ink)
                    }
                    if (r.detail.isNotBlank()) Text(r.detail, fontSize = 26.sp, color = Palette.Ink, textAlign = TextAlign.Center)
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { ReminderReceiver.taken(this@ReminderActivity, id); finish() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B6E34)),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    ) { Text(stringResource(if (r.icon == "pill") R.string.reminder_taken_big else R.string.reminder_done_big), fontSize = 44.sp, fontWeight = FontWeight.Bold) }
                    OutlinedButton(
                        onClick = { ReminderReceiver.snooze(this@ReminderActivity, id); finish() },
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                    ) { Text(androidx.compose.ui.res.pluralStringResource(R.plurals.reminder_snooze_long, r.snoozeMinutes, r.snoozeMinutes), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink) }
                }
            }
        }
    }
}
