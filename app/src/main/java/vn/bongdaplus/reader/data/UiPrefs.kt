package vn.bongdaplus.reader.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Cài đặt giao diện/teaser. theme_mode: system | light | dark */
class UiPrefs(private val ctx: Context) {
    companion object {
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_FONT = floatPreferencesKey("reader_font_scale")
    }

    val themeMode: Flow<String> = ctx.appPrefs.data.map { it[KEY_THEME] ?: "system" }
    suspend fun setThemeMode(m: String) { ctx.appPrefs.edit { it[KEY_THEME] = m } }

    /** Cỡ chữ bài đọc: 0.85f - 1.3f */
    val fontScale: Flow<Float> = ctx.appPrefs.data.map { it[KEY_FONT] ?: 1f }
    suspend fun setFontScale(f: Float) { ctx.appPrefs.edit { it[KEY_FONT] = f.coerceIn(0.85f, 1.3f) } }
}
