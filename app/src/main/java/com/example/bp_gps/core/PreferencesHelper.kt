package com.example.bp_gps.core

import android.content.Context
import androidx.core.content.edit

object PreferencesHelper {
    private const val PREFS_FILE_NAME = "bp_gps_prefs"
    private const val KEY_OFFICER_ID = "officer_id"

    fun getOfficerId(context: Context): String =
        context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
            .getString(KEY_OFFICER_ID, "") ?: ""

    fun setOfficerId(context: Context, officerId: String) {
        context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_OFFICER_ID, officerId) }
    }
}
