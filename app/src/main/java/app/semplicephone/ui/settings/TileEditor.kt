package app.semplicephone.ui.settings

import kotlinx.coroutines.launch
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
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
    TileType.WEATHER to R.string.type_weather,
    TileType.PHOTOS to R.string.type_photos,
    TileType.MISSED_CALLS to R.string.type_missed_calls,
    TileType.SEARCH to R.string.type_search,
    TileType.MESSAGES to R.string.type_messages_unread,
    TileType.RADIO to R.string.type_radio,
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
    /** Pagine della schermata e pagina del riquadro, per spostarlo su un'altra. */
    pageCount: Int = 1,
    pageIndex: Int = 0,
    /** Sposta il riquadro sulla pagina indicata; false se lì non c'è posto. */
    onMove: (Tile, Int) -> Boolean = { _, _ -> false },
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
                // Il valore del tipo precedente non vale per il nuovo (es. «@dialer» per una videochiamata).
                val phoneTypes = setOf(TileType.CALL, TileType.WA_VIDEO, TileType.SMS)
                val keep = type == t.type || (type in phoneTypes && t.type in phoneTypes)
                // Scritta e colore seguono il nuovo tipo, a meno che siano stati scelti a mano.
                val autoLabel = isAutoLabel(context, t.label)
                val autoColor = t.color.isBlank() || t.color.equals(defaultColor(t.type), ignoreCase = true) || t.color.uppercase() in OLD_DEFAULT_COLORS
                t = t.copy(
                    type = type, target = if (keep) t.target else "",
                    label = if (autoLabel) defaultLabel(context, type) else t.label,
                    color = if (autoColor || type == TileType.CLOCK || type == TileType.SOS) defaultColor(type) else t.color,
                )
            }

            when (t.type) {
                TileType.APP -> {
                    SectionLabel(stringResource(R.string.tile_app))
                    val current = apps.firstOrNull { it.target == t.target }?.label ?: t.target.ifBlank { stringResource(R.string.icon_none) }
                    OutlinedButton(onClick = { pickApp = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(10.dp)) {
                        Text(current, fontSize = 17.sp, color = Palette.Ink)
                    }
                    val openLabels = mapOf(
                        app.semplicephone.model.OPEN_DEFAULT to R.string.open_default,
                        app.semplicephone.model.OPEN_FRESH to R.string.open_fresh,
                        app.semplicephone.model.OPEN_RESUME to R.string.open_resume,
                    )
                    OptionPicker(
                        stringResource(R.string.tile_open), openLabels.keys.toList(),
                        t.open.takeIf { it in openLabels } ?: app.semplicephone.model.OPEN_DEFAULT,
                        { context.getString(openLabels.getValue(it)) },
                    ) { t = t.copy(open = it) }
                }
                TileType.CALL, TileType.WA_VIDEO, TileType.SMS -> {
                    OutlinedButton(
                        onClick = {
                            onExternalStart()
                            pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text(stringResource(R.string.tile_pick_contact), fontSize = 17.sp, color = Palette.Ink) }
                    Field(stringResource(R.string.tile_number), t.target, KeyboardType.Phone) { t = t.copy(target = it.filter { c -> c.isDigit() || c == '+' }) }
                    if (t.type == TileType.WA_VIDEO) {
                        Hint(stringResource(R.string.tile_wa_hint))
                        // Controllo subito: senza la riga «Videochiamata» di WhatsApp in rubrica si aprirebbe solo la chat.
                        val waReady = remember(t.target) { t.target.filter(Char::isDigit).length < 6 || launcher.contacts.whatsAppVideoIntent(t.target) != null }
                        if (!waReady) Text(stringResource(R.string.tile_wa_not_found), fontSize = 15.sp, color = Palette.Sos)
                    }
                    if (t.type == TileType.SMS) Field(stringResource(R.string.tile_sms_text), t.body) { t = t.copy(body = it) }
                }
                TileType.URL -> Field(stringResource(R.string.tile_url), t.target, KeyboardType.Uri) { t = t.copy(target = it.trim()) }
                TileType.SHORTCUT -> {
                    SectionLabel(stringResource(R.string.tile_shortcut))
                    OutlinedButton(onClick = { pickShortcut = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(10.dp)) {
                        Text(t.target.ifBlank { stringResource(R.string.tile_choose) }.substringAfter('/').take(40), fontSize = 17.sp, color = Palette.Ink)
                    }
                    Hint(stringResource(R.string.tile_shortcut_hint))
                }
                TileType.CLOCK -> Hint(stringResource(R.string.tile_clock_hint))
                TileType.WEATHER -> WeatherPlaceField(t.target, t.label) { place ->
                    t = t.copy(target = place.target, label = place.name)
                }
                TileType.PHOTOS -> {
                    SectionLabel(stringResource(R.string.tile_photos))
                    Hint(stringResource(R.string.tile_photos_hint))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        t.photos.forEach { ref ->
                            Box {
                                MediaThumb(ref, 84.dp)
                                TextButton(
                                    onClick = { t = t.copy(photos = t.photos - ref) },
                                    modifier = Modifier.align(Alignment.TopEnd),
                                ) { Text("✕", color = Palette.Sos, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                    MediaPickButtons(onPicked = { ref -> if (ref !in t.photos) t = t.copy(photos = t.photos + ref) }, onExternalStart = onExternalStart, multiple = true)
                    AlbumFolders(t.folders, onExternalStart) { t = t.copy(folders = it) }
                }
                TileType.MISSED_CALLS -> Hint(
                    stringResource(if (app.semplicephone.data.MissedCalls.supported()) R.string.tile_missed_hint else R.string.tile_missed_hint_play),
                )
                TileType.SEARCH -> Hint(stringResource(R.string.tile_search_hint))
                TileType.RADIO -> {
                    Hint(stringResource(R.string.tile_radio_hint))
                    t.stations.forEachIndexed { i, st ->
                        androidx.compose.material3.OutlinedTextField(
                            value = st.name, onValueChange = { v -> t = t.copy(stations = t.stations.toMutableList().also { it[i] = st.copy(name = v) }) },
                            label = { Text(stringResource(R.string.tile_radio_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        androidx.compose.material3.OutlinedTextField(
                            value = st.url, onValueChange = { v -> t = t.copy(stations = t.stations.toMutableList().also { it[i] = st.copy(url = v.trim()) }) },
                            label = { Text(stringResource(R.string.tile_radio_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        androidx.compose.material3.TextButton(onClick = { t = t.copy(stations = t.stations.filterIndexed { j, _ -> j != i }) }) {
                            Text(stringResource(R.string.tile_radio_remove), color = Palette.Sos)
                        }
                    }
                    androidx.compose.material3.OutlinedButton(
                        onClick = { t = t.copy(stations = t.stations + app.semplicephone.model.Station()) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) { Text(stringResource(R.string.tile_radio_add)) }
                }
                TileType.MESSAGES -> Hint(stringResource(if (app.semplicephone.BuildConfig.NOTIFICATIONS) R.string.tile_messages_hint else R.string.tile_messages_hint_basic))
                TileType.UNKNOWN -> Hint(stringResource(R.string.tile_unknown_hint))
                TileType.SOS -> Hint(stringResource(R.string.tile_sos_hint))
            }

            if (t.type != TileType.CLOCK) Field(stringResource(R.string.tile_label), t.label) { t = t.copy(label = it) }

            if (t.type !in setOf(TileType.CLOCK, TileType.SOS, TileType.WEATHER, TileType.PHOTOS, TileType.MISSED_CALLS, TileType.MESSAGES, TileType.UNKNOWN)) {
                OptionPicker(stringResource(R.string.tile_icon), IconLabels.keys.toList(), t.icon.kind, { context.getString(IconLabels.getValue(it)) }) { k ->
                    t = t.copy(icon = TileIcon(k, when {
                        k == IconKind.SYMBOL -> t.icon.ref.ifBlank { Symbols.defaultFor(t.type) }
                        k == IconKind.PHOTO && t.icon.ref.startsWith("media:") -> t.icon.ref
                        else -> ""
                    }, t.icon.fill))
                }
                if (t.icon.kind == IconKind.SYMBOL) SymbolGrid(t.icon.ref) { t = t.copy(icon = TileIcon(IconKind.SYMBOL, it)) }
                if (t.icon.kind == IconKind.PHOTO) Hint(stringResource(R.string.tile_photo_hint))
                val withPhoto = t.icon.kind == IconKind.PHOTO ||
                    (t.icon.kind == IconKind.AUTO && t.type in setOf(TileType.CALL, TileType.WA_VIDEO, TileType.SMS))
                if (t.type in setOf(TileType.CALL, TileType.WA_VIDEO, TileType.SMS)) {
                    // Foto scelta o scattata qui (anche se il numero non ha foto in rubrica).
                    SectionLabel(stringResource(R.string.tile_own_photo))
                    SinglePhotoField(
                        ref = t.icon.ref.takeIf { it.startsWith("media:") }.orEmpty(),
                        onChange = { r -> t = t.copy(icon = if (r.isBlank()) t.icon.copy(kind = IconKind.AUTO, ref = "") else t.icon.copy(kind = IconKind.PHOTO, ref = r)) },
                        onExternalStart = onExternalStart,
                    )
                }
                if (withPhoto) Toggle(
                    stringResource(R.string.tile_photo_fill), stringResource(R.string.tile_photo_fill_hint), t.icon.fill,
                ) { t = t.copy(icon = t.icon.copy(fill = it)) }
            }

            if (t.type in TileLauncher.HOME_BUTTON_TYPES && ConfigRepository.get(context).config.value.phone.homeButton) {
                Toggle(stringResource(R.string.tile_home_button), stringResource(R.string.tile_home_button_detail), t.homeButton) {
                    t = t.copy(homeButton = it)
                }
            }

            if (!isNew && pageCount > 1) {
                var noRoom by remember { mutableStateOf<Int?>(null) }
                SectionLabel(stringResource(R.string.tile_move_page))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    (0 until pageCount).filter { it != pageIndex }.take(4).forEach { n ->
                        androidx.compose.material3.OutlinedButton(
                            onClick = { if (!onMove(t, n)) noRoom = n },
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        ) { Text(stringResource(R.string.tile_page_n, n + 1)) }
                    }
                }
                noRoom?.let { Text(stringResource(R.string.tile_move_no_room, it + 1), color = Palette.Sos, fontSize = 15.sp) }
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
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
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

/** Colore tipico di ogni tipo di riquadro (stessa tavolozza del portale). */
fun defaultColor(type: TileType): String = when (type) {
    TileType.WA_VIDEO, TileType.WEATHER -> "#4FC3F7"
    TileType.CLOCK -> "#FFFFFF"
    TileType.PHOTOS -> "#6A1B9A"
    TileType.MISSED_CALLS -> "#FFD54F"
    TileType.SEARCH, TileType.URL -> "#1A1A1A"
    TileType.RADIO -> "#FFA726"
    TileType.SOS -> "#C62828"
    else -> "#1F4FA8"
}

/** Colori predefiniti delle versioni precedenti (prima della 0.14) → i nuovi più vicini. */
val OLD_COLOR_MAP = mapOf(
    "#0B6E4F" to "#1B7A34", "#00695C" to "#4FC3F7", "#B71C1C" to "#C62828", "#A84300" to "#FFA726",
    "#FFF3C4" to "#FFD54F", "#FFE7A8" to "#FFD54F", "#37474F" to "#1A1A1A", "#0277BD" to "#4FC3F7",
)
val OLD_DEFAULT_COLORS = OLD_COLOR_MAP.keys

/** Config con i colori vecchi già aggiornati (si applica alla bozza dell'editor: va sul telefono al Salva). */
fun migrateColors(cfg: app.semplicephone.model.LauncherConfig): app.semplicephone.model.LauncherConfig = cfg.copy(
    pages = cfg.pages.map { p -> p.copy(tiles = p.tiles.map { t -> OLD_COLOR_MAP[t.color.uppercase()]?.let { t.copy(color = it) } ?: t }) },
    phone = cfg.phone.copy(homeButtonColor = OLD_COLOR_MAP[cfg.phone.homeButtonColor.uppercase()] ?: cfg.phone.homeButtonColor),
)

/** La scritta è ancora una di quelle automatiche (di qualunque tipo o app) o è vuota? */
fun isAutoLabel(context: Context, label: String): Boolean {
    if (label.isBlank()) return true
    val auto = TileType.entries.map { defaultLabel(context, it) } + listOf(
        R.string.label_phone, R.string.label_messages, R.string.label_camera, R.string.label_photos, R.string.label_internet,
    ).map(context::getString)
    return label in auto
}

fun defaultLabel(context: Context, type: TileType): String = when (type) {
    TileType.APP -> context.getString(R.string.label_app)
    TileType.CALL -> context.getString(R.string.label_call)
    TileType.WA_VIDEO -> context.getString(R.string.label_video)
    TileType.SMS -> context.getString(R.string.label_message)
    TileType.URL -> context.getString(R.string.label_internet)
    TileType.SHORTCUT -> context.getString(R.string.label_shortcut)
    TileType.CLOCK -> ""
    TileType.WEATHER -> ""
    TileType.PHOTOS -> context.getString(R.string.label_album)
    TileType.MISSED_CALLS -> context.getString(R.string.label_missed_calls)
    TileType.SEARCH -> context.getString(R.string.label_search)
    TileType.MESSAGES -> context.getString(R.string.label_messages_unread)
    TileType.RADIO -> context.getString(R.string.label_radio)
    TileType.UNKNOWN -> ""
    TileType.SOS -> "SOS"
}

@Composable
fun EditorHeader(title: String, onBack: () -> Unit, action: String, enabled: Boolean = true, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_back), fontSize = 17.sp, color = Palette.Ink) }
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, modifier = Modifier.weight(1f).padding(start = 4.dp))
        Button(
            onClick = onAction,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Ink),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.heightIn(min = 48.dp),
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

/** Ricerca della località per il meteo (Open-Meteo), con la scelta tra i risultati. */
@Composable
private fun WeatherPlaceField(target: String, label: String, onPick: (app.semplicephone.data.Weather.Place) -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val lang = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language
    var query by remember { mutableStateOf(label) }
    var results by remember { mutableStateOf<List<app.semplicephone.data.Weather.Place>?>(null) }
    var busy by remember { mutableStateOf(false) }
    SectionLabel(stringResource(R.string.weather_place))
    if (target.isNotBlank()) Hint(stringResource(R.string.weather_current_place, label.ifBlank { target }))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(stringResource(R.string.weather_search)) }, modifier = Modifier.weight(1f))
        OutlinedButton(
            onClick = {
                busy = true
                scope.launch {
                    results = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.semplicephone.data.Weather.search(query, lang) }
                    busy = false
                }
            },
            enabled = query.isNotBlank() && !busy,
            modifier = Modifier.heightIn(min = 52.dp),
        ) { Text(stringResource(if (busy) R.string.loading else R.string.search)) }
    }
    results?.let { list ->
        if (list.isEmpty()) Hint(stringResource(R.string.weather_no_results))
        list.forEach { p ->
            OutlinedButton(onClick = { onPick(p); results = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(if (p.area.isNotBlank()) "${p.name} (${p.area})" else p.name, color = Palette.Ink)
            }
        }
    }
}

/** Cartelle del telefono collegate all'album: si aggiungono col selettore di Android, ✕ le toglie. */
@Composable
private fun AlbumFolders(folders: List<String>, onExternalStart: () -> Unit, onChange: (List<String>) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            app.semplicephone.data.Album.keep(context, uri)
            if (uri.toString() !in folders) onChange(folders + uri.toString())
        }
    }
    val readable = remember(folders) {
        context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
    }
    SectionLabel(stringResource(R.string.album_folders))
    Hint(stringResource(R.string.album_folders_hint))
    folders.forEach { f ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "📁 " + (if (f in readable) app.semplicephone.data.Album.folderName(context, f) else stringResource(R.string.album_folder_other_phone)),
                fontSize = 18.sp, color = Palette.Ink, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { app.semplicephone.data.Album.release(context, f); onChange(folders - f) }) {
                Text("✕", color = Palette.Sos, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    OutlinedButton(
        onClick = { onExternalStart(); runCatching { pick.launch(null) } },
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) { Text(stringResource(R.string.album_add_folder)) }
}
