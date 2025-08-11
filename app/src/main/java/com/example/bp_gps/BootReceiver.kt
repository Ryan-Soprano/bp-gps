package com.example.bp_gps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.bp_gps.service.NavigationService

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val PREFS = "bp_gps_prefs"
        private const val OFFICER_ID_KEY = "officer_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED != intent.action) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val officerId = prefs.getString(OFFICER_ID_KEY, "") ?: ""

        Log.d(TAG, "BOOT_COMPLETED received. OfficerId set? ${officerId.isNotBlank()}")

        if (officerId.isNotBlank()) {
            // Start the foreground service directly
            val svc = Intent(context, NavigationService::class.java).apply {
                putExtra("officer_id", officerId)
            }
            // Foreground service start from a broadcast:
            context.startForegroundService(svc)
        } else {
            // No officer ID yet; open MainActivity to collect it
            val activity = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("from_boot", true)
            }
            context.startActivity(activity)
        }
    }
}