package vn.bongdaplus.reader

import android.app.Application
import android.webkit.CookieManager
import vn.bongdaplus.reader.data.WebFetcher
import vn.bongdaplus.reader.notify.NotifyHelper

class BongDaPlusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CookieManager.getInstance().setAcceptCookie(true)
        // Core WebView làm client mạng (phiên thật) — hiển thị vẫn native
        try { WebFetcher.init(this) } catch (_: Exception) { }
        NotifyHelper.ensureChannel(this)
        NotifyHelper.schedule(this)
    }
}
