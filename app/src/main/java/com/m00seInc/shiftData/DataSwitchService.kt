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
    private lateinit var audioManager: AudioManager

    // Local RAM state for media
    private var isMediaPlayingLocally = false
    private var isCallActiveLocally = false
    private val audioPlaybackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
            // We use the callback as a trigger. This only pings the hardware
            // when the system explicitly tells us the audio state has changed.

            if (configs.isEmpty()) {
                isMediaPlayingLocally = false
                Log.d(
                    "DataSwitchService",
                    "Call condition: Media=$isMediaPlayingLocally"
                )
            } else {
                isMediaPlayingLocally = true
                Log.d(
                    "DataSwitchService",
                    "Media condition: Media=$isMediaPlayingLocally"
                )
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

    /*
    private val mobileDataObserver =
        object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)

                if (isMobileDataEnabled) isMobileDataEnabled = false
                else isMobileDataEnabled = true
                // Pull the fresh state
                //isMobileDataEnabled = Settings.Global.getInt(contentResolver, "mobile_data", 0) == 1

                Log.d(
                    "DataSwitchService",
                    "Mobile Data Setting Callback: Enabled=$isMobileDataEnabled"
                )

                // OPTIONAL: If the user manually turned data OFF while the screen was ON,
                // you might want to trigger your manual lock logic here.
            }
        }
    */
    private lateinit var connectivityManager: ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            isMobileDataEnabled = true
            Log.d("gBars_Network", "Cellular Data Available")
        }

        override fun onLost(network: Network) {
            isMobileDataEnabled = false
            Log.d("gBars_Network", "Cellular Data Lost/Disabled")
        }
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
        // Cache the system services once!
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        prefs = getSharedPreferences("shift_logs", MODE_PRIVATE)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.registerAudioPlaybackCallback(
            audioPlaybackCallback,
            Handler(Looper.getMainLooper())
        )
        // Initial sync so the RAM state is correct at boot
        isMediaPlayingLocally = audioManager.isMusicActive
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
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)

        //isMobileDataEnabled = Settings.Global.getInt(contentResolver, "mobile_data", 0) == 1

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
        isRunning =
            false
        unregisterScreenReceiver()
        try {
            if (::audioManager.isInitialized) {
                audioManager.unregisterAudioPlaybackCallback(audioPlaybackCallback)
            }
            unregisterReceiver(phoneStateReceiver)

            unregisterReceiver(hotspotReceiver)

            connectivityManager.unregisterNetworkCallback(networkCallback)

            //contentResolver.unregisterContentObserver(mobileDataObserver)

        } catch (e: Exception) {
            e.printStackTrace()
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
                    Intent.ACTION_SCREEN_OFF -> shiftMobileData(false)
                    Intent.ACTION_SCREEN_ON -> shiftMobileData(true)
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
            delay(1000)
            toggleMutex.withLock {
                try {
                    // 1. Minimum necessary data (Cached in RAM)
                    var manualLockState = isManualLocked.get()

                    if (enable) {
                        // LAZY ENABLE LOGIC
                        when {
                            // Priority 1: Hotspot (Always overrides)
                            isHotspotActive() -> {
                                logDataStateChange("HOT_ON")
                                // Note: We don't toggle data for Hotspot per old logic
                            }

                            // Priority 2: Call
                            isCallActive() -> {
                                if (!isMobileDataEnabled) {
                                    toggleData(1)
                                    isMobileDataEnabled = true
                                    logDataStateChange("ON")
                                } else logDataStateChange("CALL_ON")
                            }

                            // Priority 3: Media
                            isMediaPlaying() -> {
                                if (!isMobileDataEnabled) {
                                    toggleData(1)
                                    isMobileDataEnabled = true
                                    logDataStateChange("ON")
                                } else logDataStateChange("MED_ON")
                            }

                            // Priority 4: No Manual Lock (Normal ON)
                            !manualLockState -> {
                                if (isMobileDataEnabled) logDataStateChange("BY_ON")
                                else {
                                    logDataStateChange("ON")
                                    toggleData(1)
                                    isMobileDataEnabled = true
                                }
                            }

                            // Priority 5: Manual Lock is Active
                            else -> {
                                if (isMobileDataEnabled) logDataStateChange("BY_ON")
                                else logDataStateChange("MAN_ON")
                            }
                        }
                    } else {
                        // LAZY DISABLE LOGIC

                        // A. Sync Manual Lock state (Parity with old logic)
                        if (manualLockState && isMobileDataEnabled) {
                            updateManualLock(false)
                            manualLockState = false
                        }

                        // B. Determine if we should set the lock (Check bypasses lazily)
                        val needsLock =
                            !isMobileDataEnabled || isHotspotActive() || isCallActive() || isMediaPlaying()
                        if (needsLock) {
                            updateManualLock(true)
                        }

                        // C. Execution & Logging
                        when {
                            isHotspotActive() -> logDataStateChange("HOT_OFF")
                            isCallActive() -> logDataStateChange("CALL_OFF")
                            isMediaPlaying() -> logDataStateChange("MED_OFF")
                            !isMobileDataEnabled -> logDataStateChange("MAN_OFF")
                            else -> {
                                logDataStateChange("OFF")
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

    private fun isCallActive(): Boolean = isCallActiveLocally

    private fun isMediaPlaying(): Boolean = isMediaPlayingLocally

    private suspend fun toggleData(state: Int) {
        withContext(NonCancellable) {
            Settings.Global.putInt(contentResolver, "mobile_data", state)
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
            .setContentText("ACTIVE - v3.0.4 \\ STABLE")
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