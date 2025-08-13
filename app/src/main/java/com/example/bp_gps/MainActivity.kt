package com.example.bp_gps

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.example.bp_gps.service.NavigationService
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private val PREFS_DISPATCH = "bp_gps_dispatch_history"
    private val PREFS_APP = "bp_gps_prefs"
    private val KEY_OFFICER = "officer_id"
    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvOfficerCaption: TextView
    private lateinit var btnEditRcn: MaterialButton
    private lateinit var btnToggleFilter: MaterialButton
    private lateinit var btnClearHistory: MaterialButton
    private lateinit var list: ListView
    private lateinit var emptyView: TextView
    private lateinit var buttonContainer: LinearLayout
    private lateinit var recentTitle: TextView
    private val rawItems = mutableListOf<HistoryItem>()
    private lateinit var adapter: ArrayAdapter<HistoryItem>
    private var filterToOfficer: Boolean = true

    data class HistoryItem(
        val address: String,
        val timestamp: Long,
        val officerId: String = ""
    )

    private lateinit var historyReceiver: BroadcastReceiver
    private var refreshHandler: android.os.Handler? = null
    private var refreshRunnable: Runnable? = null

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
        setContentView(R.layout.activity_main)

        initializeViews()
        setupToolbar()
        setupAdapterAndListeners()
        setupHistoryReceiver()
        checkPermissionsThenProceed()
    }

    private fun initializeViews() {
        toolbar = findViewById(R.id.toolbar)
        tvOfficerCaption = findViewById(R.id.tvOfficerCaption)
        btnEditRcn = findViewById(R.id.btnEditRcn)
        btnToggleFilter = findViewById(R.id.btnToggleFilter)
        btnClearHistory = findViewById(R.id.btnClearHistory)
        buttonContainer = findViewById(R.id.buttonContainer)
        recentTitle = findViewById(R.id.recentTitle)
        list = findViewById(R.id.recentList)
        emptyView = findViewById(R.id.emptyView)

        list.emptyView = emptyView
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
    }

    private fun setupAdapterAndListeners() {
        adapter = object : ArrayAdapter<HistoryItem>(this, R.layout.list_item_address, rawItems) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.list_item_address, parent, false)
                val item = getItem(position) ?: return view

                val tvAddress = view.findViewById<TextView>(R.id.tvAddress)
                val tvOfficer = view.findViewById<TextView>(R.id.tvOfficer)
                val tvTime = view.findViewById<TextView>(R.id.tvTime)

                tvAddress.text = item.address

                if (item.officerId.isNotBlank()) {
                    tvOfficer.text = getString(R.string.officer_indicator, item.officerId)
                    tvOfficer.visibility = View.VISIBLE
                } else {
                    tvOfficer.visibility = View.GONE
                }

                val timeFormat = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                tvTime.text = timeFormat.format(java.util.Date(item.timestamp))

                return view
            }
        }

        list.adapter = adapter

        btnEditRcn.setOnClickListener { promptForOfficerId() }

        btnToggleFilter.setOnClickListener {
            filterToOfficer = !filterToOfficer
            btnToggleFilter.text = if (filterToOfficer)
                getString(R.string.button_show_all)
            else
                getString(R.string.button_filter_to_rcn)

            refreshOfficerCaption()
            loadAndDisplayHistory()
            updateClearButtonVisibility()
        }

        btnClearHistory.setOnClickListener {
            if (filterToOfficer) {
                confirmClearOfficerHistory()
            } else {
                showClearOptionsDialog()
            }
        }

        list.setOnItemClickListener { _, _, position, _ ->
            if (position in rawItems.indices) openMaps(rawItems[position].address)
        }

        list.setOnItemLongClickListener { _, _, position, _ ->
            if (position in rawItems.indices) {
                confirmDeleteEntry(rawItems[position])
            }
            true
        }

        updateClearButtonVisibility()
    }

    private fun updateClearButtonVisibility() {
        btnClearHistory.visibility = if (rawItems.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun showClearOptionsDialog() {
        val options = arrayOf(
            getString(R.string.option_clear_current_officer),
            getString(R.string.option_clear_all_history)
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_clear_options_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> confirmClearOfficerHistory()
                    1 -> confirmClearAllHistory()
                }
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    private fun confirmClearAllHistory() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_clear_all_history_title)
            .setMessage(R.string.msg_clear_all_history)
            .setPositiveButton(R.string.button_clear) { _, _ ->
                clearAllHistory()
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    private fun clearAllHistory() {
        getSharedPreferences(PREFS_DISPATCH, MODE_PRIVATE)
            .edit { remove("dispatch_history") }
        loadAndDisplayHistory()
        Toast.makeText(this, R.string.toast_all_history_cleared, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this,
            historyReceiver,
            IntentFilter(NavigationService.HISTORY_UPDATE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refreshOfficerCaption()
        loadAndDisplayHistory()

        startPeriodicHistoryRefresh()
    }

    private fun startPeriodicHistoryRefresh() {
        stopPeriodicHistoryRefresh()

        refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
        refreshRunnable = object : Runnable {
            override fun run() {
                loadAndDisplayHistory()
                refreshHandler?.postDelayed(this, 2000)
            }
        }
        refreshHandler?.post(refreshRunnable!!)
    }

    private fun stopPeriodicHistoryRefresh() {
        refreshRunnable?.let { runnable ->
            refreshHandler?.removeCallbacks(runnable)
        }
        refreshHandler = null
        refreshRunnable = null
    }

    override fun onPause() {
        super.onPause()
        stopPeriodicHistoryRefresh()
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

        Log.d("MainActivity", "Officer ID: '$id'")

        val captionText = if (id.isNullOrBlank()) {
            getString(R.string.caption_officer_none)
        } else {
            try {
                getString(R.string.caption_officer_id, id)
            } catch (e: Exception) {
                Log.e("MainActivity", "String formatting failed", e)
                "Officer: $id"  // Simple fallback
            }
        }

        tvOfficerCaption.text = captionText

        btnToggleFilter.text = if (filterToOfficer)
            getString(R.string.button_show_all)
        else
            getString(R.string.button_filter_to_rcn)
    }

    private fun getOfficerId(): String? {
        val id = getSharedPreferences(PREFS_APP, MODE_PRIVATE).getString(KEY_OFFICER, null)
        Log.d("MainActivity", "getOfficerId() returning: '$id'")
        return id
    }

    private fun setOfficerId(id: String) {
        Log.d("MainActivity", "setOfficerId() setting: '$id'")
        getSharedPreferences(PREFS_APP, MODE_PRIVATE).edit { putString(KEY_OFFICER, id) }
    }

    private fun startServiceWithOfficer(officerId: String) {
        val intent = Intent(this, NavigationService::class.java)
            .putExtra("officer_id", officerId)
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, getString(R.string.toast_service_started, officerId), Toast.LENGTH_SHORT).show()
    }

    private fun promptForOfficerId() {
        val input = EditText(this).apply {
            hint = getString(R.string.hint_officer_id)
            val currentId = getOfficerId()
            if (!currentId.isNullOrBlank()) {
                setText(currentId)
                selectAll()
            }
        }

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
                    loadAndDisplayHistory()
                } else {
                    Toast.makeText(this, R.string.toast_officer_required, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    private fun setupHistoryReceiver() {
        historyReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                loadAndDisplayHistory()
            }
        }
    }

    private fun loadAndDisplayHistory() {
        val items = readHistoryFromPrefs(filterToOfficer)
        rawItems.clear()
        rawItems.addAll(items)
        adapter.notifyDataSetChanged()
        emptyView.visibility =
            if (rawItems.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE

        updateClearButtonVisibility()
    }

    private fun readHistoryFromPrefs(onlyMine: Boolean): List<HistoryItem> {
        val prefs = getSharedPreferences(PREFS_DISPATCH, MODE_PRIVATE)
        val json = prefs.getString("dispatch_history", null)
            ?: return emptyList()
        val arr = JSONArray(json)
        val me = getOfficerId().orEmpty()
        val list = mutableListOf<HistoryItem>()

        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val item = HistoryItem(
                address = o.getString("address"),
                timestamp = o.getLong("timestamp"),
                officerId = o.optString("officerId", "")
            )
            if (!onlyMine || item.officerId.equals(me, ignoreCase = true)) {
                list += item
            }
        }
        return list
    }

    private fun writeHistoryToPrefs(items: List<HistoryItem>) {
        val arr = JSONArray()
        items.forEach { it ->
            arr.put(JSONObject().apply {
                put("address", it.address)
                put("timestamp", it.timestamp)
                put("officerId", it.officerId)
            })
        }
        getSharedPreferences(PREFS_DISPATCH, MODE_PRIVATE)
            .edit { putString("dispatch_history", arr.toString()) }
    }

    private fun confirmDeleteEntry(target: HistoryItem) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_entry_title)
            .setMessage(getString(R.string.msg_delete_entry, target.address))
            .setPositiveButton(R.string.button_delete) { _, _ ->
                deleteEntry(target)
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    private fun deleteEntry(target: HistoryItem) {
        val items = readHistoryFromPrefs(onlyMine = false)
        val updated = items.filterNot {
            it.address == target.address && it.timestamp == target.timestamp
        }
        writeHistoryToPrefs(updated)
        loadAndDisplayHistory()
        Toast.makeText(this, R.string.toast_entry_removed, Toast.LENGTH_SHORT).show()
    }

    private fun confirmClearOfficerHistory() {
        val me = getOfficerId().orEmpty()

        Log.d("MainActivity", "Clear history for officer: '$me'")

        if (me.isBlank()) {
            Toast.makeText(this, R.string.toast_officer_required, Toast.LENGTH_SHORT).show()
            return
        }

        val message = try {
            getString(R.string.msg_clear_history_officer, me)
        } catch (e: Exception) {
            Log.e("MainActivity", "String formatting failed for dialog", e)
            "This will remove all saved addresses for Officer $me."  // Fallback
        }
        Log.d("MainActivity", "Dialog message: '$message'")

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_clear_history_title)
            .setMessage(message)
            .setPositiveButton(R.string.button_clear) { _, _ ->
                clearOfficerHistory(me)
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    private fun clearOfficerHistory(officerId: String) {
        val items = readHistoryFromPrefs(onlyMine = false)
        val updated = items.filterNot { it.officerId.equals(officerId, true) }
        writeHistoryToPrefs(updated)
        loadAndDisplayHistory()
        Toast.makeText(this, R.string.toast_history_cleared, Toast.LENGTH_SHORT).show()
    }

    private fun openMaps(address: String) {
        runCatching {
            val encoded = Uri.encode(address)
            val uri = "geo:0,0?q=$encoded".toUri()
            startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.apps.maps")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                // Add window flags for maximized display
                putExtra("android.intent.extra.WINDOW_FEATURES", "maximized")
            })
        }
    }
}