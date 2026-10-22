package ir.payam.messenger.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

/**
 * Decides what may be sent or saved. Runs on BOTH sides (a modified sender app
 * can't bypass the receiver). Allowlist by real content (magic bytes); the file
 * extension must agree with the content, so "photo.jpg" that is really an EXE,
 * an APK renamed to .zip, or a macro document is refused. Received files are
 * never auto-opened.
 */
object FileGuard {
    data class Verdict(val ok: Boolean, val mime: String = "", val name: String = "", val reason: String = "")

    private val BLOCKED_INSIDE_ZIP = setOf(
        "apk", "apks", "xapk", "aab", "dex", "jar", "exe", "dll", "msi", "bat", "cmd", "com", "scr", "pif", "cpl",
        "ps1", "psm1", "vbs", "vbe", "js", "jse", "wsf", "wsh", "hta", "lnk", "sh", "bash", "py", "pl", "rb", "php",
        "elf", "so", "bin", "run", "app", "dmg", "pkg", "deb", "rpm", "iso", "img", "docm", "xlsm", "pptm", "dotm",
        "xltm", "potm", "reg", "inf", "html", "htm", "svg", "url", "desktop", "jnlp", "swf", "msc", "ws", "mht",
        // archives inside archives can't be inspected without extracting
        "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "cab",
    )

    private val ISO_MEDIA = mapOf(
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime",
        "3gp" to "video/3gpp", "3g2" to "video/3gpp2", "m4a" to "audio/mp4",
    )

    /** Strips path parts, control and bidi-override chars ("gpj.exe" spoofing). */
    fun cleanName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.filter { ch ->
            ch.code >= 0x20 && ch != '\u007f' && ch !in "‎‏‪‫‬‭‮⁦⁧⁨⁩؜" &&
                ch !in "<>:\"|?*"
        }.trim().trimStart('.')
        return cleaned.take(120).ifEmpty { "file" }
    }

    fun check(file: File, rawName: String): Verdict {
        val name = cleanName(rawName)
        val ext = name.substringAfterLast('.', "").lowercase()
        val h = ByteArray(64)
        val n = file.inputStream().use { input -> var o = 0; while (o < 64) { val r = input.read(h, o, 64 - o); if (r < 0) break; o += r }; o }
        fun at(off: Int, vararg b: Int) = n >= off + b.size && b.indices.all { (h[off + it].toInt() and 0xff) == b[it] }
        fun ascii(off: Int, s: String) = at(off, *s.map { it.code }.toIntArray())
        fun need(exts: Set<String>, mime: String) =
            if (ext in exts) Verdict(true, mime, name) else Verdict(false, reason = "پسوند فایل با محتوای آن یکی نیست")

        return when {
            at(0, 0xff, 0xd8, 0xff) -> need(setOf("jpg", "jpeg"), "image/jpeg")
            at(0, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> need(setOf("png"), "image/png")
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> need(setOf("gif"), "image/gif")
            ascii(0, "RIFF") && ascii(8, "WEBP") -> need(setOf("webp"), "image/webp")
            ascii(4, "ftyp") -> ISO_MEDIA[ext]?.let { Verdict(true, it, name) }
                ?: Verdict(false, reason = "پسوند فایل با محتوای آن یکی نیست")
            at(0, 0x1a, 0x45, 0xdf, 0xa3) -> when (ext) {
                "webm" -> Verdict(true, "video/webm", name)
                "mkv" -> Verdict(true, "video/x-matroska", name)
                else -> Verdict(false, reason = "پسوند فایل با محتوای آن یکی نیست")
            }
            ascii(0, "OggS") -> need(setOf("ogg", "oga", "opus"), "audio/ogg")
            ascii(0, "ID3") || (n >= 2 && (h[0].toInt() and 0xff) == 0xff && (h[1].toInt() and 0xe0) == 0xe0) ->
                need(setOf("mp3"), "audio/mpeg")
            ascii(0, "%PDF-") -> need(setOf("pdf"), "application/pdf")
            at(0, 0x50, 0x4b, 0x03, 0x04) -> checkZip(file, name, ext)
            ext == "txt" && isPlainText(file) -> Verdict(true, "text/plain", name)
            else -> Verdict(false, reason = "این نوع فایل مجاز نیست")
        }
    }

    private fun checkZip(file: File, name: String, ext: String): Verdict {
        val entries = try {
            ZipFile(file).use { z -> z.entries().asSequence().map { it.name }.toList() }
        } catch (e: Exception) {
            return Verdict(false, reason = "فایل فشرده خراب است")
        }
        val lower = entries.map { it.lowercase() }
        if (lower.any { it == "classes.dex" || it == "androidmanifest.xml" || it == "meta-inf/manifest.mf" }) {
            return Verdict(false, reason = "فایل برنامه (APK/JAR) مجاز نیست")
        }
        if ("[content_types].xml" in lower) {
            if (lower.any { it.endsWith("vbaproject.bin") || it.endsWith(".bin") && "activex" in it }) {
                return Verdict(false, reason = "سند دارای ماکرو مجاز نیست")
            }
            val (kind, mime) = when {
                lower.any { it.startsWith("word/") } -> "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                lower.any { it.startsWith("xl/") } -> "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                lower.any { it.startsWith("ppt/") } -> "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                else -> return Verdict(false, reason = "این نوع فایل مجاز نیست")
            }
            return if (ext == kind) Verdict(true, mime, name) else Verdict(false, reason = "پسوند فایل با محتوای آن یکی نیست")
        }
        if (ext != "zip") return Verdict(false, reason = "پسوند فایل با محتوای آن یکی نیست")
        val bad = lower.firstOrNull { it.substringAfterLast('/').substringAfterLast('.', "") in BLOCKED_INSIDE_ZIP }
        return if (bad == null) Verdict(true, "application/zip", name)
        else Verdict(false, reason = "فایل فشرده شامل فایل غیرمجاز است: ${bad.substringAfterLast('/')}")
    }

    private fun isPlainText(file: File): Boolean {
        val bytes = file.inputStream().use { input -> val b = ByteArray(65536); val n = input.read(b); if (n <= 0) ByteArray(0) else b.copyOf(n) }
        if (bytes.any { it == 0.toByte() }) return false
        // endOfInput=false when we only read a prefix: a multibyte char cut at the boundary isn't an error.
        val res = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes), CharBuffer.allocate(bytes.size), file.length() <= bytes.size)
        return !res.isError
    }
}
