package vn.bongdaplus.reader.data

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

/** Single DataStore instance dùng chung cho toàn app. */
val Context.appPrefs by preferencesDataStore(name = "bongdaplus_prefs")
