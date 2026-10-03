package vn.bongdaplus.reader.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Cài đặt giao diện/teaser. theme_mode: system | light | dark; font: sans | serif */
class UiPrefs(private val ctx: Context) {
    companion object {
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_FONT = floatPreferencesKey("reader_font_scale")
        val KEY_DYNAMIC = booleanPreferencesKey("dynamic_color")
        val KEY_READER_FONT = stringPreferencesKey("reader_font")
        val KEY_LINE_SPACE = floatPreferencesKey("reader_line_space")
    }

    val themeMode: Flow<String> = ctx.appPrefs.data.map { it[KEY_THEME] ?: "system" }
    suspend fun setThemeMode(m: String) { ctx.appPrefs.edit { it[KEY_THEME] = m } }

    /** Cỡ chữ bài đọc: 0.85f - 1.3f */
    val fontScale: Flow<Float> = ctx.appPrefs.data.map { it[KEY_FONT] ?: 1f }
    suspend fun setFontScale(f: Float) { ctx.appPrefs.edit { it[KEY_FONT] = f.coerceIn(0.85f, 1.3f) } }

    /** Màu động Material You theo hình nền (chỉ Android 12+) */
    val dynamicColor: Flow<Boolean> = ctx.appPrefs.data.map { it[KEY_DYNAMIC] ?: true }
    suspend fun setDynamicColor(v: Boolean) { ctx.appPrefs.edit { it[KEY_DYNAMIC] = v } }

    /** Font bài đọc: sans (không chân) | serif (có chân, kiểu báo giấy) */
    val readerFont: Flow<String> = ctx.appPrefs.data.map { it[KEY_READER_FONT] ?: "serif" }
    suspend fun setReaderFont(v: String) { ctx.appPrefs.edit { it[KEY_READER_FONT] = v } }

    /** Hệ số giãn dòng bài đọc: 1.0f - 1.6f */
    val lineSpace: Flow<Float> = ctx.appPrefs.data.map { it[KEY_LINE_SPACE] ?: 1f }
    suspend fun setLineSpace(f: Float) { ctx.appPrefs.edit { it[KEY_LINE_SPACE] = f.coerceIn(1f, 1.6f) } }
}
