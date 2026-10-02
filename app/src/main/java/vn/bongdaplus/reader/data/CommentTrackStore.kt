package vn.bongdaplus.reader.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/**
 * Theo dõi bài user đã bình luận để báo chi tiết, kể cả khi chưa bấm Lưu.
 * Lưu 1 JSON dạng: articleId -> (u, title, seenIds, mineTexts).
 * - track(): gọi ngay sau khi gửi bình luận thành công.
 * - seenIds()/saveSeenIds(): để Worker so sánh và chỉ báo cái mới.
 */
class CommentTrackStore(private val ctx: Context) {
    companion object {
        val KEY = stringPreferencesKey("comment_track_json")
        const val MAX_ARTICLES = 30
    }

    private suspend fun readObj(): JSONObject {
        val raw = ctx.appPrefs.data.map { it[KEY] ?: "{}" }.first()
        return try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
    }

    private suspend fun writeObj(o: JSONObject) {
        ctx.appPrefs.edit { it[KEY] = o.toString() }
    }

    suspend fun tracked(): List<Article> {
        val o = readObj()
        val out = mutableListOf<Article>()
        for (k in o.keys()) {
            try {
                val a = o.getJSONObject(k)
                out += Article(
                    id = k,
                    title = a.optString("t", "Bài viết"),
                    url = a.optString("u"),
                    category = a.optString("cat").ifBlank { null }
                )
            } catch (_: Exception) { }
        }
        return out
    }

    /** Gọi sau khi postComment() trả về true. Tự lưu bài + text mình vừa gửi. */
    suspend fun track(a: Article, myText: String? = null) {
        try {
            val o = readObj()
            val cur = try { o.getJSONObject(a.id) } catch (_: Exception) { JSONObject() }
            cur.put("u", a.url)
            cur.put("t", a.title)
            if (!a.category.isNullOrBlank()) cur.put("cat", a.category)
            if (!myText.isNullOrBlank()) {
                // giữ 3 text gần nhất để nhận ra comment của mình sau khi được duyệt
                val arr = try { cur.getJSONArray("mine") } catch (_: Exception) {
                    org.json.JSONArray()
                }
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) list += arr.optString(i)
                list.add(0, myText.take(300))
                val fresh = org.json.JSONArray()
                list.distinct().take(3).forEach { fresh.put(it) }
                cur.put("mine", fresh)
            }
            o.put(a.id, cur)
            // giới hạn số bài
            while (o.length() > MAX_ARTICLES) {
                val first = o.keys().next()
                o.remove(first)
            }
            writeObj(o)
        } catch (_: Exception) { }
    }

    suspend fun seenIds(articleId: String): Set<String> {
        return try {
            val o = readObj()
            val arr = o.optJSONObject(articleId)?.optJSONArray("seen") ?: return emptySet()
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }.toSet()
        } catch (_: Exception) { emptySet() }
    }

    suspend fun saveSeenIds(articleId: String, ids: List<String>, article: Article? = null) {
        try {
            val o = readObj()
            val cur = try { o.getJSONObject(articleId) } catch (_: Exception) { JSONObject() }
            if (article != null) {
                if (cur.optString("u").isBlank()) cur.put("u", article.url)
                if (cur.optString("t").isBlank()) cur.put("t", article.title)
            }
            val arr = org.json.JSONArray()
            ids.distinct().take(30).forEach { arr.put(it) }
            cur.put("seen", arr)
            o.put(articleId, cur)
            writeObj(o)
        } catch (_: Exception) { }
    }

    suspend fun myTexts(articleId: String): List<String> {
        return try {
            val arr = readObj().optJSONObject(articleId)?.optJSONArray("mine")
                ?: return emptyList()
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        } catch (_: Exception) { emptyList() }
    }
}
