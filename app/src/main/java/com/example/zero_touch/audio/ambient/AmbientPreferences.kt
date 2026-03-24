package com.example.zero_touch.audio.ambient

import android.content.Context

object AmbientPreferences {
    private const val PREF_NAME = "zerotouch_prefs"
    private const val KEY_AMBIENT_ENABLED = "ambient_enabled"
    private const val KEY_ASR_PROVIDER = "asr_provider"
    private const val KEY_AMBIENT_AUDIO_SOURCE = "ambient_audio_source"
    private const val KEY_AMBIENT_HPF_ENABLED = "ambient_hpf_enabled"
    private const val DEFAULT_ASR_PROVIDER = "speechmatics"
    private const val DEFAULT_AMBIENT_AUDIO_SOURCE = "mic"

    fun isAmbientEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AMBIENT_ENABLED, false)
    }

    fun setAmbientEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AMBIENT_ENABLED, enabled).apply()
    }

    fun getAsrProvider(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ASR_PROVIDER, DEFAULT_ASR_PROVIDER) ?: DEFAULT_ASR_PROVIDER
    }

    fun setAsrProvider(context: Context, provider: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ASR_PROVIDER, provider).apply()
    }

    fun getAmbientAudioSource(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_AMBIENT_AUDIO_SOURCE, DEFAULT_AMBIENT_AUDIO_SOURCE)
            ?: DEFAULT_AMBIENT_AUDIO_SOURCE
    }

    fun setAmbientAudioSource(context: Context, source: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_AMBIENT_AUDIO_SOURCE, source).apply()
    }

    fun isHighPassFilterEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AMBIENT_HPF_ENABLED, false)
    }

    fun setHighPassFilterEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AMBIENT_HPF_ENABLED, enabled).apply()
    }
}
