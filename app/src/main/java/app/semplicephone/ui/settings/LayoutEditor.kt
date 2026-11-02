package app.semplicephone.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.semplicephone.data.ConfigRepository
import app.semplicephone.launch.Permissions
import app.semplicephone.model.LauncherConfig
import app.semplicephone.model.Page
import app.semplicephone.model.Tile
import app.semplicephone.model.TileType
import app.semplicephone.ui.Palette
import app.semplicephone.ui.contentColorFor
import app.semplicephone.ui.parseColor

private val Gap = 8.dp

/**
 * Editor della disposizione: lavora su una bozza [draft]; nulla viene applicato alla Home
 * finché non si preme Salva (che passa per la validazione del repository).
 */
@Composable
fun LayoutEditor(
    draft: LauncherConfig,
    pageIndex: Int,
    error: String?,
    onPageIndex: (Int) -> Unit,
    onDraftChange: (LauncherConfig) -> Unit,
    onEditTile: (pageIndex: Int, tile: Tile) -> Unit,
    onAddTile: (pageIndex: Int, col: Int, row: Int) -> Unit,
    onEditSos: () -> Unit,
    onChangePin: () -> Unit,
    onResetDefaults: () -> Unit,
    onSave: () -> Unit,
    onExit: () -> Unit,
    missingPermissions: List<Permissions.Need> = emptyList(),
    onRequestPermissions: () -> Unit = {},
    missingSpecial: List<Permissions.Special> = emptyList(),
    onOpenSpecial: (Permissions.Special) -> Unit = {},
    onExternalStart: () -> Unit = {},
    restrictionHelp: Boolean = false,
    extraSections: @Composable () -> Unit = {},
) {
    val page = draft.pages.getOrNull(pageIndex) ?: return
    val context = LocalContext.current
    var notice by remember { mutableStateOf<String?>(null) }
    var showAbout by remember { mutableStateOf(false) }
    val exportDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        notice = runCatching {
            context.contentResolver.openOutputStream(uri, "wt")!!.use {
                it.write(ConfigRepository.json.encodeToString(LauncherConfig.serializer(), draft).toByteArray())
            }
            "Configurazione esportata."
        }.getOrElse { "Esportazione non riuscita: ${it.message}" }
    }
    val importDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        val cfg = text?.let { ConfigRepository.parse(it, "importata") }
        notice = if (cfg == null) "File non valido: nessuna modifica." else {
            onDraftChange(cfg)
            onPageIndex(0)
            "Configurazione importata: controlla e premi Salva."
        }
    }
    val problems = remember(draft) { ConfigRepository.validate(draft) }
    val noSos = draft.pages.none { p -> p.tiles.any { it.type == TileType.SOS } }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    fun updatePage(p: Page) = onDraftChange(draft.copy(pages = draft.pages.toMutableList().also { it[pageIndex] = p }))

    Column(Modifier.fillMaxSize().background(Color(0xFFECE9E2)).windowInsetsPadding(WindowInsets.safeDrawing)) {
        EditorHeader("Disposizione", onExit, "Salva", enabled = problems.isEmpty()) {
            if (noSos) confirm = "Nessuna pagina contiene il pulsante SOS. Salvare comunque?" to onSave else onSave()
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Navigazione pagine
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onPageIndex(pageIndex - 1) }, enabled = pageIndex > 0, modifier = Modifier.height(48.dp)) { Text("‹") }
                Text(
                    "Pagina ${pageIndex + 1} di ${draft.pages.size}",
                    fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                )
                OutlinedButton(onClick = { onPageIndex(pageIndex + 1) }, enabled = pageIndex < draft.pages.size - 1, modifier = Modifier.height(48.dp)) { Text("›") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        onDraftChange(draft.copy(pages = draft.pages + Page(cols = page.cols, rows = page.rows)))
                        onPageIndex(draft.pages.size)
                    },
                    enabled = draft.pages.size < 6,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("+ Pagina") }
                OutlinedButton(
                    onClick = {
                        confirm = "Eliminare la pagina ${pageIndex + 1} con i suoi ${page.tiles.size} tile?" to {
                            onDraftChange(draft.copy(pages = draft.pages.filterIndexed { i, _ -> i != pageIndex }))
                            onPageIndex((pageIndex - 1).coerceAtLeast(0))
                        }
                    },
                    enabled = draft.pages.size > 1,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("Elimina pagina", color = if (draft.pages.size > 1) Palette.Sos else Color.Unspecified) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Stepper("Colonne", page.cols, 1..4, { updatePage(page.copy(cols = it)) })
                Stepper("Righe", page.rows, 1..8, { updatePage(page.copy(rows = it)) })
            }

            GridPreview(
                page,
                onTile = { onEditTile(pageIndex, it) },
                onEmpty = { c, r -> onAddTile(pageIndex, c, r) },
                onMove = { moved -> updatePage(page.copy(tiles = page.tiles.map { t -> moved.firstOrNull { it.id == t.id } ?: t })) },
            )
            Text(
                "Tocca un tile per modificarlo, una casella vuota per aggiungerne uno. " +
                    "Tieni premuto un tile e trascinalo per spostarlo (sopra un tile uguale li scambia).",
                fontSize = 15.sp, color = Color(0xFF4A4A4A),
            )
            notice?.let { Text(it, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.Ink) }

            if (problems.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Da sistemare prima di salvare:", color = Palette.Sos, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    problems.forEach { Text("• $it", color = Palette.Sos, fontSize = 15.sp) }
                }
            }
            if (noSos) Text("Attenzione: nessun pulsante SOS nella Home.", color = Palette.Sos, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (!noSos && draft.sos.numbers.isEmpty()) {
                Text("Il pulsante SOS non ha numeri: impostali in «Numeri SOS» (familiari, non il 112).",
                    color = Palette.Sos, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            if (error != null) Text("Salvataggio non riuscito: $error", color = Palette.Sos, fontSize = 15.sp)

            if (missingPermissions.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("Permessi mancanti")
                    missingPermissions.map { it.why }.distinct().forEach { Text("• $it", fontSize = 15.sp, color = Palette.Ink) }
                    OutlinedButton(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Concedi permessi") }
                }
            }
            if (restrictionHelp) RestrictedSettingsHelp(onExternalStart)
            missingSpecial.forEach { sp ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel(sp.label)
                    Text("Serve per: ${sp.why}. Nella pagina che si apre attiva SemplicePhone.", fontSize = 15.sp, color = Palette.Ink)
                    OutlinedButton(onClick = { onExternalStart(); onOpenSpecial(sp) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text("Apri impostazioni")
                    }
                }
            }

            SectionLabel("Altre impostazioni")
            OutlinedButton(onClick = onEditSos, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Numeri SOS") }
            extraSections()
            OutlinedButton(onClick = onChangePin, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Cambia PIN") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onExternalStart(); exportDoc.launch("semplicephone-config.json") },
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text("Esporta su file") }
                OutlinedButton(
                    onClick = { onExternalStart(); importDoc.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text("Importa da file") }
            }
            OutlinedButton(
                onClick = { confirm = "Sostituire la disposizione con quella predefinita? Le modifiche non salvate vanno perse." to onResetDefaults },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Ripristina disposizione predefinita") }
            TextButton(onClick = { showAbout = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("Informazioni e licenza", color = Palette.Ink)
            }
        }
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("SemplicePhone ${app.semplicephone.BuildConfig.VERSION_NAME}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Copyright © 2026 Francesco Sorzini", fontSize = 15.sp)
                    Text(
                        "Software libero: puoi ridistribuirlo e modificarlo secondo la GNU General Public License " +
                            "versione 3 o successiva. Distribuito gratuitamente e SENZA ALCUNA GARANZIA.",
                        fontSize = 15.sp,
                    )
                    Text("Il pulsante SOS contatta i numeri impostati: non sostituisce il 112.", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("Codice sorgente: github.com/francescosorzini-dev/SemplicePhone", fontSize = 15.sp)
                    Text(
                        "Componenti: Atkinson Hyperlegible (SIL OFL 1.1), AndroidX e Kotlin (Apache 2.0), Bouncy Castle (MIT).",
                        fontSize = 14.sp, color = Color(0xFF4A4A4A),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showAbout = false
                    onExternalStart()
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/francescosorzini-dev/SemplicePhone"))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text("Apri sorgenti") }
            },
            dismissButton = { TextButton(onClick = { showAbout = false }) { Text("Chiudi") } },
        )
    }

    confirm?.let { (msg, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(msg, fontSize = 17.sp) },
            confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text("Conferma") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Annulla") } },
        )
    }
}

/** Griglia in scala con le stesse proporzioni della Home (circa 2:1 in verticale per 2 colonne). */
@Composable
private fun GridPreview(page: Page, onTile: (Tile) -> Unit, onEmpty: (Int, Int) -> Unit, onMove: (List<Tile>) -> Unit) {
    var dragging by remember { mutableStateOf<String?>(null) }
    var drag by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(0.62f)) {
        val cellW = (maxWidth - Gap * (page.cols - 1)) / page.cols
        val cellH = (maxHeight - Gap * (page.rows - 1)) / page.rows
        val stepX = with(density) { (cellW + Gap).toPx() }
        val stepY = with(density) { (cellH + Gap).toPx() }
        val occupied = Array(page.rows) { BooleanArray(page.cols) }
        page.tiles.forEach { t ->
            for (r in t.row until (t.row + t.h).coerceAtMost(page.rows)) for (c in t.col until (t.col + t.w).coerceAtMost(page.cols)) {
                if (r >= 0 && c >= 0) occupied[r][c] = true
            }
        }
        for (r in 0 until page.rows) for (c in 0 until page.cols) {
            if (!occupied[r][c]) {
                Box(
                    Modifier
                        .offset(x = (cellW + Gap) * c, y = (cellH + Gap) * r)
                        .size(cellW, cellH)
                        .clip(RoundedCornerShape(10.dp))
                        .border(2.dp, Color(0xFF8A8A8A), RoundedCornerShape(10.dp))
                        .clickable { onEmpty(c, r) }
                        .semantics { contentDescription = "Casella vuota colonna ${c + 1} riga ${r + 1}, aggiungi tile" },
                    contentAlignment = Alignment.Center,
                ) { Text("+", fontSize = 26.sp, color = Color(0xFF4A4A4A)) }
            }
        }
        page.tiles.forEach { t ->
            val bg = parseColor(t.color)
            val fg = contentColorFor(bg)
            val inGrid = ConfigRepository.conflict(page.copy(tiles = emptyList()), t) == null
            val isDragged = dragging == t.id
            Box(
                Modifier
                    .offset(x = (cellW + Gap) * t.col.coerceAtLeast(0), y = (cellH + Gap) * t.row.coerceAtLeast(0))
                    .offset { if (isDragged) IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) else IntOffset.Zero }
                    .zIndex(if (isDragged) 1f else 0f)
                    .alpha(if (isDragged) 0.85f else 1f)
                    .size(cellW * t.w + Gap * (t.w - 1), cellH * t.h + Gap * (t.h - 1))
                    .pointerInput(t, page) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragging = t.id; drag = Offset.Zero },
                            onDrag = { change, amount -> change.consume(); drag += amount },
                            onDragCancel = { dragging = null },
                            onDragEnd = {
                                val col = (t.col + (drag.x / stepX).roundToInt()).coerceIn(0, (page.cols - t.w).coerceAtLeast(0))
                                val row = (t.row + (drag.y / stepY).roundToInt()).coerceIn(0, (page.rows - t.h).coerceAtLeast(0))
                                dragging = null
                                movedTiles(page, t, col, row)?.let(onMove)
                            },
                        )
                    }
                    .clip(RoundedCornerShape(10.dp))
                    .background(bg)
                    .border(if (inGrid) 1.dp else 3.dp, if (inGrid) Palette.Ink else Palette.Sos, RoundedCornerShape(10.dp))
                    .clickable { onTile(t) }
                    .padding(6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    previewLabel(t),
                    color = fg,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Guida allo sblocco delle «impostazioni con restrizioni» (app installata dal browser):
 * da Info app con il menu ⋮, oppure da PC con ADB se il produttore ha tolto il menu.
 */
@Composable
private fun RestrictedSettingsHelp(onExternalStart: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFFFF0DF)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Se Android risponde «Per la tua sicurezza, questa impostazione non è disponibile»", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        Text(
            "SemplicePhone è stata installata da un file scaricato e Android blocca alcuni permessi " +
                "(SMS dell'SOS, accesso alle notifiche) finché non li consenti:",
            fontSize = 15.sp, color = Palette.Ink,
        )
        Text(
            "1. Prova una volta a concedere il permesso (così compare l'opzione).\n" +
                "2. Apri «Info app» qui sotto, tocca ⋮ in alto a destra e scegli «Consenti impostazioni con restrizioni».\n" +
                "3. Torna qui e premi di nuovo «Concedi permessi».",
            fontSize = 15.sp, color = Palette.Ink,
        )
        OutlinedButton(
            onClick = { onExternalStart(); app.semplicephone.launch.Restrictions.openAppInfo(context) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Apri Info app") }
        Text("Se il menu ⋮ non c'è (alcuni telefoni OnePlus, Oppo, Realme), da un computer con ADB:", fontSize = 15.sp, color = Palette.Ink)
        Text(
            app.semplicephone.launch.Restrictions.ADB_ALLOW,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().background(Color.White).padding(8.dp),
        )
        OutlinedButton(
            onClick = {
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", app.semplicephone.launch.Restrictions.ADB_ALLOW))
                copied = true
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text(if (copied) "Copiato" else "Copia comando") }
    }
}

/**
 * Spostamento di [t] in (col,row): libero → sposta; occupato da un solo tile della stessa misura →
 * scambio; altrimenti null (il tile torna al suo posto).
 */
internal fun movedTiles(page: Page, t: Tile, col: Int, row: Int): List<Tile>? {
    if (col == t.col && row == t.row) return null
    val moved = t.copy(col = col, row = row)
    if (ConfigRepository.conflict(page, moved) == null) return listOf(moved)
    val hit = page.tiles.filter { o ->
        o.id != t.id && moved.col < o.col + o.w && o.col < moved.col + moved.w && moved.row < o.row + o.h && o.row < moved.row + moved.h
    }
    val other = hit.singleOrNull() ?: return null
    if (other.w != t.w || other.h != t.h || other.col != col || other.row != row) return null
    return listOf(moved, other.copy(col = t.col, row = t.row))
}

private fun previewLabel(t: Tile): String = when (t.type) {
    TileType.CLOCK -> "Orologio"
    TileType.SOS -> t.label.ifBlank { "SOS" }
    else -> t.label.ifBlank { defaultLabel(t.type) }
}
