package app.semplicephone.ui.settings

import app.semplicephone.ui.Ui
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.semplicephone.R
import app.semplicephone.data.Media
import app.semplicephone.ui.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Anteprima di un'immagine della config ("media:<sha>"), quadrata. */
@Composable
fun MediaThumb(ref: String, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bmp = remember(ref) { Media.bitmap(context, ref, 400)?.asImageBitmap() }
    if (bmp != null) {
        Image(
            bmp, null, contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(Ui.ShapeSmall).border(2.dp, Palette.Ink, Ui.ShapeSmall),
        )
    }
}

/**
 * Pulsanti «Scegli foto» (selettore di sistema, nessun permesso) e «Scatta foto» (fotocamera).
 * L'immagine viene ridotta e salvata da [Media]; [onPicked] riceve il riferimento «media:<sha>».
 */
@Composable
fun MediaPickButtons(onPicked: (String) -> Unit, onExternalStart: () -> Unit, multiple: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        busy = true
        scope.launch {
            val refs = withContext(Dispatchers.IO) { uris.mapNotNull { Media.import(context, it) } }
            refs.forEach(onPicked)
            busy = false
        }
    }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> import(listOf(u)) } }
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { import(it) }
    val shotFile = remember { File(context.cacheDir, "camera/shot.jpg").apply { parentFile?.mkdirs() } }
    val shotUri = remember { FileProvider.getUriForFile(context, "${context.packageName}.files", shotFile) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) import(listOf(shotUri)) }
    val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { onExternalStart(); if (multiple) pickMany.launch(request) else pickOne.launch(request) },
            enabled = !busy,
            modifier = Modifier.weight(1f).heightIn(min = Ui.HeightSettings),
        ) { Text(stringResource(if (busy) R.string.loading else R.string.media_choose)) }
        OutlinedButton(
            onClick = { onExternalStart(); runCatching { takePicture.launch(shotUri) } },
            enabled = !busy,
            modifier = Modifier.weight(1f).heightIn(min = Ui.HeightSettings),
        ) { Text(stringResource(R.string.media_take)) }
    }
}

/** Foto singola (promemoria): anteprima, scelta e rimozione. */
@Composable
fun SinglePhotoField(ref: String, onChange: (String) -> Unit, onExternalStart: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ref.isNotBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MediaThumb(ref, 96.dp)
                OutlinedButton(onClick = { onChange("") }, modifier = Modifier.heightIn(min = Ui.HeightSettings)) {
                    Text(stringResource(R.string.media_remove), color = Palette.Warn)
                }
            }
        }
        MediaPickButtons(onPicked = onChange, onExternalStart = onExternalStart)
    }
}
