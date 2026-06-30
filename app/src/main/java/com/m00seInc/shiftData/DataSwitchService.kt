package com.m00seInc.shiftData

import android.app.AlarmManager
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
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener

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
                            contentType == android.media.AudioAttributes.CONTENT_TYPE_SPEECH
                        ) {
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
                Log.d(
                    "DataSwitchService",
                    "Audio Evaluator -> Media Track State Active: $isMediaPlayingLocally"
                )
            }
            if (wasVoipActive != isVoipCallActiveLocally) {
                Log.d(
                    "DataSwitchService",
                    "Audio Evaluator -> VoIP Call State Active: $isVoipCallActiveLocally"
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
    private var isAlarmActive = false
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

    //private var telephonyCallback: Any? = null // Typed as Any? to prevent class-loading crashes on older APIs
    //private var connectivityManager: ConnectivityManager? = null
    //private var networkCallback: ConnectivityManager.NetworkCallback? = null
    // --- ADVANCED MECHANICAL LOCOMOTION FIELDS ---
    private lateinit var sensorManager: SensorManager
    private var significantMotionSensor: Sensor? = null
    private var motionTriggerListener: TriggerEventListener? = null

    private var isValidationActive = false
    private var wasTruncatedByMotion = false
    private var alarmTriggerTime: Long = 0L
    private var mobileDataObserver: android.database.ContentObserver? = null

    private fun registerDataToggleListener() {
        // Synchronize initial baseline RAM state straight from the system table
        isMobileDataEnabled = Settings.Global.getInt(contentResolver, "mobile_data", 0) == 1
        currentControlState = if (isMobileDataEnabled) DataControlState.AUTOMATED_ON else DataControlState.MANUAL_USER_CONTROL

        mobileDataObserver = object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)

                // Read directly from the software settings database key to remain immune to network drops
                val rawValue = Settings.Global.getString(contentResolver, "mobile_data")
                val enabled = rawValue == "1"
                isMobileDataEnabled = enabled

                // Differentiate automated script operations from manual user shade interactions
                if (!isInternalToggle.get()) {
                    currentControlState = DataControlState.MANUAL_USER_CONTROL
                    Log.d("DataSwitchService", "ContentObserver -> True Manual User Toggle Detected: $enabled")
                } else {
                    Log.d("DataSwitchService", "ContentObserver -> Automated Internal Toggle Absorbed: $enabled")
                }
            }
        }

        contentResolver.registerContentObserver(
            Settings.Global.getUriFor("mobile_data"),
            false,
            mobileDataObserver!!
        )
        Log.d("DataSwitchService", "Engine Lock -> ContentObserver successfully bound to global table.")
    }

    private fun registerMotionTriggerListener() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        significantMotionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

        motionTriggerListener = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                val currentTime = android.os.SystemClock.elapsedRealtime()

                if (isValidationActive) {
                    // PHASE 2 CONFIRMED: Continuous motion verified during the 3-minute window!
                    Log.d("DataSwitchService", "[MOTION] 🏃 Sustained motion verified. Executing immediate cutoff.")
                    cancelValidationTimeout()
                    shiftMobileData(false, isMotionTriggered = true)
                } else {
                    // PHASE 1: Movement detected during the standard 90-minute cooldown
                    val remainingMillis = alarmTriggerTime - currentTime
                    val primaryCooldownInMillis = 5L * 60L * 1000L //LOCAL TESTING VALUE, PRODUCTION: 5L * 60L * 1000L

                    if (isAlarmActive && remainingMillis > primaryCooldownInMillis) {
                        Log.d("DataSwitchService", "[MOTION] Motion detected. Truncating cooldown down to 5 mins.")
                        wasTruncatedByMotion = true
                        cancelDelayedOff()
                        scheduleDelayedOff(5) // LOCAL TESTING VALUE, PRODUCTION : 5
                    } else {
                        // Re-arm the one-shot hardware sensor if parameters aren't met
                        significantMotionSensor?.let { sensor ->
                            sensorManager.requestTriggerSensor(this, sensor)
                        }
                    }
                }
            }
        }
        Log.d("DataSwitchService", "Engine Lock -> MotionTriggerListener successfully initialized.")
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

        // Unique action identifier for the 1-hour system alarm
        private const val ACTION_DELAYED_OFF = "com.m00seInc.shiftData.ACTION_DELAYED_OFF"
        //Unique identifier for the 3-minute validation channel
        private const val ACTION_VALIDATION_TIMEOUT = "com.m00seInc.shiftData.ACTION_VALIDATION_TIMEOUT"
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
        ContextCompat.registerReceiver(this, phoneStateReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

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
        ContextCompat.registerReceiver(this, hotspotReceiver, hotspotFilter, ContextCompat.RECEIVER_EXPORTED)
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

        registerMotionTriggerListener()

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
        Log.d(
            "ShiftData_LeakCheck",
            "[Instance:$instanceId] onDestroy() triggered. Starting teardown..."
        )
        Log.d("ShiftData_LeakCheck", "==================================================")

        unregisterScreenReceiver()
        cancelDelayedOff()

        try {
            Log.d(
                "ShiftData_LeakCheck",
                "[Instance:$instanceId] Attempting Audio Callback unregistration..."
            )
            if (::audioManager.isInitialized) {
                audioManager.unregisterAudioPlaybackCallback(audioPlaybackCallback)
            }
            Log.d(
                "ShiftData_LeakCheck",
                "[Instance:$instanceId] Audio Callback unregistered successfully."
            )

            Log.d(
                "ShiftData_LeakCheck",
                "[Instance:$instanceId] Attempting Phone State Receiver unregistration..."
            )
            unregisterReceiver(phoneStateReceiver)
            Log.d(
                "ShiftData_LeakCheck",
                "[Instance:$instanceId] Phone State Receiver unregistered successfully."
            )

            Log.d(
                "ShiftData_LeakCheck",
                "[Instance:$instanceId] Attempting Hotspot Receiver unregistration..."
            )
            unregisterReceiver(hotspotReceiver)
            Log.d(
                "ShiftData_LeakCheck",
                "[Instance:$instanceId] Hotspot Receiver unregistered successfully."
            )

            mobileDataObserver?.let {
                Log.d("ShiftData_LeakCheck", "[Instance:$instanceId] Unregistering ContentObserver...")
                contentResolver.unregisterContentObserver(it)
                mobileDataObserver = null
                Log.d("DataSwitchService", "ContentObserver detached cleanly.")
            }

            cancelValidationTimeout()
            significantMotionSensor?.let { sensor ->
                if (motionTriggerListener != null) {
                    sensorManager.cancelTriggerSensor(motionTriggerListener, sensor)
                }
            }

        } catch (e: Exception) {
            Log.e(
                "ShiftData_LeakCheck",
                "[Instance:$instanceId] CRITICAL TEARDOWN FAILURE! Chain broken.",
                e
            )
        }

        updateUI()
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(ACTION_DELAYED_OFF)
            addAction(ACTION_VALIDATION_TIMEOUT) // Monitor the 3-minute verification timeout
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.d("ShiftData_Lifecycle", "[HARDWARE] 🔒 Phone Locked (SCREEN_OFF)")
                        shiftJob?.cancel()
                        wasTruncatedByMotion = false
                        isValidationActive = false

                        // Start standard 90-minute cooldown
                        scheduleDelayedOff(90) //LOCAL TESTING VALUE, PRODUCTION : 90

                        // Arm the hardware sensor trap
                        significantMotionSensor?.let { sensor ->
                            sensorManager.requestTriggerSensor(motionTriggerListener, sensor)
                            Log.d("DataSwitchService", "[MOTION] Sensor armed for travel tracking.")
                        }
                    }

                    Intent.ACTION_SCREEN_ON -> {
                        Log.d("ShiftData_Lifecycle", "[HARDWARE] 🔓 Phone Unlocked (SCREEN_ON)")

                        // 1. Snapshot if the automation cycle was still running/waiting to turn data off
                        val isAutomationInProgress = isAlarmActive || isValidationActive

                        // 2. Clear all hardware sensors and pending alarm managers cleanly
                        significantMotionSensor?.let { sensor ->
                            sensorManager.cancelTriggerSensor(motionTriggerListener, sensor)
                        }
                        cancelDelayedOff()
                        cancelValidationTimeout()
                        wasTruncatedByMotion = false

                        // 3. Apply the unified short-circuit guard
                        if (isAutomationInProgress) {
                            // CASE 1: Quick unlock during cooldown OR validation window. Data was never cut.
                            Log.d(
                                "ShiftData_Lifecycle",
                                "Unlock -> System was still in tracking phase. Data untouched. Short-circuiting smoothly."
                            )
                        } else {
                            // CASE 2: Extended lock period. Both tracking phases passed, and data was safely cut.
                            Log.d(
                                "ShiftData_Lifecycle",
                                "Unlock -> Countdown expired completely. Restoring data link..."
                            )
                            shiftMobileData(true)
                        }
                    }

                    ACTION_DELAYED_OFF -> {
                        isAlarmActive = false

                        if (wasTruncatedByMotion) {
                            // The 5-minute truncation timer ran out! Pivot to verification mode.
                            wasTruncatedByMotion = false
                            scheduleValidationTimeout()

                            significantMotionSensor?.let { sensor ->
                                sensorManager.requestTriggerSensor(motionTriggerListener, sensor)
                            }
                            Log.d("ShiftData_Lifecycle", "[ALARM] 5-min cooldown hit. Transitioning to 3-min validation phase...")
                        } else {
                            // Standard 90-minute timeout won the race
                            significantMotionSensor?.let { sensor ->
                                sensorManager.cancelTriggerSensor(motionTriggerListener, sensor)
                            }
                            Log.d("ShiftData_Lifecycle", "[ALARM] ⏰ 90-min standard timeout reached.")
                            shiftMobileData(false, isMotionTriggered = false)
                        }
                    }

                    ACTION_VALIDATION_TIMEOUT -> {
                        // The 3 minutes passed without any confirmed sustained movement.
                        // The user has stopped moving. Reset the cycle completely.
                        isValidationActive = false

                        // Clear the hardware handle out of the registry before re-arming
                        significantMotionSensor?.let { sensor ->
                            sensorManager.cancelTriggerSensor(motionTriggerListener, sensor)
                        }

                        Log.d(
                            "ShiftData_Lifecycle",
                            "[VALIDATION] Window expired with zero motion. Spurious movement filtered. Restarting 90-min standard loop."
                        )

                        // 1. Restart the standard 90-minute tracking countdown
                        scheduleDelayedOff(90) //LOCAL TESTING VALUE, PRODUCTION : 90

                        // 2. Re-arm the hardware sensor hub to monitor this new 90-minute block
                        significantMotionSensor?.let { sensor ->
                            sensorManager.requestTriggerSensor(motionTriggerListener, sensor)
                        }
                    }
                }
            }
        }
        screenStateReceiver = receiver
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    private fun unregisterScreenReceiver() {
        screenStateReceiver?.let { unregisterReceiver(it); screenStateReceiver = null }
    }

    // 1. UPDATE THE FUNCTION SIGNATURE:
    private fun shiftMobileData(enable: Boolean, isMotionTriggered: Boolean = false) {
        shiftJob?.cancel()

        // ACQUIRE TRANSIENT WAKELOCK: Holds the CPU awake just long enough to execute this coroutine safely
        val wakeLock =
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shiftData:ActionWakeLock")
                .apply {
                    acquire(5000L) // 5-second maximum safety hardware fallback timeout
                }

        shiftJob = serviceScope.launch {
            try {
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
                            val isTrueManualOff =
                                (currentControlState == DataControlState.MANUAL_USER_CONTROL) && !isMobileDataEnabled
                            val needsLock =
                                isTrueManualOff || isHotspotActive() || isCallActive() || isMediaPlaying()

                            if (needsLock) {
                                updateManualLock(true)
                            }

                            when {
                                isHotspotActive() -> logDataStateChange("HOT_OFF")
                                isCallActive() -> logDataStateChange("CALL_OFF")
                                isMediaPlaying() -> logDataStateChange("MED_OFF")
                                isTrueManualOff -> logDataStateChange("MAN_OFF")
                                else -> {
                                    // Set clean string code based on hardware sensor parameters
                                    val telemetryTag = if (isMotionTriggered) "MOTION_OFF" else "OFF"
                                    logDataStateChange(telemetryTag)

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
            }finally {
                // ALWAYS RELEASE: Guarantees the CPU can safely drop back into deep sleep immediately
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
            }
        }
    }

    private fun scheduleDelayedOff(minutes: Int = 90) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ACTION_DELAYED_OFF).setPackage(packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 99, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val delayMillis = minutes.toLong() * 60L * 1000L
        alarmTriggerTime = android.os.SystemClock.elapsedRealtime() + delayMillis

        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            alarmTriggerTime,
            pendingIntent
        )

        isAlarmActive = true
        Log.d("DataSwitchService", "[ALARM] Scheduled successfully for $minutes minutes.")
    }

    private fun cancelDelayedOff() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ACTION_DELAYED_OFF).setPackage(packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 99, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()

        isAlarmActive = false
        Log.d("DataSwitchService", "Pending alarm token successfully cleared from system table.")
    }

    private fun scheduleValidationTimeout() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ACTION_VALIDATION_TIMEOUT).setPackage(packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 100, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAtMillis = android.os.SystemClock.elapsedRealtime() + (3 * 60 * 1000) // LOCAL TESTING VALUE , PRODUCTION: 3 * 60 * 1000
        alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)

        isValidationActive = true
        Log.d("DataSwitchService", "[VALIDATION] 3-minute verification window opened.")
    }

    private fun cancelValidationTimeout() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ACTION_VALIDATION_TIMEOUT).setPackage(packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 100, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
        isValidationActive = false
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
            .setContentText("ACTIVE - v3.0.8.0 \\ STABLE")
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