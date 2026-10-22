package ir.payam.messenger.core

import android.content.Context
import android.content.SharedPreferences
import ir.payam.messenger.BuildConfig
import ir.payam.messenger.data.AppDb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Up to 3 identities on one phone, each with its own DB, keys, socket and media folder. */
class Accounts(private val ctx: Context) {
    private val p = ctx.getSharedPreferences("accounts", Context.MODE_PRIVATE)
    private val open = HashMap<String, Acc>()
    val list = MutableStateFlow(p.getString("list", "")!!.split(',').filter { it.isNotBlank() })
    val active = MutableStateFlow(p.getString("active", null)?.takeIf { it in list.value } ?: list.value.firstOrNull())

    private fun save(l: List<String>) {
        p.edit().putString("list", l.joinToString(",")).apply()
        list.value = l
    }

    fun get(user: String): Acc = synchronized(open) { open.getOrPut(user) { Acc(ctx, user) } }
    fun current(): Acc? = active.value?.let(::get)
    fun all(): List<Acc> = list.value.map(::get)

    fun setActive(u: String?) {
        p.edit().putString("active", u).apply()
        active.value = u
    }

    /** Creates (or restores) an identity locally. The server learns it on first login. */
    fun create(server: String, restore: JSONObject? = null): Acc {
        require(list.value.size < 3) { "max 3 accounts" }
        val pk = restore?.let { unb64(it.getString("pk")) } ?: randomBytes(32)
        val pickle = restore?.getString("account") ?: Olm.newAccount(pk)
        val (ed, curve) = Olm.identity(pickle, pk)
        val user = Ids.usernameOf(ed, curve)
        require(user !in list.value) { "account already on this phone" }
        ctx.getSharedPreferences("acc_$user", Context.MODE_PRIVATE).edit().apply {
            putString("server", server.trim().trimEnd('/'))
            putString("pk", Vault.seal(pk))
            putString("account", pickle)
            putString("ed", ed)
            putString("curve", curve)
            if (restore != null) {
                putString("name", restore.optString("name"))
                putString("bio", restore.optString("bio"))
                putString("restoreContacts", restore.optJSONArray("contacts")?.toString() ?: "[]")
            }
            putBoolean("profileDirty", true)
        }.commit()
        save(list.value + user)
        setActive(user)
        return get(user).also { it.start() }
    }

    fun remove(user: String) {
        synchronized(open) { open.remove(user) }?.shutdown()
        ctx.deleteDatabase("acc_$user.db")
        ctx.deleteSharedPreferences("acc_$user")
        File(ctx.filesDir, user).deleteRecursively()
        save(list.value - user)
        if (active.value == user) setActive(list.value.firstOrNull())
    }
}

class Acc(val ctx: Context, val user: String) {
    val p: SharedPreferences = ctx.getSharedPreferences("acc_$user", Context.MODE_PRIVATE)
    val server: String = p.getString("server", "")!!
    val ed: String = p.getString("ed", "")!!
    val curve: String = p.getString("curve", "")!!
    val fp: String = Ids.fingerprint(ed, curve)
    private val pk: ByteArray = Vault.open(p.getString("pk", "")!!)
    val db = AppDb.open(ctx, user)
    val d = db.d()
    val dir = File(ctx.filesDir, user)
    val mediaDir = File(dir, "media").apply { mkdirs() }
    val avatarDir = File(dir, "avatars").apply { mkdirs() }
    val tmpDir = File(ctx.cacheDir, user).apply { mkdirs() }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val inviteLink get() = "$server/u/$user#$fp"
    val wsUrl = server.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws"

    /** Pickled Olm account (encrypted by vodozemac with `pk`). Only touched under Engine.lock. */
    var account: String
        get() = p.getString("account", "")!!
        set(v) { p.edit().putString("account", v).commit() }
    internal val pickleKey get() = pk

    // ---- own public profile ----
    val profileRev = MutableStateFlow(0)
    var name: String get() = p.getString("name", "")!!; set(v) = edit { putString("name", v) }
    var bio: String get() = p.getString("bio", "")!!; set(v) = edit { putString("bio", v) }
    var hideSeen: Boolean get() = p.getBoolean("hideSeen", false); set(v) = edit { putBoolean("hideSeen", v) }
    var hidePhoto: Boolean get() = p.getBoolean("hidePhoto", false); set(v) = edit { putBoolean("hidePhoto", v) }
    var profileDirty: Boolean get() = p.getBoolean("profileDirty", false); set(v) { p.edit().putBoolean("profileDirty", v).apply() }
    fun myPhotos(): List<File> = (0 until 5).map { File(avatarDir, "me_$it.jpg") }.filter { it.exists() }

    private fun edit(f: SharedPreferences.Editor.() -> Unit) {
        p.edit().apply(f).putBoolean("profileDirty", true).apply()
        profileRev.value++
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            // Trust-on-first-use pin of the server's public key (on top of normal CA checks).
            // The deploy guide uses certbot --reuse-key so renewals keep the same key.
            val hs = chain.connection()?.handshake()
            if (hs == null && !BuildConfig.ALLOW_CLEARTEXT) throw IOException("TLS required")
            if (hs != null) {
                val pin = b64(sha256(hs.peerCertificates.first().publicKey.encoded))
                val saved = p.getString("pin", null)
                if (saved == null) p.edit().putString("pin", pin).apply()
                else if (saved != pin) throw IOException("SERVER KEY CHANGED")
            }
            chain.proceed(chain.request())
        }
        .build()

    val conn = Conn(this)
    val engine = Engine(this)

    fun start() = conn.start()
    fun shutdown() {
        conn.stop()
        scope.cancel()
        db.close()
    }

    fun sign(msg: ByteArray): String = Olm.sign(account, pk, msg)

    // ---- HTTP: blobs and profile photos (blocking; call from IO) ----
    private fun authed(path: String) = Request.Builder().url(server + path).header("Authorization", "Bearer ${conn.token}")
    private inline fun <T> call(r: Request, f: (Response) -> T): T? = try { http.newCall(r).execute().use(f) } catch (e: IOException) { null }

    fun uploadBlob(f: File): String? {
        conn.token ?: return null
        val r = authed("/blob").post(f.asRequestBody("application/octet-stream".toMediaType())).build()
        return call(r) { if (it.isSuccessful) JSONObject(it.body.string()).getString("id") else null }
    }

    fun downloadBlob(id: String, dst: File): Boolean =
        call(Request.Builder().url("$server/blob/$id").build()) { res ->
            if (!res.isSuccessful) return@call false
            dst.outputStream().use { res.body.byteStream().copyTo(it) }
            true
        } ?: false

    fun deleteBlob(id: String) { call(Request.Builder().url("$server/blob/$id").delete().build()) {} }

    fun putPhoto(n: Int, f: File): Boolean =
        call(authed("/photo/$n").put(f.asRequestBody("image/jpeg".toMediaType())).build()) { it.isSuccessful } ?: false

    fun deletePhoto(n: Int) { call(authed("/photo/$n").delete().build()) {} }

    fun getPhoto(u: String, n: Int, dst: File): Boolean =
        call(Request.Builder().url("$server/photo/$u/$n").build()) { res ->
            if (!res.isSuccessful) return@call false
            dst.writeBytes(res.body.bytes())
            true
        } ?: false

    fun restoreContacts(): List<String> {
        val s = p.getString("restoreContacts", null) ?: return emptyList()
        val a = JSONArray(s)
        return (0 until a.length()).map { a.getString(it) }
    }

    fun clearRestoreContacts() = p.edit().remove("restoreContacts").apply()
}

/** One WebSocket per account; reconnects with backoff while the account is started. */
class Conn(private val acc: Acc) : WebSocketListener() {
    @Volatile private var ws: WebSocket? = null
    @Volatile private var stopped = true
    @Volatile var token: String? = null
    /** 0 offline, 1 connecting, 2 online */
    val state = MutableStateFlow(0)
    /** Set when another phone logged in with this identity (restored backup). */
    val kicked = MutableStateFlow(false)
    val lastError = MutableStateFlow<String?>(null)
    private var backoff = 1000L
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    @Synchronized fun start() {
        stopped = false
        kicked.value = false
        if (ws == null) open()
    }

    @Synchronized fun stop() {
        stopped = true
        ws?.close(1000, null)
        ws = null
    }

    /** Network came back: skip the backoff wait. */
    @Synchronized fun nudge() {
        backoff = 1000
        if (!stopped && ws == null) open()
    }

    private fun open() {
        state.value = 1
        ws = acc.http.newWebSocket(Request.Builder().url(acc.wsUrl).build(), this)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        if (webSocket !== ws) return
        val m = try { JSONObject(text) } catch (e: Exception) { return }
        when (m.optString("t")) {
            "challenge" -> {
                val sig = acc.engine.signAuth(m.getString("nonce"))
                webSocket.send(JSONObject().put("t", "auth").put("ed", acc.ed).put("curve", acc.curve).put("sig", sig).toString())
            }
            "ok" -> {
                token = m.getString("token")
                backoff = 1000
                lastError.value = null
                state.value = 2
                acc.engine.onConnected(m)
            }
            "kicked" -> { kicked.value = true; stopped = true }
            "bundle", "profile" -> pending.remove(m.optString("t") + ":" + m.optString("user"))?.complete(m)
            "error" -> {
                val ref = m.optString("ref")
                val waiter = pending.remove("bundle:$ref") ?: pending.remove("profile:$ref")
                when {
                    waiter != null -> waiter.complete(m)
                    m.optString("code") in setOf("auth", "taken") -> { lastError.value = m.optString("code"); stopped = true; webSocket.close(1000, null) }
                    else -> acc.engine.onFrame(m)
                }
            }
            else -> acc.engine.onFrame(m)
        }
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket)
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        if (t.message == "SERVER KEY CHANGED") lastError.value = "pin"
        dropped(webSocket)
    }

    private fun dropped(webSocket: WebSocket) {
        synchronized(this) {
            if (webSocket !== ws) return
            ws = null
        }
        token = null
        state.value = 0
        val offline = JSONObject().put("t", "error").put("code", "offline")
        pending.values.forEach { it.complete(offline) }
        pending.clear()
        acc.engine.onDisconnected()
        if (!stopped) acc.scope.launch {
            delay(backoff)
            backoff = minOf(backoff * 2, 30_000)
            synchronized(this@Conn) { if (!stopped && ws == null) open() }
        }
    }

    fun send(o: JSONObject): Boolean = state.value == 2 && ws?.send(o.toString()) == true

    /** bundle / getProfile round-trip. Returns the reply or an {t:error} object. */
    suspend fun request(t: String, user: String): JSONObject {
        val key = (if (t == "getProfile") "profile" else t) + ":" + user
        val d = CompletableDeferred<JSONObject>()
        pending[key] = d
        if (!send(JSONObject().put("t", t).put("user", user))) {
            pending.remove(key)
            return JSONObject().put("t", "error").put("code", "offline")
        }
        return withTimeoutOrNull(15_000) { d.await() } ?: JSONObject().put("t", "error").put("code", "timeout").also { pending.remove(key) }
    }
}
