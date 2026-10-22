package ir.payam.messenger.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import ir.payam.messenger.core.Acc
import ir.payam.messenger.core.Ent
import ir.payam.messenger.core.Fmt
import ir.payam.messenger.core.L
import ir.payam.messenger.core.Md
import ir.payam.messenger.core.Player
import ir.payam.messenger.core.unb64
import ir.payam.messenger.data.Contact
import ir.payam.messenger.data.Msg
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.Locale

fun richText(text: String, ents: List<Ent>, revealed: Boolean, link: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    for (e in ents) {
        val s = e.s.coerceIn(0, text.length)
        val en = e.e.coerceIn(s, text.length)
        when (e.t) {
            "b" -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), s, en)
            "i" -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), s, en)
            "s" -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), s, en)
            "c" -> addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x22808080)), s, en)
            "sp" -> if (!revealed) addStyle(SpanStyle(color = Color.Transparent, background = Color(0xFF9E9E9E)), s, en)
        }
    }
    for (r in Md.URL.findAll(text)) {
        val url = if (r.value.startsWith("www.", true)) "https://" + r.value else r.value
        addLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline))), r.range.first, r.range.last + 1)
    }
}

private fun thumbOf(media: JSONObject): ImageBitmap? = media.optString("thumb").takeIf { it.isNotEmpty() }?.let {
    runCatching { val b = unb64(it); BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull()
}

@Composable
fun Bubble(
    acc: Acc, m: Msg, peer: Contact?, selected: Boolean, highlight: Boolean,
    onClick: () -> Unit, onLong: () -> Unit, onReplyClick: (String) -> Unit,
) {
    if (m.type == "system") {
        Box(Modifier.fillMaxWidth().padding(6.dp), contentAlignment = Alignment.Center) {
            Text(m.text, Modifier.background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(12.dp)).padding(10.dp, 4.dp), color = Color.White, fontSize = 13.sp)
        }
        return
    }
    val dark = dark()
    val bg = if (m.out) (if (dark) OutBubbleDark else OutBubbleLight) else MaterialTheme.colorScheme.surface
    val meta = if (m.out) (if (dark) Color(0xFF7DA8D3) else Color(0xFF4FAE4E)) else MaterialTheme.colorScheme.onSurfaceVariant
    val media = remember(m.media) { m.media?.let { JSONObject(it) } }
    val bare = m.type == "sticker" || m.type == "vnote"
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) Accent.copy(alpha = 0.25f) else if (highlight) Accent.copy(alpha = 0.15f) else Color.Transparent)
            .combinedClickable(onLongClick = onLong, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = if (m.out) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier.widthIn(max = 310.dp)
                .then(if (bare) Modifier else Modifier.background(bg, RoundedCornerShape(16.dp)))
                .padding(if (bare) 0.dp else 8.dp),
        ) {
            m.fwd?.let { f ->
                Text(L("هدایت‌شده از ", "Forwarded from ") + JSONObject(f).optString("n"), color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            m.replyTo?.let { rid -> ReplyQuote(acc, m.peer, rid, peer) { onReplyClick(rid) } }
            if (media != null) MediaBody(acc, m, media)
            if (m.text.isNotEmpty()) {
                var revealed by remember { mutableStateOf(false) }
                val ents = remember(m.ent) { Md.fromJson(m.ent) }
                Text(
                    richText(m.text, ents, revealed, Accent), fontSize = 16.sp,
                    modifier = Modifier.padding(top = if (media != null) 4.dp else 0.dp)
                        .then(if (ents.any { it.t == "sp" }) Modifier.combinedClickable(onLongClick = onLong) { revealed = !revealed } else Modifier),
                )
            }
            m.lp?.let { LinkPreview(JSONObject(it)) }
            Reactions(acc, m)
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (m.viewOnce) Text("🔥 ", fontSize = 11.sp)
                if (m.expiresAt > 0) Text("⏱ ", fontSize = 11.sp)
                if (m.edited) Text(L("ویرایش‌شده ", "edited "), fontSize = 11.sp, color = meta)
                Text(Fmt.time(m.ts), fontSize = 11.sp, color = if (bare) Color.White else meta,
                    modifier = if (bare) Modifier.background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(8.dp)).padding(4.dp, 1.dp) else Modifier)
                if (m.out && m.peer != acc.user) {
                    Spacer(Modifier.width(3.dp))
                    when (m.status) {
                        -1 -> Icon(Icons.Default.Error, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.error)
                        -2, 0 -> Icon(Icons.Default.Schedule, null, Modifier.size(14.dp), tint = meta)
                        1 -> Icon(Icons.Default.Done, null, Modifier.size(15.dp), tint = meta)
                        else -> Icon(Icons.Default.DoneAll, null, Modifier.size(15.dp), tint = if (m.status == 3) Accent else meta)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplyQuote(acc: Acc, peer: String, id: String, c: Contact?, onClick: () -> Unit) {
    val target by produceState<Msg?>(null, id) { value = acc.d.msg(peer, id) }
    Row(Modifier.padding(bottom = 4.dp).combinedClickable(onClick = onClick).background(Accent.copy(alpha = 0.1f), RoundedCornerShape(6.dp))) {
        Box(Modifier.width(3.dp).height(38.dp).background(Accent))
        Column(Modifier.padding(6.dp, 2.dp)) {
            Text(
                when { target == null -> ""; target!!.out -> acc.name.ifEmpty { L("شما", "You") }; else -> c?.display ?: peer },
                color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            )
            Text(target?.let { msgPreview(it) } ?: L("پیام حذف شده", "Deleted message"), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
        }
    }
}

@Composable
private fun LinkPreview(lp: JSONObject) {
    Row(Modifier.padding(top = 6.dp)) {
        Box(Modifier.width(3.dp).height(48.dp).background(Accent))
        Column(Modifier.padding(start = 6.dp)) {
            Text(lp.optString("title"), fontWeight = FontWeight.SemiBold, color = Accent, maxLines = 2, fontSize = 14.sp)
            lp.optString("desc").takeIf { it.isNotEmpty() }?.let { Text(it, maxLines = 3, fontSize = 13.sp, overflow = TextOverflow.Ellipsis) }
            thumbOf(JSONObject().put("thumb", lp.optString("img")))?.let {
                Image(it, null, Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.FillWidth)
            }
        }
    }
}

@Composable
private fun Reactions(acc: Acc, m: Msg) {
    val r = m.reactions?.let { JSONObject(it) } ?: return
    val scope = rememberCoroutineScope()
    val counts = r.keys().asSequence().groupingBy { r.getString(it) }.eachCount()
    val mine = r.optString(acc.user)
    Row(Modifier.padding(top = 4.dp)) {
        counts.forEach { (emoji, n) ->
            Text(
                "$emoji $n",
                Modifier.padding(end = 4.dp)
                    .background(if (emoji == mine) Accent.copy(alpha = 0.35f) else Color(0x22808080), RoundedCornerShape(12.dp))
                    .combinedClickable { scope.launch { acc.engine.react(m, emoji) } }
                    .padding(8.dp, 2.dp),
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun MediaBody(acc: Acc, m: Msg, media: JSONObject) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloading by acc.engine.downloading.collectAsState()
    val path = media.optString("path").takeIf { it.isNotEmpty() && File(it).exists() }
    val blocked = media.optString("blocked").takeIf { it.isNotEmpty() }
    val busy = m.id in downloading || (m.out && m.status == 0 && !media.has("blob") && m.peer != acc.user)
    fun download() = scope.launch { acc.engine.download(m) }

    if (blocked != null) {
        Text("⛔ " + blocked, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
        return
    }
    if (path == null && media.optBoolean("gone")) {
        Text(L("فایل از حافظه پاک شده است", "File was cleared from storage"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    when (m.type) {
        "photo", "video", "sticker", "vnote" -> {
            if (m.viewOnce && !m.out) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.combinedClickable {
                    if (path != null) Nav.go(Scr.Viewer(m.peer, m.id)) else if (!m.viewed) download()
                }) {
                    Text("🔥", fontSize = 28.sp)
                    Text(
                        when { m.viewed -> L("  دیده شد", "  Opened"); path != null -> L("  عکس یک‌بار مصرف — برای دیدن بزنید", "  View-once photo — tap to view"); else -> L("  دریافت عکس یک‌بار مصرف", "  Download view-once photo") },
                        fontSize = 14.sp,
                    )
                    if (busy) CircularProgressIndicator(Modifier.padding(start = 8.dp).size(18.dp), strokeWidth = 2.dp)
                }
                return
            }
            val w = media.optInt("w", 1).coerceAtLeast(1)
            val h = media.optInt("h", 1).coerceAtLeast(1)
            val ratio = (w.toFloat() / h).coerceIn(0.6f, 1.8f)
            val shape = if (m.type == "vnote") CircleShape else RoundedCornerShape(12.dp)
            val mod = when (m.type) {
                "sticker" -> Modifier.size(160.dp)
                "vnote" -> Modifier.size(200.dp).clip(CircleShape)
                else -> Modifier.width(260.dp).aspectRatio(ratio).clip(shape)
            }
            val thumb = remember(m.id) { thumbOf(media) }
            Box(mod.combinedClickable { if (path != null && m.type != "sticker") Nav.go(Scr.Viewer(m.peer, m.id)) else if (path == null) download() }, contentAlignment = Alignment.Center) {
                when {
                    path != null && m.type in setOf("photo", "sticker") -> AsyncImage(File(path), null, Modifier.matchParentSize(), contentScale = if (m.type == "sticker") ContentScale.Fit else ContentScale.Crop)
                    thumb != null -> Image(thumb, null, Modifier.matchParentSize().then(if (path == null && m.type == "photo") Modifier.blur(12.dp) else Modifier), contentScale = ContentScale.Crop)
                    else -> Box(Modifier.matchParentSize().background(Color(0x33808080)))
                }
                when {
                    busy -> CircularProgressIndicator(Modifier.size(40.dp), color = Color.White)
                    path == null -> Icon(Icons.Default.Download, null, Modifier.size(44.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(8.dp), tint = Color.White)
                    m.type == "video" || m.type == "vnote" -> Icon(Icons.Default.PlayArrow, null, Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(8.dp), tint = Color.White)
                }
                if (m.type == "video" && media.optInt("dur") > 0) Text(
                    Fmt.dur(media.optInt("dur")), Modifier.align(Alignment.TopStart).padding(6.dp).background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp)).padding(4.dp, 1.dp),
                    color = Color.White, fontSize = 11.sp,
                )
            }
        }
        "voice" -> Voice(m, media, path, busy) { download() }
        "file" -> {
            val mime = media.optString("mime")
            if (mime.startsWith("audio/") && path != null) { Voice(m, media, path, false) {}; Text(media.optString("name"), fontSize = 13.sp); return }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.combinedClickable { if (path != null) openFile(ctx, File(path), mime.ifEmpty { "application/octet-stream" }) else download() },
            ) {
                Box(Modifier.size(46.dp).background(Accent, CircleShape), contentAlignment = Alignment.Center) {
                    when {
                        busy -> CircularProgressIndicator(Modifier.size(26.dp), color = Color.White, strokeWidth = 2.dp)
                        path == null -> Icon(Icons.Default.Download, null, tint = Color.White)
                        else -> Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = Color.White)
                    }
                }
                Column(Modifier.padding(start = 10.dp)) {
                    Text(media.optString("name"), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(Fmt.size(media.optLong("size")), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        "loc" -> {
            val lat = media.optDouble("lat"); val lon = media.optDouble("lon")
            Column(Modifier.combinedClickable {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon"))) }
                    .onFailure { toast(ctx, L("برنامه نقشه پیدا نشد", "No map app")) }
            }.padding(4.dp)) {
                Text("📍 " + L("موقعیت مکانی", "Location"), fontWeight = FontWeight.SemiBold)
                Text(String.format(Locale.US, "%.5f, %.5f", lat, lon), fontSize = 13.sp)
                Text(L("برای باز کردن در نقشه بزنید", "Tap to open in maps"), fontSize = 12.sp, color = Accent)
            }
        }
        "contact" -> ContactCard(acc, media)
    }
}

@Composable
private fun Voice(m: Msg, media: JSONObject, path: String?, busy: Boolean, download: () -> Unit) {
    val playing by Player.playing.collectAsState()
    val progress by Player.progress.collectAsState()
    val speed by Player.speed.collectAsState()
    val me = playing == m.id
    val wave = remember(m.id) { media.optString("wave").takeIf { it.isNotEmpty() }?.let { runCatching { unb64(it) }.getOrNull() } ?: ByteArray(48) { 6 } }
    val accent = Accent
    val idle = Accent.copy(alpha = 0.35f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton({ if (path != null) Player.toggle(m.id, path) else download() }, Modifier.size(44.dp).background(Accent, CircleShape)) {
            when {
                busy -> CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                path == null -> Icon(Icons.Default.Download, null, tint = Color.White)
                me -> Icon(Icons.Default.Pause, null, tint = Color.White)
                else -> Icon(Icons.Default.PlayArrow, null, tint = Color.White)
            }
        }
        Column(Modifier.padding(start = 8.dp)) {
            Canvas(Modifier.width(160.dp).height(28.dp)) {
                val bw = size.width / wave.size
                wave.forEachIndexed { i, v ->
                    val bh = (v.toInt().coerceIn(1, 31) / 31f) * size.height
                    drawRoundRect(
                        if (me && i.toFloat() / wave.size <= progress) accent else idle,
                        Offset(i * bw, size.height - bh), Size(bw * 0.6f, bh), CornerRadius(2f, 2f),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Fmt.dur(media.optInt("dur")), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (me) Text(
                    "  ${if (speed == 1f) "1" else speed.toString()}x",
                    Modifier.combinedClickable { Player.cycleSpeed() }, fontSize = 12.sp, color = Accent, fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

