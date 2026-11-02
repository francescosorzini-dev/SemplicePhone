package app.semplicephone.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import app.semplicephone.data.AppCatalog
import app.semplicephone.data.AppEntry
import app.semplicephone.launch.TileLauncher
import app.semplicephone.ui.Palette
import app.semplicephone.ui.Symbols
import app.semplicephone.ui.parseColor

/** Palette ad alto contrasto: ogni colore regge testo bianco o nero a 4.5:1. */
val TilePalette = listOf(
    "#1F4FA8" to "Blu", "#0B6E4F" to "Verde", "#6A1B9A" to "Viola", "#1A1A1A" to "Nero",
    "#B71C1C" to "Rosso", "#A84300" to "Arancio", "#00695C" to "Petrolio", "#FFFFFF" to "Bianco",
    "#FFF3C4" to "Giallo chiaro",
)

@Composable
fun SectionLabel(text: String) {
    Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
}

@Composable
fun Stepper(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    format: (Int) -> String = { "$it" },
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 15.sp, color = Palette.Ink)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { onChange(value - 1) },
                enabled = value > range.first,
                modifier = Modifier.size(48.dp).semantics { contentDescription = "$label meno" },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) { Text("−", fontSize = 22.sp) }
            Text(format(value), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
            OutlinedButton(
                onClick = { onChange(value + 1) },
                enabled = value < range.last,
                modifier = Modifier.size(48.dp).semantics { contentDescription = "$label più" },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) { Text("+", fontSize = 22.sp) }
        }
    }
}

/** Bottone che mostra il valore scelto e apre una lista di opzioni. */
@Composable
fun <T> OptionPicker(label: String, options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel(label)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(10.dp)) {
            Text(text(selected), fontSize = 17.sp, color = Palette.Ink, modifier = Modifier.weight(1f))
            Text("▾", fontSize = 17.sp, color = Palette.Ink)
        }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(options) { o ->
                        Text(
                            text(o),
                            fontSize = 18.sp,
                            fontWeight = if (o == selected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(o); open = false }
                                .padding(vertical = 14.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Chiudi") } },
        )
    }
}

@Composable
fun ColorSwatches(selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TilePalette.chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (hex, name) ->
                    val isSel = hex.equals(selected, ignoreCase = true)
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(parseColor(hex))
                            .border(if (isSel) 4.dp else 2.dp, if (isSel) androidx.compose.ui.graphics.Color(0xFFE8A33D) else Palette.Ink, CircleShape)
                            .clickable { onSelect(hex) }
                            .semantics { contentDescription = name; this.selected = isSel },
                    )
                }
            }
        }
    }
}

@Composable
fun SymbolGrid(selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Symbols.names.toList().chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { name ->
                    val isSel = name == selected
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSel) Palette.Ink else androidx.compose.ui.graphics.Color.White)
                            .border(2.dp, Palette.Ink, RoundedCornerShape(10.dp))
                            .clickable { onSelect(name) }
                            .semantics { contentDescription = name; this.selected = isSel },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Symbols.get(name), null, Modifier.size(30.dp),
                            tint = if (isSel) androidx.compose.ui.graphics.Color.White else Palette.Ink,
                        )
                    }
                }
            }
        }
    }
}

/** Selezione app con ricerca per contenuto/iniziali. */
@Composable
fun AppPickerDialog(apps: List<AppEntry>, launcher: TileLauncher, onPick: (AppEntry) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(query, apps) { apps.filter { AppCatalog.matches(query, it.label) } }
    val iconPx = with(LocalDensity.current) { 40.dp.roundToPx() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scegli app") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Cerca") },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(shown, key = { it.target }) { app ->
                        val icon = remember(app.target) {
                            launcher.appIcon(app.target)?.toBitmap(iconPx, iconPx)?.asImageBitmap()
                        }
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(app) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (icon != null) Image(icon, null, Modifier.size(40.dp)) else Spacer(Modifier.size(40.dp))
                            Spacer(Modifier.size(12.dp))
                            Text(app.label, fontSize = 17.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

/** Elenco delle scorciatoie delle app installate, con ricerca. */
@Composable
fun ShortcutPickerDialog(launcher: TileLauncher, onDismiss: () -> Unit, onPick: (app.semplicephone.launch.ShortcutEntry) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var query by remember { mutableStateOf("") }
    val all by androidx.compose.runtime.produceState<List<app.semplicephone.launch.ShortcutEntry>?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.semplicephone.launch.ShortcutPins.available(context) }
    }
    val canUse = remember { app.semplicephone.launch.ShortcutPins.canUse(context) }
    val iconPx = with(LocalDensity.current) { 36.dp.roundToPx() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scegli scorciatoia") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    !canUse -> Text("Disponibile solo con SemplicePhone impostato come schermata Home.")
                    all == null -> Text("Caricamento…")
                    all!!.isEmpty() -> Text("Nessuna app installata offre scorciatoie.")
                    else -> {
                        OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Cerca") }, modifier = Modifier.fillMaxWidth())
                        val shown = all!!.filter { AppCatalog.matches(query, it.label) || AppCatalog.matches(query, it.appLabel) }
                        LazyColumn(Modifier.heightIn(max = 420.dp)) {
                            items(shown, key = { it.target }) { sc ->
                                val icon = remember(sc.pkg) { launcher.appIcon(sc.pkg)?.toBitmap(iconPx, iconPx)?.asImageBitmap() }
                                Row(Modifier.fillMaxWidth().clickable { onPick(sc) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (icon != null) Image(icon, null, Modifier.size(36.dp)) else Spacer(Modifier.size(36.dp))
                                    Spacer(Modifier.size(12.dp))
                                    Column {
                                        Text(sc.label, fontSize = 17.sp)
                                        Text(sc.appLabel, fontSize = 14.sp, color = androidx.compose.ui.graphics.Color(0xFF4A4A4A))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}
