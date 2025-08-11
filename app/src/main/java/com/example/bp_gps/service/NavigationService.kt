package com.example.bp_gps.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.example.bp_gps.R
import com.example.bp_gps.core.PreferencesHelper
import com.example.bp_gps.core.StatusStore
import com.example.bp_gps.model.DispatchMessage
import com.example.bp_gps.util.Ids
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.microsoft.signalr.HubConnectionState
import com.microsoft.signalr.TransportEnum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import androidx.core.content.edit


class NavigationService : Service() {

    companion object {
        private const val TAG = "NavigationService"

        // Foreground + heads-up
        private const val NOTIFICATION_ID_FOREGROUND = 1
        private const val NOTIFICATION_ID_ALERT = 2

        // Channels (you can keep one if you prefer; these names are clearer)
        private const val CHANNEL_ID_PERSISTENT = "bp_gps_channel_persistent"
        private const val CHANNEL_ID_ALERTS = "bp_gps_channel_alerts"

        // SignalR/Azure
        private const val NEGOTIATE_URL = "https://bp-gps-app.azurewebsites.net/api/negotiate"
        private const val HUB_NAME = "dispatchHub"

        // Timing
        private const val RECONNECT_DELAY_MS = 5000L
        private const val DEBOUNCE_MS = 3000L
        private const val MAX_HISTORY_SIZE = 50

        // Broadcast actions/extras
        const val STATUS_UPDATE_ACTION = "bp_gps.STATUS_UPDATE"
        const val HISTORY_UPDATE_ACTION = "bp_gps.HISTORY_UPDATE"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_DETAIL = "extra_detail"

        // Live RCN updates
        const val ACTION_SET_OFFICER = "bp_gps.SET_OFFICER"
        const val EXTRA_OFFICER_ID = "extra_officer_id"
    }

    private data class DispatchHistoryItem(
        val address: String,
        val timestamp: Long,
        val officerId: String = ""
    )

    private var hubConnection: HubConnection? = null
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var officerId: String = "" // always normalized uppercase
    @Volatile private var isReconnecting = false

    // debounce duplicate addresses
    private var lastAddress: String? = null
    private var lastAddressAt: Long = 0

    private var currentAddressForAction: String = ""

    override fun onCreate() {
        super.onCreate()
        // load saved officer id (normalized)
        officerId = Ids.normalize(PreferencesHelper.getOfficerId(this))
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Live RCN updates without restarting service
        if (intent?.action == ACTION_SET_OFFICER) {
            officerId = Ids.normalize(intent.getStringExtra(EXTRA_OFFICER_ID))
            PreferencesHelper.setOfficerId(this, officerId)
            broadcastStatus(getString(R.string.status_connected),
                getString(R.string.ui_detail_starting_for, officerId.ifBlank { "—" }))
            updateForegroundNotification(getString(R.string.notif_connected_listening))
            return START_STICKY
        }

        // initial boot/start
        val initialId = intent?.getStringExtra("officer_id")
        if (!initialId.isNullOrBlank()) {
            officerId = Ids.normalize(initialId)
            PreferencesHelper.setOfficerId(this, officerId)
        }

        startForeground(
            NOTIFICATION_ID_FOREGROUND,
            buildPersistentNotification(getString(R.string.notif_connecting))
        )
        broadcastStatus(getString(R.string.status_starting), getString(R.string.detail_initializing))
        connectToSignalR()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        broadcastStatus(getString(R.string.status_stopped), getString(R.string.detail_service_stopped))
        disconnectSignalR()
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- status helpers (persist + broadcast) ---
    private fun broadcastStatus(status: String, detail: String = "") {
        StatusStore.save(this, status, detail)
        sendBroadcast(Intent(STATUS_UPDATE_ACTION).apply {
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_DETAIL, detail)
        })
        Log.d(TAG, "📡 Status: $status${if (detail.isNotEmpty()) " - $detail" else ""}")
    }

    private fun broadcastHistoryUpdate() = sendBroadcast(Intent(HISTORY_UPDATE_ACTION))

    // --- history store (unchanged behavior, clearer names) ---
    private fun saveDispatchToHistory(address: String, officerIdForItem: String) {
        try {
            val prefs = getSharedPreferences("bp_gps_dispatch_history", Context.MODE_PRIVATE)
            val history = loadDispatchHistory().toMutableList()

            history.add(0, DispatchHistoryItem(address, System.currentTimeMillis(), officerIdForItem))
            if (history.size > MAX_HISTORY_SIZE) {
                history.subList(MAX_HISTORY_SIZE, history.size).clear()
            }

            val arr = JSONArray()
            history.forEach { item ->
                arr.put(
                    JSONObject().apply {
                        put("address", item.address)
                        put("timestamp", item.timestamp)
                        put("officerId", item.officerId)
                    }
                )
            }
            prefs.edit { putString("dispatch_history", arr.toString()) }

            broadcastHistoryUpdate()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save dispatch history", e)
        }
    }

    private fun loadDispatchHistory(): List<DispatchHistoryItem> = try {
        val prefs = getSharedPreferences("bp_gps_dispatch_history", Context.MODE_PRIVATE)
        val json = prefs.getString("dispatch_history", null) ?: return emptyList()
        val arr = JSONArray(json)
        MutableList(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            DispatchHistoryItem(
                address = o.getString("address"),
                timestamp = o.getLong("timestamp"),
                officerId = o.optString("officerId", "")
            )
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load dispatch history", e)
        emptyList()
    }

    // --- SignalR negotiation/connection ---
    private suspend fun negotiateConnection(): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val url = URL(NEGOTIATE_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                setFixedLengthStreamingMode(0)
                connectTimeout = 30000
                readTimeout = 30000
            }

            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                val response = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                val connectionUrl = json.getString("url")
                val accessToken = json.optString("accessToken", "")
                Pair(connectionUrl, accessToken)
            } else {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "Negotiate failed: $code - $error")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Negotiate exception: ${e.message}", e)
            null
        }
    }

    private fun setupSignalR(connectionUrl: String, accessToken: String) {
        try {
            val builder = HubConnectionBuilder.create(connectionUrl)
                .shouldSkipNegotiate(true)
                .withTransport(TransportEnum.WEBSOCKETS)
                .withHandshakeResponseTimeout(60000)
                .withServerTimeout(300000)

            if (accessToken.isNotEmpty()) builder.withHeader("Authorization", "Bearer $accessToken")

            hubConnection = builder.build()
            setupMessageHandlers()

            hubConnection?.onClosed { ex ->
                Log.w(TAG, "SignalR closed", ex)
                updateForegroundNotification(getString(R.string.notif_conn_lost))
                broadcastStatus(getString(R.string.status_disconnected), getString(R.string.detail_reconnecting))
                safeReconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "setupSignalR error", e)
            updateForegroundNotification(getString(R.string.notif_setup_failed))
            broadcastStatus(getString(R.string.status_setup_failed), getString(R.string.detail_retrying))
            safeReconnect()
        }
    }

    private fun setupMessageHandlers() {
        hubConnection?.on("newAddress", { msg: DispatchMessage ->
            Log.d(TAG, "📨 newAddress: $msg")
            handleDispatchMessage(msg)
        }, DispatchMessage::class.java)

        hubConnection?.on("ReceiveDispatch", { msg: DispatchMessage ->
            Log.d(TAG, "📨 ReceiveDispatch: $msg")
            handleDispatchMessage(msg)
        }, DispatchMessage::class.java)

        listOf("Connected", "Disconnected", "Ping", "Heartbeat").forEach { name ->
            hubConnection?.on(name) {
                updateForegroundNotification(getString(R.string.notif_server_event, name))
                broadcastStatus(getString(R.string.status_connected), getString(R.string.detail_server_event, name))
            }
        }
    }

    private fun connectToSignalR() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    updateForegroundNotification(getString(R.string.notif_negotiating))
                    broadcastStatus(getString(R.string.status_connecting), getString(R.string.detail_negotiating))
                }

                val result = negotiateConnection() ?: throw Exception("Negotiate failed")
                val (url, token) = result

                withContext(Dispatchers.Main) { setupSignalR(url, token) }

                if (hubConnection?.connectionState == HubConnectionState.DISCONNECTED) {
                    withContext(Dispatchers.Main) {
                        updateForegroundNotification(getString(R.string.notif_connecting_dispatch))
                        broadcastStatus(getString(R.string.status_connecting), getString(R.string.detail_establishing))
                    }
                    hubConnection?.start()?.blockingAwait()
                    withContext(Dispatchers.Main) {
                        updateForegroundNotification(getString(R.string.notif_connected_listening))
                        broadcastStatus(getString(R.string.status_connected), getString(R.string.detail_listening))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "connectToSignalR error", e)
                withContext(Dispatchers.Main) {
                    updateForegroundNotification(getString(R.string.notif_retrying, RECONNECT_DELAY_MS / 1000))
                    broadcastStatus(
                        getString(R.string.status_connection_failed),
                        getString(R.string.detail_retrying_in, RECONNECT_DELAY_MS / 1000)
                    )
                }
                safeReconnect()
            }
        }
    }

    private fun safeReconnect() {
        if (isReconnecting) return
        isReconnecting = true
        serviceScope.launch {
            kotlinx.coroutines.delay(RECONNECT_DELAY_MS)
            connectToSignalR()
            isReconnecting = false
        }
    }

    private fun shouldHandle(address: String): Boolean {
        val now = System.currentTimeMillis()
        val same = address.equals(lastAddress, ignoreCase = true)
        val tooSoon = (now - lastAddressAt) < DEBOUNCE_MS
        return if (same && tooSoon) {
            false
        } else {
            lastAddress = address
            lastAddressAt = now
            true
        }
    }

    private fun handleDispatchMessage(message: DispatchMessage) {
        val address = cleanAndFormatAddress(message.address)
        if (!shouldHandle(address)) return

        val incomingId = Ids.normalize(message.policeId)
        val myId = officerId
        val isMine = myId.isNotEmpty() && incomingId == myId

        currentAddressForAction = address
        playNotificationSound()
        showHeadsUpDispatchNotification(address)

        // ⬇️ if you want to restrict auto‑nav to matching RCN, leave this as-is.
        // If you want to always auto‑open, ignore `isMine`.
        if (isMine || myId.isBlank()) {
            openGoogleMaps(address)
            broadcastStatus(getString(R.string.status_dispatch_received), getString(R.string.detail_opening_nav, address.take(40)))
        } else {
            updateForegroundNotification(getString(R.string.notif_dispatch_prefix, address.take(30)))
            broadcastStatus(getString(R.string.status_connected), getString(R.string.detail_server_event, "Dispatch for $incomingId"))
            Log.d(TAG, "Dispatch not for this unit: mine=$myId, incoming=$incomingId")
        }

        saveDispatchToHistory(address, incomingId)

        serviceScope.launch {
            kotlinx.coroutines.delay(2500)
            broadcastStatus(getString(R.string.status_connected), getString(R.string.detail_listening))
        }
    }

    private fun cleanAndFormatAddress(rawAddress: String): String {
        var address = rawAddress.trim()
        if (address.isNotBlank() && !address.contains(",")) {
            address = address + ", " + getString(R.string.default_city_state)
        } else if (
            address.contains(",") &&
            !address.contains("LA", ignoreCase = true) &&
            !address.contains("Louisiana", ignoreCase = true)
        ) {
            address = "$address, LA"
        }
        return address
    }

    private fun playNotificationSound() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(applicationContext, uri)?.play()
        }.onFailure { Log.e(TAG, "Ringtone play failed", it) }
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun openGoogleMaps(address: String) {
        try {
            val encoded = Uri.encode(address)
            val uri = "geo:0,0?q=$encoded".toUri()
            val mapIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.apps.maps")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(mapIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Maps launch failed: $address", e)
            updateForegroundNotification(getString(R.string.notif_maps_launch_failed))
            broadcastStatus(getString(R.string.status_connected), getString(R.string.detail_maps_failed))
        }
    }

    // --- Notifications ---
    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val persistent = NotificationChannel(
            CHANNEL_ID_PERSISTENT,
            getString(R.string.channel_alerts_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.channel_alerts_desc) }

        val alerts = NotificationChannel(
            CHANNEL_ID_ALERTS,
            getString(R.string.channel_alerts_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = getString(R.string.channel_alerts_desc) }

        nm.createNotificationChannel(persistent)
        nm.createNotificationChannel(alerts)
    }

    private fun buildPersistentNotification(text: String): Notification {
        val intent = Intent(
            Intent.ACTION_VIEW,
            "geo:0,0?q=${Uri.encode(currentAddressForAction.ifBlank { getString(R.string.default_city_state) })}".toUri()
        ).apply {
            setPackage("com.google.android.apps.maps")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pending = PendingIntent.getActivity(
            this, 10, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_PERSISTENT)
            .setContentTitle(getString(R.string.notif_ongoing_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_dialog_map, getString(R.string.action_open_maps), pending)
            .build()
    }

    private fun updateForegroundNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID_FOREGROUND, buildPersistentNotification(text))
    }

    private fun showHeadsUpDispatchNotification(address: String) {
        val encoded = Uri.encode(address)
        val mapsIntent = Intent(Intent.ACTION_VIEW, "geo:0,0?q=$encoded".toUri()).apply {
            setPackage("com.google.android.apps.maps")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val requestCode = (address.hashCode() and 0x7fffffff)
        val pending = PendingIntent.getActivity(
            this, requestCode, mapsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, CHANNEL_ID_ALERTS)
            .setContentTitle(getString(R.string.notif_new_dispatch_title))
            .setContentText(address)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID_ALERT, notif)
    }

    private fun disconnectSignalR() {
        runCatching { hubConnection?.stop() }
            .onFailure { Log.e(TAG, "stop() failed", it) }
    }
}
