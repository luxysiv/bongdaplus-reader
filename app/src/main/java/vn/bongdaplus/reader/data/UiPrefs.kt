package vn.bongdaplus.reader.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Cài đặt giao diện/teaser. theme_mode: system | light | dark */
class UiPrefs(private val ctx: Context) {
    companion object {
        val KEY_THEME = stringPreferencesKey("theme_mode")
    }

    val themeMode: Flow<String> = ctx.appPrefs.data.map { it[KEY_THEME] ?: "system" }
    suspend fun setThemeMode(m: String) { ctx.appPrefs.edit { it[KEY_THEME] = m } }
}
