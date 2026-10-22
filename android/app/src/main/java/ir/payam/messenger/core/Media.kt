package ir.payam.messenger.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

object Media {
    fun displayName(ctx: Context, uri: Uri): String =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"

    /** Copies a picked file into the account's media folder under a cleaned name. */
    fun import(acc: Acc, uri: Uri, name: String = displayName(acc.ctx, uri)): File {
        val clean = FileGuard.cleanName(name)
        val dst = File(acc.mediaDir, Ids.newId() + "_" + clean)
        acc.ctx.contentResolver.openInputStream(uri)!!.use { input -> dst.outputStream().use { input.copyTo(it) } }
        return dst
    }

    private fun decode(ctx: Context, uri: Uri, maxSide: Int): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
                val s = info.size
                val scale = maxSide.toFloat() / max(s.width, s.height)
                if (scale < 1f) dec.setTargetSize((s.width * scale).roundToInt(), (s.height * scale).roundToInt())
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }!!
        return scaleDown(bmp, maxSide)
    }

    private fun scaleDown(b: Bitmap, maxSide: Int): Bitmap {
        val scale = maxSide.toFloat() / max(b.width, b.height)
        return if (scale >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * scale).roundToInt().coerceAtLeast(1), (b.height * scale).roundToInt().coerceAtLeast(1), true)
    }

    private fun jpeg(b: Bitmap, q: Int) = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()

    /** Tiny blurred-looking preview that rides inside the encrypted message. */
    fun thumb(b: Bitmap, side: Int = 40): String = b64(jpeg(scaleDown(b, side), 50))

    /** Re-encodes a photo to JPEG ≤1600px. Drops EXIF (GPS location!) as a side effect. GIFs stay as-is. */
    fun preparePhoto(acc: Acc, uri: Uri): Pair<File, JSONObject> {
        val name = displayName(acc.ctx, uri)
        val mime = acc.ctx.contentResolver.getType(uri) ?: ""
        if (mime == "image/gif" || name.lowercase().endsWith(".gif")) {
            val f = import(acc, uri, name.substringBeforeLast('.') + ".gif")
            val b = BitmapFactory.decodeFile(f.path)
            val meta = JSONObject().put("mime", "image/gif").put("name", "animation.gif")
            if (b != null) meta.put("w", b.width).put("h", b.height).put("thumb", thumb(b))
            return f to meta
        }
        val b = decode(acc.ctx, uri, 1600)
        val f = File(acc.mediaDir, Ids.newId() + ".jpg")
        f.writeBytes(jpeg(b, 85))
        return f to JSONObject().put("mime", "image/jpeg").put("name", "photo.jpg").put("w", b.width).put("h", b.height).put("thumb", thumb(b))
    }

    fun videoMeta(f: File, name: String): JSONObject {
        val meta = JSONObject().put("name", name)
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.path)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val dur = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) / 1000
            meta.put("w", if (rot % 180 == 0) w else h).put("h", if (rot % 180 == 0) h else w).put("dur", dur)
            r.frameAtTime?.let { meta.put("thumb", b64(jpeg(scaleDown(it, 320), 60))) }
        } catch (e: Exception) {
        } finally {
            r.release()
        }
        return meta
    }

    /** Square, 640px, ≤200 KB JPEG for profile photos (server limit). */
    fun prepareAvatar(ctx: Context, uri: Uri, dst: File) {
        val b = decode(ctx, uri, 1280)
        val side = minOf(b.width, b.height)
        val sq = Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
        val out = scaleDown(sq, 640)
        var q = 85
        var bytes = jpeg(out, q)
        while (bytes.size > 190_000 && q > 30) { q -= 10; bytes = jpeg(out, q) }
        dst.writeBytes(bytes)
    }

    /** Sticker = 512px WebP with transparency kept. */
    fun makeSticker(acc: Acc, uri: Uri): File {
        val b = decode(acc.ctx, uri, 512)
        val dir = File(acc.dir, "stickers").apply { mkdirs() }
        val f = File(dir, Ids.newId() + ".webp")
        f.outputStream().use {
            @Suppress("DEPRECATION")
            b.compress(if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP, 90, it)
        }
        return f
    }

    // ---------------------------------------------------------------- link preview (opt-in)

    private val plainHttp by lazy { OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).followRedirects(true).build() }
    private fun meta(html: String, prop: String): String? =
        Regex("""<meta[^>]+(?:property|name)=["']$prop["'][^>]*content=["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: Regex("""<meta[^>]+content=["']([^"']*)["'][^>]*(?:property|name)=["']$prop["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)

    /** Fetched by the SENDER (reveals the sender's IP to that site — hence off by default). */
    fun linkPreview(acc: Acc, text: String): JSONObject? = try {
        val url = Md.URL.find(text)?.value?.let { if (it.startsWith("www.", true)) "https://$it" else it }
        if (url == null || !url.startsWith("https://")) null
        else plainHttp.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()).execute().use { res ->
            if (!res.isSuccessful || res.body.contentType()?.subtype != "html") return@use null
            val html = String(res.body.source().let { src -> src.request(262_144); src.buffer.readByteArray(minOf(src.buffer.size, 262_144L)) })
            val title = meta(html, "og:title") ?: Regex("<title>([^<]*)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            title ?: return@use null
            val lp = JSONObject().put("url", url).put("title", unescape(title).take(200))
            meta(html, "og:description")?.let { lp.put("desc", unescape(it).take(300)) }
            meta(html, "og:image")?.takeIf { it.startsWith("https://") }?.let { img ->
                runCatching {
                    plainHttp.newCall(Request.Builder().url(img).build()).execute().use { r ->
                        if (r.isSuccessful && (r.body.contentLength() in 1..2_000_000)) {
                            BitmapFactory.decodeStream(r.body.byteStream())?.let { lp.put("img", b64(jpeg(scaleDown(it, 320), 60))) }
                        }
                    }
                }
            }
            lp
        }
    } catch (e: Exception) { null }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
}

/** Voice messages: AAC in MP4 (.m4a) + 48-bar waveform sampled while recording. */
class VoiceRecorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var started = 0L
    private val samples = mutableListOf<Int>()
    private var job: Job? = null
    val level = MutableStateFlow(0f)
    val elapsed = MutableStateFlow(0)

    fun start(dst: File) {
        file = dst
        samples.clear()
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(48_000)
        r.setAudioSamplingRate(44_100)
        r.setOutputFile(dst.path)
        r.prepare()
        r.start()
        rec = r
        started = System.currentTimeMillis()
        job = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                val a = runCatching { rec?.maxAmplitude ?: 0 }.getOrDefault(0)
                samples += a
                level.value = (a / 32767f).coerceIn(0f, 1f)
                elapsed.value = ((System.currentTimeMillis() - started) / 1000).toInt()
                delay(100)
            }
        }
    }

    /** Returns (file, meta) or null when cancelled / too short. */
    fun stop(cancel: Boolean = false): Pair<File, JSONObject>? {
        job?.cancel()
        val f = file
        val dur = ((System.currentTimeMillis() - started) / 1000).toInt()
        runCatching { rec?.stop() }
        rec?.release()
        rec = null
        if (cancel || f == null || System.currentTimeMillis() - started < 700) { f?.delete(); return null }
        val bars = 48
        val wave = ByteArray(bars) { i ->
            val chunk = samples.filterIndexed { idx, _ -> idx * bars / max(1, samples.size) == i }
            ((chunk.maxOrNull() ?: 0) * 31 / 32767).coerceIn(0, 31).toByte()
        }
        return f to JSONObject().put("mime", "audio/mp4").put("name", "voice.m4a").put("dur", max(1, dur)).put("wave", b64(wave))
    }
}

/** One audio player for the whole app (voice messages and audio files). */
object Player {
    private var mp: MediaPlayer? = null
    private var job: Job? = null
    val playing = MutableStateFlow<String?>(null)
    val progress = MutableStateFlow(0f)
    val speed = MutableStateFlow(1f)

    fun toggle(id: String, path: String) {
        if (playing.value == id) { stop(); return }
        stop()
        val p = MediaPlayer()
        try {
            p.setDataSource(path)
            p.prepare()
            if (Build.VERSION.SDK_INT >= 23) p.playbackParams = p.playbackParams.setSpeed(speed.value)
            p.setOnCompletionListener { stop() }
            p.start()
        } catch (e: Exception) { p.release(); return }
        mp = p
        playing.value = id
        job = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                mp?.let { if (it.duration > 0) progress.value = it.currentPosition.toFloat() / it.duration }
                delay(100)
            }
        }
    }

    fun cycleSpeed() {
        speed.value = when (speed.value) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
        mp?.let { runCatching { it.playbackParams = it.playbackParams.setSpeed(speed.value) } }
    }

    fun stop() {
        job?.cancel()
        mp?.release()
        mp = null
        playing.value = null
        progress.value = 0f
    }
}
