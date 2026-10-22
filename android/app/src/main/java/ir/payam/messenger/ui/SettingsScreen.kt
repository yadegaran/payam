package ir.payam.messenger.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as ASettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.payam.messenger.App
import ir.payam.messenger.BuildConfig
import ir.payam.messenger.core.Acc
import ir.payam.messenger.core.Fmt
import ir.payam.messenger.core.L
import ir.payam.messenger.core.Pw
import ir.payam.messenger.core.Settings
import ir.payam.messenger.core.b64
import ir.payam.messenger.core.hex
import ir.payam.messenger.core.sha256
import ir.payam.messenger.svc.SocketService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(acc: Acc, act: MainActivity) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Settings.rev.intValue
    acc.profileRev.value
    var pinDialog by remember { mutableStateOf(false) }
    var backupDialog by remember { mutableStateOf<Uri?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var wall by remember { mutableStateOf(false) }
    var storageRev by remember { mutableStateOf(0) }
    val used by produceState(0L, storageRev) { value = withContext(Dispatchers.IO) { acc.mediaDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } } }

    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> backupDialog = uri }

    @Composable
    fun Section(t: String) = Text(t, Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp), color = Accent, fontWeight = FontWeight.SemiBold)

    @Composable
    fun Toggle(title: String, sub: String? = null, v: Boolean, on: (Boolean) -> Unit) =
        Row(Modifier.fillMaxWidth().clickable { on(!v) }.padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title)
                sub?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Switch(v, on)
        }

    @Composable
    fun Item(title: String, sub: String? = null, onClick: () -> Unit) =
        Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp, 12.dp)) {
            Text(title)
            sub?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

    Scaffold(topBar = {
        TopAppBar(title = { Text(L("تنظیمات", "Settings")) }, navigationIcon = { IconButton({ Nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L("بازگشت", "Back")) } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Section(L("حریم خصوصی", "Privacy"))
            Toggle(L("پنهان کردن آخرین بازدید", "Hide last seen"), L("دیگران آنلاین بودن شما را نمی‌بینند و شما هم آن‌ها را نمی‌بینید", "Others can't see when you're online, and you can't see theirs"), acc.hideSeen) {
                acc.hideSeen = it; scope.launch(Dispatchers.IO) { acc.engine.publishProfile() }
            }
            Toggle(L("پنهان کردن عکس پروفایل", "Hide profile photo"), null, acc.hidePhoto) {
                acc.hidePhoto = it; scope.launch(Dispatchers.IO) { acc.engine.publishProfile() }
            }
            Toggle(L("پیش‌نمایش لینک‌ها", "Link previews"), L("برای ساخت پیش‌نمایش، گوشی شما سایت را باز می‌کند و IP شما را می‌بیند", "Your phone fetches the site, which sees your IP"), Settings.linkPreview) { Settings.linkPreview = it }
            Toggle(L("جلوگیری از اسکرین‌شات", "Block screenshots"), null, Settings.secureScreen) { Settings.secureScreen = it; act.applySecure() }

            Section(L("قفل برنامه", "App lock"))
            Item(if (AppLock.enabled()) L("تغییر یا حذف رمز", "Change or remove passcode") else L("تنظیم رمز برنامه", "Set passcode")) { pinDialog = true }
            if (AppLock.enabled()) {
                Toggle(L("باز کردن با اثر انگشت", "Unlock with fingerprint"), null, Settings.biometric) { Settings.biometric = it }
                Item(L("قفل خودکار", "Auto-lock"), when (Settings.lockAfterMin) { 0 -> L("فوری", "Immediately"); 60 -> L("بعد از ۱ ساعت", "After 1 hour"); else -> L("بعد از ${Settings.lockAfterMin} دقیقه", "After ${Settings.lockAfterMin} min") }) {
                    Settings.lockAfterMin = when (Settings.lockAfterMin) { 0 -> 1; 1 -> 5; 5 -> 60; else -> 0 }
                }
            }

            Section(L("ظاهر", "Appearance"))
            Item(L("پوسته", "Theme"), when (Settings.theme) { 1 -> L("روشن", "Light"); 2 -> L("تیره", "Dark"); else -> L("مطابق سیستم", "System") }) { Settings.theme = (Settings.theme + 1) % 3 }
            Item(L("تصویر پس‌زمینه گفتگوها", "Chat wallpaper")) { wall = true }
            Item(L("زبان", "Language"), if (Settings.lang == "en") "English" else "فارسی") { Settings.lang = if (Settings.lang == "en") "fa" else "en"; act.recreate() }

            Section(L("اعلان‌ها", "Notifications"))
            Toggle(L("نمایش متن پیام در اعلان", "Show message text in notifications"), L("خاموش: فقط نام فرستنده", "Off: sender name only"), Settings.notifPreview) { Settings.notifPreview = it }
            Item(L("اجرای پس‌زمینه بدون محدودیت باتری", "Allow background without battery limits"), L("برای دریافت پیام وقتی برنامه بسته است", "Needed to receive messages while closed")) {
                runCatching { ctx.startActivity(Intent(ASettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))) }
            }
            Item(L("تنظیمات اعلان سیستم", "System notification settings")) {
                ctx.startActivity(Intent(ASettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(ASettings.EXTRA_APP_PACKAGE, ctx.packageName))
            }

            Section(L("داده و حافظه", "Data & storage"))
            Text(L("دانلود خودکار (Wi-Fi / اینترنت همراه)", "Auto-download (Wi-Fi / mobile)"), Modifier.padding(16.dp, 4.dp), fontSize = 13.sp)
            listOf("photo" to L("عکس", "Photos"), "voice" to L("صدا و پیام ویدیویی", "Voice & video messages"), "video" to L("ویدیو", "Videos"), "file" to L("فایل", "Files")).forEach { (k, label) ->
                Row(Modifier.fillMaxWidth().padding(16.dp, 0.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f))
                    Switch(Settings.autoDl(k, true), { Settings.setAutoDl(k, true, it) })
                    Text(" Wi-Fi  ", fontSize = 11.sp)
                    Switch(Settings.autoDl(k, false), { Settings.setAutoDl(k, false, it) })
                    Text(" " + L("همراه", "Mobile"), fontSize = 11.sp)
                }
            }
            Item(L("پاک کردن رسانه‌ها از حافظه", "Clear media from storage"), L("فضای استفاده‌شده: ", "Used: ") + Fmt.size(used)) { confirm = "clear" }

            Section(L("حساب", "Account"))
            Item(L("پشتیبان‌گیری از حساب", "Back up account"), L("بدون پشتیبان، با پاک شدن برنامه حساب شما از دست می‌رود", "Without a backup, uninstalling loses your account")) {
                exportBackup.launch("payam-${acc.user}.backup")
            }
            Item(L("خروج از حساب در این گوشی", "Log out on this phone")) { confirm = "logout" }
            Item(L("حذف کامل حساب", "Delete account")) { confirm = "delete" }

            Section(L("درباره", "About"))
            Text(L("نسخه ", "Version ") + BuildConfig.VERSION_NAME, Modifier.padding(16.dp, 4.dp))
            if (ir.payam.messenger.core.Updater.enabled) {
                Toggle(L("بررسی خودکار نسخه جدید", "Check for updates automatically"),
                    L("روزی یک بار از GitHub؛ فقط IP شما را می‌بیند", "Once a day from GitHub; it only sees your IP"), Settings.autoUpdate) { Settings.autoUpdate = it }
                Item(L("بررسی نسخه جدید", "Check for updates")) {
                    scope.launch {
                        if (ir.payam.messenger.core.Updater.check(force = true) == null) toast(ctx, L("آخرین نسخه را دارید", "You have the latest version"))
                    }
                }
                UpdateCard(act, inSettings = true)
            }
            Text(L("اثر انگشت امضای برنامه (برای اطمینان از اصل بودن APK):", "App signing fingerprint (verify your APK is genuine):"), Modifier.padding(16.dp, 4.dp), fontSize = 13.sp)
            Text(apkFingerprint(ctx), Modifier.padding(16.dp, 0.dp, 16.dp, 24.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }

    if (wall) WallpaperDialog(acc, { wall = false }) { Settings.wallpaper = it }

    if (pinDialog) {
        var a by remember { mutableStateOf("") }
        var b by remember { mutableStateOf("") }
        AlertDialog({ pinDialog = false }, title = { Text(L("رمز برنامه (۴ تا ۱۲ رقم)", "Passcode (4–12 digits)")) }, text = {
            Column {
                OutlinedTextField(a, { a = it.filter(Char::isDigit).take(12) }, singleLine = true, label = { Text(L("رمز جدید", "New passcode")) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                OutlinedTextField(b, { b = it.filter(Char::isDigit).take(12) }, singleLine = true, label = { Text(L("تکرار رمز", "Repeat")) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            }
        }, confirmButton = {
            TextButton({ AppLock.set(a); pinDialog = false; toast(ctx, L("رمز تنظیم شد", "Passcode set")) }, enabled = a.length >= 4 && a == b) { Text(L("ذخیره", "Save")) }
        }, dismissButton = {
            if (AppLock.enabled()) TextButton({ AppLock.set(null); Settings.biometric = false; pinDialog = false }) { Text(L("حذف رمز", "Remove"), color = MaterialTheme.colorScheme.error) }
        })
    }

    backupDialog?.let { uri ->
        var p1 by remember { mutableStateOf("") }
        var p2 by remember { mutableStateOf("") }
        var working by remember { mutableStateOf(false) }
        AlertDialog({ if (!working) backupDialog = null }, title = { Text(L("رمز فایل پشتیبان", "Backup password")) }, text = {
            Column {
                Text(L("این رمز را فراموش نکنید؛ بدون آن پشتیبان قابل بازیابی نیست.", "Don't forget it — the backup can't be restored without it."), fontSize = 13.sp)
                OutlinedTextField(p1, { p1 = it }, singleLine = true, label = { Text(L("رمز (حداقل ۸ حرف)", "Password (min 8)")) }, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(p2, { p2 = it }, singleLine = true, label = { Text(L("تکرار رمز", "Repeat")) }, visualTransformation = PasswordVisualTransformation())
                if (working) CircularProgressIndicator(Modifier.padding(top = 8.dp))
            }
        }, confirmButton = {
            TextButton({
                working = true
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            val contacts = acc.d.contacts().map { it.user }.filter { it != acc.user }
                            val json = JSONObject().put("v", 1).put("server", acc.server).put("pk", b64(acc.pickleKey)).put("account", acc.account)
                                .put("name", acc.name).put("bio", acc.bio).put("contacts", JSONArray(contacts))
                            val blob = Pw.encrypt(p1.toCharArray(), json.toString().toByteArray())
                            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(blob) }
                        }.isSuccess
                    }
                    working = false
                    backupDialog = null
                    toast(ctx, if (ok) L("پشتیبان ذخیره شد", "Backup saved") else L("خطا در ذخیره پشتیبان", "Backup failed"))
                }
            }, enabled = !working && p1.length >= 8 && p1 == p2) { Text(L("ذخیره", "Save")) }
        })
    }

    confirm?.let { what ->
        AlertDialog({ confirm = null }, title = {
            Text(when (what) {
                "clear" -> L("رسانه‌ها پاک شوند؟", "Clear media?")
                "logout" -> L("خروج از حساب؟", "Log out?")
                else -> L("حذف کامل حساب؟", "Delete account?")
            })
        }, text = {
            Text(when (what) {
                "clear" -> L("عکس‌ها، ویدیوها و فایل‌های دریافتی از گوشی پاک می‌شوند و دوباره قابل دانلود نیستند (سرور چیزی نگه نمی‌دارد).",
                    "Received photos, videos and files are deleted and can't be downloaded again (the server keeps nothing).")
                "logout" -> L("همه گفتگوها از این گوشی پاک می‌شوند. اگر پشتیبان ندارید، حساب برای همیشه از دست می‌رود.",
                    "All chats are deleted from this phone. Without a backup, the account is lost forever.")
                else -> L("حساب و پروفایل از سرور و همه داده‌ها از این گوشی پاک می‌شوند. این کار برگشت‌پذیر نیست.",
                    "Your account and profile are removed from the server and all data from this phone. This can't be undone.")
            })
        }, confirmButton = {
            TextButton({
                confirm = null
                uiScope.launch { // logout can remove the last account, which disposes this screen
                    when (what) {
                        "clear" -> withContext(Dispatchers.IO) {
                            for (m in acc.d.withFiles()) {
                                val j = JSONObject(m.media!!)
                                File(j.optString("path")).delete()
                                j.remove("path"); j.put("gone", true)
                                acc.d.putMsg(m.copy(media = j.toString()))
                            }
                            storageRev++
                        }
                        else -> {
                            if (what == "delete") { acc.engine.deleteAccountOnServer(); delay(800) }
                            withContext(Dispatchers.IO) { App.I.accounts.remove(acc.user) }
                            if (App.I.accounts.list.value.isEmpty()) ctx.stopService(Intent(ctx, SocketService::class.java))
                            Nav.reset()
                        }
                    }
                }
            }) { Text(L("تأیید", "Confirm"), color = MaterialTheme.colorScheme.error) }
        }, dismissButton = { TextButton({ confirm = null }) { Text(L("انصراف", "Cancel")) } })
    }
}

@Suppress("DEPRECATION")
private fun apkFingerprint(ctx: android.content.Context): String = runCatching {
    val sig = if (Build.VERSION.SDK_INT >= 28) {
        ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!.apkContentsSigners.first()
    } else ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures!!.first()
    hex(sha256(sig.toByteArray())).uppercase().chunked(2).joinToString(":")
}.getOrDefault("?")
