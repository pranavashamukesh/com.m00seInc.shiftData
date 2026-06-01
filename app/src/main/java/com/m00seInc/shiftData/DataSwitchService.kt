package com.m00seInc.shiftData

import android.telephony.TelephonyManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.telephony.TelephonyCallback
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job

class DataSwitchService : Service() {

    private var screenStateReceiver: BroadcastReceiver? = null

    //private var lastLoggedStatus: String? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val inMemoryLogs = mutableListOf<String>()

    private val logsMutex = Mutex()
    private val toggleMutex = Mutex()

    private enum class DataControlState {
        AUTOMATED_ON,
        AUTOMATED_OFF,
        MANUAL_USER_CONTROL
    }

    private var currentControlState = DataControlState.AUTOMATED_ON
    private val isInternalToggle = AtomicBoolean(false)

    private lateinit var audioManager: AudioManager

    // Local RAM state for media
    private var isMediaPlayingLocally = false
    private var isCallActiveLocally = false
    private val audioPlaybackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
            var mediaActive = false
            var voipActive = false

            configs.forEach { config ->
                val attrs = config.audioAttributes
                when (attrs.usage) {
                    // Entertainment & Gaming streams
                    android.media.AudioAttributes.USAGE_MEDIA -> {
                        // STRICT FILTER: Ensure it is a valid long-form entertainment stream
                        val contentType = attrs.contentType
                        if (contentType == android.media.AudioAttributes.CONTENT_TYPE_MUSIC ||
                            contentType == android.media.AudioAttributes.CONTENT_TYPE_MOVIE ||
                            contentType == android.media.AudioAttributes.CONTENT_TYPE_SPEECH) {
                            mediaActive = true
                        }
                    }

                    android.media.AudioAttributes.USAGE_GAME -> {
                        mediaActive = true
                    }

                    // VoIP voice/video communication streams (WhatsApp, Signal, Zoom, Teams)
                    android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION,
                    android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING -> {
                        voipActive = true
                    }
                }
            }

            // Detect state changes for logging
            val wasMediaPlaying = isMediaPlayingLocally
            val wasVoipActive = isVoipCallActiveLocally

            isMediaPlayingLocally = mediaActive
            isVoipCallActiveLocally = voipActive

            if (wasMediaPlaying != isMediaPlayingLocally) {
                Log.d("DataSwitchService", "Audio Evaluator -> Media Track State Active: $isMediaPlayingLocally")
            }
            if (wasVoipActive != isVoipCallActiveLocally) {
                Log.d("DataSwitchService", "Audio Evaluator -> VoIP Call State Active: $isVoipCallActiveLocally")
            }
        }
    }

    private lateinit var telephonyManager: TelephonyManager

    private val phoneStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                val stateString = intent.getStringExtra(TelephonyManager.EXTRA_STATE)

                val wasCallActive = isCallActiveLocally

                isCallActiveLocally = when (stateString) {
                    TelephonyManager.EXTRA_STATE_RINGING,
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> true

                    TelephonyManager.EXTRA_STATE_IDLE -> false
                    else -> isCallActiveLocally
                }

                if (wasCallActive != isCallActiveLocally) {
                    Log.d(
                        "DataSwitchService",
                        "Broadcast: Call State Changed to $stateString (Active: $isCallActiveLocally)"
                    )
                }
            }
        }
    }

    private var isHotspotActiveLocally = false

    private val hotspotReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "android.net.wifi.WIFI_AP_STATE_CHANGED") {
                val state = intent.getIntExtra("wifi_state", 11) // 11 is disabled
                // 13 is AP_STATE_ENABLED
                isHotspotActiveLocally = (state == 13)
                Log.d("DataSwitchService", "Hotspot Callback: Active=$isHotspotActiveLocally")
            }
        }
    }

    private var isMobileDataEnabled = false
    private var isVoipCallActiveLocally = false

    /*private lateinit var connectivityManager: ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            isMobileDataEnabled = true
            Log.d("ShiftData_LeakCheck", "[Instance:${this@DataSwitchService.hashCode()}] Callback -> Cellular Data Available")
        }

        override fun onLost(network: Network) {
            isMobileDataEnabled = false
            Log.d("ShiftData_LeakCheck", "[Instance:${this@DataSwitchService.hashCode()}] Callback -> Cellular Data Lost/Disabled")
        }
    }*/

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // 🛠️ REPLACE your entire registerDataToggleListener() function with this:
    private fun registerDataToggleListener() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                isMobileDataEnabled = true
                Log.d("ShiftData_LeakCheck", "[Instance:${this@DataSwitchService.hashCode()}] NetworkCallback -> Cellular Data Available")

                // 🛡️ MANUAL SWITCH DETECTOR: Check if the change came from a user or our internal toggle logic
                if (!isInternalToggle.get()) {
                    currentControlState = DataControlState.MANUAL_USER_CONTROL
                    Log.d("DataSwitchService", "NetworkCallback -> Manual User Toggle (Data Enabled) Detected")
                }
            }

            override fun onLost(network: Network) {
                isMobileDataEnabled = false
                Log.d("ShiftData_LeakCheck", "[Instance:${this@DataSwitchService.hashCode()}] NetworkCallback -> Cellular Data Lost/Disabled")

                // 🛡️ MANUAL SWITCH DETECTOR
                if (!isInternalToggle.get()) {
                    currentControlState = DataControlState.MANUAL_USER_CONTROL
                    Log.d("DataSwitchService", "NetworkCallback -> Manual User Toggle (Data Disabled) Detected")
                }
            }
        }
        networkCallback = callback

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()

        connectivityManager?.registerNetworkCallback(request, callback)

        // Initial read to sync up RAM state immediately on start
        isMobileDataEnabled = Settings.Global.getInt(contentResolver, "mobile_data", 0) == 1
    }

    private var shiftJob: Job? = null // Tracks the active pending task
    private lateinit var powerManager: PowerManager
    private lateinit var prefs: android.content.SharedPreferences // Cache the prefs too
    private val dateFormatter =
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
    private val isManualLocked = AtomicBoolean(false)

    companion object {
        var isRunning = false
        private const val CHANNEL_ID = "shiftData_channel"
        private const val NOTIFICATION_ID = 1
        private const val MAX_LOG_ENTRIES = 20
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("ShiftData_LeakCheck", "==================================================")
        Log.d("ShiftData_LeakCheck", "[Instance:${this.hashCode()}] onCreate() initialized")
        Log.d("ShiftData_LeakCheck", "==================================================")
        // Cache the system services once!
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        prefs = getSharedPreferences("shift_logs", MODE_PRIVATE)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.registerAudioPlaybackCallback(
            audioPlaybackCallback,
            Handler(Looper.getMainLooper())
        )

        val bootConfigs = audioManager.activePlaybackConfigurations
        var bootMediaActive = false
        var bootVoipActive = false

        bootConfigs.forEach { config ->
            when (config.audioAttributes.usage) {
                android.media.AudioAttributes.USAGE_MEDIA,
                android.media.AudioAttributes.USAGE_GAME -> {
                    bootMediaActive = true
                }
                android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION,
                android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING -> {
                    bootVoipActive = true
                }
            }
        }

        // Apply findings with a safety fallback for music trackers
        isMediaPlayingLocally = bootMediaActive || audioManager.isMusicActive
        isVoipCallActiveLocally = bootVoipActive

        Log.d(
            "DataSwitchService",
            "Boot Audio Table Sync Completed -> Legitimate Media: $isMediaPlayingLocally | Legitimate VoIP: $isVoipCallActiveLocally"
        )

        // Initial sync
        // Register the Phone State Receiver
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        registerReceiver(phoneStateReceiver, filter)
        val hasPerm = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.READ_PHONE_STATE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (hasPerm) {
            // This pulls the CURRENT radio state immediately
            val initialState = telephonyManager.callState
            isCallActiveLocally = (initialState != TelephonyManager.CALL_STATE_IDLE)

            Log.d(
                "DataSwitchService",
                "Initial Call Sync: Active=$isCallActiveLocally (State=$initialState)"
            )
        } else {
            // Fallback to AudioManager if telephony permission is missing/denied
            isCallActiveLocally = (audioManager.mode == AudioManager.MODE_IN_CALL ||
                    audioManager.mode == AudioManager.MODE_IN_COMMUNICATION)
            Log.d(
                "DataSwitchService",
                "Initial Call Sync (Audio Fallback): Active=$isCallActiveLocally"
            )
        }

        val hotspotFilter = IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED")
        registerReceiver(hotspotReceiver, hotspotFilter)
        isHotspotActiveLocally = Settings.Global.getInt(contentResolver, "wifi_ap_state", 0) == 1
        /*
        val mobileDataUri = Settings.Global.getUriFor("mobile_data")
        contentResolver.registerContentObserver(mobileDataUri, false, mobileDataObserver)
        */
        //connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        //val request = NetworkRequest.Builder()
        //    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        //    .build()
        //connectivityManager.registerNetworkCallback(request, networkCallback)

        //isMobileDataEnabled = Settings.Global.getInt(contentResolver, "mobile_data", 0) == 1
        registerDataToggleListener()

        serviceScope.launch {
            logsMutex.withLock {
                val savedLogs = prefs.getString("logs_list", "") ?: ""
                inMemoryLogs.clear()
                if (savedLogs.isNotEmpty()) {
                    inMemoryLogs.addAll(savedLogs.split("|"))
                }

                // 2. ADD THIS LINE:
                isManualLocked.set(prefs.getBoolean("manual_lock_active", false))
            }
        }
        createNotificationChannel()
        registerScreenReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        updateUI()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        val instanceId = this.hashCode()

        Log.d("ShiftData_LeakCheck", "==================================================")
        Log.d("ShiftData_LeakCheck", "[Instance:$instanceId] onDestroy() triggered. Starting teardown...")
        Log.d("ShiftData_LeakCheck", "==================================================")

        unregisterScreenReceiver()
        try {
            Log.d("ShiftData_LeakCheck", "[Instance:$instanceId] Attempting Audio Callback unregistration...")
            if (::audioManager.isInitialized) {
                audioManager.unregisterAudioPlaybackCallback(audioPlaybackCallback)
            }
            Log.d("ShiftData_LeakCheck", "[Instance:$instanceId] Audio Callback unregistered successfully.")

            Log.d("ShiftData_LeakCheck", "[Instance:$instanceId] Attempting Phone State Receiver unregistration...")
            unregisterReceiver(phoneStateReceiver)
            Log.d("ShiftData_LeakCheck", "[Instance:$instanceId] Phone State Receiver unregistered successfully.")

            Log.d("ShiftData_LeakCheck", "[Instance:$instanceId] Attempting Hotspot Receiver unregistration...")
            unregisterReceiver(hotspotReceiver)
            Log.d("ShiftData_LeakCheck", "[Instance:$instanceId] Hotspot Receiver unregistered successfully.")

            networkCallback?.let {
                Log.d("ShiftData_LeakCheck", "[Instance:$instanceId] Attempting Network Callback unregistration...")
                connectivityManager?.unregisterNetworkCallback(it)
                Log.d("DataSwitchService", "NetworkCallback unregistered cleanly.")
            }

        } catch (e: Exception) {
            Log.e("ShiftData_LeakCheck", "[Instance:$instanceId] CRITICAL TEARDOWN FAILURE! Chain broken.", e)
        }

        updateUI()
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerScreenReceiver() {
        val filter =
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.d("ShiftData_Lifecycle", "[HARDWARE] 🔒 Phone Locked (SCREEN_OFF)")
                        shiftMobileData(false)
                    }

                    Intent.ACTION_SCREEN_ON -> {
                        Log.d("ShiftData_Lifecycle", "[HARDWARE] 🔓 Phone Unlocked (SCREEN_ON)")
                        shiftMobileData(true)
                    }
                }
            }
        }
        screenStateReceiver = receiver
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun unregisterScreenReceiver() {
        screenStateReceiver?.let { unregisterReceiver(it); screenStateReceiver = null }
    }

    private fun shiftMobileData(enable: Boolean) {
        shiftJob?.cancel()
        shiftJob = serviceScope.launch {
            if (enable) {
                delay(1000)
            }
            toggleMutex.withLock {
                try {
                    var manualLockState = isManualLocked.get()

                    if (enable) {
                        // LAZY ENABLE LOGIC
                        when {
                            isHotspotActive() -> {
                                logDataStateChange("HOT_ON")
                                currentControlState = DataControlState.AUTOMATED_ON
                            }

                            isCallActive() -> {
                                currentControlState = DataControlState.AUTOMATED_ON
                                if (!isMobileDataEnabled) {
                                    toggleData(1)
                                    isMobileDataEnabled = true
                                    logDataStateChange("ON")
                                } else logDataStateChange("CALL_ON")
                            }

                            isMediaPlaying() -> {
                                currentControlState = DataControlState.AUTOMATED_ON
                                if (!isMobileDataEnabled) {
                                    toggleData(1)
                                    isMobileDataEnabled = true
                                    logDataStateChange("ON")
                                } else logDataStateChange("MED_ON")
                            }

                            !manualLockState -> {
                                currentControlState = DataControlState.AUTOMATED_ON
                                if (isMobileDataEnabled) logDataStateChange("BY_ON")
                                else {
                                    logDataStateChange("ON")
                                    toggleData(1)
                                    isMobileDataEnabled = true
                                }
                            }

                            else -> {
                                if (isMobileDataEnabled) {
                                    currentControlState = DataControlState.AUTOMATED_ON
                                    logDataStateChange("BY_ON")
                                } else logDataStateChange("MAN_ON")
                            }
                        }
                    } else {
                        // LAZY DISABLE LOGIC

                        if (manualLockState && isMobileDataEnabled) {
                            updateManualLock(false)
                            manualLockState = false
                        }

                        // CONFIRMED USER ACTION: The user deliberately turned it off manually
                        // and the current control state confirms it isn't an automated pass
                        val isTrueManualOff = (currentControlState == DataControlState.MANUAL_USER_CONTROL) && !isMobileDataEnabled
                        val needsLock = isTrueManualOff || isHotspotActive() || isCallActive() || isMediaPlaying()

                        if (needsLock) {
                            updateManualLock(true)
                        }

                        when {
                            isHotspotActive() -> logDataStateChange("HOT_OFF")
                            isCallActive() -> logDataStateChange("CALL_OFF")
                            isMediaPlaying() -> logDataStateChange("MED_OFF")
                            isTrueManualOff -> logDataStateChange("MAN_OFF")
                            else -> {
                                logDataStateChange("OFF")
                                currentControlState = DataControlState.AUTOMATED_OFF
                                toggleData(0)
                                isMobileDataEnabled = false
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

// --- PURE MEMORY/CACHED HELPERS ---

    private fun isHotspotActive(): Boolean = isHotspotActiveLocally

    private fun isCallActive(): Boolean = isCallActiveLocally || isVoipCallActiveLocally

    private fun isMediaPlaying(): Boolean = isMediaPlayingLocally

    private suspend fun toggleData(state: Int) {
        withContext(NonCancellable) {
            isInternalToggle.set(true)
            try {
                Settings.Global.putInt(contentResolver, "mobile_data", state)
                Log.d(
                    "DataSwitchService",
                    "Media data switched to: $state"
                )
            } finally {
                // Give the system registry a brief window to complete its broadcast cycle
                delay(200)
                isInternalToggle.set(false)
            }
        }
    }

    private fun updateManualLock(locked: Boolean) {
        isManualLocked.set(locked)
        prefs.edit { putBoolean("manual_lock_active", locked) }
    }

    private suspend fun logDataStateChange(status: String) {
        val timestamp = LocalDateTime.now().format(dateFormatter)
        val newEntry = "$timestamp - $status"

        withContext(NonCancellable) {
            logsMutex.withLock {
                inMemoryLogs.add(0, newEntry)
                if (inMemoryLogs.size > MAX_LOG_ENTRIES) {
                    inMemoryLogs.removeAt(inMemoryLogs.lastIndex)
                }
                val truncatedLogs = inMemoryLogs.joinToString("|")
                prefs.edit {
                    putString("logs_list", truncatedLogs)
                }
            }
        }
        updateUI()
    }

    private fun updateUI() {
        // Efficiency fix: Only broadcast to the UI if the screen is actually on
        if (powerManager.isInteractive) {
            val updateIntent =
                Intent("com.m00seInc.shiftData.UPDATE_UI").apply { setPackage(packageName) }
            sendBroadcast(updateIntent)
        }
    }

    private fun buildNotification(): Notification {
        // 1. Create the intent that targets your app's UI
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        // 2. Wrap it in a PendingIntent so the OS can fire it on your behalf
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 3. Attach it to the notification builder
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentText("ACTIVE - v3.0.6 \\ STABLE")
            .setSmallIcon(R.drawable.ic_stat_shiftdata)
            .setContentIntent(pendingIntent) // <--- THIS MAKES IT CLICKABLE
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ShiftData Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Background monitoring for ShiftData"; setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}