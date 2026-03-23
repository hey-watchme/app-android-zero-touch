package com.example.zero_touch.audio.ambient

import android.content.Context

object AmbientPreferences {
    private const val PREF_NAME = "zerotouch_prefs"
    private const val KEY_AMBIENT_ENABLED = "ambient_enabled"

    fun isAmbientEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AMBIENT_ENABLED, false)
    }

    fun setAmbientEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AMBIENT_ENABLED, enabled).apply()
    }
}
