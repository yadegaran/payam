package ir.payam.messenger.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// ponytail: chat history relies on Android file-based encryption + app sandbox; SQLCipher if device-at-rest threat grows.

@Entity(tableName = "contact")
data class Contact(
    @PrimaryKey val user: String,
    val ed: String,
    val curve: String,
    val name: String = "",
    val bio: String = "",
    /** JSON array of SHA-256 hex of profile photos, as signed by the owner */
    val photos: String = "[]",
    val localName: String? = null,
    /** 0 = key matches username hash, 1 = full fingerprint verified (link/QR/in person) */
    val verified: Int = 0,
    val blocked: Boolean = false,
    val isContact: Boolean = false,
    val hidePhoto: Boolean = false,
    val profileTs: Long = 0,
) {
    val display get() = localName?.takeIf { it.isNotBlank() } ?: name.takeIf { it.isNotBlank() } ?: user
}

@Entity(tableName = "chat")
data class Chat(
    @PrimaryKey val peer: String,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val muted: Boolean = false,
    val folder: String? = null,
    val draft: String = "",
    val lastTs: Long = 0,
    val unread: Int = 0,
    /** disappearing-messages timer, seconds (0 = off) */
    val ttl: Int = 0,
    val wallpaper: String? = null,
    val pinnedMsg: String? = null,
)

/**
 * status — outgoing: -2 scheduled, -1 failed, 0 sending, 1 sent, 2 delivered, 3 read
 *          incoming: 0 unread, 3 read
 * type — text|photo|video|file|voice|vnote|sticker|loc|contact|system
 */
@Entity(tableName = "msg", primaryKeys = ["peer", "id"], indices = [Index("peer", "ts")])
data class Msg(
    val peer: String,
    val id: String,
    val out: Boolean,
    val ts: Long,
    val type: String,
    val text: String = "",
    val ent: String? = null,
    val replyTo: String? = null,
    /** JSON {u, n} of the original author */
    val fwd: String? = null,
    /** JSON: blob,key,size,mime,name,w,h,dur,wave,thumb,path,blocked,lat,lon,user,fp */
    val media: String? = null,
    val status: Int = 0,
    val edited: Boolean = false,
    /** JSON {user: emoji} */
    val reactions: String? = null,
    val expiresAt: Long = 0,
    val viewOnce: Boolean = false,
    val viewed: Boolean = false,
    val scheduledAt: Long = 0,
    val silent: Boolean = false,
    /** JSON link preview {url, title, desc, img} */
    val lp: String? = null,
    val pinned: Boolean = false,
)

@Entity(tableName = "olm_session", indices = [Index("peer")])
data class OlmSess(@PrimaryKey(autoGenerate = true) val rid: Long = 0, val peer: String, val pickle: String, val updated: Long)

/** Plaintext envelopes waiting for the socket; encrypted right before sending. */
@Entity(tableName = "outbox")
data class Out(
    @PrimaryKey(autoGenerate = true) val rid: Long = 0,
    val peer: String,
    val wid: String,
    val payload: String,
    val msgId: String? = null,
    val created: Long = System.currentTimeMillis(),
)

/** Wire ids already processed — a redelivered (unacked) message must not be decrypted twice. */
@Entity(tableName = "seen")
data class Seen(@PrimaryKey val key: String, val ts: Long = System.currentTimeMillis())

@Entity(tableName = "sticker")
data class Sticker(@PrimaryKey val path: String, val added: Long = System.currentTimeMillis())

@Dao
interface D {
    // contacts
    @Query("SELECT * FROM contact WHERE user = :u") suspend fun contact(u: String): Contact?
    @Query("SELECT * FROM contact WHERE user = :u") fun contactFlow(u: String): Flow<Contact?>
    @Query("SELECT * FROM contact WHERE isContact = 1 ORDER BY COALESCE(NULLIF(localName,''), NULLIF(name,''), user)") fun contactsFlow(): Flow<List<Contact>>
    @Query("SELECT * FROM contact WHERE isContact = 1") suspend fun contacts(): List<Contact>
    @Query("SELECT * FROM contact") fun allContactsFlow(): Flow<List<Contact>>
    @Upsert suspend fun putContact(c: Contact)

    // chats
    @Query("SELECT * FROM chat WHERE peer = :p") suspend fun chat(p: String): Chat?
    @Query("SELECT * FROM chat WHERE peer = :p") fun chatFlow(p: String): Flow<Chat?>
    @Query("SELECT * FROM chat ORDER BY pinned DESC, lastTs DESC") fun chatsFlow(): Flow<List<Chat>>
    @Query("SELECT peer FROM chat") suspend fun chatPeers(): List<String>
    @Upsert suspend fun putChat(c: Chat)
    @Query("DELETE FROM chat WHERE peer = :p") suspend fun deleteChat(p: String)
    @Query("UPDATE chat SET unread = (SELECT COUNT(*) FROM msg WHERE msg.peer = :p AND out = 0 AND status = 0) WHERE peer = :p") suspend fun recount(p: String)

    // messages
    @Query("SELECT * FROM msg WHERE peer = :p AND scheduledAt = 0 ORDER BY ts DESC LIMIT :limit") fun msgsFlow(p: String, limit: Int): Flow<List<Msg>>
    @Query("SELECT * FROM msg WHERE peer = :p AND status = -2 ORDER BY scheduledAt") fun scheduledFlow(p: String): Flow<List<Msg>>
    @Query("SELECT * FROM msg WHERE peer = :p AND id = :id") suspend fun msg(p: String, id: String): Msg?
    @Query("SELECT * FROM msg WHERE peer = :p AND scheduledAt = 0 ORDER BY ts DESC LIMIT 1") suspend fun lastMsg(p: String): Msg?
    @Query("SELECT * FROM msg WHERE peer = :p AND scheduledAt = 0 ORDER BY ts DESC LIMIT 1") fun lastMsgFlow(p: String): Flow<Msg?>
    @Query("SELECT * FROM msg WHERE text LIKE '%' || :q || '%' AND type != 'system' ORDER BY ts DESC LIMIT 200") fun search(q: String): Flow<List<Msg>>
    @Query("SELECT * FROM msg WHERE peer = :p AND (type IN ('photo','video','file','voice','vnote')) ORDER BY ts DESC") fun mediaFlow(p: String): Flow<List<Msg>>
    @Query("SELECT * FROM msg WHERE status = -2 AND scheduledAt <= :now") suspend fun dueScheduled(now: Long): List<Msg>
    @Query("SELECT * FROM msg WHERE expiresAt > 0 AND expiresAt <= :now") suspend fun expired(now: Long): List<Msg>
    @Query("SELECT * FROM msg WHERE out = 1 AND status = 0 AND type NOT IN ('loc','contact') AND media IS NOT NULL AND media NOT LIKE '%\"blob\"%'") suspend fun pendingUploads(): List<Msg>
    @Query("SELECT * FROM msg WHERE media IS NOT NULL AND media LIKE '%\"path\"%'") suspend fun withFiles(): List<Msg>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMsg(m: Msg): Long
    @Upsert suspend fun putMsg(m: Msg)
    @Query("DELETE FROM msg WHERE peer = :p AND id = :id") suspend fun deleteMsg(p: String, id: String)
    @Query("DELETE FROM msg WHERE peer = :p") suspend fun clearChat(p: String)
    @Query("UPDATE msg SET status = :s WHERE peer = :p AND id = :id AND out = 1 AND status >= 0 AND status < :s") suspend fun raiseStatus(p: String, id: String, s: Int)
    @Query("UPDATE msg SET status = 3 WHERE peer = :p AND out = 1 AND status IN (1,2) AND ts <= :upTo") suspend fun markReadUpTo(p: String, upTo: Long)
    @Query("SELECT * FROM msg WHERE peer = :p AND out = 0 AND status = 0") suspend fun unreadIncoming(p: String): List<Msg>
    @Query("UPDATE msg SET status = 3 WHERE peer = :p AND out = 0 AND status = 0") suspend fun markIncomingRead(p: String)
    @Query("SELECT * FROM msg WHERE peer = :p AND pinned = 1 ORDER BY ts DESC") fun pinnedFlow(p: String): Flow<List<Msg>>

    // olm sessions
    @Query("SELECT * FROM olm_session WHERE peer = :p ORDER BY updated DESC") suspend fun sessions(p: String): List<OlmSess>
    @Upsert suspend fun putSession(s: OlmSess): Long
    @Query("DELETE FROM olm_session WHERE peer = :p") suspend fun dropSessions(p: String)
    @Query("DELETE FROM olm_session WHERE peer = :p AND rid NOT IN (SELECT rid FROM olm_session WHERE peer = :p ORDER BY updated DESC LIMIT 3)") suspend fun pruneSessions(p: String)

    // outbox
    @Query("SELECT * FROM outbox ORDER BY rid") suspend fun outbox(): List<Out>
    @Insert suspend fun addOut(o: Out): Long
    @Query("DELETE FROM outbox WHERE wid = :wid") suspend fun doneOut(wid: String)
    @Query("SELECT * FROM outbox WHERE wid = :wid") suspend fun out(wid: String): Out?

    // dedupe
    @Query("SELECT COUNT(*) FROM seen WHERE `key` = :k") suspend fun seen(k: String): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addSeen(s: Seen)
    @Query("DELETE FROM seen WHERE ts < :before") suspend fun pruneSeen(before: Long)

    // stickers
    @Query("SELECT * FROM sticker ORDER BY added DESC") fun stickersFlow(): Flow<List<Sticker>>
    @Upsert suspend fun putSticker(s: Sticker)
    @Query("DELETE FROM sticker WHERE path = :p") suspend fun deleteSticker(p: String)
}

@Database(entities = [Contact::class, Chat::class, Msg::class, OlmSess::class, Out::class, Seen::class, Sticker::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun d(): D

    companion object {
        fun open(ctx: Context, user: String): AppDb =
            Room.databaseBuilder(ctx, AppDb::class.java, "acc_$user.db").build()
    }
}
