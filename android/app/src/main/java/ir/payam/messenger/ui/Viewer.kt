package ir.payam.messenger.ui

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import ir.payam.messenger.core.Acc
import ir.payam.messenger.core.L
import ir.payam.messenger.data.Msg
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

@Composable
fun ViewerScreen(acc: Acc, peer: String, id: String) {
    val ctx = LocalContext.current
    val m by produceState<Msg?>(null, id) { value = acc.d.msg(peer, id) }
    val msg = m ?: return Box(Modifier.fillMaxSize().background(Color.Black))
    val media = remember(msg.media) { JSONObject(msg.media ?: "{}") }
    val path = media.optString("path")
    DisposableEffect(id) {
        onDispose { if (msg.viewOnce && !msg.out) acc.scope.launch { acc.engine.consumeOnce(msg) } }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (path.isEmpty() || !File(path).exists()) {
            Text(L("فایل در دسترس نیست", "File not available"), Modifier.align(Alignment.Center), color = Color.White)
        } else if (msg.type == "video" || msg.type == "vnote") {
            AndroidView({ c ->
                VideoView(c).apply {
                    setMediaController(MediaController(c).also { it.setAnchorView(this) })
                    setVideoPath(path)
                    setOnPreparedListener { start() }
                }
            }, Modifier.fillMaxSize().safeDrawingPadding(), onRelease = { it.stopPlayback() })
        } else {
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            val st = rememberTransformableState { z, pan, _ ->
                scale = (scale * z).coerceIn(1f, 6f)
                offset = if (scale == 1f) Offset.Zero else offset + pan
            }
            AsyncImage(
                File(path), null,
                Modifier.fillMaxSize().transformable(st).graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                },
                contentScale = ContentScale.Fit,
            )
        }
        Row(Modifier.fillMaxWidth().safeDrawingPadding()) {
            IconButton({ Nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L("بازگشت", "Back"), tint = Color.White) }
            Box(Modifier.weight(1f))
            if (!msg.viewOnce && path.isNotEmpty() && Build.VERSION.SDK_INT >= 29) IconButton({
                saveToGallery(ctx, File(path), media.optString("mime"), msg.type == "video" || msg.type == "vnote")
            }) { Icon(Icons.Default.Download, L("ذخیره در گالری", "Save to gallery"), tint = Color.White) }
        }
    }
}

private fun saveToGallery(ctx: android.content.Context, f: File, mime: String, video: Boolean) {
    if (Build.VERSION.SDK_INT < 29) return
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "Payam_" + System.currentTimeMillis() + "." + f.extension)
        put(MediaStore.MediaColumns.MIME_TYPE, mime.ifEmpty { if (video) "video/mp4" else "image/jpeg" })
        put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/Payam")
    }
    val col = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val uri = ctx.contentResolver.insert(col, values) ?: return
    ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
    toast(ctx, L("در گالری ذخیره شد", "Saved to gallery"))
}
