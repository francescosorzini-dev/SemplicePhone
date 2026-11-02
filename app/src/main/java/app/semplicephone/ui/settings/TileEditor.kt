package app.semplicephone.ui.settings

import android.content.Context
import androidx.compose.ui.platform.LocalContext
import app.semplicephone.R
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.semplicephone.data.AppEntry
import app.semplicephone.data.ConfigRepository
import app.semplicephone.data.TileConflict
import app.semplicephone.launch.TileLauncher
import app.semplicephone.model.IconKind
import app.semplicephone.model.Page
import app.semplicephone.model.SosConfig
import app.semplicephone.model.Tile
import app.semplicephone.model.TileIcon
import app.semplicephone.model.TileType
import app.semplicephone.ui.Palette
import app.semplicephone.ui.Symbols
import app.semplicephone.ui.TileView

private val TypeLabels = linkedMapOf(
    TileType.APP to R.string.type_app,
    TileType.CALL to R.string.type_call,
    TileType.WA_VIDEO to R.string.type_wa_video,
    TileType.SMS to R.string.type_sms,
    TileType.URL to R.string.type_url,
    TileType.SHORTCUT to R.string.type_shortcut,
    TileType.CLOCK to R.string.label_clock,
    TileType.SOS to 0,
)

private fun typeLabel(context: Context, type: TileType): String =
    TypeLabels.getValue(type).let { if (it == 0) "SOS" else context.getString(it) }

private val IconLabels = linkedMapOf(
    IconKind.AUTO to R.string.icon_auto,
    IconKind.APP to R.string.icon_app,
    IconKind.SYMBOL to R.string.icon_symbol,
    IconKind.PHOTO to R.string.icon_photo,
    IconKind.NONE to R.string.icon_none,
)

/**
 * Modifica di un tile. [page] è la pagina che lo contiene (con il tile nella versione salvata,
 * o senza se nuovo): serve per i limiti di griglia e il controllo sovrapposizioni.
 */
@Composable
fun TileEditor(
    initial: Tile,
    page: Page,
    isNew: Boolean,
    sos: SosConfig,
    sosCountElsewhere: Int,
    apps: List<AppEntry>,
    launcher: TileLauncher,
    onSave: (Tile) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    onExternalStart: () -> Unit = {},
) {
    val context = LocalContext.current
    var t by remember { mutableStateOf(initial) }
    var pickApp by remember { mutableStateOf(false) }
    var pickShortcut by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val conflict = ConfigRepository.conflict(page, t)
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val picked = res.data?.data?.let { launcher.contacts.readPicked(it) } ?: return@rememberLauncherForActivityResult
        val keepLabel = t.label.isNotBlank() && t.label != defaultLabel(context, t.type)
        t = t.copy(
            target = picked.number,
            label = if (keepLabel) t.label else picked.name,
            icon = when (t.icon.kind) {
                IconKind.AUTO, IconKind.PHOTO -> TileIcon(t.icon.kind, picked.lookupKey?.let { "contact:$it" }.orEmpty())
                else -> t.icon
            },
        )
    }

    Column(Modifier.fillMaxSize().background(Color(0xFFECE9E2)).windowInsetsPadding(WindowInsets.safeDrawing)) {
        EditorHeader(stringResource(if (isNew) R.string.tile_new else R.string.tile_edit), onCancel, stringResource(R.string.action_save), enabled = conflict == null) { onSave(t) }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Anteprima reale, non interattiva (overlay che assorbe i tocchi).
            Box(Modifier.fillMaxWidth(if (t.w >= 2) 1f else 0.5f).height(if (t.h >= 2) 220.dp else 120.dp)) {
                TileView(t, sos, launcher, Modifier.matchParentSize())
                Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { } })
            }
            if (conflict != null) Text(
                when (conflict) {
                    is TileConflict.OutOfGrid -> stringResource(R.string.conflict_out_of_grid, conflict.cols, conflict.rows)
                    is TileConflict.Overlap -> stringResource(
                        R.string.conflict_overlap, conflict.other.label.ifBlank { typeLabel(context, conflict.other.type) },
                    )
                },
                color = Palette.Sos, fontSize = 16.sp, fontWeight = FontWeight.Bold)

            OptionPicker(stringResource(R.string.tile_action), TypeLabels.keys.toList(), t.type, { typeLabel(context, it) }) { type ->
                t = t.copy(type = type, label = if (t.label.isBlank()) defaultLabel(context, type) else t.label)
            }

            when (t.type) {
                TileType.APP -> {
                    SectionLabel(stringResource(R.string.tile_app))
                    val current = apps.firstOrNull { it.target == t.target }?.label ?: t.target.ifBlank { stringResource(R.string.icon_none) }
                    OutlinedButton(onClick = { pickApp = true }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(10.dp)) {
                        Text(current, fontSize = 17.sp, color = Palette.Ink)
                    }
                }
                TileType.CALL, TileType.WA_VIDEO, TileType.SMS -> {
                    OutlinedButton(
                        onClick = {
                            onExternalStart()
                            pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text(stringResource(R.string.tile_pick_contact), fontSize = 17.sp, color = Palette.Ink) }
                    Field(stringResource(R.string.tile_number), t.target, KeyboardType.Phone) { t = t.copy(target = it.filter { c -> c.isDigit() || c == '+' }) }
                    if (t.type == TileType.WA_VIDEO) Hint(stringResource(R.string.tile_wa_hint))
                    if (t.type == TileType.SMS) Field(stringResource(R.string.tile_sms_text), t.body) { t = t.copy(body = it) }
                }
                TileType.URL -> Field(stringResource(R.string.tile_url), t.target, KeyboardType.Uri) { t = t.copy(target = it.trim()) }
                TileType.SHORTCUT -> {
                    SectionLabel(stringResource(R.string.tile_shortcut))
                    OutlinedButton(onClick = { pickShortcut = true }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(10.dp)) {
                        Text(t.target.ifBlank { stringResource(R.string.tile_choose) }.substringAfter('/').take(40), fontSize = 17.sp, color = Palette.Ink)
                    }
                    Hint(stringResource(R.string.tile_shortcut_hint))
                }
                TileType.CLOCK -> Hint(stringResource(R.string.tile_clock_hint))
                TileType.SOS -> Hint(stringResource(R.string.tile_sos_hint))
            }

            if (t.type != TileType.CLOCK) Field(stringResource(R.string.tile_label), t.label) { t = t.copy(label = it) }

            if (t.type != TileType.CLOCK && t.type != TileType.SOS) {
                OptionPicker(stringResource(R.string.tile_icon), IconLabels.keys.toList(), t.icon.kind, { context.getString(IconLabels.getValue(it)) }) { k ->
                    t = t.copy(icon = TileIcon(k, if (k == IconKind.SYMBOL) t.icon.ref.ifBlank { Symbols.defaultFor(t.type) } else ""))
                }
                if (t.icon.kind == IconKind.SYMBOL) SymbolGrid(t.icon.ref) { t = t.copy(icon = TileIcon(IconKind.SYMBOL, it)) }
                if (t.icon.kind == IconKind.PHOTO) Hint(stringResource(R.string.tile_photo_hint))
            }

            SectionLabel(stringResource(R.string.tile_color))
            ColorSwatches(t.color) { t = t.copy(color = it) }

            SectionLabel(stringResource(R.string.tile_size_position))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Stepper(stringResource(R.string.tile_width), t.w, 1..page.cols, { t = t.copy(w = it) })
                Stepper(stringResource(R.string.tile_height), t.h, 1..page.rows, { t = t.copy(h = it) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Stepper(stringResource(R.string.tile_column), t.col + 1, 1..page.cols, { t = t.copy(col = it - 1) })
                Stepper(stringResource(R.string.tile_row), t.row + 1, 1..page.rows, { t = t.copy(row = it - 1) })
            }

            if (!isNew) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(stringResource(R.string.tile_delete), color = Palette.Sos, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }

    if (pickApp) {
        AppPickerDialog(apps, launcher, onDismiss = { pickApp = false }, onPick = { app ->
            val oldDefault = apps.firstOrNull { it.target == t.target }?.defaultLabel
            val keepLabel = t.label.isNotBlank() && t.label != oldDefault && t.label != defaultLabel(context, TileType.APP)
            t = t.copy(target = app.target, label = if (keepLabel) t.label else app.defaultLabel)
            pickApp = false
        })
    }

    if (pickShortcut) {
        ShortcutPickerDialog(launcher, onDismiss = { pickShortcut = false }) { sc ->
            val keepLabel = t.label.isNotBlank() && t.label != defaultLabel(context, TileType.SHORTCUT)
            t = t.copy(target = sc.target, label = if (keepLabel) t.label else sc.label)
            pickShortcut = false
        }
    }

    if (confirmDelete) {
        val lastSos = t.type == TileType.SOS && sosCountElsewhere == 0
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(if (lastSos) R.string.tile_delete_last_sos else R.string.tile_delete_confirm)) },
            text = {
                Text(
                    if (lastSos) stringResource(R.string.tile_delete_last_sos_text)
                    else stringResource(R.string.tile_delete_text, t.label.ifBlank { typeLabel(context, t.type) }),
                )
            },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.action_delete), color = Palette.Sos) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

fun defaultLabel(context: Context, type: TileType): String = when (type) {
    TileType.APP -> context.getString(R.string.label_app)
    TileType.CALL -> context.getString(R.string.label_call)
    TileType.WA_VIDEO -> context.getString(R.string.label_video)
    TileType.SMS -> context.getString(R.string.label_message)
    TileType.URL -> context.getString(R.string.label_internet)
    TileType.SHORTCUT -> context.getString(R.string.label_shortcut)
    TileType.CLOCK -> ""
    TileType.SOS -> "SOS"
}

@Composable
fun EditorHeader(title: String, onBack: () -> Unit, action: String, enabled: Boolean = true, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.action_back), fontSize = 17.sp, color = Palette.Ink) }
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, modifier = Modifier.weight(1f).padding(start = 4.dp))
        Button(
            onClick = onAction,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Ink),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.height(48.dp),
        ) { Text(action, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun Field(label: String, value: String, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = keyboard != KeyboardType.Text,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 15.sp, color = Color(0xFF4A4A4A))
}
