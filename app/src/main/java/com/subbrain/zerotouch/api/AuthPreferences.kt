package com.subbrain.zerotouch.api

import android.content.Context
import com.google.gson.Gson

object AuthPreferences {
    private const val PREF_NAME = "zerotouch_auth"
    private const val KEY_SESSION = "auth_session"
    private val gson = Gson()

    fun getSession(context: Context): AuthSession? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SESSION, null) ?: return null
        return runCatching { gson.fromJson(raw, AuthSession::class.java) }.getOrNull()
    }

    fun setSession(context: Context, session: AuthSession?) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (session == null) {
            prefs.edit().remove(KEY_SESSION).apply()
        } else {
            prefs.edit().putString(KEY_SESSION, gson.toJson(session)).apply()
        }
    }
}
