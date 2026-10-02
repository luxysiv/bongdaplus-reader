package vn.bongdaplus.reader.data

import android.content.Context
import android.webkit.CookieManager
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Quản lý đăng nhập tài khoản BongdaPlus (member.bongdaplus.vn).
 * Login thực hiện qua WebView (hỗ trợ Email + Google + Apple của site),
 * app đọc cookie session để giữ trạng thái + gửi kèm khi scrape bài Premium.
 */
class AuthManager(private val ctx: Context) {
    companion object {
        val KEY_LOGGED = booleanPreferencesKey("logged_in")
        val KEY_EMAIL = stringPreferencesKey("email")
        val KEY_FOLLOW = stringSetPreferencesKey("follow_slugs")
        val KEY_LAST_IDS = stringPreferencesKey("last_seen_ids")
        val KEY_NOTIFY = booleanPreferencesKey("notify_enabled")
        val KEY_NOTIFY_COMMENTS = booleanPreferencesKey("notify_comments")
        val KEY_CMT_COUNTS = stringPreferencesKey("comment_counts_json")
        val KEY_SEEN_NOTIFS = stringPreferencesKey("seen_notif_keys")
        const val LOGIN_URL = "https://member.bongdaplus.vn/Identity/Account/Login?returnUrl=%2F"
        const val REGISTER_URL = "https://member.bongdaplus.vn/Identity/Account/Register?returnUrl=%2F"
        const val HOME = "https://bongdaplus.vn/"
        /** URL handshake SSO: mở top-level, member server tự đẩy token về bongdaplus.vn */
        const val SSO_LOGIN_URL =
            "https://member.bongdaplus.vn/Identity/Account/Login?ReturnUrl=%2FHome%2FLoginFromBongdaplus"
    }

    val loggedIn: Flow<Boolean> = ctx.appPrefs.data.map { it[KEY_LOGGED] == true }
    val email: Flow<String> = ctx.appPrefs.data.map { it[KEY_EMAIL] ?: "" }
    val followSlugs: Flow<Set<String>> = ctx.appPrefs.data.map {
        it[KEY_FOLLOW] ?: setOf("tin-moi", "bong-da-viet-nam", "ngoai-hang-anh")
    }
    val notifyEnabled: Flow<Boolean> = ctx.appPrefs.data.map { it[KEY_NOTIFY] ?: true }
    val notifyComments: Flow<Boolean> = ctx.appPrefs.data.map { it[KEY_NOTIFY_COMMENTS] ?: true }

    suspend fun setNotify(v: Boolean) { ctx.appPrefs.edit { it[KEY_NOTIFY] = v } }
    suspend fun setNotifyComments(v: Boolean) { ctx.appPrefs.edit { it[KEY_NOTIFY_COMMENTS] = v } }
    suspend fun setFollow(slugs: Set<String>) { ctx.appPrefs.edit { it[KEY_FOLLOW] = slugs } }

    /** Gọi sau khi WebView báo login thành công */
    suspend fun markLoggedIn(emailGuess: String = "") {
        ctx.appPrefs.edit {
            it[KEY_LOGGED] = true
            if (emailGuess.isNotBlank()) it[KEY_EMAIL] = emailGuess
        }
    }

    suspend fun logout() {
        ctx.appPrefs.edit {
            it[KEY_LOGGED] = false
            it[KEY_EMAIL] = ""
        }
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    suspend fun lastSeenIds(): Set<String> {
        val s = ctx.appPrefs.data.map { it[KEY_LAST_IDS] ?: "" }.first()
        return s.split(",").filter { it.isNotBlank() }.toSet()
    }
    suspend fun saveLastSeen(ids: Set<String>) {
        ctx.appPrefs.edit { it[KEY_LAST_IDS] = ids.take(30).joinToString(",") }
    }

    /** Đếm bình luận đã thấy: articleId -> count (JSON) */
    suspend fun commentCounts(): Map<String, Int> {
        val raw = ctx.appPrefs.data.map { it[KEY_CMT_COUNTS] ?: "{}" }.first()
        return try {
            val o = org.json.JSONObject(raw)
            o.keys().asSequence().associateWith { o.optInt(it) }
        } catch (_: Exception) { emptyMap() }
    }
    suspend fun saveCommentCounts(m: Map<String, Int>) {
        val o = org.json.JSONObject()
        m.entries.take(60).forEach { o.put(it.key, it.value) }
        ctx.appPrefs.edit { it[KEY_CMT_COUNTS] = o.toString() }
    }

    /** Key các thông báo member đã thấy (để Worker chỉ báo cái mới từ div#lstnoti) */
    suspend fun seenNotifKeys(): Set<String> {
        val s = ctx.appPrefs.data.map { it[KEY_SEEN_NOTIFS] ?: "" }.first()
        return s.split("|").filter { it.isNotBlank() }.toSet()
    }
    suspend fun saveSeenNotifKeys(keys: Set<String>) {
        ctx.appPrefs.edit { it[KEY_SEEN_NOTIFS] = keys.take(60).joinToString("|") }
    }

    /**
     * Cookie hiện tại để gắn vào Jsoup (đọc Premium + Dashboard member + div#lstnoti).
     * Cookie login nằm ở domain member.bongdaplus.vn nên PHẢI gộp cả 2 domain,
     * nếu không server trả trang chưa đăng nhập (lstnoti rỗng).
     */
    fun currentCookies(): Map<String, String> {
        return try {
            val cm = CookieManager.getInstance()
            val out = LinkedHashMap<String, String>()
            for (host in listOf("https://bongdaplus.vn", "https://member.bongdaplus.vn")) {
                try {
                    val raw = cm.getCookie(host) ?: continue
                    // getCookie trả về "k=v; k2=v2"
                    raw.split(";").forEach {
                        val kv = it.trim().split("=", limit = 2)
                        if (kv.size == 2 && kv[0].isNotBlank()) out[kv[0].trim()] = kv[1].trim()
                    }
                } catch (_: Exception) { }
            }
            out
        } catch (_: Exception) { emptyMap() }
    }

    /** Heuristic: đã có cookie Identity của member => coi như đã login */
    fun hasSessionCookie(): Boolean {
        return try {
            val cm = CookieManager.getInstance()
            val c1 = cm.getCookie("https://member.bongdaplus.vn") ?: ""
            val c2 = cm.getCookie("https://bongdaplus.vn") ?: ""
            (c1 + c2).contains(".AspNetCore.Identity.Application", ignoreCase = true) ||
                (c1 + c2).contains("Identity", ignoreCase = true)
        } catch (_: Exception) { false }
    }

    /** Phiên member (member.bongdaplus.vn) — xong bước 1 của login */
    fun hasMemberCookie(): Boolean {
        return try {
            val c = CookieManager.getInstance().getCookie("https://member.bongdaplus.vn") ?: ""
            c.contains(".AspNetCore.Identity.Application", ignoreCase = true) ||
                c.contains("Identity", ignoreCase = true)
        } catch (_: Exception) { false }
    }

    /**
     * Phiên chính (bongdaplus.vn) — server này mới nhận POST bình luận/vote.
     * Phiên này chỉ có sau khi WebView chạy handshake SSO (mở bongdaplus.vn
     * sau khi login member). Thiếu nó là bình luận báo "chưa đăng nhập".
     */
    fun hasSiteCookie(): Boolean {
        return try {
            val c = CookieManager.getInstance().getCookie("https://bongdaplus.vn") ?: ""
            c.contains(".AspNetCore.Identity.Application", ignoreCase = true) ||
                c.contains("Identity", ignoreCase = true)
        } catch (_: Exception) { false }
    }

    /** Đẩy cookie WebView xuống bộ nhớ chung để Jsoup/màn hình khác đọc được ngay */
    fun flushCookies() {
        try { CookieManager.getInstance().flush() } catch (_: Exception) { }
    }
}
