package ir.payam.messenger.svc

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ir.payam.messenger.App
import ir.payam.messenger.R
import ir.payam.messenger.core.Acc
import ir.payam.messenger.core.L
import ir.payam.messenger.core.Settings
import ir.payam.messenger.data.Contact
import ir.payam.messenger.data.Msg
import ir.payam.messenger.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object Notif {
    private const val CH_MSG = "messages"
    private const val CH_SVC = "service"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_MSG, L("پیام‌ها", "Messages"), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(
            NotificationChannel(CH_SVC, L("اتصال پس‌زمینه", "Background connection"), NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) }
        )
    }

    private fun id(acc: Acc, peer: String) = (acc.user + peer).hashCode()

    fun preview(m: Msg): String = when (m.type) {
        "text" -> m.text
        "photo" -> "🖼 " + L("عکس", "Photo")
        "video" -> "🎬 " + L("ویدیو", "Video")
        "vnote" -> "⏺ " + L("پیام ویدیویی", "Video message")
        "voice" -> "🎤 " + L("پیام صوتی", "Voice message")
        "file" -> "📎 " + L("فایل", "File")
        "sticker" -> L("استیکر", "Sticker")
        "loc" -> "📍 " + L("موقعیت مکانی", "Location")
        "contact" -> "👤 " + L("مخاطب", "Contact")
        else -> m.text
    }.let { if (m.text.isNotEmpty() && m.type != "text") "$it ${m.text}" else it }

    fun message(acc: Acc, c: Contact, m: Msg) {
        val ctx = acc.ctx
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val multi = App.I.accounts.list.value.size > 1
        val title = c.display + if (multi) " • " + acc.name.ifEmpty { acc.user } else ""
        val text = if (Settings.notifPreview) preview(m).take(200) else L("پیام جدید", "New message")
        val intent = Intent(ctx, MainActivity::class.java)
            .putExtra("acc", acc.user).putExtra("peer", c.user)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, id(acc, c.user), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CH_MSG)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(ctx, CH_MSG).setSmallIcon(R.drawable.ic_notif)
                    .setContentTitle(L("پیام جدید", "New message")).build()
            )
            .build()
        NotificationManagerCompat.from(ctx).notify(id(acc, c.user), n)
    }

    fun clear(acc: Acc, peer: String) = NotificationManagerCompat.from(acc.ctx).cancel(id(acc, peer))

    fun service(ctx: Context) = NotificationCompat.Builder(ctx, CH_SVC)
        .setSmallIcon(R.drawable.ic_notif)
        .setContentTitle(L("پیام‌رسان آماده دریافت پیام است", "Ready to receive messages"))
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        )
        .build()
}

/**
 * Keeps each account's WebSocket open so messages arrive while the app is closed
 * (no Google push in Iran). Also runs scheduled and disappearing messages.
 */
class SocketService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var netCb: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, 1, Notif.service(this), type)
        val cm = getSystemService(ConnectivityManager::class.java)
        netCb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = App.I.accounts.all().forEach { it.conn.nudge() }
        }.also { cm.registerDefaultNetworkCallback(it) }
        scope.launch {
            while (isActive) {
                App.I.accounts.all().forEach { runCatching { it.engine.tick() } }
                delay(15_000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val accs = App.I.accounts.all()
        if (accs.isEmpty()) { stopSelf(); return START_NOT_STICKY }
        accs.forEach { it.start() }
        return START_STICKY
    }

    override fun onDestroy() {
        netCb?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, SocketService::class.java)) }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (App.I.accounts.list.value.isNotEmpty()) SocketService.start(ctx)
        }
    }
}
