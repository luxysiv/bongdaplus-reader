package vn.bongdaplus.reader.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Client mạng chạy bằng core WebView thay vì HttpURLConnection/Jsoup thuần:
 * - Dùng đúng phiên đăng nhập trong máy (cookie HttpOnly/Secure/third-party đầy đủ).
 * - Trang đã chạy JS xong (objectid, emotion, comment render sẵn).
 * - Qua được lớp kiểm tra bot chặn client lạ.
 * Kết quả trả về là HTML/text thô, parse + hiển thị vẫn native như cũ.
 */
object WebFetcher {
    private var webView: WebView? = null
    private val mutex = Mutex()
    private const val TIMEOUT_MS = 25000L

    /** Gọi 1 lần lúc app khởi động (bắt buộc trên main thread). Idempotent. */
    @SuppressLint("SetJavaScriptEnabled")
    fun init(ctx: Context) {
        if (webView != null) return
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) return
            val wv = WebView(ctx.applicationContext)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.mediaPlaybackRequiresUserGesture = true
            wv.settings.loadsImagesAutomatically = false
            wv.settings.blockNetworkImage = true
            wv.webViewClient = WebViewClient()
            webView = wv
        } catch (_: Exception) { webView = null }
    }

    fun available(): Boolean = webView != null

    /** GET trang, đợi JS render xong rồi trả outerHTML. Null = thất bại/timeout. */
    suspend fun getHtml(url: String, settleMs: Long = 1200): String? {
        val wv = webView ?: return null
        return mutex.withLock {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    var done = false
                    fun finish(v: String?) { if (!done) { done = true; cont.resume(v) } }
                    val handler = Handler(Looper.getMainLooper())
                    val timeout = Runnable { try { wv.stopLoading() } catch (_: Exception) { }; finish(null) }
                    handler.postDelayed(timeout, TIMEOUT_MS)
                    try {
                        wv.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, u: String) {
                                handler.postDelayed({
                                    try {
                                        view.evaluateJavascript("document.documentElement.outerHTML") { html ->
                                            handler.removeCallbacks(timeout)
                                            finish(html.unescapeJsString())
                                        }
                                    } catch (_: Exception) {
                                        handler.removeCallbacks(timeout); finish(null)
                                    }
                                }, settleMs)
                            }
                        }
                        wv.loadUrl(url)
                    } catch (_: Exception) { handler.removeCallbacks(timeout); finish(null) }
                    cont.invokeOnCancellation {
                        handler.removeCallbacks(timeout)
                        try { wv.stopLoading() } catch (_: Exception) { }
                    }
                }
            }
        }
    }

    /**
     * POST form qua fetch() của trang pageUrl (cùng origin nên cookie phiên
     * đính kèm đầy đủ). Trả response text (cắt 8KB), null khi lỗi/timeout.
     */
    suspend fun postForm(postUrl: String, pageUrl: String, params: Map<String, String>): String? {
        val wv = webView ?: return null
        return mutex.withLock {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    var done = false
                    fun finish(v: String?) { if (!done) { done = true; cont.resume(v) } }
                    val handler = Handler(Looper.getMainLooper())
                    val timeout = Runnable { try { wv.stopLoading() } catch (_: Exception) { }; finish(null) }
                    handler.postDelayed(timeout, TIMEOUT_MS)
                    val bridge = PostBridge { t ->
                        handler.removeCallbacks(timeout)
                        finish(t?.take(8000))
                    }
                    try {
                        try { wv.removeJavascriptInterface("AndroidPost") } catch (_: Exception) { }
                        wv.addJavascriptInterface(bridge, "AndroidPost")
                        wv.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, u: String) {
                                try {
                                    val q = params.entries.joinToString(";") { (k, v) ->
                                        "p.append(${JSONObject.quote(k)},${JSONObject.quote(v)})"
                                    }
                                    view.evaluateJavascript(
                                        "(function(){try{var p=new URLSearchParams();$q;" +
                                            "fetch(${JSONObject.quote(postUrl)}," +
                                            "{method:'POST'," +
                                            "headers:{'X-Requested-With':'XMLHttpRequest'}," +
                                            "body:p,credentials:'same-origin'})" +
                                            ".then(function(r){return r.text().then(function(t){AndroidPost.onResult(t);});})" +
                                            ".catch(function(e){AndroidPost.onError(''+e);});" +
                                            "}catch(e){AndroidPost.onError(''+e);}})()"
                                    ) { _ -> }
                                } catch (_: Exception) {
                                    handler.removeCallbacks(timeout); finish(null)
                                }
                            }
                        }
                        wv.loadUrl(pageUrl)
                    } catch (_: Exception) { handler.removeCallbacks(timeout); finish(null) }
                    cont.invokeOnCancellation {
                        handler.removeCallbacks(timeout)
                        try { wv.stopLoading() } catch (_: Exception) { }
                    }
                }
            }
        }
    }

    private class PostBridge(val onDone: (String?) -> Unit) {
        @JavascriptInterface
        fun onResult(t: String?) { onDone(t) }

        @JavascriptInterface
        fun onError(e: String?) { onDone(null) }
    }

    /** Chuỗi JS trả về dạng JSON-encoded: bỏ ngoặc, giải escape. */
    private fun String?.unescapeJsString(): String? {
        var s = this?.trim() ?: return null
        if (s.isEmpty() || s == "null") return null
        s = s.removeSurrounding("\"")
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        val hex = s.substring(i + 2, minOf(i + 6, s.length))
                        sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                        i += 4
                    }
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }
}
