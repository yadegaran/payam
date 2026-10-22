package ir.payam.messenger.ui

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import ir.payam.messenger.core.Acc
import ir.payam.messenger.core.Fmt
import ir.payam.messenger.core.FileGuard
import ir.payam.messenger.core.Ids
import ir.payam.messenger.core.L
import ir.payam.messenger.core.Media
import ir.payam.messenger.core.Settings
import ir.payam.messenger.core.VoiceRecorder
import ir.payam.messenger.data.Msg
import ir.payam.messenger.data.Sticker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Calendar

private class CaptureVideoNote : ActivityResultContract<Uri, Boolean>() {
    override fun createIntent(context: Context, input: Uri) = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
        .putExtra(MediaStore.EXTRA_OUTPUT, input)
        .putExtra(MediaStore.EXTRA_DURATION_LIMIT, 60)
        .putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 0)
        .putExtra("android.intent.extras.CAMERA_FACING", 1)
        .putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
        .putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)

    override fun parseResult(resultCode: Int, intent: Intent?) = resultCode == Activity.RESULT_OK
}

fun wallpaperColor(dark: Boolean) = if (dark) Color(0xFF0E1621) else Color(0xFFCAD9E3)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(acc: Acc, peer: String, jumpTo: String?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val e = acc.engine
    val saved = peer == acc.user
    val c by acc.d.contactFlow(peer).collectAsState(null)
    val chat by acc.d.chatFlow(peer).collectAsState(null)
    var limit by remember { mutableIntStateOf(200) }
    val msgs by remember(peer, limit) { acc.d.msgsFlow(peer, limit) }.collectAsState(emptyList())
    val scheduled by acc.d.scheduledFlow(peer).collectAsState(emptyList())
    val pinned by acc.d.pinnedFlow(peer).collectAsState(emptyList())
    val presence by e.presence.collectAsState()
    val typing by e.typing.collectAsState()
    val alert by e.securityAlert.collectAsState()
    val list = rememberLazyListState()

    var input by remember { mutableStateOf(TextFieldValue("")) }
    var draftLoaded by remember { mutableStateOf(false) }
    var replyTo by remember { mutableStateOf<Msg?>(null) }
    var editing by remember { mutableStateOf<Msg?>(null) }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var menuMsg by remember { mutableStateOf<Msg?>(null) }
    var deleting by remember { mutableStateOf<List<Msg>?>(null) }
    var attachMenu by remember { mutableStateOf(false) }
    var topMenu by remember { mutableStateOf(false) }
    var stickers by remember { mutableStateOf(false) }
    var contactPick by remember { mutableStateOf(false) }
    var sendOptions by remember { mutableStateOf(false) }
    var ttlDialog by remember { mutableStateOf(false) }
    var wallDialog by remember { mutableStateOf(false) }
    var showScheduled by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf<String?>(null) }
    var searchPos by remember { mutableIntStateOf(0) }
    var highlight by remember { mutableStateOf(jumpTo) }
    var viewOnceNext by remember { mutableStateOf(false) }
    val recorder = remember { VoiceRecorder(ctx) }
    var recording by remember { mutableStateOf(false) }
    val elapsed by recorder.elapsed.collectAsState()

    DisposableEffect(peer) {
        e.openChat = peer
        onDispose {
            e.openChat = null
            val draft = input.text
            acc.scope.launch { acc.d.chat(peer)?.let { acc.d.putChat(it.copy(draft = draft)) } }
            if (recording) recorder.stop(cancel = true)
        }
    }
    LaunchedEffect(chat) {
        if (!draftLoaded && chat != null) {
            draftLoaded = true
            if (chat!!.draft.isNotEmpty()) input = TextFieldValue(chat!!.draft, TextRange(chat!!.draft.length))
        }
    }
    LaunchedEffect(msgs.firstOrNull()?.id) { if (!saved) e.markRead(peer) }
    LaunchedEffect(peer) {
        if (!saved) {
            if (acc.d.chat(peer) == null) acc.d.putChat(ir.payam.messenger.data.Chat(peer, lastTs = System.currentTimeMillis()))
            e.subscribe()
            val cc = acc.d.contact(peer)
            if (cc == null || System.currentTimeMillis() - cc.profileTs > 6 * 3600_000L) e.lookup(peer)
        }
    }
    LaunchedEffect(jumpTo, msgs.size) {
        val i = msgs.indexOfFirst { it.id == jumpTo }
        if (jumpTo != null && i >= 0 && highlight == jumpTo) { list.scrollToItem(i); kotlinx.coroutines.delay(1500); highlight = null }
    }
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { if (it > limit - 20) limit += 200 }
    }

    // ---------------------------------------------------------------- pickers
    fun caption(): String = input.text.also { input = TextFieldValue("") }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val cap = caption()
        val once = viewOnceNext.also { viewOnceNext = false }
        val reply = replyTo?.id.also { replyTo = null }
        scope.launch(Dispatchers.IO) {
            uris.forEachIndexed { i, uri ->
                val mime = ctx.contentResolver.getType(uri) ?: ""
                try {
                    if (mime.startsWith("video")) {
                        val name = FileGuard.cleanName(Media.displayName(ctx, uri)).let { if ('.' in it) it else "$it.mp4" }
                        val f = Media.import(acc, uri, name)
                        val v = FileGuard.check(f, name)
                        if (!v.ok) { f.delete(); withContext(Dispatchers.Main) { toast(ctx, v.reason) }; return@forEachIndexed }
                        e.sendMedia(peer, "video", f, Media.videoMeta(f, v.name).put("mime", v.mime), if (i == 0) cap else "", reply, viewOnce = once)
                    } else {
                        val (f, meta) = Media.preparePhoto(acc, uri)
                        e.sendMedia(peer, "photo", f, meta, if (i == 0) cap else "", reply, viewOnce = once)
                    }
                } catch (ex: Exception) {
                    withContext(Dispatchers.Main) { toast(ctx, L("ارسال نشد", "Couldn't send")) }
                }
            }
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val cap = caption()
        val reply = replyTo?.id.also { replyTo = null }
        scope.launch(Dispatchers.IO) {
            val name = Media.displayName(ctx, uri)
            val f = Media.import(acc, uri, name)
            if (f.length() > 100L * 1024 * 1024) { f.delete(); withContext(Dispatchers.Main) { toast(ctx, L("حداکثر حجم ۱۰۰ مگابایت است", "Max size is 100 MB")) }; return@launch }
            val v = FileGuard.check(f, name)
            if (!v.ok) { f.delete(); withContext(Dispatchers.Main) { toast(ctx, "⛔ " + v.reason) }; return@launch }
            e.sendMedia(peer, "file", f, JSONObject().put("mime", v.mime).put("name", v.name), cap, reply)
        }
    }
    var noteFile by remember { mutableStateOf<File?>(null) }
    val captureNote = rememberLauncherForActivityResult(CaptureVideoNote()) { ok ->
        val f = noteFile ?: return@rememberLauncherForActivityResult
        if (!ok || f.length() == 0L) { f.delete(); return@rememberLauncherForActivityResult }
        scope.launch(Dispatchers.IO) {
            val v = FileGuard.check(f, "note.mp4")
            if (!v.ok) { f.delete(); return@launch }
            e.sendMedia(peer, "vnote", f, Media.videoMeta(f, "note.mp4").put("mime", v.mime))
        }
    }
    fun startNote() {
        val f = File(acc.mediaDir, Ids.newId() + ".mp4")
        noteFile = f
        captureNote.launch(uriFor(ctx, f))
    }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) startNote() }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val locPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) sendLocation(ctx, acc, peer) }

    fun send(silent: Boolean = false, at: Long = 0) {
        val text = input.text
        if (text.isBlank()) return
        val ed = editing
        val reply = replyTo?.id
        input = TextFieldValue("")
        replyTo = null
        editing = null
        scope.launch(Dispatchers.IO) {
            if (ed != null) e.edit(ed, text) else e.sendText(peer, text, reply, silent, at)
        }
    }

    // ---------------------------------------------------------------- layout
    val title = chatTitle(acc, peer, c)
    val subtitle = when {
        saved -> ""
        typing[peer]?.let { System.currentTimeMillis() - it < 6000 } == true -> L("در حال نوشتن…", "typing…")
        else -> presence[peer]?.let { Fmt.lastSeen(it.online, it.lastSeen, it.hidden) } ?: ""
    }
    val searchHits = remember(search, msgs) { search?.takeIf { it.length >= 2 }?.let { q -> msgs.indices.filter { msgs[it].text.contains(q, true) } } ?: emptyList() }
    val isDark = dark()
    val wp = chat?.wallpaper?.takeIf { it.isNotEmpty() } ?: Settings.wallpaper

    Scaffold(
        topBar = {
            when {
                selection.isNotEmpty() -> TopAppBar(
                    navigationIcon = { IconButton({ selection = emptySet() }) { Icon(Icons.Default.Close, null) } },
                    title = { Text("${selection.size}") },
                    actions = {
                        val sel = msgs.filter { it.id in selection }
                        IconButton({ copy(ctx, sel.sortedBy { it.ts }.joinToString("\n") { it.text }); selection = emptySet() }) { Icon(Icons.Default.ContentCopy, L("کپی", "Copy")) }
                        IconButton({ Nav.go(Scr.ForwardPick(peer, sel.map { it.id })); selection = emptySet() }) { Icon(Icons.AutoMirrored.Filled.Forward, L("هدایت", "Forward")) }
                        IconButton({ deleting = sel; selection = emptySet() }) { Icon(Icons.Default.Delete, L("حذف", "Delete")) }
                    },
                )
                search != null -> TopAppBar(
                    navigationIcon = { IconButton({ search = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L("بازگشت", "Back")) } },
                    title = {
                        TextField(search!!, { search = it; searchPos = 0 }, singleLine = true, placeholder = { Text(L("جستجو در گفتگو", "Search chat")) },
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent))
                    },
                    actions = {
                        if (searchHits.isNotEmpty()) Text("${searchPos + 1}/${searchHits.size}", fontSize = 13.sp)
                        IconButton({ if (searchHits.isNotEmpty()) { searchPos = (searchPos + 1) % searchHits.size; scope.launch { list.scrollToItem(searchHits[searchPos]) } } }) { Icon(Icons.Default.KeyboardArrowUp, null) }
                        IconButton({ if (searchHits.isNotEmpty()) { searchPos = (searchPos - 1 + searchHits.size) % searchHits.size; scope.launch { list.scrollToItem(searchHits[searchPos]) } } }) { Icon(Icons.Default.KeyboardArrowDown, null) }
                    },
                )
                else -> TopAppBar(
                    navigationIcon = { IconButton({ Nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L("بازگشت", "Back")) } },
                    title = {
                        Row(Modifier.clickable(enabled = !saved) { Nav.go(Scr.Info(peer)) }, verticalAlignment = Alignment.CenterVertically) {
                            Avatar(acc, c, 40.dp, peer, saved = saved)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 17.sp)
                                    if (c?.verified == 1 && !saved) Icon(Icons.Default.Verified, null, Modifier.padding(start = 4.dp).size(16.dp), tint = Accent)
                                }
                                if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    },
                    actions = {
                        IconButton({ search = "" }) { Icon(Icons.Default.Search, L("جستجو", "Search")) }
                        Box {
                            IconButton({ topMenu = true }) { Icon(Icons.Default.MoreVert, L("بیشتر", "More")) }
                            DropdownMenu(topMenu, { topMenu = false }) {
                                if (!saved) DropdownMenuItem({ Text(L("اطلاعات مخاطب", "Contact info")) }, { topMenu = false; Nav.go(Scr.Info(peer)) })
                                if (!saved) DropdownMenuItem({ Text(if (chat?.muted == true) L("روشن کردن اعلان", "Unmute") else L("بی‌صدا کردن", "Mute")) },
                                    { topMenu = false; scope.launch { chat?.let { acc.d.putChat(it.copy(muted = !it.muted)) } } })
                                if (!saved) DropdownMenuItem({ Text(L("حذف خودکار پیام‌ها", "Auto-delete messages")) }, { topMenu = false; ttlDialog = true })
                                DropdownMenuItem({ Text(L("تصویر پس‌زمینه", "Wallpaper")) }, { topMenu = false; wallDialog = true })
                                DropdownMenuItem({ Text(L("پاک کردن تاریخچه", "Clear history")) }, { topMenu = false; clearConfirm = true })
                                if (!saved) DropdownMenuItem({ Text(if (c?.blocked == true) L("رفع مسدودیت", "Unblock") else L("مسدود کردن", "Block")) },
                                    { topMenu = false; scope.launch { e.setBlocked(peer, c?.blocked != true) } })
                            }
                        }
                    },
                )
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(wallpaperColor(isDark))) {
            when {
                wp.startsWith("#") -> Box(Modifier.fillMaxSize().background(Color(android.graphics.Color.parseColor(wp))))
                wp.isNotEmpty() && File(wp).exists() -> AsyncImage(File(wp), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.fillMaxSize().imePadding()) {
                if (alert == peer) Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                    Text(
                        L("هشدار امنیتی: سرور کلیدی داد که با هویت این مخاطب نمی‌خواند. پیام‌ها ارسال نمی‌شوند.",
                            "Security warning: the server returned a key that doesn't match this contact. Messages are not sent."),
                        Modifier.weight(1f).padding(horizontal = 8.dp), fontSize = 13.sp,
                    )
                    IconButton({ e.securityAlert.value = null }) { Icon(Icons.Default.Close, null) }
                }
                pinned.firstOrNull()?.let { p ->
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                            .clickable { msgs.indexOfFirst { it.id == p.id }.takeIf { it >= 0 }?.let { scope.launch { list.scrollToItem(it) }; highlight = p.id } }
                            .padding(12.dp, 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.PushPin, null, Modifier.size(18.dp), tint = Accent)
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            Text(L("پیام سنجاق‌شده", "Pinned message"), color = Accent, fontSize = 13.sp)
                            Text(msgPreview(p), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                        }
                        IconButton({ scope.launch { e.pin(p, false) } }) { Icon(Icons.Default.Close, null, Modifier.size(18.dp)) }
                    }
                }
                if (scheduled.isNotEmpty()) Text(
                    "🕓 " + L("${scheduled.size} پیام زمان‌بندی‌شده", "${scheduled.size} scheduled"),
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable { showScheduled = true }.padding(12.dp, 8.dp),
                    color = Accent,
                )
                if (chat?.ttl ?: 0 > 0) Text(
                    "⏱ " + L("حذف خودکار: ", "Auto-delete: ") + Fmt.ttl(chat!!.ttl),
                    Modifier.fillMaxWidth().padding(4.dp), fontSize = 12.sp, color = Color.White,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )

                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, reverseLayout = true) {
                    itemsIndexed(msgs, key = { _, m -> m.id }) { i, m ->
                        Column {
                            if (i == msgs.lastIndex || Fmt.day(msgs[i + 1].ts) != Fmt.day(m.ts)) DayChip(Fmt.day(m.ts))
                            Bubble(
                                acc, m, c,
                                selected = m.id in selection,
                                highlight = m.id == highlight || (search != null && searchHits.getOrNull(searchPos) == i),
                                onClick = { if (selection.isNotEmpty()) selection = if (m.id in selection) selection - m.id else selection + m.id },
                                onLong = { if (selection.isNotEmpty()) selection = selection + m.id else if (m.type != "system") menuMsg = m },
                                onReplyClick = { id -> msgs.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { scope.launch { list.scrollToItem(it) }; highlight = id } },
                            )
                        }
                    }
                }

                // ---------------------------------------------------- input area
                Surface(tonalElevation = 2.dp) {
                    Column(Modifier.navigationBarsPadding()) {
                        (editing ?: replyTo)?.let { r ->
                            Row(Modifier.fillMaxWidth().padding(12.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (editing != null) Icons.Default.Edit else Icons.AutoMirrored.Filled.Reply, null, tint = Accent)
                                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                    Text(if (editing != null) L("ویرایش پیام", "Edit message") else if (r.out) L("پاسخ به خودتان", "Reply to yourself") else L("پاسخ به ", "Reply to ") + title, color = Accent, fontSize = 13.sp)
                                    Text(msgPreview(r), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                                }
                                IconButton({ if (editing != null) input = TextFieldValue(""); editing = null; replyTo = null }) { Icon(Icons.Default.Close, null) }
                            }
                        }
                        if (c?.blocked == true) {
                            TextButton({ scope.launch { e.setBlocked(peer, false) } }, Modifier.fillMaxWidth().padding(8.dp)) { Text(L("رفع مسدودیت", "Unblock")) }
                        } else if (recording) {
                            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Dot(Color.Red)
                                Text("  " + Fmt.dur(elapsed), Modifier.weight(1f))
                                TextButton({ recorder.stop(cancel = true); recording = false }) { Text(L("لغو", "Cancel"), color = MaterialTheme.colorScheme.error) }
                                IconButton({
                                    recording = false
                                    val res = recorder.stop()
                                    if (res != null) scope.launch(Dispatchers.IO) { e.sendMedia(peer, "voice", res.first, res.second, replyTo = replyTo?.id); replyTo = null }
                                }) { Icon(Icons.AutoMirrored.Filled.Send, L("ارسال", "Send"), tint = Accent) }
                            }
                        } else Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.Bottom) {
                            Box {
                                IconButton({ attachMenu = true }) { Icon(Icons.Default.AttachFile, L("پیوست", "Attach")) }
                                DropdownMenu(attachMenu, { attachMenu = false }) {
                                    DropdownMenuItem({ Text("🖼  " + L("عکس یا ویدیو", "Photo or video")) }, {
                                        attachMenu = false; pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                                    })
                                    if (!saved) DropdownMenuItem({ Text("🔥  " + L("عکس یک‌بار مصرف", "View-once photo")) }, {
                                        attachMenu = false; viewOnceNext = true; pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    })
                                    DropdownMenuItem({ Text("📎  " + L("فایل", "File")) }, { attachMenu = false; pickFile.launch(arrayOf("*/*")) })
                                    DropdownMenuItem({ Text("⏺  " + L("پیام ویدیویی", "Video message")) }, {
                                        attachMenu = false
                                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startNote()
                                        else camPerm.launch(Manifest.permission.CAMERA)
                                    })
                                    DropdownMenuItem({ Text("📍  " + L("موقعیت مکانی", "Location")) }, {
                                        attachMenu = false
                                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) sendLocation(ctx, acc, peer)
                                        else locPerm.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                    })
                                    DropdownMenuItem({ Text("👤  " + L("مخاطب", "Contact")) }, { attachMenu = false; contactPick = true })
                                }
                            }
                            TextField(
                                input, { input = it; if (!saved && it.text.isNotEmpty()) e.typingNow(peer) },
                                Modifier.weight(1f).heightIn(max = 160.dp),
                                placeholder = { Text(L("پیام", "Message")) },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                                ),
                            )
                            IconButton({ stickers = true }) { Icon(Icons.Default.EmojiEmotions, L("استیکر", "Stickers")) }
                            if (input.text.isBlank()) {
                                IconButton({
                                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                        micPerm.launch(Manifest.permission.RECORD_AUDIO)
                                    } else runCatching {
                                        recorder.start(File(acc.mediaDir, Ids.newId() + ".m4a")); recording = true
                                    }.onFailure { toast(ctx, L("میکروفون در دسترس نیست", "Microphone unavailable")) }
                                }) { Icon(Icons.Default.Mic, L("ضبط صدا", "Record voice")) }
                            } else {
                                Box(
                                    Modifier.size(48.dp).combinedClickable(onLongClick = { if (editing == null) sendOptions = true }) { send() },
                                    contentAlignment = Alignment.Center,
                                ) { Icon(if (editing != null) Icons.Default.Edit else Icons.AutoMirrored.Filled.Send, L("ارسال", "Send"), tint = Accent) }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- dialogs
    menuMsg?.let { m ->
        MessageMenu(acc, m, saved,
            dismiss = { menuMsg = null },
            onReply = { replyTo = m; editing = null },
            onEdit = { editing = m; replyTo = null; input = TextFieldValue(m.text, TextRange(m.text.length)) },
            onSelect = { selection = setOf(m.id) },
            onDelete = { deleting = listOf(m) },
        )
    }
    deleting?.let { ms ->
        var both by remember { mutableStateOf(false) }
        AlertDialog({ deleting = null },
            title = { Text(L("حذف ${ms.size} پیام؟", "Delete ${ms.size} message(s)?")) },
            text = {
                if (!saved) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { both = !both }) {
                    Checkbox(both, { both = it })
                    Text(L("حذف برای ", "Also delete for ") + title)
                }
            },
            confirmButton = { TextButton({ scope.launch { e.delete(peer, ms, both && !saved) }; deleting = null }) { Text(L("حذف", "Delete"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ deleting = null }) { Text(L("انصراف", "Cancel")) } })
    }
    if (stickers) StickerSheet(acc, dismiss = { stickers = false }) { s ->
        stickers = false
        scope.launch(Dispatchers.IO) {
            val src = File(s.path)
            if (!src.exists()) return@launch
            val f = File(acc.mediaDir, Ids.newId() + ".webp")
            src.copyTo(f)
            e.sendMedia(peer, "sticker", f, JSONObject().put("mime", "image/webp").put("name", "sticker.webp").put("w", 512).put("h", 512), replyTo = replyTo?.id)
            replyTo = null
        }
    }
    if (contactPick) {
        val contacts by acc.d.contactsFlow().collectAsState(emptyList())
        AlertDialog({ contactPick = false }, confirmButton = {}, title = { Text(L("ارسال مخاطب", "Share contact")) }, text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                itemsIndexed(contacts.filter { it.user != peer }) { _, cc ->
                    Row(Modifier.fillMaxWidth().clickable { contactPick = false; scope.launch { e.sendContactCard(peer, cc) } }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(acc, cc, 36.dp); Spacer(Modifier.width(8.dp)); Text(cc.display)
                    }
                }
            }
        })
    }
    if (sendOptions) AlertDialog({ sendOptions = false }, confirmButton = {}, text = {
        Column {
            MenuLine("🔕  " + L("ارسال بدون صدا", "Send without sound")) { sendOptions = false; send(silent = true) }
            MenuLine("🕓  " + L("زمان‌بندی ارسال", "Schedule message")) {
                sendOptions = false
                pickDateTime(ctx) { at -> send(at = at) }
            }
        }
    })
    if (showScheduled) AlertDialog({ showScheduled = false }, confirmButton = { TextButton({ showScheduled = false }) { Text(L("بستن", "Close")) } },
        title = { Text(L("پیام‌های زمان‌بندی‌شده", "Scheduled messages")) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                scheduled.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(Fmt.day(m.scheduledAt) + " " + Fmt.time(m.scheduledAt), fontSize = 12.sp, color = Accent)
                            Text(msgPreview(m), maxLines = 2)
                        }
                        TextButton({ scope.launch { acc.d.putMsg(m.copy(scheduledAt = System.currentTimeMillis())); e.tick() } }) { Text(L("الان", "Now")) }
                        IconButton({ scope.launch { e.delete(peer, listOf(m), false) } }) { Icon(Icons.Default.Delete, null) }
                    }
                    HorizontalDivider()
                }
            }
        })
    if (ttlDialog) AlertDialog({ ttlDialog = false }, confirmButton = {}, title = { Text(L("حذف خودکار پیام‌ها", "Auto-delete messages")) }, text = {
        Column {
            listOf(0, 86400, 7 * 86400, 30, 300, 3600).forEach { s ->
                MenuLine((if (chat?.ttl == s) "● " else "○ ") + Fmt.ttl(s)) { ttlDialog = false; scope.launch { e.setTtl(peer, s.coerceAtMost(7 * 86400)) } }
            }
            Text(L("پیام‌های جدید پس از این مدت از هر دو گوشی پاک می‌شوند.", "New messages disappear from both phones after this time."), fontSize = 12.sp)
        }
    })
    if (wallDialog) WallpaperDialog(acc, { wallDialog = false }) { v -> scope.launch { chat?.let { acc.d.putChat(it.copy(wallpaper = v)) } } }
    if (clearConfirm) AlertDialog({ clearConfirm = false },
        title = { Text(L("پاک کردن تاریخچه؟", "Clear history?")) },
        text = { Text(L("پیام‌ها فقط از این گوشی پاک می‌شوند.", "Messages are removed from this phone only.")) },
        confirmButton = { TextButton({ clearConfirm = false; scope.launch { e.clearHistory(peer) } }) { Text(L("پاک کن", "Clear"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ clearConfirm = false }) { Text(L("انصراف", "Cancel")) } })
}

@Composable
private fun DayChip(day: String) = Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
    Text(day, Modifier.background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(12.dp)).padding(10.dp, 3.dp), color = Color.White, fontSize = 13.sp)
}

private val QUICK = listOf("❤️", "👍", "👎", "🔥", "🥰", "👏", "😁", "😮", "😢", "🙏")

@Composable
private fun MessageMenu(acc: Acc, m: Msg, saved: Boolean, dismiss: () -> Unit, onReply: () -> Unit, onEdit: () -> Unit, onSelect: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val e = acc.engine
    AlertDialog(dismiss, confirmButton = {}, text = {
        Column {
            if (!saved) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                QUICK.take(6).forEach { r -> Text(r, Modifier.clickable { scope.launch { e.react(m, r) }; dismiss() }.padding(4.dp), fontSize = 24.sp) }
            }
            if (!saved) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                QUICK.drop(6).forEach { r -> Text(r, Modifier.clickable { scope.launch { e.react(m, r) }; dismiss() }.padding(4.dp), fontSize = 24.sp) }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (m.status == -1) MenuLine("🔁  " + L("تلاش دوباره", "Retry")) { scope.launch(Dispatchers.IO) { e.retry(m) }; dismiss() }
            MenuLine("↩  " + L("پاسخ", "Reply")) { onReply(); dismiss() }
            if (m.text.isNotEmpty()) MenuLine("📋  " + L("کپی", "Copy")) { copy(ctx, m.text); dismiss() }
            MenuLine("↪  " + L("هدایت", "Forward")) { dismiss(); Nav.go(Scr.ForwardPick(m.peer, listOf(m.id))) }
            if (m.out && m.type in setOf("text", "photo", "video", "file") && m.status >= 0) MenuLine("✏  " + L("ویرایش", "Edit")) { onEdit(); dismiss() }
            MenuLine("📌  " + if (m.pinned) L("برداشتن سنجاق", "Unpin") else L("سنجاق", "Pin")) { scope.launch { e.pin(m, !m.pinned) }; dismiss() }
            if (m.type == "sticker") m.media?.let { JSONObject(it).optString("path") }?.takeIf { it.isNotEmpty() }?.let { p ->
                MenuLine("⭐  " + L("افزودن به استیکرها", "Add to stickers")) {
                    scope.launch(Dispatchers.IO) {
                        val dir = File(acc.dir, "stickers").apply { mkdirs() }
                        val dst = File(dir, Ids.newId() + ".webp"); File(p).copyTo(dst)
                        acc.d.putSticker(Sticker(dst.path))
                    }
                    dismiss()
                }
            }
            MenuLine("☑  " + L("انتخاب", "Select")) { onSelect(); dismiss() }
            MenuLine("🗑  " + L("حذف", "Delete")) { onDelete(); dismiss() }
        }
    })
}

@Composable
private fun StickerSheet(acc: Acc, dismiss: () -> Unit, onPick: (Sticker) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list by acc.d.stickersFlow().collectAsState(emptyList())
    val add = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) { runCatching { acc.d.putSticker(Sticker(Media.makeSticker(acc, uri).path)) } }
    }
    AlertDialog(dismiss, confirmButton = {
        TextButton({ add.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("+ " + L("ساخت استیکر از عکس", "Make sticker from photo")) }
    }, title = { Text(L("استیکرها", "Stickers")) }, text = {
        if (list.isEmpty()) Text(L("هنوز استیکری ندارید. از عکس بسازید یا استیکرهای دریافتی را ذخیره کنید.\nبرای ایموجی از صفحه‌کلید استفاده کنید.",
            "No stickers yet. Make one from a photo or save received ones.\nUse the keyboard for emoji."))
        else LazyVerticalGrid(GridCells.Fixed(4), Modifier.heightIn(max = 360.dp)) {
            items(list, key = { it.path }) { s ->
                AsyncImage(File(s.path), null, Modifier.padding(4.dp).aspectRatio(1f).combinedClickableCompat(
                    onLong = { scope.launch { acc.d.deleteSticker(s.path); File(s.path).delete() }; toast(ctx, L("حذف شد", "Removed")) },
                ) { onPick(s) })
            }
        }
    })
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.combinedClickableCompat(onLong: () -> Unit, onClick: () -> Unit) = combinedClickable(onLongClick = onLong, onClick = onClick)

private val SWATCHES = listOf("", "#FF8AB4F8", "#FFB5E2C3", "#FFF8D7A9", "#FFE6B8D8", "#FF5D6D7E", "#FF1F2B38", "#FF3E2723")

@Composable
fun WallpaperDialog(acc: Acc, dismiss: () -> Unit, onSet: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val (f, _) = Media.preparePhoto(acc, uri)
            val dst = File(acc.dir, "wall_" + Ids.newId() + ".jpg"); f.renameTo(dst)
            withContext(Dispatchers.Main) { onSet(dst.path); dismiss() }
        }
    }
    AlertDialog(dismiss, confirmButton = {
        TextButton({ pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(L("انتخاب عکس", "Choose photo")) }
    }, title = { Text(L("تصویر پس‌زمینه", "Wallpaper")) }, text = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SWATCHES.forEach { s ->
                Box(Modifier.size(32.dp).background(if (s.isEmpty()) wallpaperColor(dark()) else Color(android.graphics.Color.parseColor(s)), CircleShape)
                    .clickable { onSet(s); dismiss() }, contentAlignment = Alignment.Center) { if (s.isEmpty()) Text("∅", fontSize = 12.sp) }
            }
        }
    })
}

fun copy(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("text", text))
    toast(ctx, L("کپی شد", "Copied"))
}

fun pickDateTime(ctx: Context, onPick: (Long) -> Unit) {
    val cal = Calendar.getInstance()
    DatePickerDialog(ctx, { _, y, mo, d ->
        TimePickerDialog(ctx, { _, h, mi ->
            cal.set(y, mo, d, h, mi, 0)
            if (cal.timeInMillis > System.currentTimeMillis()) onPick(cal.timeInMillis)
            else toast(ctx, L("زمان باید در آینده باشد", "Time must be in the future"))
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).apply {
        datePicker.minDate = System.currentTimeMillis() - 1000
    }.show()
}

@Suppress("DEPRECATION", "MissingPermission")
private fun sendLocation(ctx: Context, acc: Acc, peer: String) {
    val lm = ctx.getSystemService(LocationManager::class.java)
    val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER
    else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) LocationManager.NETWORK_PROVIDER
    else { toast(ctx, L("مکان‌یابی گوشی خاموش است", "Location is turned off")); return }
    toast(ctx, L("در حال یافتن موقعیت…", "Finding location…"))
    val done: (android.location.Location?) -> Unit = { loc ->
        val l = loc ?: lm.getLastKnownLocation(provider)
        if (l == null) toast(ctx, L("موقعیت پیدا نشد", "Location not found"))
        else acc.scope.launch { acc.engine.sendLocation(peer, l.latitude, l.longitude) }
    }
    if (Build.VERSION.SDK_INT >= 30) lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(ctx)) { done(it) }
    else lm.requestSingleUpdate(provider, { done(it) }, Looper.getMainLooper())
}

@Composable
fun ContactCard(acc: Acc, media: JSONObject) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    Column(Modifier.width(220.dp)) {
        Text("👤 " + media.optString("cname"), fontWeight = FontWeight.SemiBold)
        Text("@" + media.optString("user"), fontSize = 13.sp)
        TextButton({
            scope.launch {
                val fp = media.optString("fp").takeIf { it.length == 64 }
                val r = acc.engine.addContact(media.optString("user"), fp)
                if (r is ir.payam.messenger.core.Engine.Lookup.Ok) Nav.go(Scr.ChatS(media.optString("user")))
                else toast(ctx, L("افزودن ممکن نشد", "Couldn't add"))
            }
        }) { Text(L("ارسال پیام", "Message")) }
    }
}
