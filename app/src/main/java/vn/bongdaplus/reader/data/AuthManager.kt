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
        const val LOGIN_URL = "https://member.bongdaplus.vn/Identity/Account/Login?returnUrl=%2F"
        const val HOME = "https://bongdaplus.vn/"
    }

    val loggedIn: Flow<Boolean> = ctx.appPrefs.data.map { it[KEY_LOGGED] == true }
    val email: Flow<String> = ctx.appPrefs.data.map { it[KEY_EMAIL] ?: "" }
    val followSlugs: Flow<Set<String>> = ctx.appPrefs.data.map {
        it[KEY_FOLLOW] ?: setOf("tin-moi", "bong-da-viet-nam", "ngoai-hang-anh")
    }
    val notifyEnabled: Flow<Boolean> = ctx.appPrefs.data.map { it[KEY_NOTIFY] ?: true }

    suspend fun setNotify(v: Boolean) { ctx.appPrefs.edit { it[KEY_NOTIFY] = v } }
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

    /** Cookie hiện tại để gắn vào Jsoup (đọc Premium) */
    fun currentCookies(): Map<String, String> {
        return try {
            val cm = CookieManager.getInstance()
            val raw = cm.getCookie("https://bongdaplus.vn") ?: ""
            // getCookie trả về "k=v; k2=v2"
            raw.split(";").mapNotNull {
                val kv = it.trim().split("=", limit = 2)
                if (kv.size == 2 && kv[0].isNotBlank()) kv[0].trim() to kv[1].trim() else null
            }.toMap()
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
}
