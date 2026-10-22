package ir.payam.messenger.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import ir.payam.messenger.data.Chat
import ir.payam.messenger.data.Contact
import ir.payam.messenger.data.Msg
import ir.payam.messenger.data.OlmSess
import ir.payam.messenger.data.Out
import ir.payam.messenger.data.Seen
import ir.payam.messenger.svc.Notif
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Messaging engine for one account. All Olm state changes happen under [lock];
 * incoming frames are processed strictly in order by a single consumer.
 *
 * Wire envelope (inside Olm): {"t": msg|edit|del|react|read|typing|pin|prof|ttl, ...}
 */
class Engine(private val acc: Acc) {
    private val d get() = acc.d
    private val lock = Mutex()
    private val inbox = Channel<JSONObject>(Channel.UNLIMITED)
    private val inflight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val flushing = AtomicBoolean(false)
    @Volatile private var flushAgain = false

    data class Presence(val online: Boolean?, val lastSeen: Long?, val hidden: Boolean)

    val presence = MutableStateFlow<Map<String, Presence>>(emptyMap())
    val typing = MutableStateFlow<Map<String, Long>>(emptyMap())
    val downloading = MutableStateFlow<Set<String>>(emptySet())
    /** Raised when the server served a key that doesn't match a username/fingerprint. */
    val securityAlert = MutableStateFlow<String?>(null)
    /** Chat currently on screen: no notification, messages marked read at once. */
    @Volatile var openChat: String? = null

    init {
        acc.scope.launch {
            if (d.contact(acc.user) == null) d.putContact(Contact(acc.user, acc.ed, acc.curve, name = acc.name, verified = 1))
            for (m in inbox) {
                try { process(m) } catch (e: Exception) { /* a bad frame must not stop the loop */ }
            }
        }
    }

    fun signAuth(nonce: String) = acc.sign("auth:$nonce".toByteArray())

    // ------------------------------------------------------------------ connection

    fun onConnected(m: JSONObject) {
        inflight.clear()
        acc.scope.launch {
            lock.withLock { ensureKeys(m.optInt("otks"), m.optBoolean("isNew")) }
            if (acc.profileDirty) publishProfile()
            subscribe()
            restoreContacts()
            d.pendingUploads().forEach { uploadAndSend(it) }
            flushNow()
        }
    }

    fun onDisconnected() {
        inflight.clear()
        presence.value = emptyMap()
    }

    fun onFrame(m: JSONObject) {
        if (m.optString("t") == "presence") {
            val p = Presence(m.opt("online") as? Boolean, m.optLong("lastSeen").takeIf { it > 0 }, m.optBoolean("hidden"))
            presence.update { it + (m.getString("user") to p) }
        } else inbox.trySend(m)
    }

    private suspend fun process(m: JSONObject) {
        when (m.optString("t")) {
            "msg" -> lock.withLock { receive(m) }
            "sent" -> onSent(m.getString("id"))
            "delivered" -> { d.raiseStatus(m.getString("from"), m.getString("id"), 2); ack(m.optString("q")) }
            "lowKeys" -> lock.withLock { ensureKeys(m.optInt("otks"), false) }
            "profileOk" -> {
                acc.profileDirty = false
                d.chatPeers().filter { it != acc.user }.forEach { enqueue(it, JSONObject().put("t", "prof")) }
            }
            "error" -> {
                val ref = m.optString("ref")
                inflight.remove(ref)
                when (m.optString("code")) {
                    "nouser" -> d.out(ref)?.let { o -> d.doneOut(ref); o.msgId?.let { fail(o.peer, it) } }
                    "full", "rate" -> acc.scope.launch { delay(30_000); flushNow() }
                }
            }
        }
    }

    private fun ack(q: String) {
        if (q.isNotEmpty()) acc.conn.send(JSONObject().put("t", "ack").put("q", q))
    }

    suspend fun subscribe() {
        val peers = d.chatPeers().filter { it != acc.user }
        acc.conn.send(JSONObject().put("t", "sub").put("users", JSONArray(peers)))
    }

    /** Keeps ~50 signed one-time keys on the server and rotates the fallback key weekly. */
    private fun ensureKeys(count: Int, isNew: Boolean) {
        val k = acc.pickleKey
        val frame = JSONObject().put("t", "keys")
        var any = false
        if (count < 20) {
            val r = Olm.genOneTimeKeys(acc.account, k, 50 - count)
            acc.account = r[0]
            val otks = JSONArray()
            for (i in 1 until r.size step 2) otks.put(signedKey(r[i], r[i + 1]))
            frame.put("otks", otks)
            any = otks.length() > 0
        }
        val now = System.currentTimeMillis()
        if (isNew || now - acc.p.getLong("fbTs", 0) > 7 * 86400_000L) {
            val r = Olm.genFallbackKey(acc.account, k)
            acc.account = r[0]
            frame.put("fallback", signedKey(r[1], r[2]))
            acc.p.edit().putLong("fbTs", now).apply()
            any = true
        }
        if (any) acc.conn.send(frame)
    }

    private fun signedKey(id: String, key: String) =
        JSONObject().put("id", id).put("key", key).put("sig", acc.sign("otk:$id:$key".toByteArray()))

    // ------------------------------------------------------------------ identity lookup

    sealed interface Lookup {
        data class Ok(val c: Contact) : Lookup
        data object Offline : Lookup
        data object NoUser : Lookup
        data object Mismatch : Lookup
    }

    /**
     * Fetches keys + profile and VERIFIES them: the keys must hash to the username
     * (and to the fingerprint, when we have one from a link/QR) and must equal any
     * keys we pinned before. A server that swaps keys is caught here.
     */
    suspend fun lookup(u: String, fp: String? = null): Lookup {
        val r = acc.conn.request("getProfile", u)
        if (r.optString("t") == "error") return if (r.optString("code") == "nouser") Lookup.NoUser else Lookup.Offline
        val ed = r.getString("ed")
        val curve = r.getString("curve")
        val old = d.contact(u)
        if (Ids.usernameOf(ed, curve) != u || (fp != null && Ids.fingerprint(ed, curve) != fp) ||
            (old != null && (old.ed != ed || old.curve != curve))
        ) {
            securityAlert.value = u
            return Lookup.Mismatch
        }
        var c = old ?: Contact(u, ed, curve)
        val prof = r.optString("profile")
        val sig = r.optString("sig")
        if (prof.isNotEmpty() && sig.isNotEmpty() && Olm.verify(ed, prof.toByteArray(), sig)) {
            val j = JSONObject(prof)
            val photos = j.optJSONArray("photos") ?: JSONArray()
            val hidePhoto = r.optBoolean("hidePhoto")
            if (!hidePhoto) syncPhotos(u, photos)
            c = c.copy(name = j.optString("name").take(64), bio = j.optString("bio").take(300), photos = photos.toString(), hidePhoto = hidePhoto)
        }
        c = c.copy(profileTs = System.currentTimeMillis(), verified = if (fp != null) 1 else c.verified)
        d.putContact(c)
        return Lookup.Ok(c)
    }

    /** Downloads photos whose SHA-256 matches what the owner signed. */
    private fun syncPhotos(u: String, hashes: JSONArray) {
        for (i in 0 until 5) {
            val f = File(acc.avatarDir, "${u}_$i.jpg")
            if (i >= hashes.length()) { f.delete(); continue }
            val want = hashes.optString(i)
            if (f.exists() && hex(sha256(f.readBytes())) == want) continue
            val tmp = File(acc.tmpDir, Ids.newId())
            if (acc.getPhoto(u, i, tmp) && hex(sha256(tmp.readBytes())) == want) tmp.copyTo(f, true)
            tmp.delete()
        }
    }

    fun photoFiles(c: Contact): List<File> =
        if (c.user == acc.user) acc.myPhotos()
        else (0 until 5).map { File(acc.avatarDir, "${c.user}_$it.jpg") }.filter { it.exists() }

    suspend fun addContact(u: String, fp: String?): Lookup {
        if (u == acc.user) return Lookup.Ok(d.contact(u)!!)
        val r = lookup(u, fp)
        if (r is Lookup.Ok) {
            d.putContact(r.c.copy(isContact = true))
            if (d.chat(u) == null) d.putChat(Chat(u, lastTs = System.currentTimeMillis()))
            subscribe()
        }
        return r
    }

    private suspend fun restoreContacts() {
        val users = acc.restoreContacts()
        if (users.isEmpty()) return
        for (u in users) {
            if (addContact(u, null) is Lookup.Ok) enqueueReset(u)
        }
        acc.clearRestoreContacts()
    }

    // ------------------------------------------------------------------ Olm

    private suspend fun encryptTo(c: Contact, pt: ByteArray): Pair<Int, String>? {
        val k = acc.pickleKey
        var s = d.sessions(c.user).firstOrNull()
        if (s == null) {
            val b = acc.conn.request("bundle", c.user)
            if (b.optString("t") != "bundle") return null
            if (b.optString("ed") != c.ed || b.optString("curve") != c.curve) { securityAlert.value = c.user; return null }
            val otk = b.optJSONObject("otk") ?: return null
            val id = otk.optString("id"); val key = otk.optString("key")
            // The one-time key must be signed by the peer's identity key, or the server forged it.
            if (!Olm.verify(c.ed, "otk:$id:$key".toByteArray(), otk.optString("sig"))) { securityAlert.value = c.user; return null }
            val fresh = OlmSess(peer = c.user, pickle = Olm.outbound(acc.account, k, c.curve, key), updated = System.currentTimeMillis())
            s = fresh.copy(rid = d.putSession(fresh))
        }
        val r = Olm.encrypt(s.pickle, k, pt)
        d.putSession(s.copy(pickle = r[0], updated = System.currentTimeMillis()))
        return r[1].toInt() to r[2]
    }

    private suspend fun decryptFrom(c: Contact, kind: Int, body: String): ByteArray? {
        val k = acc.pickleKey
        val sessions = d.sessions(c.user)
        fun attempt(s: OlmSess, t: Int): Pair<OlmSess, ByteArray>? = try {
            val r = Olm.decrypt(s.pickle, k, t, body)
            s.copy(pickle = r[0], updated = System.currentTimeMillis()) to unb64(r[1])
        } catch (e: IllegalStateException) { null }

        if (kind == 0) {
            sessions.firstOrNull { runCatching { Olm.sessionMatches(it.pickle, k, body) }.getOrDefault(false) }?.let { s ->
                return attempt(s, 0)?.let { (ns, pt) -> d.putSession(ns); pt }
            }
            return try {
                val r = Olm.inbound(acc.account, k, c.curve, body)
                acc.account = r[0]
                d.putSession(OlmSess(peer = c.user, pickle = r[1], updated = System.currentTimeMillis()))
                d.pruneSessions(c.user)
                unb64(r[2])
            } catch (e: IllegalStateException) { null }
        }
        for (s in sessions) attempt(s, 1)?.let { (ns, pt) -> d.putSession(ns); return pt }
        return null
    }

    private suspend fun receive(m: JSONObject) {
        val from = m.getString("from")
        val q = m.optString("q")
        val seenKey = from + ":" + m.optString("id")
        if (q.isNotEmpty() && d.seen(seenKey) > 0) { ack(q); return }
        val c = d.contact(from) ?: when (val r = lookup(from)) {
            is Lookup.Ok -> r.c
            Lookup.Offline -> return // not acked → redelivered on reconnect
            else -> { ack(q); return }
        }
        if (c.blocked) { ack(q); return }
        val kind = m.optInt("kind")
        if (kind == 2) { d.dropSessions(from); ack(q); return }
        val pt = decryptFrom(c, kind, m.getString("body"))
        if (pt == null) {
            ack(q)
            enqueueReset(from)
            system(from, L("پیامی رسید که قابل رمزگشایی نبود؛ نشست امن دوباره ساخته شد.", "A message could not be decrypted; the secure session was reset."))
            return
        }
        try { handle(from, JSONObject(String(pt)), m.optLong("ts")) } catch (e: Exception) { }
        if (q.isNotEmpty()) d.addSeen(Seen(seenKey))
        ack(q)
    }

    // ------------------------------------------------------------------ incoming envelopes

    private val TYPES = setOf("text", "photo", "video", "file", "voice", "vnote", "sticker", "loc", "contact")
    private val MEDIA_KEYS = listOf("blob", "key", "size", "mime", "name", "w", "h", "dur", "wave", "thumb", "lat", "lon", "user", "fp", "cname")

    private suspend fun handle(from: String, j: JSONObject, serverTs: Long) {
        val now = System.currentTimeMillis()
        when (j.optString("t")) {
            "msg" -> {
                val id = j.optString("id").take(64).ifEmpty { return }
                if (d.msg(from, id) != null) return
                val type = j.optString("type").takeIf { it in TYPES } ?: return
                val media = j.optJSONObject("media")?.let { src ->
                    JSONObject().also { dst -> MEDIA_KEYS.forEach { k -> if (src.has(k)) dst.put(k, src.get(k)) } }
                        .also { if (it.has("name")) it.put("name", FileGuard.cleanName(it.getString("name"))) }
                }
                val ttl = j.optInt("ttl")
                val ts = j.optLong("ts").coerceIn(now - 7 * 86400_000L, now).takeIf { it > 0 } ?: serverTs
                val fwd = j.optJSONObject("fwd")?.let { JSONObject().put("u", it.optString("u").take(8)).put("n", it.optString("n").take(64)).toString() }
                val msg = Msg(
                    peer = from, id = id, out = false, ts = ts, type = type,
                    text = j.optString("text").take(8192), ent = j.optJSONArray("ent")?.toString(),
                    replyTo = j.optString("reply").takeIf { it.isNotEmpty() }, fwd = fwd, media = media?.toString(),
                    status = 0, expiresAt = if (ttl > 0) now + ttl * 1000L else 0, viewOnce = j.optBoolean("once"),
                    silent = j.optBoolean("silent"), lp = j.optJSONObject("lp")?.toString(),
                )
                if (d.insertMsg(msg) < 0) return
                val chat = d.chat(from) ?: Chat(from)
                d.putChat(chat.copy(lastTs = maxOf(chat.lastTs, ts), archived = chat.archived && chat.muted))
                if (openChat == from) markRead(from) else d.recount(from)
                typing.update { it - from }
                if (media != null && media.has("blob") && autoDownload(type)) acc.scope.launch { download(msg) }
                if (openChat != from && !chat.muted && !msg.silent) Notif.message(acc, d.contact(from) ?: return, msg)
            }
            "edit" -> {
                val t = d.msg(from, j.optString("id")) ?: return
                if (t.out) return // they may only edit their own messages
                d.putMsg(t.copy(text = j.optString("text").take(8192), ent = j.optJSONArray("ent")?.toString(), edited = true))
            }
            "del" -> {
                val ids = j.optJSONArray("ids") ?: return
                for (i in 0 until minOf(ids.length(), 500)) d.msg(from, ids.optString(i))?.let { removeMsg(it) }
                d.recount(from)
            }
            "react" -> {
                val t = d.msg(from, j.optString("id")) ?: return
                d.putMsg(t.copy(reactions = withReaction(t.reactions, from, j.optString("e").take(16))))
            }
            "read" -> {
                val ids = j.optJSONArray("ids") ?: return
                for (i in 0 until minOf(ids.length(), 500)) d.raiseStatus(from, ids.optString(i), 3)
            }
            "typing" -> typing.update { it + (from to now) }
            "pin" -> {
                val t = d.msg(from, j.optString("id")) ?: return
                d.putMsg(t.copy(pinned = j.optBoolean("on")))
            }
            "prof" -> lookup(from)
            "ttl" -> {
                val v = j.optInt("v").coerceIn(0, 7 * 86400)
                val chat = d.chat(from) ?: Chat(from)
                d.putChat(chat.copy(ttl = v))
                system(from, L("حذف خودکار پیام‌ها: ", "Auto-delete: ") + Fmt.ttl(v))
            }
        }
    }

    private fun withReaction(json: String?, user: String, e: String): String? {
        val o = json?.let { JSONObject(it) } ?: JSONObject()
        if (e.isEmpty()) o.remove(user) else o.put(user, e)
        return if (o.length() == 0) null else o.toString()
    }

    private suspend fun system(peer: String, text: String) {
        val now = System.currentTimeMillis()
        d.insertMsg(Msg(peer, Ids.newId(), false, now, "system", text, status = 3))
        val chat = d.chat(peer) ?: Chat(peer)
        d.putChat(chat.copy(lastTs = now))
    }

    // ------------------------------------------------------------------ outgoing

    private suspend fun enqueue(peer: String, payload: JSONObject, msgId: String? = null) {
        if (peer == acc.user) return
        // Content messages travel under their own id: the server's "delivered" receipt then names the
        // message, and a retransmitted copy is dropped by the receiver's seen-table.
        d.addOut(Out(peer = peer, wid = msgId ?: Ids.newId(), payload = payload.toString(), msgId = msgId))
        flush() // async: callers may hold `lock`, which flushing needs
    }

    private suspend fun enqueueReset(peer: String) {
        d.dropSessions(peer)
        d.addOut(Out(peer = peer, wid = Ids.newId(), payload = RESET))
        flush()
    }

    fun flush() { acc.scope.launch { flushNow() } }

    private suspend fun flushNow() {
        if (!flushing.compareAndSet(false, true)) { flushAgain = true; return }
        try {
            do {
                flushAgain = false
                for (o in d.outbox()) {
                    if (acc.conn.state.value != 2) return
                    if (o.wid in inflight) continue
                    val c = d.contact(o.peer) ?: continue
                    val frame = JSONObject().put("t", "send").put("to", o.peer).put("id", o.wid)
                    if (o.payload == RESET) frame.put("body", "reset").put("kind", 2)
                    else {
                        val enc = lock.withLock { encryptTo(c, o.payload.toByteArray()) } ?: continue
                        frame.put("body", enc.second).put("kind", enc.first)
                    }
                    inflight += o.wid
                    if (!acc.conn.send(frame)) { inflight -= o.wid; return }
                }
            } while (flushAgain)
        } finally {
            flushing.set(false)
        }
    }

    private suspend fun onSent(wid: String) {
        inflight.remove(wid)
        val o = d.out(wid) ?: return
        d.doneOut(wid)
        o.msgId?.let { d.raiseStatus(o.peer, it, 1) }
    }

    private suspend fun fail(peer: String, id: String) {
        d.msg(peer, id)?.let { d.putMsg(it.copy(status = -1)) }
    }

    private fun sendEph(peer: String, payload: JSONObject) {
        if (peer == acc.user || acc.conn.state.value != 2) return
        acc.scope.launch {
            val c = d.contact(peer) ?: return@launch
            if (d.sessions(peer).isEmpty()) return@launch // don't burn a one-time key on a typing hint
            val enc = lock.withLock { encryptTo(c, payload.toString().toByteArray()) } ?: return@launch
            acc.conn.send(JSONObject().put("t", "send").put("to", peer).put("id", Ids.newId()).put("body", enc.second).put("kind", enc.first).put("eph", true))
        }
    }

    private var lastTyping = 0L
    fun typingNow(peer: String) {
        val now = System.currentTimeMillis()
        if (now - lastTyping < 4000) return
        lastTyping = now
        sendEph(peer, JSONObject().put("t", "typing"))
    }

    private fun envelope(m: Msg, ttl: Int): JSONObject {
        val j = JSONObject().put("t", "msg").put("id", m.id).put("ts", m.ts).put("type", m.type).put("text", m.text)
        m.ent?.let { j.put("ent", JSONArray(it)) }
        m.replyTo?.let { j.put("reply", it) }
        m.fwd?.let { j.put("fwd", JSONObject(it)) }
        m.lp?.let { j.put("lp", JSONObject(it)) }
        m.media?.let { src ->
            val o = JSONObject(src)
            j.put("media", JSONObject().also { dst -> MEDIA_KEYS.forEach { k -> if (o.has(k)) dst.put(k, o.get(k)) } })
        }
        if (m.silent) j.put("silent", true)
        if (m.viewOnce) j.put("once", true)
        if (ttl > 0) j.put("ttl", ttl)
        return j
    }

    private suspend fun dispatch(m: Msg) {
        if (m.peer == acc.user) { d.putMsg(m.copy(status = 1)); return }
        enqueue(m.peer, envelope(m, d.chat(m.peer)?.ttl ?: 0), m.id)
    }

    private suspend fun store(m: Msg) {
        val chat = d.chat(m.peer) ?: Chat(m.peer)
        val ttl = chat.ttl
        d.insertMsg(if (ttl > 0 && m.status != -2) m.copy(expiresAt = m.ts + ttl * 1000L) else m)
        d.putChat(chat.copy(lastTs = maxOf(chat.lastTs, m.ts), draft = "", archived = false))
    }

    suspend fun sendText(peer: String, raw: String, replyTo: String? = null, silent: Boolean = false, at: Long = 0) {
        val (text, ents) = Md.parse(raw.trim())
        if (text.isBlank()) return
        val lp = if (Settings.linkPreview && at == 0L) Media.linkPreview(acc, text) else null
        val m = Msg(
            peer, Ids.newId(), true, if (at > 0) at else System.currentTimeMillis(), "text", text, Md.toJson(ents), replyTo,
            status = if (at > 0) -2 else 0, silent = silent, scheduledAt = at, lp = lp?.toString(),
        )
        store(m)
        if (at == 0L) dispatch(m)
    }

    /** [file] must already be inside mediaDir and pass FileGuard. */
    suspend fun sendMedia(
        peer: String, type: String, file: File, meta: JSONObject, caption: String = "", replyTo: String? = null,
        silent: Boolean = false, viewOnce: Boolean = false, at: Long = 0,
    ) {
        val (text, ents) = Md.parse(caption.trim())
        meta.put("path", file.path).put("size", file.length())
        val m = Msg(
            peer, Ids.newId(), true, if (at > 0) at else System.currentTimeMillis(), type, text, Md.toJson(ents), replyTo,
            media = meta.toString(), status = if (at > 0) -2 else 0, silent = silent, viewOnce = viewOnce, scheduledAt = at,
        )
        store(m)
        if (at == 0L) uploadAndSend(m)
    }

    suspend fun sendLocation(peer: String, lat: Double, lon: Double) {
        val m = Msg(peer, Ids.newId(), true, System.currentTimeMillis(), "loc", media = JSONObject().put("lat", lat).put("lon", lon).toString())
        store(m); dispatch(m)
    }

    suspend fun sendContactCard(peer: String, c: Contact) {
        val meta = JSONObject().put("user", c.user).put("fp", Ids.fingerprint(c.ed, c.curve)).put("cname", c.display)
        val m = Msg(peer, Ids.newId(), true, System.currentTimeMillis(), "contact", media = meta.toString())
        store(m); dispatch(m)
    }

    private val uploading: MutableSet<String> = ConcurrentHashMap.newKeySet()

    suspend fun uploadAndSend(m: Msg) {
        if (m.peer == acc.user) { d.putMsg(m.copy(status = 1)); return }
        if (!uploading.add(m.id)) return
        try {
            val media = JSONObject(m.media ?: return)
            if (media.has("blob")) { dispatch(m); return }
            val f = File(media.optString("path"))
            if (!f.exists()) { fail(m.peer, m.id); return }
            val tmp = File(acc.tmpDir, Ids.newId())
            val key = Attach.encrypt(f.inputStream(), tmp)
            val blob = acc.uploadBlob(tmp)
            tmp.delete()
            if (blob == null) {
                if (acc.conn.state.value == 2) fail(m.peer, m.id) // online but rejected (too big / server full)
                return // offline: retried on reconnect
            }
            media.put("blob", blob).put("key", b64(key))
            val sent = (d.msg(m.peer, m.id) ?: return).copy(media = media.toString(), status = 0)
            d.putMsg(sent)
            dispatch(sent)
        } finally {
            uploading.remove(m.id)
        }
    }

    suspend fun retry(m: Msg) {
        d.putMsg(m.copy(status = 0))
        if (m.media != null && m.type !in setOf("loc", "contact")) uploadAndSend(m.copy(status = 0)) else dispatch(m)
    }

    suspend fun edit(m: Msg, raw: String) {
        val (text, ents) = Md.parse(raw.trim())
        if (text.isBlank() && m.media == null) return
        d.putMsg(m.copy(text = text, ent = Md.toJson(ents), edited = true))
        enqueue(m.peer, JSONObject().put("t", "edit").put("id", m.id).put("text", text).putOpt("ent", Md.toJson(ents)?.let(::JSONArray)))
    }

    suspend fun delete(peer: String, msgs: List<Msg>, forAll: Boolean) {
        msgs.forEach { removeMsg(it) }
        d.recount(peer)
        if (forAll) enqueue(peer, JSONObject().put("t", "del").put("ids", JSONArray(msgs.map { it.id })))
    }

    private suspend fun removeMsg(m: Msg) {
        m.media?.let { JSONObject(it).optString("path").takeIf { p -> p.isNotEmpty() }?.let { p -> File(p).delete() } }
        d.deleteMsg(m.peer, m.id)
    }

    suspend fun react(m: Msg, e: String) {
        val mine = m.reactions?.let { JSONObject(it).optString(acc.user) }
        val emoji = if (mine == e) "" else e
        d.putMsg(m.copy(reactions = withReaction(m.reactions, acc.user, emoji)))
        enqueue(m.peer, JSONObject().put("t", "react").put("id", m.id).put("e", emoji))
    }

    suspend fun pin(m: Msg, on: Boolean) {
        d.putMsg(m.copy(pinned = on))
        enqueue(m.peer, JSONObject().put("t", "pin").put("id", m.id).put("on", on))
    }

    suspend fun setTtl(peer: String, sec: Int) {
        val chat = d.chat(peer) ?: Chat(peer)
        d.putChat(chat.copy(ttl = sec))
        system(peer, L("حذف خودکار پیام‌ها: ", "Auto-delete: ") + Fmt.ttl(sec))
        enqueue(peer, JSONObject().put("t", "ttl").put("v", sec))
    }

    suspend fun markRead(peer: String) {
        val unread = d.unreadIncoming(peer)
        if (unread.isEmpty()) { d.recount(peer); return }
        d.markIncomingRead(peer)
        d.recount(peer)
        Notif.clear(acc, peer)
        enqueue(peer, JSONObject().put("t", "read").put("ids", JSONArray(unread.takeLast(300).map { it.id })))
    }

    suspend fun forward(msgs: List<Msg>, to: String) {
        for (m in msgs.sortedBy { it.ts }) {
            if (m.type == "system") continue
            val author = m.fwd ?: JSONObject().put("u", if (m.out) acc.user else m.peer)
                .put("n", if (m.out) acc.name else d.contact(m.peer)?.display ?: m.peer).toString()
            var media = m.media?.let { JSONObject(it) }
            if (media != null && media.has("path")) {
                val src = File(media.getString("path"))
                if (!src.exists()) continue
                val copy = File(acc.mediaDir, Ids.newId() + "." + src.extension)
                src.copyTo(copy)
                media = JSONObject(media.toString()).apply { remove("blob"); remove("key"); put("path", copy.path) }
            } else if (media != null && media.has("blob") && m.type !in setOf("loc", "contact")) continue // not downloaded
            val n = Msg(to, Ids.newId(), true, System.currentTimeMillis(), m.type, m.text, m.ent, fwd = author, media = media?.toString())
            store(n)
            if (n.media != null && n.type !in setOf("loc", "contact")) uploadAndSend(n) else dispatch(n)
        }
    }

    suspend fun clearHistory(peer: String) {
        d.withFiles().filter { it.peer == peer }.forEach { removeMsg(it) }
        d.clearChat(peer)
        d.recount(peer)
    }

    suspend fun deleteChat(peer: String) {
        clearHistory(peer)
        d.deleteChat(peer)
    }

    suspend fun setBlocked(peer: String, on: Boolean) {
        d.contact(peer)?.let { d.putContact(it.copy(blocked = on)) }
    }

    // ------------------------------------------------------------------ media download

    private fun autoDownload(type: String): Boolean {
        val cm = acc.ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifi = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val kind = when (type) { "photo", "sticker" -> "photo"; "voice", "vnote" -> "voice"; "video" -> "video"; else -> "file" }
        return Settings.autoDl(kind, wifi)
    }

    private val EXPECT = mapOf("photo" to "image/", "sticker" to "image/", "video" to "video/", "vnote" to "video/", "voice" to "audio/")

    /** Downloads, MAC-checks, decrypts and FileGuard-checks an attachment, then deletes it from the server. */
    suspend fun download(m: Msg): Boolean {
        if (!downloadingAdd(m.id)) return false
        try {
            val media = JSONObject((d.msg(m.peer, m.id) ?: m).media ?: return false)
            if (media.has("path") || media.has("blocked")) return true
            val blob = media.optString("blob")
            val key = runCatching { unb64(media.optString("key")) }.getOrNull() ?: return false
            val enc = File(acc.tmpDir, Ids.newId())
            val plain = File(acc.tmpDir, Ids.newId())
            if (!acc.downloadBlob(blob, enc)) { enc.delete(); return false }
            val verdict = try {
                Attach.decrypt(enc, key, plain)
                val v = FileGuard.check(plain, media.optString("name").ifEmpty { "file" })
                val want = EXPECT[m.type]
                if (v.ok && want != null && !v.mime.startsWith(want) && !(m.type == "voice" && v.mime == "video/mp4")) FileGuard.Verdict(false, reason = L("نوع فایل نامعتبر است", "Unexpected file type")) else v
            } catch (e: SecurityException) {
                FileGuard.Verdict(false, reason = L("فایل دستکاری شده است", "File was tampered with"))
            } finally {
                enc.delete()
            }
            acc.deleteBlob(blob)
            val cur = d.msg(m.peer, m.id) ?: return false
            if (!verdict.ok) {
                plain.delete()
                d.putMsg(cur.copy(media = media.put("blocked", verdict.reason).toString()))
                return false
            }
            val dst = File(acc.mediaDir, m.id + "." + verdict.name.substringAfterLast('.', "bin"))
            if (!plain.renameTo(dst)) { plain.copyTo(dst, true); plain.delete() }
            d.putMsg(cur.copy(media = media.put("path", dst.path).put("mime", verdict.mime).toString()))
            return true
        } finally {
            downloading.update { it - m.id }
        }
    }

    private fun downloadingAdd(id: String): Boolean {
        var added = false
        downloading.update { if (id in it) it else { added = true; it + id } }
        return added
    }

    /** View-once media: delete the file after it was viewed. */
    suspend fun consumeOnce(m: Msg) {
        if (m.out || !m.viewOnce) return
        val media = m.media?.let { JSONObject(it) } ?: return
        media.optString("path").takeIf { it.isNotEmpty() }?.let { File(it).delete() }
        media.remove("path")
        d.putMsg(m.copy(viewed = true, media = media.toString()))
    }

    // ------------------------------------------------------------------ periodic (called by the service)

    suspend fun tick() {
        val now = System.currentTimeMillis()
        for (m in d.dueScheduled(now)) {
            val chat = d.chat(m.peer)
            val ready = m.copy(status = 0, scheduledAt = 0, ts = now, expiresAt = if ((chat?.ttl ?: 0) > 0) now + chat!!.ttl * 1000L else 0)
            d.putMsg(ready)
            d.putChat((chat ?: Chat(m.peer)).copy(lastTs = now))
            if (ready.media != null && ready.type !in setOf("loc", "contact")) uploadAndSend(ready) else dispatch(ready)
        }
        for (m in d.expired(now)) removeMsg(m)
        d.pruneSeen(now - 3 * 86400_000L)
        typing.update { map -> map.filterValues { now - it < 6000 } }
    }

    // ------------------------------------------------------------------ own profile

    suspend fun publishProfile() {
        if (acc.conn.state.value != 2) return
        val photos = acc.myPhotos()
        val hashes = JSONArray()
        photos.forEachIndexed { i, f ->
            if (!acc.putPhoto(i, f)) return
            hashes.put(hex(sha256(f.readBytes())))
        }
        for (i in photos.size until 5) acc.deletePhoto(i)
        val prof = JSONObject().put("name", acc.name).put("bio", acc.bio).put("photos", hashes).put("v", System.currentTimeMillis()).toString()
        acc.conn.send(
            JSONObject().put("t", "setProfile").put("profile", prof).put("sig", acc.sign(prof.toByteArray()))
                .put("hideSeen", acc.hideSeen).put("hidePhoto", acc.hidePhoto)
        )
        d.contact(acc.user)?.let { d.putContact(it.copy(name = acc.name, bio = acc.bio)) }
    }

    fun deleteAccountOnServer() = acc.conn.send(JSONObject().put("t", "deleteAccount"))

    companion object {
        const val RESET = "__reset__"
    }
}
