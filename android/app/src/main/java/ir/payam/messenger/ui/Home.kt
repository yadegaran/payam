package ir.payam.messenger.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as ASettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import ir.payam.messenger.App
import ir.payam.messenger.core.Acc
import ir.payam.messenger.core.Engine
import ir.payam.messenger.core.Fmt
import ir.payam.messenger.core.L
import ir.payam.messenger.core.Settings
import ir.payam.messenger.data.Chat
import ir.payam.messenger.data.Contact
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(acc: Acc, archive: Boolean, act: MainActivity) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val chats by acc.d.chatsFlow().collectAsState(emptyList())
    val contacts by acc.d.allContactsFlow().collectAsState(emptyList())
    val byUser = remember(contacts) { contacts.associateBy { it.user } }
    val state by acc.conn.state.collectAsState()
    val kicked by acc.conn.kicked.collectAsState()
    val connErr by acc.conn.lastError.collectAsState()
    var folder by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<Chat?>(null) }
    var newChat by remember { mutableStateOf(false) }
    val invite = pendingInvite.value
    LaunchedEffect(invite) { if (invite != null) newChat = true }

    LaunchedEffect(Unit) { ir.payam.messenger.core.Updater.check() }

    LaunchedEffect(Unit) {
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (!Settings.batteryAsked && !pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            Settings.batteryAsked = true
            runCatching {
                ctx.startActivity(Intent(ASettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)))
            }
        }
    }

    val visible = chats.filter { it.archived == archive && (it.peer != acc.user || it.lastTs > 0) }
    val folders = chats.mapNotNull { it.folder }.distinct().sorted()
    val shown = when (folder) {
        null -> visible
        "\u0000unread" -> visible.filter { it.unread > 0 }
        else -> visible.filter { it.folder == folder }
    }

    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = !archive, drawerContent = { Drawer(acc) { scope.launch { drawer.close() } } }) {
        Scaffold(
            topBar = {
                if (search != null) {
                    TopAppBar(
                        navigationIcon = { IconButton({ search = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L("بازگشت", "Back")) } },
                        title = {
                            TextField(
                                search!!, { search = it }, Modifier.fillMaxWidth(), singleLine = true,
                                placeholder = { Text(L("جستجو", "Search")) },
                                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                            )
                        },
                    )
                } else TopAppBar(
                    navigationIcon = {
                        if (archive) IconButton({ Nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L("بازگشت", "Back")) }
                        else IconButton({ scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, L("منو", "Menu")) }
                    },
                    title = {
                        Text(
                            when {
                                archive -> L("بایگانی", "Archive")
                                kicked -> L("این حساب روی گوشی دیگری باز شد", "Logged in on another phone")
                                connErr == "pin" -> L("⚠ کلید سرور تغییر کرده!", "⚠ Server key changed!")
                                state == 2 -> L("پیام", "Payam")
                                state == 1 -> L("در حال اتصال…", "Connecting…")
                                else -> L("در انتظار شبکه…", "Waiting for network…")
                            },
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    },
                    actions = { IconButton({ search = "" }) { Icon(Icons.Default.Search, L("جستجو", "Search")) } },
                )
            },
            floatingActionButton = {
                if (!archive && search == null) FloatingActionButton({ newChat = true }) { Icon(Icons.Default.Edit, L("گفتگوی جدید", "New chat")) }
            },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                if (search != null) {
                    SearchResults(acc, search!!, byUser)
                    return@Column
                }
                if (!archive && (folders.isNotEmpty() || chats.any { it.unread > 0 })) {
                    val tabs = listOf<String?>(null, "\u0000unread") + folders
                    ScrollableTabRow(selectedTabIndex = tabs.indexOf(folder).coerceAtLeast(0), edgePadding = 8.dp) {
                        tabs.forEach { f ->
                            Tab(folder == f, { folder = f }, text = {
                                Text(when (f) { null -> L("همه", "All"); "\u0000unread" -> L("خوانده‌نشده", "Unread"); else -> f })
                            })
                        }
                    }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    if (!archive) item { UpdateCard(act) }
                    if (!archive && folder == null && chats.any { it.archived }) item {
                        Row(Modifier.fillMaxWidth().clickable { Nav.go(Scr.Archive) }.padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Archive, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(20.dp))
                            Text(L("بایگانی", "Archived chats"), fontWeight = FontWeight.Medium)
                        }
                    }
                    if (shown.isEmpty()) item {
                        Text(
                            L("هنوز گفتگویی ندارید. با دکمه ✎ نام کاربری یا لینک دوستتان را وارد کنید.", "No chats yet. Tap ✎ and enter a friend's username or link."),
                            Modifier.padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(shown, key = { it.peer }) { c -> ChatRow(acc, c, byUser[c.peer], onLong = { menuFor = c }) }
                }
            }
        }
    }

    menuFor?.let { c -> ChatMenu(acc, c) { menuFor = null } }
    if (newChat) NewChatDialog(acc, pendingInvite.value) { newChat = false; pendingInvite.value = null }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(acc: Acc, chat: Chat, c: Contact?, onLong: () -> Unit) {
    val last by remember(chat.peer) { acc.d.lastMsgFlow(chat.peer) }.collectAsState(null)
    val typing by acc.engine.typing.collectAsState()
    Row(
        Modifier.fillMaxWidth().combinedClickable(onLongClick = onLong) { Nav.go(Scr.ChatS(chat.peer)) }.padding(12.dp, 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(acc, c, 54.dp, chat.peer, saved = chat.peer == acc.user)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(chatTitle(acc, chat.peer, c), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                if (c?.blocked == true) Text(" ⛔", fontSize = 12.sp)
                if (chat.muted) Icon(Icons.Default.NotificationsOff, null, Modifier.padding(start = 4.dp).size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                last?.let { m ->
                    if (m.out && m.type != "system") Icon(
                        if (m.status >= 2) Icons.Default.DoneAll else Icons.Default.Done, null, Modifier.size(16.dp),
                        tint = if (m.status == 3) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(" " + Fmt.listTime(m.ts), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val isTyping = typing[chat.peer]?.let { System.currentTimeMillis() - it < 6000 } == true
                when {
                    isTyping -> Text(L("در حال نوشتن…", "typing…"), color = Accent, modifier = Modifier.weight(1f))
                    chat.draft.isNotBlank() -> Text(
                        L("پیش‌نویس: ", "Draft: ") + chat.draft, color = MaterialTheme.colorScheme.error,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    else -> Text(msgPreview(last), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
                if (chat.pinned) Icon(Icons.Default.PushPin, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (chat.unread > 0) Badge(containerColor = if (chat.muted) Color.Gray else Accent) { Text("${chat.unread}", color = Color.White) }
            }
        }
    }
}

@Composable
private fun Drawer(acc: Acc, close: () -> Unit) {
    val ctx = LocalContext.current
    val list by App.I.accounts.list.collectAsState()
    val me by acc.d.contactFlow(acc.user).collectAsState(null)
    acc.profileRev.collectAsState().value
    ModalDrawerSheet {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).padding(16.dp)) {
            Avatar(acc, me, 64.dp, acc.user)
            Spacer(Modifier.height(8.dp))
            Text(acc.name.ifEmpty { L("بدون نام", "No name") }, color = Color.White, fontWeight = FontWeight.Bold)
            Text("@" + acc.user, color = Color.White.copy(alpha = 0.8f))
        }
        list.filter { it != acc.user }.forEach { u ->
            val other = App.I.accounts.get(u)
            NavigationDrawerItem(
                label = { Text(other.name.ifEmpty { u } + "  @$u") }, selected = false,
                icon = { Icon(Icons.Default.Person, null) },
                onClick = { App.I.accounts.setActive(u); Nav.reset(); close() },
            )
        }
        if (list.size < 3) NavigationDrawerItem(label = { Text(L("افزودن حساب", "Add account")) }, selected = false,
            icon = { Icon(Icons.Default.Add, null) }, onClick = { close(); Nav.go(Scr.Onboard) })
        HorizontalDivider()
        NavigationDrawerItem(label = { Text(L("پروفایل من", "My profile")) }, selected = false, icon = { Icon(Icons.Default.Person, null) },
            onClick = { close(); Nav.go(Scr.Me) })
        NavigationDrawerItem(label = { Text(L("مخاطبین", "Contacts")) }, selected = false, icon = { Icon(Icons.Default.PersonAdd, L("افزودن مخاطب", "Add contact")) },
            onClick = { close(); Nav.go(Scr.Contacts) })
        NavigationDrawerItem(label = { Text(L("پیام‌های ذخیره‌شده", "Saved Messages")) }, selected = false, icon = { Icon(Icons.Default.Bookmark, null) },
            onClick = { close(); Nav.go(Scr.ChatS(acc.user)) })
        NavigationDrawerItem(label = { Text(L("تنظیمات", "Settings")) }, selected = false, icon = { Icon(Icons.Default.Settings, null) },
            onClick = { close(); Nav.go(Scr.SettingsS) })
        NavigationDrawerItem(label = { Text(L("دعوت دوستان", "Invite friends")) }, selected = false, icon = { Icon(Icons.Default.Share, null) },
            onClick = { close(); share(ctx, L("با من در پیام‌رسان امن گفتگو کن: ", "Chat with me securely: ") + acc.inviteLink) })
    }
}

@Composable
private fun SearchResults(acc: Acc, q: String, byUser: Map<String, Contact>) {
    val msgs by remember(q) { if (q.length < 2) flowOf(emptyList()) else acc.d.search(q) }.collectAsState(emptyList())
    val people = byUser.values.filter { q.isNotEmpty() && (it.display.contains(q, true) || it.user.contains(q.lowercase())) }
    LazyColumn(Modifier.fillMaxSize()) {
        items(people, key = { "c" + it.user }) { c ->
            Row(Modifier.fillMaxWidth().clickable { Nav.go(Scr.ChatS(c.user)) }.padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(acc, c, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column { Text(chatTitle(acc, c.user, c), fontWeight = FontWeight.SemiBold); Text("@" + c.user, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        items(msgs, key = { "m" + it.peer + it.id }) { m ->
            Column(Modifier.fillMaxWidth().clickable { Nav.go(Scr.ChatS(m.peer, m.id)) }.padding(16.dp, 8.dp)) {
                Text(chatTitle(acc, m.peer, byUser[m.peer]) + " · " + Fmt.day(m.ts), fontSize = 13.sp, color = Accent)
                Text(m.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ChatMenu(acc: Acc, c: Chat, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var folderEdit by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    fun upd(f: (Chat) -> Chat) = scope.launch { acc.d.putChat(f(c)); dismiss() }
    when {
        folderEdit -> {
            var name by remember { mutableStateOf(c.folder ?: "") }
            AlertDialog(dismiss, title = { Text(L("پوشه", "Folder")) },
                text = { OutlinedTextField(name, { name = it.take(24) }, singleLine = true, placeholder = { Text(L("مثلاً کار", "e.g. Work")) }) },
                confirmButton = { TextButton({ upd { it.copy(folder = name.trim().ifEmpty { null }) } }) { Text(L("ذخیره", "Save")) } },
                dismissButton = { TextButton({ upd { it.copy(folder = null) } }) { Text(L("حذف از پوشه", "Remove")) } })
        }
        confirmDelete -> AlertDialog(dismiss, title = { Text(L("حذف گفتگو؟", "Delete chat?")) },
            text = { Text(L("پیام‌ها فقط از گوشی شما پاک می‌شوند.", "Messages are deleted from this phone only.")) },
            confirmButton = { TextButton({ scope.launch { acc.engine.deleteChat(c.peer); dismiss() } }) { Text(L("حذف", "Delete"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(dismiss) { Text(L("انصراف", "Cancel")) } })
        else -> AlertDialog(dismiss, confirmButton = {}, text = {
            Column {
                MenuLine(if (c.pinned) L("برداشتن سنجاق", "Unpin") else L("سنجاق کردن", "Pin")) { upd { it.copy(pinned = !it.pinned) } }
                MenuLine(if (c.archived) L("خارج کردن از بایگانی", "Unarchive") else L("بایگانی", "Archive")) { upd { it.copy(archived = !it.archived) } }
                MenuLine(if (c.muted) L("روشن کردن اعلان", "Unmute") else L("بی‌صدا کردن", "Mute")) { upd { it.copy(muted = !it.muted) } }
                MenuLine(L("انتقال به پوشه", "Move to folder")) { folderEdit = true }
                if (c.unread > 0) MenuLine(L("علامت خوانده‌شده", "Mark as read")) { scope.launch { acc.engine.markRead(c.peer); dismiss() } }
                MenuLine(L("حذف گفتگو", "Delete chat")) { confirmDelete = true }
            }
        })
    }
}

@Composable
fun MenuLine(text: String, onClick: () -> Unit) =
    Text(text, Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), fontSize = 16.sp)

@Composable
fun lookupMessage(r: Engine.Lookup?): String? = when (r) {
    null, is Engine.Lookup.Ok -> null
    Engine.Lookup.Offline -> L("اتصال به سرور برقرار نیست", "Not connected to the server")
    Engine.Lookup.NoUser -> L("کاربری با این نام پیدا نشد", "No such user")
    Engine.Lookup.Mismatch -> L(
        "هشدار امنیتی: کلیدی که سرور داد با این نام کاربری/لینک نمی‌خواند. ممکن است کسی در میانه ارتباط باشد. گفتگو باز نشد.",
        "Security warning: the server's key does not match this username/link. Someone may be intercepting. Chat not opened.",
    )
}

@Composable
fun NewChatDialog(acc: Acc, initial: String?, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(initial ?: "") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Engine.Lookup?>(null) }
    var otherServer by remember { mutableStateOf<String?>(null) }
    fun go(s: String) {
        val inv = parseInvite(s) ?: run { result = Engine.Lookup.NoUser; return }
        if (acc.isOtherServer(inv)) { otherServer = inv.host; return }
        otherServer = null
        busy = true
        scope.launch {
            val r = acc.engine.addContact(inv.user, inv.fp)
            busy = false
            if (r is Engine.Lookup.Ok) { dismiss(); Nav.go(Scr.ChatS(inv.user)) } else result = r
        }
    }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { text = it; go(it) } }
    LaunchedEffect(initial) { if (initial != null) go(initial) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(L("گفتگوی جدید", "New chat")) },
        text = {
            Column {
                OutlinedTextField(text, { text = it; result = null }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(L("نام کاربری یا لینک دعوت", "Username or invite link")) },
                    trailingIcon = {
                        IconButton({ scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setOrientationLocked(false).setPrompt("")) }) {
                            Icon(Icons.Default.QrCodeScanner, L("اسکن QR", "Scan QR"))
                        }
                    })
                otherServer?.let { host ->
                    Text(
                        L("این کاربر روی سرور دیگری ($host) است. برای گفتگو با او، از منو «افزودن حساب» را بزنید و روی همان سرور حساب بسازید.",
                            "This user is on another server ($host). To chat with them, add an account on that server (Menu → Add account)."),
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp),
                    )
                }
                lookupMessage(result)?.let {
                    Row(Modifier.padding(top = 8.dp)) {
                        if (result == Engine.Lookup.Mismatch) Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
                Text(
                    L("لینک یا QR امن‌تر است: کلید را کامل تأیید می‌کند.", "A link or QR is safer: it verifies the full key."),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )
                if (busy) CircularProgressIndicator(Modifier.padding(top = 8.dp).size(24.dp))
            }
        },
        confirmButton = { TextButton({ go(text) }, enabled = !busy && text.isNotBlank()) { Text(L("شروع گفتگو", "Start chat")) } },
        dismissButton = { TextButton(dismiss) { Text(L("انصراف", "Cancel")) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(acc: Acc) {
    val list by acc.d.contactsFlow().collectAsState(emptyList())
    var newChat by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(L("مخاطبین", "Contacts")) },
                navigationIcon = { IconButton({ Nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L("بازگشت", "Back")) } },
                actions = { IconButton({ newChat = true }) { Icon(Icons.Default.PersonAdd, L("افزودن مخاطب", "Add contact")) } })
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            items(list, key = { it.user }) { c ->
                Row(Modifier.fillMaxWidth().clickable { Nav.go(Scr.ChatS(c.user)) }.padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(acc, c, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.display + if (c.verified == 1) " ✅" else "", fontWeight = FontWeight.SemiBold)
                        Text("@" + c.user, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (newChat) NewChatDialog(acc, null) { newChat = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardScreen(acc: Acc, from: String, ids: List<String>) {
    val scope = rememberCoroutineScope()
    val chats by acc.d.chatsFlow().collectAsState(emptyList())
    val contacts by acc.d.allContactsFlow().collectAsState(emptyList())
    val byUser = contacts.associateBy { it.user }
    val targets = (listOf(acc.user) + chats.map { it.peer } + contacts.filter { it.isContact }.map { it.user }).distinct()
    Scaffold(topBar = {
        TopAppBar(title = { Text(L("ارسال به…", "Forward to…")) },
            navigationIcon = { IconButton({ Nav.back() }) { Icon(Icons.Default.Close, null) } })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            items(targets, key = { it }) { peer ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        scope.launch {
                            val msgs = ids.mapNotNull { acc.d.msg(from, it) }
                            acc.engine.forward(msgs, peer)
                            Nav.back()
                            Nav.back()
                            Nav.go(Scr.ChatS(peer))
                        }
                    }.padding(12.dp, 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(acc, byUser[peer], 44.dp, peer, saved = peer == acc.user)
                    Spacer(Modifier.width(12.dp))
                    Text(chatTitle(acc, peer, byUser[peer]), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun Dot(color: Color) = Box(Modifier.size(8.dp).background(color, CircleShape))

@Composable
fun Centered(content: @Composable () -> Unit) =
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }

@Composable
fun RowSpaced(content: @Composable () -> Unit) =
    Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) { content() }
