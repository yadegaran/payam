package ir.payam.messenger.core

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import ir.payam.messenger.App
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Global (not per-account) preferences. `rev` bumps on every write so Compose re-reads. */
object Settings {
    private val p get() = App.I.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val rev = mutableIntStateOf(0)

    private fun put(k: String, v: Any) {
        p.edit().apply {
            when (v) {
                is Int -> putInt(k, v)
                is Boolean -> putBoolean(k, v)
                is String -> putString(k, v)
                is Long -> putLong(k, v)
            }
        }.apply()
        rev.intValue++
    }

    var lang: String get() = p.getString("lang", "fa")!!; set(v) = put("lang", v)
    /** 0 system, 1 light, 2 dark */
    var theme: Int get() = p.getInt("theme", 0); set(v) = put("theme", v)
    /** "" default, "#RRGGBB" colour, or an image path */
    var wallpaper: String get() = p.getString("wallpaper", "")!!; set(v) = put("wallpaper", v)
    var pinHash: String get() = p.getString("pinHash", "")!!; set(v) = put("pinHash", v)
    var pinSalt: String get() = p.getString("pinSalt", "")!!; set(v) = put("pinSalt", v)
    var biometric: Boolean get() = p.getBoolean("bio", false); set(v) = put("bio", v)
    var lockAfterMin: Int get() = p.getInt("lockAfter", 1); set(v) = put("lockAfter", v)
    var secureScreen: Boolean get() = p.getBoolean("secure", true); set(v) = put("secure", v)
    var notifPreview: Boolean get() = p.getBoolean("notifPreview", false); set(v) = put("notifPreview", v)
    var linkPreview: Boolean get() = p.getBoolean("linkPreview", false); set(v) = put("linkPreview", v)
    var batteryAsked: Boolean get() = p.getBoolean("batteryAsked", false); set(v) = put("batteryAsked", v)

    /** kind: photo|video|voice|file — wifi=true for Wi-Fi, false for mobile data. */
    fun autoDl(kind: String, wifi: Boolean): Boolean =
        p.getBoolean("dl_${kind}_$wifi", kind in setOf("photo", "voice", "vnote", "sticker") || wifi)

    fun setAutoDl(kind: String, wifi: Boolean, v: Boolean) = put("dl_${kind}_$wifi", v)
}

fun L(fa: String, en: String) = if (Settings.lang == "en") en else fa

/** Formatting entity: [s, e) of type b|i|s|c|sp (bold, italic, strike, code, spoiler). */
data class Ent(val s: Int, val e: Int, val t: String)

object Md {
    private val MARKS = listOf("**" to "b", "__" to "i", "~~" to "s", "||" to "sp", "`" to "c")

    /** Telegram-style markdown in the input box → plain text + entities (no nesting). */
    fun parse(src: String): Pair<String, List<Ent>> {
        val out = StringBuilder()
        val ents = mutableListOf<Ent>()
        var i = 0
        loop@ while (i < src.length) {
            for ((m, t) in MARKS) {
                if (src.startsWith(m, i)) {
                    val close = src.indexOf(m, i + m.length)
                    if (close > i + m.length) {
                        val start = out.length
                        out.append(src, i + m.length, close)
                        ents += Ent(start, out.length, t)
                        i = close + m.length
                        continue@loop
                    }
                }
            }
            out.append(src[i]); i++
        }
        return out.toString() to ents
    }

    fun toJson(ents: List<Ent>): String? =
        if (ents.isEmpty()) null else JSONArray(ents.map { JSONArray(listOf(it.s, it.e, it.t)) }).toString()

    fun fromJson(s: String?): List<Ent> {
        if (s.isNullOrEmpty()) return emptyList()
        val a = JSONArray(s)
        return (0 until a.length()).mapNotNull { i ->
            val e = a.optJSONArray(i) ?: return@mapNotNull null
            Ent(e.optInt(0), e.optInt(1), e.optString(2))
        }
    }

    val URL = Regex("""(https?://|www\.)[^\s<>"]+""", RegexOption.IGNORE_CASE)
}

object Fmt {
    fun time(ts: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ts))

    fun day(ts: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        val now = Calendar.getInstance()
        return when {
            c.get(Calendar.YEAR) == now.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) -> L("امروز", "Today")
            c.get(Calendar.YEAR) == now.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) - 1 -> L("دیروز", "Yesterday")
            else -> SimpleDateFormat("yyyy/MM/dd", Locale.US).format(Date(ts))
        }
    }

    fun listTime(ts: Long): String =
        if (System.currentTimeMillis() - ts < 20 * 3600_000L && day(ts) == L("امروز", "Today")) time(ts)
        else SimpleDateFormat("MM/dd", Locale.US).format(Date(ts))

    fun size(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(Locale.US, b / 1024.0)
        else -> "%.1f MB".format(Locale.US, b / 1048576.0)
    }

    fun dur(sec: Int) = "%d:%02d".format(Locale.US, sec / 60, sec % 60)

    fun lastSeen(online: Boolean?, ts: Long?, hidden: Boolean): String = when {
        hidden -> L("اخیراً آنلاین بوده", "last seen recently")
        online == true -> L("آنلاین", "online")
        ts != null -> L("آخرین بازدید ", "last seen ") + day(ts) + " " + time(ts)
        else -> L("آخرین بازدید خیلی وقت پیش", "last seen a long time ago")
    }

    fun ttl(sec: Int): String = when {
        sec <= 0 -> L("خاموش", "Off")
        sec < 60 -> L("$sec ثانیه", "$sec s")
        sec < 3600 -> L("${sec / 60} دقیقه", "${sec / 60} min")
        sec < 86400 -> L("${sec / 3600} ساعت", "${sec / 3600} h")
        else -> L("${sec / 86400} روز", "${sec / 86400} d")
    }
}
