package app.semplicephone.ui.settings

import app.semplicephone.ui.Ui
import app.semplicephone.ui.TextSize
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.semplicephone.R
import app.semplicephone.data.Backup
import app.semplicephone.model.LauncherConfig
import app.semplicephone.ui.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Copia di sicurezza senza portale: versioni salvate sul telefono (una a ogni salvataggio) da
 * ripristinare, ed esportazione/importazione su file (config + foto) per cambiare telefono.
 * Ripristino e importazione caricano la config nell'editor: si salva come al solito.
 */
@Composable
fun BackupScreen(current: LauncherConfig, onLoad: (LauncherConfig) -> Unit, onBack: () -> Unit, onExternalStart: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    val copies = remember { Backup.list(context) }
    val fmt = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault()) }
    val exportOk = stringResource(R.string.backup_exported)
    val failed = stringResource(R.string.backup_failed)
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { msg = if (withContext(Dispatchers.IO) { Backup.export(context, current, uri) }) exportOk else failed }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val cfg = withContext(Dispatchers.IO) { Backup.import(context, uri) }
            if (cfg != null) onLoad(cfg) else msg = failed
        }
    }

    Column(Modifier.fillMaxSize()) {
        EditorHeader(stringResource(R.string.backup_title), onBack, onAction = onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.backup_intro), fontSize = TextSize.Small, color = Palette.Ink)
            OutlinedButton(
                onClick = { onExternalStart(); export.launch("SemplicePhone-${java.time.LocalDate.now()}.zip") },
                modifier = Modifier.fillMaxWidth().heightIn(min = Ui.HeightSettings),
            ) { Text(stringResource(R.string.backup_export), fontSize = TextSize.Body) }
            OutlinedButton(
                onClick = { onExternalStart(); import.launch(arrayOf("application/zip", "application/octet-stream")) },
                modifier = Modifier.fillMaxWidth().heightIn(min = Ui.HeightSettings),
            ) { Text(stringResource(R.string.backup_import), fontSize = TextSize.Body) }
            msg?.let { Text(it, fontSize = TextSize.Body, color = Palette.Ink) }

            SectionLabel(stringResource(R.string.backup_saved))
            if (copies.isEmpty()) Text(stringResource(R.string.backup_none), fontSize = TextSize.Small, color = Palette.Ink)
            copies.forEach { (at, file) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(fmt.format(Instant.ofEpochMilli(at)), fontSize = TextSize.Body, color = Palette.Ink, modifier = Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { Backup.load(file)?.let(onLoad) ?: run { msg = failed } },
                        modifier = Modifier.heightIn(min = Ui.HeightSettings),
                    ) { Text(stringResource(R.string.backup_restore)) }
                }
            }
        }
    }
}
