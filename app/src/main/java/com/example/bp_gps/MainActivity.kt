package com.example.bp_gps

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.example.bp_gps.service.NavigationService
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    // --- keys / prefs ---
    private val PREFS_DISPATCH = "bp_gps_dispatch_history"
    private val PREFS_APP = "bp_gps_prefs"
    private val KEY_OFFICER = "officer_id"

    // UI
    private lateinit var tvOfficerCaption: TextView
    private lateinit var btnEditRcn: Button
    private lateinit var btnToggleFilter: Button
    private lateinit var list: ListView
    private lateinit var emptyView: TextView

    // Data
    private val displayItems = mutableListOf<String>()
    private val rawItems = mutableListOf<HistoryItem>()
    private lateinit var adapter: ArrayAdapter<String>

    private var filterToOfficer: Boolean = true

    data class HistoryItem(
        val address: String,
        val timestamp: Long,
        val officerId: String = ""
    )

    // Broadcast to refresh list when service updates history
    private lateinit var historyReceiver: BroadcastReceiver

    // Android 13+ notifications permission
    private val requestNotif =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                ensureOfficerAndStart()
            } else {
                Toast.makeText(this, R.string.toast_notifications_required, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        tvOfficerCaption = findViewById(R.id.tvOfficerCaption)
        btnEditRcn = findViewById(R.id.btnEditRcn)
        btnToggleFilter = findViewById(R.id.btnToggleFilter)
        list = findViewById(R.id.recentList)
        emptyView = findViewById(R.id.emptyView)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayItems)
        list.adapter = adapter
        list.emptyView = emptyView

        btnEditRcn.setOnClickListener { promptForOfficerId() }
        btnToggleFilter.setOnClickListener {
            filterToOfficer = !filterToOfficer
            btnToggleFilter.text = if (filterToOfficer) getString(R.string.button_filter_to_rcn) else getString(R.string.button_show_all)
            refreshOfficerCaption()
            loadAndDisplayHistory()
        }
        list.setOnItemClickListener { _, _, position, _ ->
            if (position in rawItems.indices) openMaps(rawItems[position].address)
        }

        setupHistoryReceiver()
        checkPermissionsThenProceed()
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this,
            historyReceiver,
            IntentFilter(com.example.bp_gps.service.NavigationService.HISTORY_UPDATE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // keep UI fresh
        refreshOfficerCaption()
        loadAndDisplayHistory()
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(historyReceiver) }
    }

    private fun checkPermissionsThenProceed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        ensureOfficerAndStart()
    }

    private fun ensureOfficerAndStart() {
        val id = getOfficerId()
        if (id.isNullOrBlank()) {
            promptForOfficerId()
        } else {
            refreshOfficerCaption()
            startServiceWithOfficer(id)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun refreshOfficerCaption() {
        val id = getOfficerId()
        tvOfficerCaption.text =
            if (id.isNullOrBlank()) getString(R.string.caption_officer_none)
            else getString(R.string.caption_officer_id, id)
        btnToggleFilter.text = if (filterToOfficer) getString(R.string.button_filter_to_rcn) else getString(R.string.button_show_all)
    }

    private fun getOfficerId(): String? =
        getSharedPreferences(PREFS_APP, MODE_PRIVATE).getString(KEY_OFFICER, null)

    private fun setOfficerId(id: String) =
        getSharedPreferences(PREFS_APP, MODE_PRIVATE).edit { putString(KEY_OFFICER, id) }

    private fun startServiceWithOfficer(officerId: String) {
        val intent = Intent(this, NavigationService::class.java)
            .putExtra("officer_id", officerId)
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, getString(R.string.toast_service_started, officerId), Toast.LENGTH_SHORT).show()
    }

    private fun promptForOfficerId() {
        val input = EditText(this).apply { hint = getString(R.string.hint_officer_id) }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_login_title)
            .setMessage(R.string.dialog_login_msg)
            .setView(input)
            .setCancelable(true)
            .setPositiveButton(R.string.button_ok) { _, _ ->
                val id = input.text.toString().trim()
                if (id.isNotEmpty()) {
                    setOfficerId(id)
                    refreshOfficerCaption()
                    startServiceWithOfficer(id)
                    loadAndDisplayHistory() // re-filter immediately
                } else {
                    Toast.makeText(this, R.string.toast_officer_required, Toast.LENGTH_SHORT).show()
                }
            }.show()
    }

    // ---- history ----

    private fun setupHistoryReceiver() {
        historyReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                loadAndDisplayHistory()
            }
        }
    }

    private fun loadAndDisplayHistory() {
        val (items, display) = readHistoryFromPrefs(filterToOfficer)
        rawItems.clear()
        rawItems.addAll(items)
        displayItems.clear()
        displayItems.addAll(display)
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (displayItems.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun readHistoryFromPrefs(onlyMine: Boolean): Pair<List<HistoryItem>, List<String>> {
        val prefs = getSharedPreferences(PREFS_DISPATCH, MODE_PRIVATE)
        val json = prefs.getString("dispatch_history", null) ?: return emptyList<HistoryItem>() to emptyList()
        val arr = JSONArray(json)

        val me = getOfficerId().orEmpty()
        val list = mutableListOf<HistoryItem>()
        val display = mutableListOf<String>()

        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val item = HistoryItem(
                address = o.getString("address"),
                timestamp = o.getLong("timestamp"),
                officerId = o.optString("officerId", "")
            )
            if (!onlyMine || item.officerId.equals(me, ignoreCase = true)) {
                list += item
                val line = "\uD83D\uDCCC ${item.address}${if (item.officerId.isNotBlank()) " (${item.officerId})" else ""}"
                display += line
            }
        }
        return list to display
    }
    private fun openMaps(address: String) {
        runCatching {
            val encoded = Uri.encode(address)
            val uri = "geo:0,0?q=$encoded".toUri()
            startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.apps.maps")
            })
        }
    }
}
