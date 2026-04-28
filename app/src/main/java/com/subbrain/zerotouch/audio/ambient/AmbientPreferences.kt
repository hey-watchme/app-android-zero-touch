package com.subbrain.zerotouch.audio.ambient

import android.content.Context
import org.json.JSONArray

object AmbientPreferences {
    private const val PREF_NAME = "zerotouch_prefs"
    private const val KEY_AMBIENT_ENABLED = "ambient_enabled"
    private const val KEY_ASR_PROVIDER = "asr_provider"
    private const val KEY_AMBIENT_AUDIO_SOURCE = "ambient_audio_source"
    private const val KEY_AMBIENT_HPF_ENABLED = "ambient_hpf_enabled"
    private const val KEY_VAD_ENGINE = "vad_engine"
    private const val KEY_LLM_PROVIDER = "llm_provider"
    private const val KEY_LLM_MODEL = "llm_model"
    private const val KEY_IMPORTANCE_LEVELS = "importance_levels"
    private const val KEY_LIVE_TRANSCRIPT_HISTORY = "live_transcript_history"
    private const val KEY_LIVE_ASR_MODEL = "live_asr_model"
    private const val DEFAULT_ASR_PROVIDER = "azure"
    private const val DEFAULT_LLM_PROVIDER = "openai"
    private const val DEFAULT_LLM_MODEL = "gpt-4.1-nano"
    private const val DEFAULT_AMBIENT_AUDIO_SOURCE = "mic"
    private const val DEFAULT_VAD_ENGINE = "threshold"
    private const val DEFAULT_IMPORTANCE_LEVELS = "0,1,2,3,4,5"
    const val VAD_ENGINE_THRESHOLD = "threshold"
    const val VAD_ENGINE_SILERO = "silero"
    const val VAD_ENGINE_WEBRTC = "webrtc"

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

    fun getVadEngine(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_VAD_ENGINE, DEFAULT_VAD_ENGINE) ?: DEFAULT_VAD_ENGINE
    }

    fun setVadEngine(context: Context, engine: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_VAD_ENGINE, engine).apply()
    }

    fun getLlmProvider(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LLM_PROVIDER, DEFAULT_LLM_PROVIDER) ?: DEFAULT_LLM_PROVIDER
    }

    fun setLlmProvider(context: Context, provider: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LLM_PROVIDER, provider).apply()
    }

    fun getLlmModel(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LLM_MODEL, DEFAULT_LLM_MODEL) ?: DEFAULT_LLM_MODEL
    }

    fun setLlmModel(context: Context, model: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LLM_MODEL, model).apply()
    }

    fun getImportanceLevels(context: Context): Set<Int> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_IMPORTANCE_LEVELS, DEFAULT_IMPORTANCE_LEVELS) ?: DEFAULT_IMPORTANCE_LEVELS
        return raw.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 0..5 }
            .toSet()
    }

    fun setImportanceLevels(context: Context, levels: Set<Int>) {
        val safe = levels.filter { it in 0..5 }.sorted().joinToString(",")
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_IMPORTANCE_LEVELS, safe).apply()
    }

    fun getLiveTranscriptHistory(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_LIVE_TRANSCRIPT_HISTORY, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            buildList {
                for (i in 0 until json.length()) {
                    val line = json.optString(i).trim()
                    if (line.isNotBlank()) add(line)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun setLiveTranscriptHistory(context: Context, lines: List<String>) {
        val safeLines = lines.map { it.trim() }.filter { it.isNotBlank() }
        val json = JSONArray()
        safeLines.forEach { json.put(it) }
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LIVE_TRANSCRIPT_HISTORY, json.toString()).apply()
    }

    fun getLiveAsrModel(context: Context): String? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LIVE_ASR_MODEL, null)?.trim()?.takeIf { it.isNotBlank() }
    }

    fun setLiveAsrModel(context: Context, model: String?) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LIVE_ASR_MODEL, model?.trim()?.takeIf { it.isNotBlank() }).apply()
    }
}
