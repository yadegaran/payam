package ir.payam.messenger.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as ASettings
import androidx.core.content.FileProvider
import ir.payam.messenger.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** "v1.2.10" vs "1.2.9" → true. Non-numeric suffixes ("-dev") are ignored. */
fun isNewerVersion(latest: String, current: String): Boolean {
    fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val a = parts(latest); val b = parts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/**
 * Checks GitHub Releases of [BuildConfig.UPDATE_REPO] for a newer payam.apk, downloads it,
 * and only hands it to the system installer after two checks: SHA-256 matches the published
 * payam.apk.sha256, and the APK is signed by the same key as the installed app.
 */
object Updater {
    data class Release(val version: String, val apkUrl: String, val shaUrl: String?, val notes: String)

    val available = MutableStateFlow<Release?>(null)
    /** null = idle, 0..1 = downloading */
    val progress = MutableStateFlow<Float?>(null)
    val error = MutableStateFlow<String?>(null)

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    val enabled get() = BuildConfig.UPDATE_REPO.isNotBlank()

    /** Returns the newer release, or null. Automatic checks run at most once a day. */
    suspend fun check(force: Boolean = false): Release? = withContext(Dispatchers.IO) {
        if (!enabled || (!force && !Settings.autoUpdate)) return@withContext null
        val now = System.currentTimeMillis()
        if (!force && now - Settings.lastUpdateCheck < 24 * 3600_000L) return@withContext available.value
        try {
            val req = Request.Builder().url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
                .header("Accept", "application/vnd.github+json").build()
            val json = http.newCall(req).execute().use { if (it.isSuccessful) JSONObject(it.body.string()) else null }
                ?: return@withContext null
            Settings.lastUpdateCheck = now
            val tag = json.optString("tag_name")
            val assets = json.optJSONArray("assets") ?: return@withContext null
            var apk: String? = null; var sha: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                when (a.optString("name")) {
                    "payam.apk" -> apk = a.optString("browser_download_url")
                    "payam.apk.sha256" -> sha = a.optString("browser_download_url")
                }
            }
            val r = if (apk != null && isNewerVersion(tag, BuildConfig.VERSION_NAME))
                Release(tag.removePrefix("v"), apk, sha, json.optString("body")) else null
            available.value = r
            r
        } catch (e: Exception) {
            null
        }
    }

    /** Downloads and verifies the APK; returns it or null (with [error] set). */
    suspend fun download(ctx: Context, r: Release): File? = withContext(Dispatchers.IO) {
        error.value = null
        progress.value = 0f
        val dir = File(ctx.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "payam-${r.version}.apk")
        try {
            val expected = r.shaUrl?.let { url ->
                http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body.string().trim().take(64) else null }
            } ?: return@withContext fail(L("فایل تأیید (sha256) در دسترس نیست", "Checksum file unavailable"))
            val md = MessageDigest.getInstance("SHA-256")
            http.newCall(Request.Builder().url(r.apkUrl).build()).execute().use { res ->
                if (!res.isSuccessful) return@withContext fail(L("دانلود ناموفق بود", "Download failed"))
                val total = res.body.contentLength().takeIf { it > 0 }
                res.body.byteStream().use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); md.update(buf, 0, n); done += n
                            if (total != null) progress.value = done.toFloat() / total
                        }
                    }
                }
            }
            if (hex(md.digest()) != expected.lowercase()) return@withContext fail(L("فایل دانلودشده سالم نیست (SHA-256 نمی‌خواند)", "Downloaded file is corrupt (SHA-256 mismatch)"), file)
            if (!sameSigner(ctx, file)) return@withContext fail(L("امضای فایل با برنامه نصب‌شده یکی نیست؛ نصب نشد", "The update is not signed by the same key; not installed"), file)
            file
        } catch (e: Exception) {
            fail(L("دانلود ناموفق بود: ", "Download failed: ") + (e.message ?: ""), file)
        } finally {
            progress.value = null
        }
    }

    private fun fail(msg: String, file: File? = null): File? {
        file?.delete()
        error.value = msg
        return null
    }

    @Suppress("DEPRECATION")
    private fun signers(pm: PackageManager, pi: PackageInfo?): List<String> =
        if (Build.VERSION.SDK_INT >= 28) pi?.signingInfo?.apkContentsSigners?.map { hex(sha256(it.toByteArray())) } ?: emptyList()
        else pi?.signatures?.map { hex(sha256(it.toByteArray())) } ?: emptyList()

    @Suppress("DEPRECATION")
    private fun sameSigner(ctx: Context, apk: File): Boolean {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return false
        if (archive.packageName != ctx.packageName) return false
        val mine = signers(pm, pm.getPackageInfo(ctx.packageName, flags))
        val theirs = signers(pm, archive)
        return mine.isNotEmpty() && mine.toSet() == theirs.toSet()
    }

    /** Hands the verified APK to the system installer (asks for "install unknown apps" once). */
    fun install(activity: Activity, file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(Intent(ASettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName)))
            error.value = L("اجازه «نصب برنامه‌های ناشناس» را برای پیام بدهید و دوباره «نصب» را بزنید.",
                "Allow Payam to install unknown apps, then tap Install again.")
            return
        }
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".files", file)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }
}
