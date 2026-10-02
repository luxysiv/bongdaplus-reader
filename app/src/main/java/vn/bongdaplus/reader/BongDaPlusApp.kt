package vn.bongdaplus.reader

import android.app.Application
import android.webkit.CookieManager
import vn.bongdaplus.reader.notify.NotifyHelper

class BongDaPlusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CookieManager.getInstance().setAcceptCookie(true)
        NotifyHelper.ensureChannel(this)
        NotifyHelper.schedule(this)
    }
}
