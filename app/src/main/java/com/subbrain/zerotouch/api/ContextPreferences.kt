package com.subbrain.zerotouch.api

import android.content.Context

object ContextPreferences {
    private const val PREF_NAME = "zerotouch_prefs"
    private const val KEY_ONBOARDING_PREFIX = "context_onboarding_completed_"

    fun isOnboardingCompleted(context: Context, workspaceId: String): Boolean {
        val key = "$KEY_ONBOARDING_PREFIX$workspaceId"
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(key, false)
    }

    fun setOnboardingCompleted(context: Context, workspaceId: String, completed: Boolean) {
        val key = "$KEY_ONBOARDING_PREFIX$workspaceId"
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key, completed)
            .apply()
    }
}
