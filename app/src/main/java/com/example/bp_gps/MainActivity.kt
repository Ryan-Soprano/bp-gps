package com.example.bp_gps

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.bp_gps.core.PreferencesHelper
import com.example.bp_gps.core.StatusStore
import com.example.bp_gps.service.NavigationService
import com.example.bp_gps.util.Ids

class MainActivity : AppCompatActivity() {

    private lateinit var serviceStatus: TextView
    private lateinit var connectionStatus: TextView
    private lateinit var statusDetail: TextView
    private lateinit var statusReceiver: BroadcastReceiver

    private val requestNotifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) promptForOfficerId()
            else Toast.makeText(this, R.string.toast_notifications_required, Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serviceStatus = findViewById(R.id.serviceStatusText)
        connectionStatus = findViewById(R.id.connectionStatusText)
        statusDetail = findViewById(R.id.statusDetailText)

        // Optional: if you add a button in your layout with this id
        runCatching {
            findViewById<Button>(R.id.btnEditRcn)?.setOnClickListener { showEditRcnDialog() }
        }

        setupStatusReceiver()
        checkPermissionsAndStart()
    }

    private fun setupStatusReceiver() {
        statusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    NavigationService.STATUS_UPDATE_ACTION -> {
                        val status = intent.getStringExtra(NavigationService.EXTRA_STATUS).orEmpty()
                        val detail = intent.getStringExtra(NavigationService.EXTRA_DETAIL).orEmpty()
                        updateStatusUI(status, detail)
                    }
                    NavigationService.HISTORY_UPDATE_ACTION -> {
                        // no-op here; you handle list elsewhere
                    }
                }
            }
        }
    }

    private fun updateStatusUI(status: String, detail: String) {
        val title = when (status) {
            getString(R.string.status_connected) -> getString(R.string.ui_title_connected)
            getString(R.string.status_connecting) -> getString(R.string.ui_title_connecting)
            getString(R.string.status_starting) -> getString(R.string.ui_title_starting)
            getString(R.string.status_disconnected) -> getString(R.string.ui_title_reconnecting)
            getString(R.string.status_connection_failed) -> getString(R.string.ui_title_failed)
            getString(R.string.status_dispatch_received) -> getString(R.string.ui_title_active)
            getString(R.string.status_setup_failed) -> getString(R.string.ui_title_setup_error)
            getString(R.string.status_stopped) -> getString(R.string.ui_title_stopped)
            else -> getString(R.string.app_name)
        }
        val chip = when (status) {
            getString(R.string.status_connected) -> getString(R.string.ui_chip_connected)
            getString(R.string.status_connecting) -> getString(R.string.ui_chip_connecting)
            getString(R.string.status_starting) -> getString(R.string.ui_chip_starting)
            getString(R.string.status_disconnected) -> getString(R.string.ui_chip_disconnected)
            getString(R.string.status_connection_failed) -> getString(R.string.ui_chip_failed)
            getString(R.string.status_dispatch_received) -> getString(R.string.ui_chip_active)
            getString(R.string.status_setup_failed) -> getString(R.string.ui_chip_setup_error)
            getString(R.string.status_stopped) -> getString(R.string.ui_chip_stopped)
            else -> getString(R.string.ui_chip_unknown, status)
        }

        serviceStatus.text = chip
        connectionStatus.text = title
        statusDetail.text = if (detail.isNotEmpty()) detail
        else getString(R.string.ui_detail_default, status)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(NavigationService.STATUS_UPDATE_ACTION)
            addAction(NavigationService.HISTORY_UPDATE_ACTION)
        }
        ContextCompat.registerReceiver(this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // Sync last known status immediately
        val (lastStatus, lastDetail) = StatusStore.load(this)
        if (!lastStatus.isNullOrEmpty()) updateStatusUI(lastStatus, lastDetail)
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(statusReceiver) }
    }

    private fun checkPermissionsAndStart() {
        statusDetail.text = getString(R.string.detail_checking_permissions)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        promptForOfficerId()
    }

    @SuppressLint("SetTextI18n")
    private fun promptForOfficerId() {
        val cached = PreferencesHelper.getOfficerId(this)

        if (!Ids.isValid(cached)) {
            statusDetail.text = getString(R.string.ui_detail_login_required)
            val input = EditText(this).apply { hint = getString(R.string.hint_officer_id) }
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_login_title)
                .setMessage(R.string.dialog_login_msg)
                .setView(input)
                .setCancelable(false)
                .setPositiveButton(R.string.button_ok) { _, _ ->
                    val id = Ids.normalize(input.text.toString())
                    if (Ids.isValid(id)) {
                        applyOfficerId(id)
                        startServiceIfNeeded(id)
                    } else {
                        Toast.makeText(this, R.string.toast_officer_required, Toast.LENGTH_SHORT).show()
                        promptForOfficerId()
                    }
                }
                .show()
        } else {
            statusDetail.text = getString(R.string.ui_detail_starting_for, cached)
            startServiceIfNeeded(cached)
        }
    }

    private fun applyOfficerId(newId: String) {
        PreferencesHelper.setOfficerId(this, newId)
        // tell the running service (or it will pick up on next start)
        startForegroundService(Intent(this, NavigationService::class.java).apply {
            action = NavigationService.ACTION_SET_OFFICER
            putExtra(NavigationService.EXTRA_OFFICER_ID, newId)
        })
        Toast.makeText(this, getString(R.string.toast_service_started, newId), Toast.LENGTH_SHORT).show()
    }

    private fun startServiceIfNeeded(officerId: String) {
        val i = Intent(this, NavigationService::class.java).putExtra("officer_id", officerId)
        startForegroundService(i)
    }

    // Optional: call from a button/menu to edit the RCN anytime
    private fun showEditRcnDialog() {
        val current = PreferencesHelper.getOfficerId(this)
        val input = EditText(this).apply {
            hint = getString(R.string.hint_officer_id)
            setText(current)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_login_title)
            .setMessage(R.string.dialog_login_msg)
            .setView(input)
            .setPositiveButton(R.string.button_ok) { _, _ ->
                val newId = Ids.normalize(input.text.toString())
                if (Ids.isValid(newId)) applyOfficerId(newId)
                else Toast.makeText(this, R.string.toast_officer_required, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    /* ------------- KIOSK / LOCK TASK (commented for POC) -------------
    @SuppressLint("MissingPermission")
    private fun tryEnterLockTask() {
        try { startLockTask() } catch (_: Exception) { }
    }
    private fun exitLockTaskIfActive() {
        try { stopLockTask() } catch (_: Exception) { }
    }
    ------------------------------------------------------------------ */
}
