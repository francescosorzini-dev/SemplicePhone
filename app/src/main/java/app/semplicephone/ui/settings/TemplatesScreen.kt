package app.semplicephone.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.semplicephone.R
import app.semplicephone.data.Template
import app.semplicephone.ui.Palette

/** Scelta di un modello pronto; [onPick] riceve il modello confermato, [onSkip] lascia tutto com'è. */
@Composable
fun TemplatesScreen(firstTime: Boolean, onPick: (Template) -> Unit, onSkip: () -> Unit) {
    var asking by remember { mutableStateOf<Template?>(null) }
    Column(
        Modifier.fillMaxSize().background(Color(0xFFF6F4EF)).windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.tpl_title), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        Text(stringResource(R.string.tpl_lead), fontSize = 16.sp, color = Color(0xFF4A4A4A))
        Template.entries.forEach { t ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)
                    .clickable { if (firstTime) onPick(t) else asking = t }.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(t.title), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                Text(stringResource(t.description), fontSize = 16.sp, color = Color(0xFF333333))
            }
        }
        OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(if (firstTime) R.string.tpl_skip_first else R.string.tpl_back), fontSize = 18.sp)
        }
    }
    asking?.let { t ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(stringResource(t.title)) },
            text = { Text(stringResource(R.string.tpl_confirm)) },
            confirmButton = { TextButton(onClick = { asking = null; onPick(t) }) { Text(stringResource(R.string.tpl_apply)) } },
            dismissButton = { TextButton(onClick = { asking = null }) { Text(stringResource(R.string.tpl_back)) } },
        )
    }
}
