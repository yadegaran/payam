package ir.payam.messenger.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import ir.payam.messenger.core.Acc
import ir.payam.messenger.core.Fmt
import ir.payam.messenger.core.Ids
import ir.payam.messenger.core.L
import ir.payam.messenger.core.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(acc: Acc, peer: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c by acc.d.contactFlow(peer).collectAsState(null)
    val chat by acc.d.chatFlow(peer).collectAsState(null)
    val media by acc.d.mediaFlow(peer).collectAsState(emptyList())
    val presence by acc.engine.presence.collectAsState()
    var safety by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    val contact = c ?: return
    val theirFp = Ids.fingerprint(contact.ed, contact.curve)
    val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
        val inv = r.contents?.let(::parseInvite) ?: return@rememberLauncherForActivityResult
        scope.launch {
            if (inv.user == peer && inv.fp == theirFp && !acc.isOtherServer(inv)) {
                acc.d.putContact(contact.copy(verified = 1))
                toast(ctx, L("✅ هویت تأیید شد", "✅ Identity verified"))
            } else toast(ctx, L("⚠ این کد با مخاطب نمی‌خواند!", "⚠ This code does not match the contact!"))
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(L("اطلاعات", "Info")) }, navigationIcon = { IconButton({ Nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L("بازگشت", "Back")) } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            val photos = acc.engine.photoFiles(contact)
            if (photos.isNotEmpty()) {
                val pager = rememberPagerState { photos.size }
                HorizontalPager(pager, Modifier.fillMaxWidth().aspectRatio(1f)) { i ->
                    AsyncImage(photos[i], null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            } else Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Avatar(acc, contact, 120.dp) }
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(contact.display, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    if (contact.verified == 1) Icon(Icons.Default.Verified, null, Modifier.padding(start = 6.dp), tint = Accent)
                }
                presence[peer]?.let { Text(Fmt.lastSeen(it.online, it.lastSeen, it.hidden), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.height(12.dp))
                Text("@" + contact.user, Modifier.clickable { copy(ctx, contact.user) }, color = Accent, fontSize = 17.sp)
                Text(L("نام کاربری", "Username"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (contact.bio.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(contact.bio)
                    Text(L("درباره", "Bio"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (contact.localName != null && contact.name.isNotEmpty()) Text(L("نام در پروفایل: ", "Profile name: ") + contact.name, fontSize = 13.sp)

                Spacer(Modifier.height(16.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        if (contact.verified == 1) Text(L("✅ هویت این مخاطب به‌طور کامل تأیید شده است.", "✅ This contact's identity is fully verified."))
                        else Row {
                            Icon(Icons.Default.Warning, null, tint = Color(0xFFF9A825))
                            Text(
                                L("کلید این مخاطب با نام کاربری‌اش می‌خواند. برای اطمینان کامل، QR او را اسکن کنید یا کد امنیتی را حضوری مقایسه کنید.",
                                    "The key matches the username. For full certainty, scan their QR or compare the safety number in person."),
                                Modifier.padding(start = 6.dp), fontSize = 14.sp,
                            )
                        }
                        Row(Modifier.padding(top = 8.dp)) {
                            TextButton({ safety = true }) { Text(L("کد امنیتی", "Safety number")) }
                            TextButton({ scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setOrientationLocked(false).setPrompt("")) }) {
                                Text(L("اسکن QR مخاطب", "Scan their QR"))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                MenuLine("💬  " + L("ارسال پیام", "Send message")) { Nav.go(Scr.ChatS(peer)) }
                MenuLine("✏  " + L("نام دلخواه", "Custom name")) { rename = true }
                MenuLine(if (contact.isContact) "➖  " + L("حذف از مخاطبین", "Remove contact") else "➕  " + L("افزودن به مخاطبین", "Add to contacts")) {
                    scope.launch { acc.d.putContact(contact.copy(isContact = !contact.isContact)) }
                }
                chat?.let { ch ->
                    MenuLine(if (ch.muted) "🔔  " + L("روشن کردن اعلان", "Unmute") else "🔕  " + L("بی‌صدا کردن", "Mute")) { scope.launch { acc.d.putChat(ch.copy(muted = !ch.muted)) } }
                }
                MenuLine("📤  " + L("اشتراک‌گذاری مخاطب", "Share contact")) { share(ctx, "${acc.server}/u/${contact.user}#$theirFp") }
                MenuLine(if (contact.blocked) "✅  " + L("رفع مسدودیت", "Unblock") else "⛔  " + L("مسدود کردن", "Block")) {
                    scope.launch { acc.engine.setBlocked(peer, !contact.blocked) }
                }
                if (media.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(L("رسانه‌ها و فایل‌ها (${media.size})", "Media & files (${media.size})"), fontWeight = FontWeight.SemiBold)
                    media.filter { it.type == "photo" || it.type == "video" }.take(30).chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth()) {
                            row.forEach { m ->
                                val p = m.media?.let { JSONObject(it).optString("path") }?.takeIf { File(it).exists() }
                                Box(Modifier.weight(1f).aspectRatio(1f).padding(1.dp).background(Color(0x33808080)).clickable { if (p != null) Nav.go(Scr.Viewer(peer, m.id)) }) {
                                    if (p != null && m.type == "photo") AsyncImage(File(p), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    else Text(if (m.type == "video") "🎬" else "🖼", Modifier.align(Alignment.Center), fontSize = 24.sp)
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    media.filter { it.type == "file" || it.type == "voice" }.take(30).forEach { m ->
                        val j = JSONObject(m.media ?: "{}")
                        Text((if (m.type == "voice") "🎤 " else "📎 ") + j.optString("name") + "  " + Fmt.size(j.optLong("size")), Modifier.padding(vertical = 4.dp), fontSize = 14.sp)
                    }
                }
            }
        }
    }

    if (safety) AlertDialog({ safety = false }, confirmButton = { TextButton({ safety = false }) { Text(L("بستن", "Close")) } },
        title = { Text(L("کد امنیتی", "Safety number")) }, text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Ids.safetyNumber(acc.fp, theirFp), fontFamily = FontFamily.Monospace, fontSize = 17.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(L("این عدد باید روی گوشی هر دو نفر یکسان باشد. یا مخاطب QR زیر را اسکن کند:", "This number must be identical on both phones. Or let them scan this QR:"), fontSize = 13.sp)
                Image(qr(acc.inviteLink), null, Modifier.size(200.dp).padding(8.dp))
            }
        })
    if (rename) {
        var v by remember { mutableStateOf(contact.localName ?: "") }
        AlertDialog({ rename = false }, title = { Text(L("نام دلخواه", "Custom name")) },
            text = { OutlinedTextField(v, { v = it.take(64) }, singleLine = true, placeholder = { Text(contact.name.ifEmpty { contact.user }) }) },
            confirmButton = { TextButton({ rename = false; scope.launch { acc.d.putContact(contact.copy(localName = v.trim().ifEmpty { null })) } }) { Text(L("ذخیره", "Save")) } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeScreen(acc: Acc) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rev by acc.profileRev.collectAsState()
    var name by remember { mutableStateOf(acc.name) }
    var bio by remember { mutableStateOf(acc.bio) }
    val photos = remember(rev) { acc.myPhotos() }

    fun publish() = scope.launch(Dispatchers.IO) { acc.engine.publishProfile() }
    fun renumber() {
        val files = (0 until 5).map { File(acc.avatarDir, "me_$it.jpg") }.filter { it.exists() }
        files.forEachIndexed { i, f -> val t = File(acc.avatarDir, "tmp_$i.jpg"); f.renameTo(t) }
        files.indices.forEach { i -> File(acc.avatarDir, "tmp_$i.jpg").renameTo(File(acc.avatarDir, "me_$i.jpg")) }
    }
    fun changed() { acc.profileDirty = true; acc.profileRev.value++; publish() }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                // New photo goes first (Telegram-style), the rest shift right; max 5.
                File(acc.avatarDir, "me_4.jpg").delete()
                for (i in 3 downTo 0) File(acc.avatarDir, "me_$i.jpg").takeIf { it.exists() }?.renameTo(File(acc.avatarDir, "me_${i + 1}.jpg"))
                Media.prepareAvatar(ctx, uri, File(acc.avatarDir, "me_0.jpg"))
                renumber()
            }
            changed()
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(L("پروفایل من", "My profile")) }, navigationIcon = { IconButton({ Nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L("بازگشت", "Back")) } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                photos.forEachIndexed { i, f ->
                    Box {
                        AsyncImage(f, null, Modifier.size(if (i == 0) 96.dp else 56.dp).clip(CircleShape).clickable {
                            if (i > 0) { // make main
                                val main = File(acc.avatarDir, "me_0.jpg"); val t = File(acc.avatarDir, "swap.jpg")
                                main.renameTo(t); f.renameTo(main); t.renameTo(f); changed()
                            }
                        }, contentScale = ContentScale.Crop)
                        Icon(Icons.Default.Close, null, Modifier.align(Alignment.TopEnd).size(20.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            .clickable { f.delete(); renumber(); changed() }, tint = Color.White)
                    }
                }
                if (photos.size < 5) IconButton({ pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    Modifier.size(56.dp).background(Accent.copy(alpha = 0.2f), CircleShape)) { Icon(Icons.Default.Add, null, tint = Accent) }
            }
            if (photos.size > 1) Text(L("برای عکس اصلی روی عکس کوچک بزنید", "Tap a small photo to make it main"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(name, { name = it.take(64) }, Modifier.fillMaxWidth(), label = { Text(L("نام", "Name")) }, singleLine = true)
            OutlinedTextField(bio, { bio = it.take(300) }, Modifier.fillMaxWidth(), label = { Text(L("درباره من", "Bio")) }, maxLines = 4)
            Button({ acc.name = name.trim(); acc.bio = bio.trim(); publish(); toast(ctx, L("ذخیره شد", "Saved")) }, Modifier.padding(top = 8.dp)) { Text(L("ذخیره", "Save")) }

            Spacer(Modifier.height(24.dp))
            Text(L("نام کاربری شما", "Your username"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(acc.user, fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.clickable { copy(ctx, acc.user) })
            Text(L("این نام را به دوستانتان بدهید. لینک یا QR امن‌تر است چون کلید شما را کامل تأیید می‌کند.",
                "Share this with friends. The link or QR is safer — it verifies your full key."), textAlign = TextAlign.Center, fontSize = 13.sp)
            Image(qr(acc.inviteLink), null, Modifier.padding(12.dp).size(220.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(8.dp))
            Row {
                OutlinedButton({ share(ctx, L("با من در پیام‌رسان امن گفتگو کن: ", "Chat with me securely: ") + acc.inviteLink) }) { Text(L("اشتراک لینک", "Share link")) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton({ copy(ctx, acc.inviteLink) }) { Text(L("کپی لینک", "Copy link")) }
            }
            Spacer(Modifier.height(16.dp))
            Text(L("اثر انگشت کلید شما", "Your key fingerprint"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(acc.fp.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 13.sp, textAlign = TextAlign.Center)
            Text(L("سرور: ", "Server: ") + acc.server, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
