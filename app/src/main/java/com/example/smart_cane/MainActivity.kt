package com.example.smart_cane

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.smart_cane.databinding.ActivityMainBinding
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    @Volatile
    private var isConnected = false
    private var readThread: Thread? = null

    private val hazardLogs = mutableListOf<HazardLogAdapter.HazardLogEntry>()
    private lateinit var logAdapter: HazardLogAdapter

    private lateinit var notificationManager: NotificationManager
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private lateinit var prefs: SharedPreferences
    private var locationManager: LocationManager? = null
    private var lastKnownLocation: Location? = null

    /** Last readings from `DATA|` lines — used when SOS arrives without fresh sensor packet. */
    private var lastDistanceCm = 0f
    private var lastSmoke = 0
    private var lastTempC = 0f
    private var lastHumidity = 0f

    private var lastSosHandledMs = 0L

    // Alert cooldown state
    private var lastLocalAlertStatus = ""
    private var lastLocalAlertTimeMs = 0L
    private var lastCaretakerAlertTimeMs = 0L
    private var lastTelegramSentTimeMs = 0L
    private var notificationId = 1000

    // Sustained danger tracking
    private var dangerStartTimeMs = 0L
    private var sustainedAlertSent = false

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val REQUEST_BT_PERMISSIONS = 1001
        private const val REQUEST_NOTIF_PERMISSION = 1002
        private const val REQUEST_LOCATION_PERMISSION = 1003
        private const val CHANNEL_HAZARD = "hazard_alerts"
        private const val LOCAL_COOLDOWN_MS = 3000L
        private const val TELEGRAM_COOLDOWN_MS = 60_000L
        private const val SUSTAINED_DANGER_MS = 5000L
        private const val SOS_DEDUP_MS = 12_000L
        private const val TELEGRAM_API = "https://api.telegram.org/bot"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(TelegramPrefs.PREFS_NAME, MODE_PRIVATE)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager

        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter

        setupNotificationChannel()
        requestNotificationPermission()
        requestLocationPermission()
        tts = TextToSpeech(this, this)

        setupRecyclerView()
        setupClickListeners()
        updateConnectionUI(false)
        updateStatusBanner(0f, 0, 0f)
        updateLocationStatusUI()
    }

    override fun onResume() {
        super.onResume()
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            startLocationUpdates()
        }
        updateLocationStatusUI()
    }

    // ── TextToSpeech Callback ────────────────────────────────────────

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            ttsReady = true
        }
    }

    // ── Notification Channel ─────────────────────────────────────────

    private fun setupNotificationChannel() {
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_HAZARD,
                getString(R.string.alert_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.alert_channel_desc)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIF_PERMISSION
                )
            }
        }
    }

    // ── Location ─────────────────────────────────────────────────────

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastKnownLocation = location
            updateLocationStatusUI()
        }

        // Kept for Android <= 9 compatibility where these callbacks are still abstract.
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) {
            updateLocationStatusUI()
        }
    }

    private fun requestLocationPermission() {
        val hasFine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val hasCoarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!hasFine && !hasCoarse) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQUEST_LOCATION_PERMISSION
            )
            updateLocationStatusUI()
        } else {
            startLocationUpdates()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val hasFine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val hasCoarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!hasFine && !hasCoarse) {
            updateLocationStatusUI()
            return
        }
        val lm = locationManager ?: return

        var requestedAnyProvider = false
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            // GPS requires fine location; network provider works with coarse/fine.
            if (provider == LocationManager.GPS_PROVIDER && !hasFine) continue
            if (provider == LocationManager.NETWORK_PROVIDER && !hasCoarse && !hasFine) continue
            if (lm.isProviderEnabled(provider)) {
                try {
                    lm.getLastKnownLocation(provider)?.let { cached ->
                        if (lastKnownLocation == null || cached.time > lastKnownLocation!!.time) {
                            lastKnownLocation = cached
                        }
                    }
                    lm.requestLocationUpdates(provider, 15_000L, 5f, locationListener, Looper.getMainLooper())
                    requestedAnyProvider = true
                } catch (_: SecurityException) {
                    // If permission/provider constraints change at runtime, skip safely.
                }
            }
        }
        if (!requestedAnyProvider) {
            lastKnownLocation = null
        }
        updateLocationStatusUI()
    }

    private fun stopLocationUpdates() {
        locationManager?.removeUpdates(locationListener)
    }

    private fun buildLocationString(): String {
        val loc = lastKnownLocation ?: return getString(R.string.location_unavailable)
        return "https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
    }

    // ── Telegram Helpers ────────────────────────────────────────────

    private fun getBotToken(): String =
        prefs.getString(TelegramPrefs.KEY_BOT_TOKEN, "")?.trim() ?: ""

    private fun getChatIds(): List<String> = TelegramPrefs.readChatIds(prefs)

    // ── RecyclerView & Listeners ─────────────────────────────────────

    private fun setupRecyclerView() {
        logAdapter = HazardLogAdapter(hazardLogs)
        binding.recyclerHazardLog.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = logAdapter
            isNestedScrollingEnabled = false
        }
    }

    private fun setupClickListeners() {
        binding.btnConnect.setOnClickListener {
            if (isConnected) {
                disconnect()
            } else {
                checkPermissionsAndConnect()
            }
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnEnableGps.setOnClickListener {
            val hasLoc = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (!hasLoc) {
                requestLocationPermission()
            } else {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                toast(getString(R.string.toast_location_settings_opened))
            }
        }
    }

    // ── Bluetooth Permissions ────────────────────────────────────────

    private fun checkPermissionsAndConnect() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN))
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION))
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_BT_PERMISSIONS)
        } else {
            showDevicePicker()
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_BT_PERMISSIONS -> {
                val btGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                if (btGranted) {
                    showDevicePicker()
                } else {
                    toast(getString(R.string.bt_permission_required))
                }
                if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                    hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    startLocationUpdates()
                } else {
                    updateLocationStatusUI()
                }
            }
            REQUEST_LOCATION_PERMISSION -> {
                if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                    hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    startLocationUpdates()
                } else {
                    updateLocationStatusUI()
                }
            }
        }
    }

    // ── Device Picker ────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun showDevicePicker() {
        val adapter = bluetoothAdapter ?: run {
            toast(getString(R.string.bt_not_supported))
            return
        }

        if (!adapter.isEnabled) {
            toast(getString(R.string.bt_not_enabled))
            return
        }

        val paired = adapter.bondedDevices?.toList() ?: emptyList()
        if (paired.isEmpty()) {
            toast(getString(R.string.bt_no_paired))
            return
        }

        val names = paired.map { "${it.name ?: "Unknown"}\n${it.address}" }
        val listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)

        AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle(getString(R.string.select_device))
            .setAdapter(listAdapter) { _, which -> connectToDevice(paired[which]) }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ── Bluetooth Connection ─────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        binding.btnConnect.isEnabled = false
        binding.btnConnect.text = getString(R.string.connecting)

        Thread {
            try {
                bluetoothAdapter?.cancelDiscovery()
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                bluetoothSocket = socket
                isConnected = true

                handler.post {
                    updateConnectionUI(true)
                    toast(getString(R.string.bt_connected_to, device.name ?: device.address))
                }

                startReadThread()
            } catch (e: IOException) {
                handler.post {
                    isConnected = false
                    updateConnectionUI(false)
                    toast(getString(R.string.bt_connection_failed, e.localizedMessage ?: "Unknown error"))
                }
                try {
                    bluetoothSocket?.close()
                } catch (_: IOException) { }
            }
        }.start()
    }

    private fun startReadThread() {
        readThread = Thread {
            try {
                val input = bluetoothSocket?.inputStream ?: return@Thread
                val reader = BufferedReader(InputStreamReader(input))
                while (isConnected) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        handler.post { processLine(trimmed) }
                    }
                }
            } catch (e: IOException) {
                if (isConnected) {
                    handler.post {
                        isConnected = false
                        updateConnectionUI(false)
                        toast(getString(R.string.bt_connection_lost))
                    }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun disconnect() {
        isConnected = false
        try {
            bluetoothSocket?.close()
        } catch (_: IOException) { }
        readThread?.interrupt()
        readThread = null
        bluetoothSocket = null
        updateConnectionUI(false)
    }

    // ── Data Parsing ─────────────────────────────────────────────────

    private fun processLine(line: String) {
        // Handle occasional framing noise by trimming and parsing only known payload starts.
        val payload = normalizeBluetoothPayload(line)
        val parts = payload.split('|')
        if (parts.isEmpty()) return

        when (parts[0]) {
            "DATA" -> parseDataMessage(parts)
            "LOG" -> parseLogMessage(parts)
            "SOS" -> handleSosMessage(parts)
        }
    }

    private fun normalizeBluetoothPayload(raw: String): String {
        val cleaned = raw.trim()
        val knownPrefixes = listOf("DATA|", "LOG|", "SOS|")
        for (prefix in knownPrefixes) {
            val idx = cleaned.indexOf(prefix)
            if (idx >= 0) return cleaned.substring(idx)
        }
        return cleaned
    }

    /**
     * Arduino: `SOS|PANIC|HH:MM:SS|USER_NEEDS_RESCUE`
     */
    private fun handleSosMessage(parts: List<String>) {
        val now = System.currentTimeMillis()
        if (now - lastSosHandledMs < SOS_DEDUP_MS) return
        lastSosHandledMs = now

        val caneTime = parts.getOrNull(2) ?: "--:--:--"
        val caneDetail = parts.getOrNull(3) ?: "USER_NEEDS_RESCUE"

        val entry = HazardLogAdapter.HazardLogEntry(
            type = "SOS / PANIC",
            time = caneTime,
            detail = caneDetail
        )
        hazardLogs.add(0, entry)
        logAdapter.notifyItemInserted(0)
        binding.recyclerHazardLog.scrollToPosition(0)
        binding.tvEmptyLog.visibility = View.GONE

        val statusLine = summarizeSituationForTelegram()

        binding.tvStatusBanner.text = getString(R.string.status_sos)
        binding.cardStatusBanner.setCardBackgroundColor(
            ContextCompat.getColor(this, R.color.status_danger)
        )

        vibratePattern(longArrayOf(0, 500, 150, 500, 150, 500, 300, 1000))
        speak(getString(R.string.tts_sos))

        sendHazardNotification(getString(R.string.alert_sos))

        val telegramBody = getString(
            R.string.telegram_msg_sos,
            caneTime,
            lastDistanceCm,
            lastSmoke,
            lastTempC,
            lastHumidity,
            statusLine,
            currentTime()
        )
        sendTelegramMessage(telegramBody, forceSend = true)

        if (getBotToken().isEmpty() || getChatIds().isEmpty()) {
            toast(getString(R.string.sos_telegram_not_configured))
        }
    }

    /** Short situation summary for caretaker text (readings already listed separately in template). */
    private fun summarizeSituationForTelegram(): String {
        val hazard = getString(R.string.status_hazard)
        val danger = getString(R.string.status_danger)
        val warning = getString(R.string.status_warning)
        val clear = getString(R.string.status_clear)
        val banner = binding.tvStatusBanner.text?.toString() ?: clear
        return when (banner) {
            hazard -> "Smoke/temp hazard"
            danger -> "Obstacle danger zone"
            warning -> "Obstacle warning zone"
            clear -> "Was all clear before SOS (cane clock may differ)"
            else -> banner
        }
    }

    private fun parseDataMessage(parts: List<String>) {
        var distance = 0f
        var smoke = 0
        var temp = 0f
        var humidity = 0f

        for (i in 1 until parts.size) {
            val seg = parts[i]
            when {
                seg.startsWith("D:") -> distance = seg.substringAfter("D:").toFloatOrNull() ?: 0f
                seg.startsWith("S:") -> smoke = seg.substringAfter("S:").toIntOrNull() ?: 0
                seg.startsWith("T:") -> temp = seg.substringAfter("T:").toFloatOrNull() ?: 0f
                seg.startsWith("H:") -> humidity = seg.substringAfter("H:").toFloatOrNull() ?: 0f
            }
        }

        lastDistanceCm = distance
        lastSmoke = smoke
        lastTempC = temp
        lastHumidity = humidity

        updateSensorCards(distance, smoke, temp, humidity)
        updateStatusBanner(distance, smoke, temp)
    }

    private fun parseLogMessage(parts: List<String>) {
        if (parts.size >= 4) {
            val entry = HazardLogAdapter.HazardLogEntry(
                type = parts[1],
                time = parts[2],
                detail = parts[3]
            )
            hazardLogs.add(0, entry)
            logAdapter.notifyItemInserted(0)
            binding.recyclerHazardLog.scrollToPosition(0)
            binding.tvEmptyLog.visibility = View.GONE
        }
    }

    // ── UI Updates ───────────────────────────────────────────────────

    private fun updateSensorCards(distance: Float, smoke: Int, temp: Float, humidity: Float) {
        binding.tvDistanceValue.text = String.format(Locale.US, "%.0f", distance)
        binding.tvSmokeValue.text = smoke.toString()
        binding.tvTempValue.text = String.format(Locale.US, "%.1f", temp)
        binding.tvHumidityValue.text = String.format(Locale.US, "%.1f", humidity)
    }

    private fun updateStatusBanner(distance: Float, smoke: Int, temp: Float) {
        val (status, colorRes) = when {
            smoke > 400 || temp > 45 -> getString(R.string.status_hazard) to R.color.status_danger
            distance in 0.1f..29.9f  -> getString(R.string.status_danger) to R.color.status_danger
            distance in 30f..79.9f   -> getString(R.string.status_warning) to R.color.status_warning
            else                     -> getString(R.string.status_clear) to R.color.status_clear
        }

        binding.tvStatusBanner.text = status
        binding.cardStatusBanner.setCardBackgroundColor(ContextCompat.getColor(this, colorRes))

        handleAlerts(status, distance, smoke, temp)
    }

    // ── Two-Tier Alert System ────────────────────────────────────────

    private fun handleAlerts(status: String, distance: Float, smoke: Int, temp: Float) {
        val clearStatus = getString(R.string.status_clear)
        val hazardStatus = getString(R.string.status_hazard)
        val dangerStatus = getString(R.string.status_danger)
        val warningStatus = getString(R.string.status_warning)

        // Reset everything when clear
        if (status == clearStatus) {
            lastLocalAlertStatus = ""
            dangerStartTimeMs = 0L
            sustainedAlertSent = false
            return
        }

        // ── TIER 2: Caretaker alerts (Telegram + notification) ────────
        if (status == hazardStatus) {
            dangerStartTimeMs = 0L
            sustainedAlertSent = false
            triggerLocalAlert(hazardStatus, smoke, temp)
            triggerCaretakerAlert(
                getString(R.string.alert_smoke_temp),
                getString(R.string.telegram_msg_smoke_temp, currentTime()),
                isCritical = true
            )
            return
        }

        // ── Sustained danger tracking ────────────────────────────────
        if (status == dangerStatus) {
            val now = System.currentTimeMillis()

            // Start the timer if this is the first danger reading
            if (dangerStartTimeMs == 0L) {
                dangerStartTimeMs = now
            }

            // Always give local feedback to the blind user
            triggerLocalAlert(dangerStatus, smoke, temp)

            // Escalate to caretaker only after 5 continuous seconds
            val sustained = (now - dangerStartTimeMs) >= SUSTAINED_DANGER_MS
            if (sustained && !sustainedAlertSent) {
                sustainedAlertSent = true
                triggerCaretakerAlert(
                    getString(R.string.alert_sustained_obstacle),
                    getString(R.string.telegram_msg_sustained_obstacle, currentTime())
                )
            }
            return
        }

        // ── TIER 1: Local-only alerts (warning zone) ─────────────────
        if (status == warningStatus) {
            dangerStartTimeMs = 0L
            sustainedAlertSent = false
            triggerLocalAlert(warningStatus, smoke, temp)
        }
    }

    // ── Tier 1: Local Alert (vibration + TTS for blind user) ─────────

    private fun triggerLocalAlert(status: String, smoke: Int, temp: Float) {
        val now = System.currentTimeMillis()
        val statusChanged = status != lastLocalAlertStatus
        val cooldownElapsed = (now - lastLocalAlertTimeMs) >= LOCAL_COOLDOWN_MS
        if (!statusChanged && !cooldownElapsed) return

        lastLocalAlertStatus = status
        lastLocalAlertTimeMs = now

        val hazardStatus = getString(R.string.status_hazard)
        val dangerStatus = getString(R.string.status_danger)
        val warningStatus = getString(R.string.status_warning)

        when (status) {
            hazardStatus -> {
                vibratePattern(longArrayOf(0, 1000))
                speak(getString(R.string.tts_smoke_temp))
            }
            dangerStatus -> {
                vibratePattern(longArrayOf(0, 200, 100, 200, 100, 200))
                speak(getString(R.string.tts_danger_obstacle))
            }
            warningStatus -> {
                vibratePattern(longArrayOf(0, 300, 200, 300))
                speak(getString(R.string.tts_warning_obstacle))
            }
        }
    }

    // ── Tier 2: Caretaker Alert (notification + Telegram) ───────────

    private fun triggerCaretakerAlert(
        notifBody: String,
        telegramBody: String,
        isCritical: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        val cooldownElapsed = (now - lastCaretakerAlertTimeMs) >= LOCAL_COOLDOWN_MS
        if (!isCritical && !cooldownElapsed) return
        lastCaretakerAlertTimeMs = now

        sendHazardNotification(notifBody)
        sendTelegramMessage(telegramBody, isCritical)
    }

    private fun sendHazardNotification(body: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_HAZARD)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(getString(R.string.alert_title))
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            return
        }

        notificationManager.notify(notificationId++, notification)
    }

    private fun sendTelegramMessage(message: String, forceSend: Boolean = false) {
        val token = getBotToken()
        val chatIds = getChatIds()
        if (token.isEmpty() || chatIds.isEmpty()) return

        val now = System.currentTimeMillis()
        if (!forceSend && (now - lastTelegramSentTimeMs) < TELEGRAM_COOLDOWN_MS) return
        lastTelegramSentTimeMs = now

        val location = lastKnownLocation
        val locationLine = if (location != null) {
            "\n📍 Location: https://maps.google.com/?q=${location.latitude},${location.longitude}"
        } else {
            "\n📍 Location: ${getString(R.string.location_unavailable)}"
        }
        val fullMessage = message + locationLine

        Thread {
            for (chatId in chatIds) {
                try {
                    val encodedMsg = URLEncoder.encode(fullMessage, "UTF-8")
                    val url = URL("${TELEGRAM_API}$token/sendMessage?chat_id=$chatId&text=$encodedMsg")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    conn.responseCode
                    conn.disconnect()

                    if (location != null) {
                        val locationUrl = URL(
                            "${TELEGRAM_API}$token/sendLocation?chat_id=$chatId" +
                                "&latitude=${location.latitude}&longitude=${location.longitude}"
                        )
                        val locationConn = locationUrl.openConnection() as HttpURLConnection
                        locationConn.requestMethod = "GET"
                        locationConn.connectTimeout = 10_000
                        locationConn.readTimeout = 10_000
                        locationConn.responseCode
                        locationConn.disconnect()
                    }
                } catch (_: Exception) { }
            }
        }.start()
    }

    // ── Vibration & TTS ──────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun vibratePattern(pattern: LongArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            val vibrator = vibratorManager.defaultVibrator
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    private fun speak(message: String) {
        if (ttsReady) {
            tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "hazard_${System.currentTimeMillis()}")
        }
    }

    private fun currentTime(): String =
        SimpleDateFormat("hh:mm:ss a", Locale.US).format(Date())

    // ── Connection UI ────────────────────────────────────────────────

    private fun updateConnectionUI(connected: Boolean) {
        binding.btnConnect.isEnabled = true
        if (connected) {
            binding.btnConnect.text = getString(R.string.disconnect)
            val green = ContextCompat.getColor(this, R.color.connected_green)
            binding.viewConnectionDot.backgroundTintList = ColorStateList.valueOf(green)
            binding.tvConnectionStatus.text = getString(R.string.connected)
            binding.tvConnectionStatus.setTextColor(green)
        } else {
            binding.btnConnect.text = getString(R.string.connect)
            val red = ContextCompat.getColor(this, R.color.disconnected_red)
            binding.viewConnectionDot.backgroundTintList = ColorStateList.valueOf(red)
            binding.tvConnectionStatus.text = getString(R.string.disconnected)
            binding.tvConnectionStatus.setTextColor(red)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun updateLocationStatusUI() {
        val hasFine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val hasCoarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        val lm = locationManager
        val providerEnabled = lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
            lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true

        val (textRes, colorRes) = when {
            !hasFine && !hasCoarse ->
                R.string.location_status_permission_needed to R.color.status_warning
            !providerEnabled ->
                R.string.location_status_disabled to R.color.status_warning
            lastKnownLocation != null ->
                R.string.location_status_ready to R.color.status_clear
            else ->
                R.string.location_status_searching to R.color.card_stroke
        }

        binding.tvLocationStatus.text = getString(textRes)
        binding.tvLocationStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        tts?.stop()
        tts?.shutdown()
        tts = null
        disconnect()
    }
}
