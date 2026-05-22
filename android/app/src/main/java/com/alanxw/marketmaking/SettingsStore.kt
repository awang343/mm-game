package com.alanxw.marketmaking

import android.content.Context

/** Thin SharedPreferences wrapper for persistent client settings. */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().putString(KEY_SERVER_URL, value?.trim()?.ifBlank { null }).apply()
        }

    var useBluetoothMic: Boolean
        get() = prefs.getBoolean(KEY_BT_MIC, false)
        set(value) = prefs.edit().putBoolean(KEY_BT_MIC, value).apply()

    /** "pkg/cls" string or null = use system default. */
    var recognizerComponent: String?
        get() = prefs.getString(KEY_RECOGNIZER, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().putString(KEY_RECOGNIZER, value?.takeIf { it.isNotBlank() }).apply()
        }

    companion object {
        private const val PREFS = "mm-client"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_BT_MIC = "use_bluetooth_mic"
        private const val KEY_RECOGNIZER = "recognizer_component"
    }
}
