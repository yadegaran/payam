package ir.payam.messenger.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import ir.payam.messenger.core.Acc
import ir.payam.messenger.core.L
import ir.payam.messenger.core.Settings
import ir.payam.messenger.data.Contact
import ir.payam.messenger.data.Msg
import java.io.File

// ------------------------------------------------------------------ navigation

sealed interface Scr {
    data object Home : Scr
    data object Archive : Scr
    data class ChatS(val peer: String, val jumpTo: String? = null) : Scr
    data class Info(val peer: String) : Scr
    data object Me : Scr
    data object SettingsS : Scr
    data object Contacts : Scr
    data class Viewer(val peer: String, val id: String) : Scr
    data class ForwardPick(val peer: String, val ids: List<String>) : Scr
    data object Onboard : Scr
}

object Nav {
    val stack = mutableStateListOf<Scr>(Scr.Home)
    val top get() = stack.last()
    fun go(s: Scr) { stack.add(s) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    fun reset(s: Scr = Scr.Home) { stack.clear(); stack.add(s) }
}

// ------------------------------------------------------------------ theme

val Accent = Color(0xFF2AABEE)
val OutBubbleLight = Color(0xFFEFFDDE)
val OutBubbleDark = Color(0xFF2B5278)

@Composable
fun dark(): Boolean {
    Settings.rev.intValue
    return when (Settings.theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
}

@Composable
fun PayamTheme(content: @Composable () -> Unit) {
    val scheme: ColorScheme = if (dark()) darkColorScheme(
        primary = Accent, secondary = Accent, background = Color(0xFF17212B), surface = Color(0xFF17212B),
        surfaceVariant = Color(0xFF242F3D), surfaceContainer = Color(0xFF1E2C3A),
    ) else lightColorScheme(primary = Color(0xFF3390EC), secondary = Accent, surfaceContainer = Color(0xFFF1F4F7))
    val dir = if (Settings.lang == "en") LayoutDirection.Ltr else LayoutDirection.Rtl
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalLayoutDirection provides dir, content = content)
    }
}

// ------------------------------------------------------------------ avatar

private val PALETTE = listOf(0xFFE17076, 0xFF7BC862, 0xFFE5CA77, 0xFF65AADD, 0xFFA695E7, 0xFFEE7AAE, 0xFF6EC9CB, 0xFFFAA774)

@Composable
fun Avatar(acc: Acc, c: Contact?, size: Dp = 48.dp, user: String = c?.user ?: "", saved: Boolean = false) {
    val photo = c?.let { acc.engine.photoFiles(it).firstOrNull() }
    val mod = Modifier.size(size).clip(CircleShape)
    if (saved) {
        Box(mod.background(Accent), contentAlignment = Alignment.Center) { Text("🔖", fontSize = (size.value * 0.45).sp) }
    } else if (photo != null) {
        AsyncImage(model = photo, contentDescription = null, modifier = mod, contentScale = ContentScale.Crop)
    } else {
        val name = c?.display ?: user
        Box(mod.background(Color(PALETTE[(user.hashCode() and 0x7fffffff) % PALETTE.size])), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42).sp)
        }
    }
}

fun chatTitle(acc: Acc, peer: String, c: Contact?) = if (peer == acc.user) L("پیام‌های ذخیره‌شده", "Saved Messages") else c?.display ?: peer

// ------------------------------------------------------------------ helpers

fun qr(text: String, size: Int = 640): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) AColor.BLACK else AColor.WHITE }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

data class Invite(val user: String, val fp: String?, val host: String?)

/** "ab12cd34" or "https://host/u/ab12cd34#<64 hex fingerprint>" */
fun parseInvite(s: String): Invite? {
    val t = s.trim()
    Regex("""^[0-9a-zA-Z]{8}$""").find(t)?.let { return Invite(t.lowercase(), null, null) }
    val m = Regex("""^(?:https?://([^/\s]+))?.*?/u/([0-9a-z]{8})(?:#([0-9a-f]{64}))?""").find(t) ?: return null
    return Invite(m.groupValues[2], m.groupValues[3].ifEmpty { null }, m.groupValues[1].lowercase().ifEmpty { null })
}

/** Each self-hosted server is its own world: an invite only works on the server it came from. */
fun Acc.isOtherServer(inv: Invite) = inv.host != null && inv.host != server.substringAfter("://").substringBefore('/').lowercase()

fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

fun share(ctx: Context, text: String) =
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))

fun uriFor(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)

/** Opens a received file in another app, with the MIME FileGuard detected (never auto-run). */
fun openFile(ctx: Context, f: File, mime: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(ctx, f), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (e: Exception) {
        toast(ctx, L("برنامه‌ای برای باز کردن این فایل نیست", "No app can open this file"))
    }
}

fun msgPreview(m: Msg?): String = m?.let { ir.payam.messenger.svc.Notif.preview(it) } ?: ""
