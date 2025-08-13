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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.example.bp_gps.R
import com.example.bp_gps.model.DispatchMessage
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.microsoft.signalr.HubConnectionState
import com.microsoft.signalr.TransportEnum
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class NavigationService : Service() {

    companion object {
        private const val TAG = "NavigationService"
        private const val CHANNEL_ID_PERSISTENT = "NavigationServiceChannel"
        private const val CHANNEL_ID_ALERTS = "NavigationAlerts"
        private const val NOTIF_PERSISTENT_ID = 1
        private const val NOTIF_DISPATCH_ID = 2
        private const val NOTIF_CONN_ALERT_ID = 1002
        private const val NEGOTIATE_URL = "https://bp-gps-app.azurewebsites.net/api/negotiate"
        private const val RECONNECT_DELAY_MS = 5000L
        private const val DEBOUNCE_MS = 3000L
        private const val MAX_HISTORY_SIZE = 50
        const val STATUS_UPDATE_ACTION = "bp_gps.STATUS_UPDATE"
        const val HISTORY_UPDATE_ACTION = "bp_gps.HISTORY_UPDATE"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_DETAIL = "extra_detail"
        const val EXTRA_STATUS_CODE = "extra_status_code"
        const val CODE_STARTING = "STARTING"
        const val CODE_CONNECTING = "CONNECTING"
        const val CODE_CONNECTED = "CONNECTED"
        const val CODE_DISCONNECTED = "DISCONNECTED"
        const val CODE_CONNECTION_FAILED = "CONNECTION_FAILED"
        const val CODE_SETUP_FAILED = "SETUP_FAILED"
        const val CODE_DISPATCH = "DISPATCH"
        const val CODE_STOPPED = "STOPPED"
        private const val PREFS = "bp_gps_prefs"
        private const val KEY_OFFICER_ID = "officer_id"
        private const val HISTORY_PREFS = "bp_gps_dispatch_history"
        private const val HISTORY_KEY = "dispatch_history"
    }

    private data class HistoryRow(val address: String, val officerId: String, val timestamp: Long)
    private var hub: HubConnection? = null
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private var officerId: String = ""
    private var lastAddress: String? = null
    private var lastAddressAt = 0L
    private var currentAddressForAction: String = ""
    private var isReconnecting = false
    private var lastStatusCode: String? = null


    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        officerId = intent?.getStringExtra("officer_id")
            ?: getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_OFFICER_ID, "").orEmpty()

        // Start foreground with a “connecting” message
        startForeground(NOTIF_PERSISTENT_ID, buildPersistentNotification(getString(R.string.notif_connecting)))
        broadcastStatus(CODE_STARTING, getString(R.string.detail_initializing))

        connectToSignalR()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        broadcastStatus(CODE_STOPPED, getString(R.string.detail_service_stopped))
        disconnectSignalR()
        job.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    private fun broadcastStatus(code: String, detail: String = "") {
        val statusText = when (code) {
            CODE_STARTING          -> getString(R.string.status_starting)
            CODE_CONNECTING        -> getString(R.string.status_connecting)
            CODE_CONNECTED         -> getString(R.string.status_connected)
            CODE_DISCONNECTED      -> getString(R.string.status_disconnected)
            CODE_CONNECTION_FAILED -> getString(R.string.status_connection_failed)
            CODE_SETUP_FAILED      -> getString(R.string.status_setup_failed)
            CODE_DISPATCH          -> getString(R.string.status_dispatch_received)
            CODE_STOPPED           -> getString(R.string.status_stopped)
            else -> code
        }

        sendBroadcast(Intent(STATUS_UPDATE_ACTION).apply {
            putExtra(EXTRA_STATUS_CODE, code)
            putExtra(EXTRA_STATUS, statusText)
            putExtra(EXTRA_DETAIL, detail)
        })

        val isConnectionLost = (code == CODE_DISCONNECTED || code == CODE_CONNECTION_FAILED)
        if (isConnectionLost && lastStatusCode != code) {
            showConnectionLostNotification()
        }

        lastStatusCode = code
    }


    private fun broadcastHistoryUpdate() {
        sendBroadcast(Intent(HISTORY_UPDATE_ACTION))
    }

    private fun saveHistory(address: String, officer: String) {
        try {
            val prefs = getSharedPreferences(HISTORY_PREFS, MODE_PRIVATE)
            val current = loadHistory().toMutableList()
            current.add(0, HistoryRow(address, officer, System.currentTimeMillis()))
            if (current.size > MAX_HISTORY_SIZE) current.subList(MAX_HISTORY_SIZE, current.size).clear()

            val arr = JSONArray()
            current.forEach {
                arr.put(JSONObject().apply {
                    put("address", it.address)
                    put("officerId", it.officerId)
                    put("timestamp", it.timestamp)
                })
            }
            prefs.edit { putString(HISTORY_KEY, arr.toString()) }
            broadcastHistoryUpdate()
        } catch (e: Exception) {
            Log.e(TAG, "History save failed", e)
        }
    }

    private fun loadHistory(): List<HistoryRow> = try {
        val prefs = getSharedPreferences(HISTORY_PREFS, MODE_PRIVATE)
        val json = prefs.getString(HISTORY_KEY, null) ?: return emptyList()
        val arr = JSONArray(json)
        MutableList(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            HistoryRow(o.getString("address"), o.optString("officerId", ""), o.getLong("timestamp"))
        }
    } catch (e: Exception) {
        Log.e(TAG, "History load failed", e)
        emptyList()
    }
    private fun connectToSignalR() {
        scope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    updatePersistent(getString(R.string.notif_negotiating))
                    broadcastStatus(CODE_CONNECTING, getString(R.string.detail_negotiating))
                }

                val result = negotiateConnection() ?: throw Exception("Negotiate failed")
                val (url, token) = result

                withContext(Dispatchers.Main) { setupSignalR(url, token) }

                if (hub?.connectionState == HubConnectionState.DISCONNECTED) {
                    withContext(Dispatchers.Main) {
                        updatePersistent(getString(R.string.notif_connecting_dispatch))
                        broadcastStatus(CODE_CONNECTING, getString(R.string.detail_establishing))
                    }
                    hub?.start()?.blockingAwait() // still on IO
                    withContext(Dispatchers.Main) {
                        updatePersistent(getString(R.string.notif_connected_listening))
                        broadcastStatus(CODE_CONNECTED, getString(R.string.detail_listening))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "connectToSignalR error", e)
                withContext(Dispatchers.Main) {
                    updatePersistent(getString(R.string.notif_retrying, RECONNECT_DELAY_MS / 1000))
                    broadcastStatus(
                        CODE_CONNECTION_FAILED,
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
        scope.launch {
            delay(RECONNECT_DELAY_MS)
            connectToSignalR()
            isReconnecting = false
        }
    }

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
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val resp = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(resp)
                Pair(json.getString("url"), json.optString("accessToken", ""))
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "negotiateConnection failed", e)
            null
        }
    }

    private fun setupSignalR(url: String, token: String) {
        try {
            val builder = HubConnectionBuilder.create(url)
                .shouldSkipNegotiate(true)
                .withTransport(TransportEnum.WEBSOCKETS)
                .withHandshakeResponseTimeout(60000)
                .withServerTimeout(300000)

            if (token.isNotEmpty()) builder.withHeader("Authorization", "Bearer $token")

            hub = builder.build()
            hub?.on("newAddress", { msg: DispatchMessage -> handleDispatch(msg) }, DispatchMessage::class.java)
            hub?.on("ReceiveDispatch", { msg: DispatchMessage -> handleDispatch(msg) }, DispatchMessage::class.java)

            listOf("Connected", "Disconnected", "Ping", "Heartbeat").forEach { name ->
                hub?.on(name) {
                    updatePersistent(getString(R.string.notif_server_event, name))
                    broadcastStatus(CODE_CONNECTED, getString(R.string.detail_server_event, name))
                }
            }

            hub?.onClosed {
                updatePersistent(getString(R.string.notif_conn_lost))
                broadcastStatus(CODE_DISCONNECTED, getString(R.string.detail_reconnecting))
                safeReconnect()
            }

        } catch (e: Exception) {
            Log.e(TAG, "setupSignalR error", e)
            updatePersistent(getString(R.string.notif_setup_failed))
            broadcastStatus(CODE_SETUP_FAILED, getString(R.string.detail_retrying))
            safeReconnect()
        }
    }

    private fun disconnectSignalR() {
        runCatching { hub?.stop() }.onFailure { Log.e(TAG, "hub stop failed", it) }
    }

    private fun shouldHandle(address: String): Boolean {
        val now = System.currentTimeMillis()
        val same = address.equals(lastAddress, ignoreCase = true)
        val tooSoon = (now - lastAddressAt) < DEBOUNCE_MS
        return if (same && tooSoon) false else {
            lastAddress = address
            lastAddressAt = now
            true
        }
    }

    private fun handleDispatch(message: DispatchMessage) {
        val raw = message.address
        if (raw.isBlank()) return

        val address = cleanAndFormatAddress(raw)
        val incomingOfficer = message.policeId.orEmpty()
        saveHistory(address, incomingOfficer)

        val shouldNavigate = incomingOfficer.isNotBlank() &&
                officerId.isNotBlank() &&
                incomingOfficer.equals(officerId, ignoreCase = true)

        if (!shouldHandle(address)) return

        if (shouldNavigate) {
            currentAddressForAction = address
            playNotificationSound()
            openGoogleMaps(address)
            showDispatchNotification(address)
            broadcastStatus(CODE_DISPATCH, getString(R.string.detail_opening_nav, address.take(40)))

            scope.launch {
                delay(3000)
                broadcastStatus(CODE_CONNECTED, getString(R.string.detail_listening))
            }
        } else {
            broadcastStatus(CODE_CONNECTED, getString(R.string.detail_dispatch_other_officer, incomingOfficer))
        }
    }

    private fun cleanAndFormatAddress(raw: String): String {
        var a = raw.trim()
        if (a.isNotBlank() && !a.contains(",")) {
            a += ", " + getString(R.string.default_city_state)
        } else if (a.contains(",") && !a.contains("LA", true) && !a.contains("Louisiana", true)) {
            a += ", LA"
        }
        return a
    }

    private fun playNotificationSound() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(applicationContext, uri)?.play()
        }.onFailure { Log.e(TAG, "sound failed", it) }
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun openGoogleMaps(address: String) {
        runCatching {
            val uri = "geo:0,0?q=${Uri.encode(address)}".toUri()
            startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.apps.maps")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }.onFailure {
            Log.e(TAG, "Maps launch failed: $address", it)
            updatePersistent(getString(R.string.notif_maps_launch_failed))
            broadcastStatus(CODE_CONNECTED, getString(R.string.detail_maps_failed))
        }
    }

    private fun showDispatchNotification(address: String) {
        val encoded = Uri.encode(address)
        val mapsIntent = Intent(Intent.ACTION_VIEW, "geo:0,0?q=$encoded".toUri()).apply {
            setPackage("com.google.android.apps.maps")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, mapsIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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
            .notify(NOTIF_DISPATCH_ID, notif)
    }
    private fun showConnectionLostNotification() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_ALERTS) // use existing alerts channel
            .setContentTitle(getString(R.string.notif_conn_lost))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .build()

        androidx.core.app.NotificationManagerCompat.from(this)
            .notify(NOTIF_CONN_ALERT_ID, notification)
    }


    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val persistent = NotificationChannel(
            CHANNEL_ID_PERSISTENT,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.channel_desc) }

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
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_dialog_map, getString(R.string.action_open_maps), pending)
            .build()
    }

    private fun updatePersistent(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_PERSISTENT_ID, buildPersistentNotification(text))
    }
}
