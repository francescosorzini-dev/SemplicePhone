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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.semplicephone.admin.Protection
import app.semplicephone.ui.Palette

@Composable
fun ProtectionScreen(protection: Protection, onBack: () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    var confirmRelease by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val isOwner = remember(tick) { protection.isDeviceOwner }

    Column(Modifier.fillMaxSize().background(Color(0xFFECE9E2)).windowInsetsPadding(WindowInsets.safeDrawing)) {
        EditorHeader(stringResource(R.string.protection_title), onBack, stringResource(R.string.action_done)) { onBack() }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.protection_always),
                fontSize = 15.sp, color = Color(0xFF4A4A4A),
            )
            if (!isOwner) {
                SectionLabel(stringResource(R.string.protection_advanced))
                Text(
                    stringResource(R.string.protection_how),
                    fontSize = 15.sp,
                )
                SelectionContainer {
                    Text(Protection.ADB_COMMAND, fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().background(Color.White).padding(12.dp))
                }
                Text(stringResource(R.string.protection_after), fontSize = 15.sp)
                OutlinedButton(onClick = { tick++ }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.protection_check_again)) }
            } else {
                SectionLabel(stringResource(R.string.protection_is_owner))
                Protection.Item.entries.forEach { item ->
                    val on = remember(tick, item) { protection.isOn(item) }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(item.label), fontSize = 17.sp, color = Palette.Ink)
                            if (item.detail != 0) Text(stringResource(item.detail), fontSize = 14.sp, color = Color(0xFF4A4A4A))
                        }
                        Switch(checked = on, onCheckedChange = {
                            runCatching { protection.set(item, it) }.onFailure { e -> error = e.message }
                            tick++
                        })
                    }
                }
                error?.let { Text(it, color = Palette.Sos, fontSize = 15.sp) }
                OutlinedButton(onClick = { confirmRelease = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(stringResource(R.string.protection_release), color = Palette.Sos)
                }
            }
        }
    }

    if (confirmRelease) {
        AlertDialog(
            onDismissRequest = { confirmRelease = false },
            title = { Text(stringResource(R.string.protection_release_confirm)) },
            text = { Text(stringResource(R.string.protection_release_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRelease = false
                    runCatching { protection.releaseDeviceOwner() }.onFailure { e -> error = e.message }
                    tick++
                }) { Text(stringResource(R.string.protection_remove), color = Palette.Sos) }
            },
            dismissButton = { TextButton(onClick = { confirmRelease = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
