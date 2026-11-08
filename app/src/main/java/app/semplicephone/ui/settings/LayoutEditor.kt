package app.semplicephone.ui.settings

import app.semplicephone.ui.Ui
import app.semplicephone.ui.TextSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import app.semplicephone.R
import androidx.compose.ui.res.stringResource
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
            context.getString(R.string.layout_exported)
        }.getOrElse { context.getString(R.string.layout_export_failed, it.message.orEmpty()) }
    }
    val importDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        val cfg = text?.let { ConfigRepository.parse(it, "importata") }
        notice = if (cfg == null) context.getString(R.string.layout_import_invalid) else {
            onDraftChange(cfg)
            onPageIndex(0)
            context.getString(R.string.layout_imported)
        }
    }
    val problems = remember(draft) { ConfigRepository.validate(draft) }
    val noSos = draft.pages.none { p -> p.tiles.any { it.type == TileType.SOS } }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    fun updatePage(p: Page) = onDraftChange(draft.copy(pages = draft.pages.toMutableList().also { it[pageIndex] = p }))

    Column(Modifier.fillMaxSize().background(Palette.Soft).windowInsetsPadding(WindowInsets.safeDrawing)) {
        EditorHeader(stringResource(R.string.layout_title), onExit, stringResource(R.string.action_save), enabled = problems.isEmpty()) {
            if (noSos) confirm = context.getString(R.string.layout_no_sos_confirm) to onSave else onSave()
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Navigazione pagine
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onPageIndex(pageIndex - 1) }, enabled = pageIndex > 0, modifier = Modifier.heightIn(min = Ui.HeightSettings)) { Text("‹") }
                Text(
                    stringResource(R.string.home_page_of, pageIndex + 1, draft.pages.size),
                    fontSize = TextSize.Body, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                )
                OutlinedButton(onClick = { onPageIndex(pageIndex + 1) }, enabled = pageIndex < draft.pages.size - 1, modifier = Modifier.heightIn(min = Ui.HeightSettings)) { Text("›") }
            }
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        onDraftChange(draft.copy(pages = draft.pages + Page(cols = page.cols, rows = page.rows)))
                        onPageIndex(draft.pages.size)
                    },
                    enabled = draft.pages.size < 6,
                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = Ui.HeightSettings),
                ) { Text(stringResource(R.string.layout_add_page)) }
                OutlinedButton(
                    onClick = {
                        confirm = context.getString(R.string.layout_delete_page_confirm, pageIndex + 1, page.tiles.size) to {
                            onDraftChange(draft.copy(pages = draft.pages.filterIndexed { i, _ -> i != pageIndex }))
                            onPageIndex((pageIndex - 1).coerceAtLeast(0))
                        }
                    },
                    enabled = draft.pages.size > 1,
                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = Ui.HeightSettings),
                ) { Text(stringResource(R.string.layout_delete_page), color = if (draft.pages.size > 1) Palette.Sos else Color.Unspecified) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Stepper(stringResource(R.string.layout_columns), page.cols, 1..4, { updatePage(page.copy(cols = it)) })
                Stepper(stringResource(R.string.layout_rows), page.rows, 1..8, { updatePage(page.copy(rows = it)) })
            }

            GridPreview(
                page,
                onTile = { onEditTile(pageIndex, it) },
                onEmpty = { c, r -> onAddTile(pageIndex, c, r) },
                onMove = { moved -> updatePage(page.copy(tiles = page.tiles.map { t -> moved.firstOrNull { it.id == t.id } ?: t })) },
            )
            Text(
                stringResource(R.string.layout_hint),
                fontSize = TextSize.Small, color = Palette.Mute,
            )
            notice?.let { Text(it, fontSize = TextSize.Body, fontWeight = FontWeight.Bold, color = Palette.Ink) }

            if (problems.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.layout_problems), color = Palette.Sos, fontWeight = FontWeight.Bold, fontSize = TextSize.Body)
                    problems.forEach { Text("• $it", color = Palette.Sos, fontSize = TextSize.Small) }
                }
            }
            if (noSos) Text(stringResource(R.string.layout_no_sos), color = Palette.Sos, fontWeight = FontWeight.Bold, fontSize = TextSize.Body)
            if (!noSos && draft.sos.numbers.isEmpty()) {
                Text(stringResource(R.string.layout_sos_no_numbers),
                    color = Palette.Sos, fontWeight = FontWeight.Bold, fontSize = TextSize.Body)
            }
            if (error != null) Text(stringResource(R.string.layout_save_failed, error), color = Palette.Sos, fontSize = TextSize.Small)

            if (missingPermissions.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel(stringResource(R.string.layout_missing_permissions))
                    missingPermissions.map { it.why }.distinct().forEach { Text("• $it", fontSize = TextSize.Small, color = Palette.Ink) }
                    OutlinedButton(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth().heightIn(min = Ui.HeightSettings)) { Text(stringResource(R.string.layout_grant_permissions)) }
                }
            }
            if (restrictionHelp) RestrictedSettingsHelp(onExternalStart)
            missingSpecial.forEach { sp ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel(sp.label)
                    Text(stringResource(R.string.layout_special_why, sp.why), fontSize = TextSize.Small, color = Palette.Ink)
                    OutlinedButton(onClick = { onExternalStart(); onOpenSpecial(sp) }, modifier = Modifier.fillMaxWidth().heightIn(min = Ui.HeightSettings)) {
                        Text(stringResource(R.string.action_open_settings))
                    }
                }
            }

            SectionLabel(stringResource(R.string.layout_more_settings))
            OutlinedButton(onClick = onEditSos, modifier = Modifier.fillMaxWidth().heightIn(min = Ui.HeightSettings)) { Text(stringResource(R.string.layout_sos_numbers)) }
            extraSections()
            OutlinedButton(onClick = onChangePin, modifier = Modifier.fillMaxWidth().heightIn(min = Ui.HeightSettings)) { Text(stringResource(R.string.layout_change_pin)) }
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onExternalStart(); exportDoc.launch("semplicephone-config.json") },
                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = Ui.HeightSettings),
                ) { Text(stringResource(R.string.layout_export)) }
                OutlinedButton(
                    onClick = { onExternalStart(); importDoc.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = Ui.HeightSettings),
                ) { Text(stringResource(R.string.layout_import)) }
            }
            OutlinedButton(
                onClick = { confirm = context.getString(R.string.layout_reset_confirm) to onResetDefaults },
                modifier = Modifier.fillMaxWidth().heightIn(min = Ui.HeightSettings),
            ) { Text(stringResource(R.string.layout_reset)) }
            TextButton(onClick = { showAbout = true }, modifier = Modifier.fillMaxWidth().heightIn(min = Ui.HeightSettings)) {
                Text(stringResource(R.string.layout_about), color = Palette.Ink)
            }
        }
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("SemplicePhone ${app.semplicephone.BuildConfig.VERSION_NAME}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Copyright © 2026 Francesco Sorzini", fontSize = TextSize.Small)
                    Text(
                        stringResource(R.string.about_license),
                        fontSize = TextSize.Small,
                    )
                    Text(stringResource(R.string.about_sos), fontSize = TextSize.Small, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.about_source, "github.com/francescosorzini-dev/SemplicePhone"), fontSize = TextSize.Small)
                    Text(
                        stringResource(R.string.about_components),
                        fontSize = TextSize.Small, color = Palette.Mute,
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
                }) { Text(stringResource(R.string.about_open_source)) }
            },
            dismissButton = { TextButton(onClick = { showAbout = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }

    confirm?.let { (msg, action) ->
        app.semplicephone.ui.ConfirmDialog(
            title = msg, text = null, confirm = stringResource(R.string.action_confirm),
            onConfirm = { confirm = null; action() }, onDismiss = { confirm = null },
        )
    }
}

/** Griglia in scala con le stesse proporzioni della Home (circa 2:1 in verticale per 2 colonne). */
@Composable
private fun GridPreview(page: Page, onTile: (Tile) -> Unit, onEmpty: (Int, Int) -> Unit, onMove: (List<Tile>) -> Unit) {
    var dragging by remember { mutableStateOf<String?>(null) }
    var drag by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val context = LocalContext.current
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
                        .clip(Ui.ShapeSmall)
                        .border(2.dp, Palette.Mute, Ui.ShapeSmall)
                        .clickable { onEmpty(c, r) }
                        .semantics { contentDescription = context.getString(R.string.layout_empty_cell, c + 1, r + 1) },
                    contentAlignment = Alignment.Center,
                ) { Text("+", fontSize = TextSize.Title, color = Palette.Mute) }
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
                    .clip(Ui.ShapeSmall)
                    .background(bg)
                    .border(if (inGrid) 1.dp else 3.dp, if (inGrid) Palette.Ink else Palette.Sos, Ui.ShapeSmall)
                    .clickable { onTile(t) }
                    .padding(6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    previewLabel(context, t),
                    color = fg,
                    fontSize = TextSize.Small,
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
        Modifier.fillMaxWidth().clip(Ui.ShapeSmall).background(Palette.NoteSoft).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.restricted_title), fontSize = TextSize.Body, fontWeight = FontWeight.Bold, color = Palette.Ink)
        Text(
            stringResource(R.string.restricted_text),
            fontSize = TextSize.Small, color = Palette.Ink,
        )
        Text(
            stringResource(R.string.restricted_steps),
            fontSize = TextSize.Small, color = Palette.Ink,
        )
        OutlinedButton(
            onClick = { onExternalStart(); app.semplicephone.launch.Restrictions.openAppInfo(context) },
            modifier = Modifier.fillMaxWidth().heightIn(min = Ui.HeightSettings),
        ) { Text(stringResource(R.string.restricted_open_info)) }
        Text(stringResource(R.string.restricted_adb), fontSize = TextSize.Small, color = Palette.Ink)
        Text(
            app.semplicephone.launch.Restrictions.ADB_ALLOW,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = TextSize.Small,
            modifier = Modifier.fillMaxWidth().background(Color.White).padding(8.dp),
        )
        OutlinedButton(
            onClick = {
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", app.semplicephone.launch.Restrictions.ADB_ALLOW))
                copied = true
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = Ui.HeightSettings),
        ) { Text(stringResource(if (copied) R.string.copied else R.string.copy_command)) }
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

private fun previewLabel(context: android.content.Context, t: Tile): String = when (t.type) {
    TileType.CLOCK -> context.getString(R.string.label_clock)
    TileType.SOS -> t.label.ifBlank { "SOS" }
    else -> t.label.ifBlank { defaultLabel(context, t.type) }
}
