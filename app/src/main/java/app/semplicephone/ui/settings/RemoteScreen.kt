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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.semplicephone.BuildConfig
import app.semplicephone.remote.RemoteClient
import app.semplicephone.update.UpdateInfo
import app.semplicephone.update.Updater
import app.semplicephone.remote.RemoteSettings
import app.semplicephone.ui.Palette
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Config remota: URL (con {device}), chiave pubblica Ed25519 del server, ID del dispositivo da
 * registrare sul server. Le impostazioni si salvano subito (non fanno parte della bozza).
 */
@Composable
fun RemoteScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { RemoteSettings(context) }
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(settings.enabled) }
    var url by remember { mutableStateOf(settings.url) }
    var key by remember { mutableStateOf(settings.publicKey) }
    var status by remember { mutableStateOf(settings.lastResult) }
    var statusOk by remember { mutableStateOf(settings.lastOk) }
    var lastCheck by remember { mutableStateOf(settings.lastCheck) }
    var busy by remember { mutableStateOf(false) }
    val updater = remember { Updater(context) }
    var updateUrl by remember { mutableStateOf(updater.url) }
    var updateStatus by remember { mutableStateOf(updater.lastResult) }
    var available by remember { mutableStateOf<UpdateInfo?>(null) }

    fun persist() {
        settings.url = url
        settings.publicKey = key
        settings.enabled = enabled
        updater.url = updateUrl
        RemoteClient.schedule(context)
    }

    Column(Modifier.fillMaxSize().background(Palette.Soft).windowInsetsPadding(WindowInsets.safeDrawing)) {
        EditorHeader(stringResource(R.string.layout_remote), { persist(); onBack() }, stringResource(R.string.action_done)) { persist(); onBack() }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.remote_intro),
                fontSize = TextSize.Small, color = Palette.Mute,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.remote_enable), fontSize = TextSize.Body, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
            var allowLocate by remember { mutableStateOf(settings.allowLocate) }
            val askBackground = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
            ) { }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.remote_allow_locate), fontSize = TextSize.Body)
                    Text(stringResource(R.string.remote_allow_locate_detail), fontSize = TextSize.Small, color = Palette.Mute)
                }
                Switch(checked = allowLocate, onCheckedChange = {
                    allowLocate = it
                    settings.allowLocate = it
                    // Fuori dalla Home serve la posizione «sempre»: solo nell'edizione completa.
                    if (it && BuildConfig.FULL &&
                        !app.semplicephone.launch.Permissions.granted(context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    ) runCatching { askBackground.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
                })
            }
            SectionLabel(stringResource(R.string.remote_device_id))
            SelectionContainer {
                Text(settings.deviceId, fontFamily = FontFamily.Monospace, fontSize = TextSize.Label, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().background(Color.White).padding(12.dp))
            }
            OutlinedTextField(
                url, { url = it },
                label = { Text(stringResource(R.string.remote_url)) },
                placeholder = { Text("https://example.com/semplicephone/?device={device}") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(key, { key = it }, label = { Text(stringResource(R.string.remote_pubkey)) }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(
                onClick = {
                    persist()
                    busy = true
                    scope.launch {
                        val res = RemoteClient(context).sync(force = true)
                        status = res.text
                        statusOk = res.ok
                        lastCheck = settings.lastCheck
                        busy = false
                    }
                },
                enabled = !busy && enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = Ui.HeightSettings),
            ) { Text(stringResource(if (busy) R.string.remote_checking else R.string.remote_sync_now)) }
            if (lastCheck > 0) {
                Text(
                    stringResource(R.string.remote_last_check, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lastCheck))),
                    fontSize = TextSize.Small,
                )
            }
            if (status.isNotBlank()) {
                Text(status, fontSize = TextSize.Body, fontWeight = FontWeight.Bold,
                    color = if (statusOk) Palette.Ok else Palette.Ink)
            }
            Text(stringResource(R.string.remote_applied_seq, settings.lastSeq), fontSize = TextSize.Small, color = Palette.Mute)

            SectionLabel(stringResource(R.string.updates_title))
            Text(stringResource(R.string.updates_installed, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.EDITION), fontSize = TextSize.Small)
            if (!updater.selfUpdate) {
                Text(stringResource(R.string.updates_from_store, updater.store ?: "Google Play"), fontSize = TextSize.Small, color = Palette.Mute)
            } else {
                OutlinedTextField(updateUrl, { updateUrl = it }, label = { Text(stringResource(R.string.updates_url)) }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(
                    onClick = {
                        updater.url = updateUrl
                        busy = true
                        scope.launch {
                            available = updater.check()
                            updateStatus = updater.lastResult
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Ui.HeightSettings),
                ) { Text(stringResource(R.string.updates_check)) }
                available?.let { info ->
                    OutlinedButton(
                        onClick = {
                            busy = true
                            scope.launch {
                                updater.install(info)
                                updateStatus = updater.lastResult
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = Ui.HeightSettings),
                    ) { Text(stringResource(if (BuildConfig.FULL) R.string.updates_install else R.string.updates_download, info.versionName)) }
                }
                if (updateStatus.isNotBlank()) Text(updateStatus, fontSize = TextSize.Small)
                Text(
                    stringResource(if (BuildConfig.FULL) R.string.updates_full_note else R.string.updates_standard_note),
                    fontSize = TextSize.Small, color = Palette.Mute,
                )
            }
        }
    }
}
