package com.example.bp_gps.core

import android.content.Context
import androidx.core.content.edit

object StatusStore {
    private const val STATUS_PREFS_FILE = "bp_gps_last_status"
    private const val KEY_LAST_STATUS = "status"
    private const val KEY_LAST_DETAIL = "detail"

    fun save(context: Context, status: String, detail: String) {
        context.getSharedPreferences(STATUS_PREFS_FILE, Context.MODE_PRIVATE).edit {
            putString(KEY_LAST_STATUS, status)
            putString(KEY_LAST_DETAIL, detail)
        }
    }

    fun load(context: Context): Pair<String?, String> {
        val prefs = context.getSharedPreferences(STATUS_PREFS_FILE, Context.MODE_PRIVATE)
        val status = prefs.getString(KEY_LAST_STATUS, null)
        val detail = prefs.getString(KEY_LAST_DETAIL, "") ?: ""
        return status to detail
    }
}
