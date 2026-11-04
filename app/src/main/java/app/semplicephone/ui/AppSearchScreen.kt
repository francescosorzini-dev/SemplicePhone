package app.semplicephone.ui

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import app.semplicephone.R
import app.semplicephone.data.AppCatalog
import app.semplicephone.data.AppEntry
import app.semplicephone.launch.TileLauncher
import app.semplicephone.model.SosConfig
import app.semplicephone.model.Tile
import app.semplicephone.model.TileType

/**
 * Ricerca delle app dentro il launcher (riquadro «Cerca app»): campo grande con la tastiera già
 * aperta, risultati mentre si scrive (per contenuto, senza accenti, per iniziali), un tocco apre
 * l'app come farebbe un suo riquadro. Si può anche dettare il nome.
 */
@Composable
fun AppSearchScreen(
    apps: List<AppEntry>?,
    launcher: TileLauncher,
    sos: SosConfig,
    onClose: () -> Unit,
    onExternalStart: () -> Unit,
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val results = remember(query, apps) { apps?.let { AppCatalog.search(query, it) } }
    val speech = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }
    val canSpeak = remember { speech.resolveActivity(context.packageManager) != null }
    val dictate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { query = it }
    }
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }

    fun open(app: AppEntry) {
        keyboard?.hide()
        // Come un riquadro «app»: stessa apertura dall'inizio, conferma vocale e pulsante «Casa».
        launcher.launch(Tile(id = "search", col = 0, row = 0, type = TileType.APP, target = app.target, label = app.label), sos)
        onClose()
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFFF6F4EF)).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.search_back), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.search_title), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f).focusRequester(focus),
                singleLine = true,
                textStyle = TextStyle(fontSize = 26.sp, color = Palette.Ink),
                placeholder = { Text(stringResource(R.string.search_placeholder), fontSize = 20.sp) },
                leadingIcon = { Icon(Symbols.get("search"), null, Modifier.size(30.dp), tint = Palette.Ink) },
                trailingIcon = if (query.isNotEmpty()) {
                    { TextButton(onClick = { query = "" }) { Text("✕", fontSize = 24.sp, color = Palette.Ink) } }
                } else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { results?.firstOrNull()?.let(::open) }),
                shape = RoundedCornerShape(16.dp),
            )
            if (canSpeak) {
                OutlinedButton(
                    onClick = { onExternalStart(); runCatching { dictate.launch(speech) } },
                    modifier = Modifier.heightIn(min = 64.dp),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) { Text("🎤 " + stringResource(R.string.search_voice), fontSize = 18.sp, color = Palette.Ink) }
            }
        }

        when {
            results == null -> Text(stringResource(R.string.loading), fontSize = 22.sp, color = Palette.Ink)
            results.isEmpty() -> Text(
                stringResource(R.string.search_none, query.trim()), fontSize = 22.sp, color = Palette.Ink,
                modifier = Modifier.padding(top = 24.dp),
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                items(results, key = { it.target }) { app -> AppRow(app, launcher) { open(app) } }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppEntry, launcher: TileLauncher, onOpen: () -> Unit) {
    val icon = remember(app.target) { runCatching { launcher.appIcon(app.target)?.toBitmap(112, 112)?.asImageBitmap() }.getOrNull() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(2.dp, Palette.Ink, RoundedCornerShape(16.dp))
            .combinedClickable(role = Role.Button, onClickLabel = app.label, onLongClick = { launcher.speakText(app.label) }, onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            if (icon != null) Image(icon, null, Modifier.size(56.dp))
            else Icon(Symbols.get("grid"), null, Modifier.size(40.dp), tint = Palette.Ink)
        }
        Text(app.label, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
