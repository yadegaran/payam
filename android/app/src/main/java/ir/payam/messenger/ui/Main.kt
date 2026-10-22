package ir.payam.messenger.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import ir.payam.messenger.App
import ir.payam.messenger.BuildConfig
import ir.payam.messenger.core.L
import ir.payam.messenger.core.Pw
import ir.payam.messenger.core.Settings
import ir.payam.messenger.core.hex
import ir.payam.messenger.core.randomBytes
import ir.payam.messenger.core.b64
import ir.payam.messenger.core.unb64
import ir.payam.messenger.svc.SocketService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

object AppLock {
    val locked = mutableStateOf(false)
    var leftAt = 0L

    fun enabled() = Settings.pinHash.isNotEmpty()
    fun check(pin: String) = enabled() && hex(Pw.derive(pin.toCharArray(), unb64(Settings.pinSalt), 120_000)) == Settings.pinHash

    fun set(pin: String?) {
        if (pin.isNullOrEmpty()) { Settings.pinHash = ""; Settings.pinSalt = ""; return }
        val salt = randomBytes(16)
        Settings.pinSalt = b64(salt)
        Settings.pinHash = hex(Pw.derive(pin.toCharArray(), salt, 120_000))
    }

    fun onStart() {
        if (enabled() && leftAt > 0 && System.currentTimeMillis() - leftAt >= Settings.lockAfterMin * 60_000L) locked.value = true
    }
}

/** For work that must outlive the screen that started it (e.g. sign-up removes the Onboard screen mid-flight). */
val uiScope = MainScope()

/** Pending invite opened from a link, consumed by the home screen's "new chat" dialog. */
val pendingInvite = mutableStateOf<String?>(null)

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applySecure()
        if (savedInstanceState == null) AppLock.locked.value = AppLock.enabled()
        if (App.I.accounts.list.value.isNotEmpty()) SocketService.start(this)
        handle(intent)
        setContent { PayamTheme { Surface(Modifier.fillMaxSize()) { Root(this) } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() { super.onStart(); AppLock.onStart() }
    override fun onStop() { super.onStop(); AppLock.leftAt = System.currentTimeMillis() }

    fun applySecure() {
        if (Settings.secureScreen) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun handle(i: Intent?) {
        i ?: return
        val acc = i.getStringExtra("acc")
        val peer = i.getStringExtra("peer")
        if (acc != null && peer != null && acc in App.I.accounts.list.value) {
            App.I.accounts.setActive(acc)
            Nav.reset()
            Nav.go(Scr.ChatS(peer))
        }
        if (i.action == Intent.ACTION_VIEW) i.dataString?.let { pendingInvite.value = it; Nav.reset() }
    }

    fun biometric(onOk: () -> Unit) {
        if (BiometricManager.from(this).canAuthenticate(BIOMETRIC_WEAK) != BiometricManager.BIOMETRIC_SUCCESS) return
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
        }).authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle(L("باز کردن قفل", "Unlock"))
                .setNegativeButtonText(L("رمز", "PIN")).setAllowedAuthenticators(BIOMETRIC_WEAK).build()
        )
    }
}

@Composable
fun Root(act: MainActivity) {
    val list by App.I.accounts.list.collectAsState()
    val active by App.I.accounts.active.collectAsState()
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (AppLock.locked.value) { LockScreen(act); return }
    if (list.isEmpty() || active == null || Nav.top == Scr.Onboard) {
        BackHandler(list.isNotEmpty()) { Nav.back() }
        Onboard(adding = list.isNotEmpty())
        return
    }
    val acc = remember(active) { App.I.accounts.get(active!!) }
    BackHandler(Nav.stack.size > 1) { Nav.back() }
    when (val s = Nav.top) {
        Scr.Home -> HomeScreen(acc, archive = false, act)
        Scr.Archive -> HomeScreen(acc, archive = true, act)
        is Scr.ChatS -> ChatScreen(acc, s.peer, s.jumpTo)
        is Scr.Info -> InfoScreen(acc, s.peer)
        Scr.Me -> MeScreen(acc)
        Scr.SettingsS -> SettingsScreen(acc, act)
        Scr.Contacts -> ContactsScreen(acc)
        is Scr.Viewer -> ViewerScreen(acc, s.peer, s.id)
        is Scr.ForwardPick -> ForwardScreen(acc, s.peer, s.ids)
        Scr.Onboard -> {}
    }
}

@Composable
fun LockScreen(act: MainActivity) {
    var pin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (Settings.biometric) act.biometric { AppLock.locked.value = false } }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Lock, null, Modifier.padding(16.dp), tint = MaterialTheme.colorScheme.primary)
        Text(L("رمز برنامه را وارد کنید", "Enter your passcode"), fontSize = 18.sp)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            pin, { pin = it.filter(Char::isDigit).take(12); err = false },
            singleLine = true, isError = err, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        )
        Spacer(Modifier.height(16.dp))
        Button({ if (AppLock.check(pin)) AppLock.locked.value = false else err = true }) { Text(L("باز کردن", "Unlock")) }
        if (Settings.biometric) IconButton({ act.biometric { AppLock.locked.value = false } }) { Icon(Icons.Default.Fingerprint, null) }
    }
}

@Composable
fun Onboard(adding: Boolean) {
    val ctx = LocalContext.current
    val defaultServer = App.I.accounts.current()?.server ?: BuildConfig.DEFAULT_SERVER
    var server by remember { mutableStateOf(defaultServer) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var backupBytes by remember { mutableStateOf<ByteArray?>(null) }

    fun validServer(s: String) = s.startsWith("https://") && s.length > 10 || (BuildConfig.ALLOW_CLEARTEXT && s.startsWith("http://") && s.length > 9)

    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        backupBytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        Text("🔐", fontSize = 64.sp)
        Text(L("پیام‌رسان امن", "Secure Messenger"), fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            L(
                "همه پیام‌ها روی گوشی شما رمز می‌شوند و فقط گیرنده می‌تواند بازشان کند. سرور هیچ پیام یا فایلی ذخیره نمی‌کند. بدون شماره تلفن؛ یک نام کاربری ۸ حرفی به شما داده می‌شود.",
                "Messages are end-to-end encrypted and the server stores no chats or files. No phone number — you get an 8-character username.",
            ),
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(server, { server = it.trim() }, Modifier.fillMaxWidth(), label = { Text(L("آدرس سرور", "Server address")) }, singleLine = true,
            supportingText = { Text(L("سروری که خودتان یا دوستتان با install.sh راه‌اندازی کرده، مثل https://chat.example.com",
                "A server you or a friend set up with install.sh, e.g. https://chat.example.com")) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(name, { name = it.take(64) }, Modifier.fillMaxWidth(), label = { Text(L("نام شما (اختیاری)", "Your name (optional)")) }, singleLine = true)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        if (busy) CircularProgressIndicator() else {
            Button({
                if (!validServer(server)) { error = L("آدرس سرور باید با https:// شروع شود", "Server must start with https://"); return@Button }
                busy = true
                uiScope.launch {
                    try {
                        val acc = withContext(Dispatchers.IO) { App.I.accounts.create(server) }
                        if (name.isNotBlank()) acc.name = name.trim()
                        SocketService.start(ctx)
                        Nav.reset(Scr.Home)
                        Nav.go(Scr.Me)
                    } catch (e: Exception) {
                        error = e.message; busy = false
                    }
                }
            }, Modifier.fillMaxWidth()) { Text(L("ساخت حساب جدید", "Create account")) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton({ pickBackup.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) { Text(L("بازیابی از فایل پشتیبان", "Restore from backup")) }
            if (adding) TextButton({ Nav.back() }) { Text(L("انصراف", "Cancel")) }
        }
    }

    backupBytes?.let { bytes ->
        var pass by remember { mutableStateOf("") }
        var working by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!working) backupBytes = null },
            title = { Text(L("رمز فایل پشتیبان", "Backup password")) },
            text = {
                Column {
                    OutlinedTextField(pass, { pass = it }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    if (working) CircularProgressIndicator(Modifier.padding(top = 12.dp))
                }
            },
            confirmButton = {
                TextButton({
                    working = true
                    uiScope.launch {
                        try {
                            val json = withContext(Dispatchers.Default) { JSONObject(String(Pw.decrypt(pass.toCharArray(), bytes))) }
                            withContext(Dispatchers.IO) { App.I.accounts.create(json.getString("server"), json) }
                            SocketService.start(ctx)
                            backupBytes = null
                            Nav.reset(Scr.Home)
                        } catch (e: Exception) {
                            working = false
                            error = L("رمز اشتباه است یا فایل معتبر نیست", "Wrong password or invalid file")
                            backupBytes = null
                        }
                    }
                }, enabled = !working && pass.isNotEmpty()) { Text(L("بازیابی", "Restore")) }
            },
        )
    }
}
