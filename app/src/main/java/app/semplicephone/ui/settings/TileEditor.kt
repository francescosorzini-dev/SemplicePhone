package app.semplicephone.ui.settings

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
    TileType.APP to "Apri app",
    TileType.CALL to "Chiama numero",
    TileType.WA_VIDEO to "Videochiamata WhatsApp",
    TileType.SMS to "SMS precompilato",
    TileType.URL to "Pagina web",
    TileType.SHORTCUT to "Scorciatoia app",
    TileType.CLOCK to "Orologio",
    TileType.SOS to "SOS",
)

private val IconLabels = linkedMapOf(
    IconKind.AUTO to "Automatica",
    IconKind.APP to "Icona dell'app",
    IconKind.SYMBOL to "Simbolo",
    IconKind.PHOTO to "Foto contatto",
    IconKind.NONE to "Nessuna",
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
    var t by remember { mutableStateOf(initial) }
    var pickApp by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val conflict = ConfigRepository.conflict(page, t)
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val picked = res.data?.data?.let { launcher.contacts.readPicked(it) } ?: return@rememberLauncherForActivityResult
        val keepLabel = t.label.isNotBlank() && t.label != defaultLabel(t.type)
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
        EditorHeader(if (isNew) "Nuovo tile" else "Modifica tile", onCancel, "Salva", enabled = conflict == null) { onSave(t) }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Anteprima reale, non interattiva (overlay che assorbe i tocchi).
            Box(Modifier.fillMaxWidth(if (t.w >= 2) 1f else 0.5f).height(if (t.h >= 2) 220.dp else 120.dp)) {
                TileView(t, sos, launcher, Modifier.matchParentSize())
                Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { } })
            }
            if (conflict != null) Text(conflict, color = Palette.Sos, fontSize = 16.sp, fontWeight = FontWeight.Bold)

            OptionPicker("Azione", TypeLabels.keys.toList(), t.type, { TypeLabels.getValue(it) }) { type ->
                t = t.copy(type = type, label = if (t.label.isBlank()) defaultLabel(type) else t.label)
            }

            when (t.type) {
                TileType.APP -> {
                    SectionLabel("App")
                    val current = apps.firstOrNull { it.target == t.target }?.label ?: t.target.ifBlank { "Nessuna" }
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
                    ) { Text("Scegli dalla rubrica", fontSize = 17.sp, color = Palette.Ink) }
                    Field("Numero di telefono", t.target, KeyboardType.Phone) { t = t.copy(target = it.filter { c -> c.isDigit() || c == '+' }) }
                    if (t.type == TileType.WA_VIDEO) Hint("Il numero deve essere salvato in rubrica con WhatsApp attivo.")
                    if (t.type == TileType.SMS) Field("Testo del messaggio", t.body) { t = t.copy(body = it) }
                }
                TileType.URL -> Field("Indirizzo (https://…)", t.target, KeyboardType.Uri) { t = t.copy(target = it.trim()) }
                TileType.SHORTCUT -> {
                    Field("Scorciatoia (pacchetto/id)", t.target) { t = t.copy(target = it.trim()) }
                    Hint("Selezione guidata delle scorciatoie: prossima versione.")
                }
                TileType.CLOCK -> Hint("Mostra ora e data. 5 tocchi rapidi aprono le impostazioni.")
                TileType.SOS -> Hint("Numeri e durata della pressione si impostano nella sezione SOS.")
            }

            if (t.type != TileType.CLOCK) Field("Testo sul tile", t.label) { t = t.copy(label = it) }

            if (t.type != TileType.CLOCK && t.type != TileType.SOS) {
                OptionPicker("Icona", IconLabels.keys.toList(), t.icon.kind, { IconLabels.getValue(it) }) { k ->
                    t = t.copy(icon = TileIcon(k, if (k == IconKind.SYMBOL) t.icon.ref.ifBlank { Symbols.defaultFor(t.type) } else ""))
                }
                if (t.icon.kind == IconKind.SYMBOL) SymbolGrid(t.icon.ref) { t = t.copy(icon = TileIcon(IconKind.SYMBOL, it)) }
                if (t.icon.kind == IconKind.PHOTO) Hint("Usa la foto del contatto in rubrica; senza foto mostra le iniziali.")
            }

            SectionLabel("Colore")
            ColorSwatches(t.color) { t = t.copy(color = it) }

            SectionLabel("Dimensione e posizione")
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Stepper("Larghezza", t.w, 1..page.cols, { t = t.copy(w = it) })
                Stepper("Altezza", t.h, 1..page.rows, { t = t.copy(h = it) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Stepper("Colonna", t.col + 1, 1..page.cols, { t = t.copy(col = it - 1) })
                Stepper("Riga", t.row + 1, 1..page.rows, { t = t.copy(row = it - 1) })
            }

            if (!isNew) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Elimina tile", color = Palette.Sos, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }

    if (pickApp) {
        AppPickerDialog(apps, launcher, onDismiss = { pickApp = false }, onPick = { app ->
            val oldDefault = apps.firstOrNull { it.target == t.target }?.defaultLabel
            val keepLabel = t.label.isNotBlank() && t.label != oldDefault && t.label != defaultLabel(TileType.APP)
            t = t.copy(target = app.target, label = if (keepLabel) t.label else app.defaultLabel)
            pickApp = false
        })
    }

    if (confirmDelete) {
        val lastSos = t.type == TileType.SOS && sosCountElsewhere == 0
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (lastSos) "Togliere l'ultimo SOS?" else "Eliminare il tile?") },
            text = {
                Text(
                    if (lastSos) "Dopo l'eliminazione non ci sarà più nessun pulsante SOS nella schermata Home."
                    else "«${t.label.ifBlank { TypeLabels.getValue(t.type) }}» verrà tolto dalla Home.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Elimina", color = Palette.Sos) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annulla") } },
        )
    }
}

fun defaultLabel(type: TileType): String = when (type) {
    TileType.APP -> "App"
    TileType.CALL -> "Chiama"
    TileType.WA_VIDEO -> "Videochiamata"
    TileType.SMS -> "Messaggio"
    TileType.URL -> "Internet"
    TileType.SHORTCUT -> "Scorciatoia"
    TileType.CLOCK -> ""
    TileType.SOS -> "SOS"
}

@Composable
fun EditorHeader(title: String, onBack: () -> Unit, action: String, enabled: Boolean = true, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack, modifier = Modifier.height(48.dp)) { Text("‹ Indietro", fontSize = 17.sp, color = Palette.Ink) }
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
