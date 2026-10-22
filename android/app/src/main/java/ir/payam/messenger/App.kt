package ir.payam.messenger

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import ir.payam.messenger.core.Accounts
import ir.payam.messenger.svc.Notif

class App : Application(), SingletonImageLoader.Factory {
    companion object { lateinit var I: App }

    val accounts by lazy { Accounts(this) }

    override fun onCreate() {
        super.onCreate()
        I = this
        Notif.channels(this)
    }

    // Coil only ever loads local files (decrypted media, verified avatars).
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context).components {
            if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
        }.build()
}
