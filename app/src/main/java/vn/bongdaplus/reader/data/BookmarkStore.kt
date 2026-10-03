package vn.bongdaplus.reader.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** Bookmark lưu local (JSON trong DataStore, tối đa 200 tin). */
class BookmarkStore(private val ctx: Context) {
    companion object {
        val KEY = stringPreferencesKey("bookmarks_json")
    }

    fun flow(): Flow<List<Article>> = ctx.appPrefs.data.map { prefs ->
        try {
            val arr = JSONArray(prefs[KEY] ?: "[]")
            List(arr.length()) { i ->
                val o: JSONObject = arr.getJSONObject(i)
                Article(
                    id = o.optString("id"),
                    title = o.optString("t").nfcVi(),
                    url = o.optString("u"),
                    imageUrl = o.optString("img").ifBlank { null },
                    category = o.optString("cat").ifBlank { null }
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    suspend fun toggle(a: Article) {
        val cur = flow().first().toMutableList()
        if (cur.any { it.id == a.id }) cur.removeAll { it.id == a.id }
        else cur.add(0, a)
        val arr = JSONArray()
        cur.take(200).forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("t", it.title); put("u", it.url)
                put("img", it.imageUrl ?: ""); put("cat", it.category ?: "")
            })
        }
        ctx.appPrefs.edit { it[KEY] = arr.toString() }
    }
}
