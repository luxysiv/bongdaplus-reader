package vn.bongdaplus.reader.data

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * HTTP transport bằng OkHttp (thay transport WebView trước đây).
 * - CookieJar đọc xuyên từ CookieManager của WebView login (phiên thật) và
 *   ghi ngược Set-Cookie về, nên WebView login và client luôn cùng 1 phiên.
 * - Nhận diện kết quả bằng URL CUỐI sau redirect (server đá về Account/Login
 *   khi chưa login) thay vì đoán nội dung body — chính xác, không báo sai.
 */
object Http {
    const val UA =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36 BongDaPlusReader/1.0"

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            try {
                val cm = CookieManager.getInstance()
                cookies.forEach { c ->
                    try { cm.setCookie(url.toString(), c.toString()) } catch (_: Exception) { }
                }
                try { cm.flush() } catch (_: Exception) { }
            } catch (_: Exception) { }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            try {
                val raw = CookieManager.getInstance().getCookie(url.toString())
                    ?.takeIf { it.isNotBlank() } ?: return emptyList()
                return raw.split(";").mapNotNull { part ->
                    val kv = part.trim().split("=", limit = 2)
                    if (kv.size != 2 || kv[0].isBlank()) return@mapNotNull null
                    Cookie.parse(url, kv[0].trim() + "=" + kv[1].trim())
                }
            } catch (_: Exception) { return emptyList() }
        }
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** Kết quả GET trang */
    data class Page(val finalUrl: String, val code: Int, val html: String) {
        /** true khi server đá về trang đăng nhập */
        fun bouncedToLogin(): Boolean =
            finalUrl.contains("Account/Login", ignoreCase = true) ||
                finalUrl.contains("Account/Register", ignoreCase = true)
    }

    suspend fun get(url: String, referer: String? = null): Page? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "vi-VN,vi;q=0.9")
                .apply { referer?.let { header("Referer", it) } }
                .build()
            client.newCall(req).execute().use { res ->
                Page(res.request.url.toString(), res.code, res.body?.string().orEmpty())
            }
        } catch (_: Exception) { null }
    }

    /** Kết quả POST form */
    data class PostResult(val finalUrl: String, val code: Int, val body: String) {
        fun bouncedToLogin(): Boolean =
            finalUrl.contains("Account/Login", ignoreCase = true) ||
                (code == 401 || code == 403)
    }

    suspend fun postForm(
        url: String, params: Map<String, String>, referer: String,
    ): PostResult? = withContext(Dispatchers.IO) {
        try {
            val form = FormBody.Builder()
            params.forEach { (k, v) -> form.add(k, v) }
            val req = Request.Builder().url(url).post(form.build())
                .header("User-Agent", UA)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Origin", "https://bongdaplus.vn")
                .header("Referer", referer)
                .build()
            client.newCall(req).execute().use { res ->
                PostResult(res.request.url.toString(), res.code, res.body?.string().orEmpty())
            }
        } catch (_: Exception) { null }
    }

    /** GET rồi parse Jsoup. Null khi mạng lỗi hoặc bị đá về login. */
    suspend fun getDoc(url: String): org.jsoup.nodes.Document? {
        val p = get(url) ?: return null
        if (p.bouncedToLogin() || p.code != 200 || p.html.isBlank()) return null
        return try { Jsoup.parse(p.html, url) } catch (_: Exception) { null }
    }
}
