package com.subbrain.zerotouch.api

import android.content.Context

object SelectionPreferences {
    private const val PREF_NAME = "zerotouch_prefs"
    private const val KEY_SELECTED_ACCOUNT_ID = "selected_account_id"
    private const val KEY_SELECTED_WORKSPACE_ID = "selected_workspace_id"
    private const val KEY_SELECTED_DEVICE_ID = "selected_device_id"

    fun getSelectedAccountId(context: Context): String? {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_ACCOUNT_ID, null)
    }

    fun setSelectedAccountId(context: Context, accountId: String?) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_ACCOUNT_ID, accountId)
            .apply()
    }

    fun getSelectedWorkspaceId(context: Context): String? {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_WORKSPACE_ID, null)
    }

    fun setSelectedWorkspaceId(context: Context, workspaceId: String?) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_WORKSPACE_ID, workspaceId)
            .apply()
    }

    fun getSelectedDeviceId(context: Context): String? {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_DEVICE_ID, null)
    }

    fun setSelectedDeviceId(context: Context, deviceId: String?) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_DEVICE_ID, deviceId)
            .apply()
    }
}
