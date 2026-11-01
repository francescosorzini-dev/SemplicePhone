package app.semplicephone.ui.settings

import androidx.compose.foundation.background
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
) {
    val page = draft.pages.getOrNull(pageIndex) ?: return
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

            GridPreview(page, onTile = { onEditTile(pageIndex, it) }, onEmpty = { c, r -> onAddTile(pageIndex, c, r) })
            Text("Tocca un tile per modificarlo, una casella vuota per aggiungerne uno.", fontSize = 15.sp, color = Color(0xFF4A4A4A))

            if (problems.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Da sistemare prima di salvare:", color = Palette.Sos, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    problems.forEach { Text("• $it", color = Palette.Sos, fontSize = 15.sp) }
                }
            }
            if (noSos) Text("Attenzione: nessun pulsante SOS nella Home.", color = Palette.Sos, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (error != null) Text("Salvataggio non riuscito: $error", color = Palette.Sos, fontSize = 15.sp)

            SectionLabel("Altre impostazioni")
            OutlinedButton(onClick = onEditSos, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Numeri SOS") }
            OutlinedButton(onClick = onChangePin, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Cambia PIN") }
            OutlinedButton(
                onClick = { confirm = "Sostituire la disposizione con quella predefinita? Le modifiche non salvate vanno perse." to onResetDefaults },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Ripristina disposizione predefinita") }
        }
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
private fun GridPreview(page: Page, onTile: (Tile) -> Unit, onEmpty: (Int, Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(0.62f)) {
        val cellW = (maxWidth - Gap * (page.cols - 1)) / page.cols
        val cellH = (maxHeight - Gap * (page.rows - 1)) / page.rows
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
            Box(
                Modifier
                    .offset(x = (cellW + Gap) * t.col.coerceAtLeast(0), y = (cellH + Gap) * t.row.coerceAtLeast(0))
                    .size(cellW * t.w + Gap * (t.w - 1), cellH * t.h + Gap * (t.h - 1))
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

private fun previewLabel(t: Tile): String = when (t.type) {
    TileType.CLOCK -> "Orologio"
    TileType.SOS -> t.label.ifBlank { "SOS" }
    else -> t.label.ifBlank { defaultLabel(t.type) }
}
