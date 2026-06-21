package com.m00seInc.shiftData

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import androidx.lifecycle.repeatOnLifecycle
import android.util.Log

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val lifecycleOwner = LocalLifecycleOwner.current
            val isDark = isSystemInDarkTheme()

            val colorScheme = if (isDark) {
                darkColorScheme(surface = Color.Black, onSurface = Color.White, surfaceVariant = Color(0xFF121212))
            } else {
                lightColorScheme(surface = Color(0xFFF5F5F5), onSurface = Color.Black, surfaceVariant = Color.White)
            }

            var hasNotifyPerm by remember { mutableStateOf(checkNotifyPerm(context)) }
            var hasSecureSettings by remember { mutableStateOf(checkSecureSettings(context)) }
            var hasPhonePerm by remember { mutableStateOf(checkPhonePerm(context)) }
            var hasAttemptedAutoPrompt by rememberSaveable { mutableStateOf(false) }

            // UPGRADED LAUNCHER: Handles Multiple Permissions
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
                hasNotifyPerm = checkNotifyPerm(context)
                hasPhonePerm = checkPhonePerm(context)
            }

            LaunchedEffect(lifecycleOwner) {
                lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    while (true) {
                        // ADD THIS LINE: Prints to Logcat every time the loop cycles
                        Log.d("ShiftDataMonitor", "Active: Polling permissions...")

                        val secure = checkSecureSettings(context)
                        val notify = checkNotifyPerm(context)
                        val phone = checkPhonePerm(context)

                        if (hasSecureSettings != secure) hasSecureSettings = secure
                        if (hasNotifyPerm != notify) hasNotifyPerm = notify
                        if (hasPhonePerm != phone) hasPhonePerm = phone

                        delay(1000)
                    }
                }
            }

            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        hasNotifyPerm = checkNotifyPerm(context)
                        hasSecureSettings = checkSecureSettings(context)
                        hasPhonePerm = checkPhonePerm(context)
                        if (!hasAttemptedAutoPrompt) {
                            hasAttemptedAutoPrompt = true

                            // Build permission request list
                            val perms = mutableListOf(Manifest.permission.READ_PHONE_STATE)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifyPerm) {
                                perms.add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            launcher.launch(perms.toTypedArray())
                        }
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            MaterialTheme(colorScheme = colorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Box(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                        ShiftDataScreen(
                            context = context, hasNotifyPerm = hasNotifyPerm, hasSecureSettings = hasSecureSettings,hasPhonePerm = hasPhonePerm,
                            onManualRequest = {
                                val perms = mutableListOf(Manifest.permission.READ_PHONE_STATE)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifyPerm) {
                                    perms.add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                launcher.launch(perms.toTypedArray())

                                // Fallback to settings if permanently denied
                                if (!hasNotifyPerm || !hasPhonePerm) {
                                    openAppSettings(context)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // RENAMED & REFACTORED to open main app settings (handles both Notifications and Phone permissions)
    private fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

@Composable
fun ShiftDataScreen(context: Context, hasNotifyPerm: Boolean, hasSecureSettings: Boolean, hasPhonePerm: Boolean, onManualRequest: () -> Unit) {
    var isServiceActive by remember { mutableStateOf(DataSwitchService.isRunning) }
    var rawLogs by remember { mutableStateOf(getRawLogs(context)) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val pairedLogs by remember(rawLogs) {
        derivedStateOf {
            val flatLogs = if (rawLogs.isEmpty()) emptyList() else rawLogs.split("|")
            val pairs = mutableListOf<Pair<String?, String?>>()
            var i = 0
            while (i < flatLogs.size) {
                val current = flatLogs[i]; val next = if (i + 1 < flatLogs.size) flatLogs[i + 1] else null
                if (current.contains("ON") && next?.contains("OFF") == true) {
                    pairs.add(Pair(next, current)); i += 2
                } else {
                    if (current.contains("OFF")) pairs.add(Pair(current, null))
                    else pairs.add(Pair(null, current))
                    i += 1
                }
            }
            pairs.take(5).toMutableList().let { list -> while (list.size < 5) list.add(Pair(null, null)); list.toList() }
        }
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                rawLogs = getRawLogs(context); isServiceActive = DataSwitchService.isRunning
            }
        }
        context.registerReceiver(receiver, IntentFilter("com.m00seInc.shiftData.UPDATE_UI"), Context.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    if (!hasSecureSettings || !hasNotifyPerm || !hasPhonePerm) {
        Box(Modifier.fillMaxSize().padding(24.dp)) {
            SetupPanel(hasNotifyPerm, hasSecureSettings, hasPhonePerm, onNotifyClick = onManualRequest)
        }
    } else {
        if (isLandscape) {
            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp)) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.fillMaxWidth()) {
                        Text("shiftData", letterSpacing = 4.sp, fontWeight = FontWeight.Black, fontSize = 12.sp)
                        Text("v3.0.6.3 / STABLE", color = Color.Gray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.weight(1f))
                    NeumorphicPowerButton(isServiceActive, isLandscape = true) {
                        val intent = Intent(context, DataSwitchService::class.java)
                        if (isServiceActive) context.stopService(intent) else context.startForegroundService(intent)
                        isServiceActive = !isServiceActive
                    }
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.width(24.dp))
                Column(modifier = Modifier.weight(1.5f).fillMaxHeight()) {
                    Text("TELEMETRY LOGS", fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Color.Gray)
                    Spacer(Modifier.height(2.dp))
                    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        pairedLogs.forEach { pair -> LogPairRow(pair, modifier = Modifier.weight(1f), isLandscape = true) }
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                Spacer(Modifier.height(24.dp))
                Text("shiftData", letterSpacing = 4.sp, fontWeight = FontWeight.Black, fontSize = 13.sp)
                Text("v3.0.6.3 / STABLE", color = Color.Gray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.weight(1f))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    NeumorphicPowerButton(isServiceActive, isLandscape = false) {
                        val intent = Intent(context, DataSwitchService::class.java)
                        if (isServiceActive) context.stopService(intent) else context.startForegroundService(intent)
                        isServiceActive = !isServiceActive
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("TELEMETRY LOGS", fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                Column(modifier = Modifier.fillMaxWidth().wrapContentHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    pairedLogs.forEach { pair -> LogPairRow(pair, isLandscape = false) }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun LogPairRow(pair: Pair<String?, String?>, modifier: Modifier = Modifier, isLandscape: Boolean) {
    Row(
        modifier = modifier.fillMaxWidth().then(if (!isLandscape) Modifier.height(IntrinsicSize.Min) else Modifier)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .border(1.dp, if (isSystemInDarkTheme()) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
            .padding(vertical = if (isLandscape) 2.dp else 8.dp, horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        LogColumn(pair.first, isLeftColumn = true, isLandscape = isLandscape)
        Box(Modifier.padding(horizontal = 6.dp).width(1.dp).fillMaxHeight(0.8f).background(if(isSystemInDarkTheme()) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.1f)))
        LogColumn(pair.second, isLeftColumn = false, isLandscape = isLandscape)
    }
}

@Composable
fun RowScope.LogColumn(log: String?, isLeftColumn: Boolean, isLandscape: Boolean) {
    Column(modifier = Modifier.weight(1f), horizontalAlignment = if (isLeftColumn) Alignment.Start else Alignment.End, verticalArrangement = Arrangement.Center) {
        val fontSize = if (isLandscape) 9.sp else 10.sp
        if (log != null) {
            val parts = log.split(" - "); val time = parts.getOrNull(0) ?: "--/-- --:--:--"
            val rawStatus = parts.getOrNull(1) ?: ""

            val label = when (rawStatus) {
                "OFF" -> "mobile data - off"
                "ON" -> "mobile data - on"
                "HOT_OFF", "HOT_ON" -> "bypass - hotspot"
                "MED_OFF", "MED_ON" -> "bypass - media"
                "CALL_OFF", "CALL_ON" -> "bypass - call"
                "MAN_OFF", "MAN_ON" -> "bypass - manual"
                "BY_ON" -> "bypass - nochange"
                "CALL_ON_ON" -> "mobile data - on:call"
                "MED_ON_ON" -> "mobile data - on:call"
                else -> "bypass"
            }

            val textColor = if (rawStatus == "ON" || rawStatus == "OFF") MaterialTheme.colorScheme.onSurface else Color.Gray

            Text(time, color = textColor, fontSize = fontSize, fontFamily = FontFamily.Monospace)
            if (!isLandscape) Spacer(Modifier.height(2.dp))
            Text(label, color = textColor, fontSize = fontSize, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        } else {
            Text("--/-- --:--:--", color = Color.Gray.copy(alpha = 0.25f), fontSize = fontSize, fontFamily = FontFamily.Monospace)
            if (!isLandscape) Spacer(Modifier.height(2.dp))
            Text("...", color = Color.Gray.copy(alpha = 0.25f), fontSize = fontSize, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun NeumorphicPowerButton(active: Boolean, isLandscape: Boolean, onClick: () -> Unit) {
    val buttonBg = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
    val pulseColor = if (isSystemInDarkTheme()) Color.White else Color.Black
    val sizePx = if (isLandscape) 140.dp else 180.dp
    Box(
        modifier = Modifier.size(sizePx)
            .drawBehind {
                if (active) {
                    drawCircle(color = pulseColor.copy(alpha = 0.08f), radius = size.maxDimension / 1.7f)
                    drawCircle(color = pulseColor.copy(alpha = 0.2f), radius = size.maxDimension / 2.1f, style = Stroke(width = 1.dp.toPx()))
                }
            }
            .clip(CircleShape).background(buttonBg).border(1.dp, pulseColor.copy(alpha = 0.15f), CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text = if (active) "ACTIVE" else "STANDBY", color = textColor, fontWeight = FontWeight.Black, fontSize = if(isLandscape) 16.sp else 18.sp, letterSpacing = 2.sp)
    }
}

@Composable
fun SetupPanel(hasNotifyPerm: Boolean, hasSecureSettings: Boolean, hasPhonePerm: Boolean, onNotifyClick: () -> Unit) {
    val isDark = isSystemInDarkTheme()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text("SYSTEM INITIALIZATION", fontWeight = FontWeight.Black, fontSize = 16.sp)
        Spacer(Modifier.height(24.dp))
        if (!hasNotifyPerm || !hasPhonePerm) {
            Button(onClick = onNotifyClick, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface)) {
                Text("1. AUTHORIZE RUNTIME PERMISSIONS", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text("Requires Notifications (Foreground Service) and Phone State (Call Protection).", fontSize = 10.sp, color = Color.Gray)
            Spacer(Modifier.height(16.dp))
        } else {
            Text("1. NOTIFICATIONS AUTHORIZED", color = Color(0xFF4CAF50), fontSize = 12.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(16.dp))
        }
        if (!hasSecureSettings) {
            Text("2. ADB PERMISSION STRING", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(12.dp).border(1.dp, if(isDark) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.05f), RoundedCornerShape(8.dp))) {
                Text("adb shell pm grant com.m00seInc.shiftData android.permission.WRITE_SECURE_SETTINGS", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = if(isDark) Color.LightGray else Color.DarkGray)
            }
        } else {
            Text("2. SECURE SETTINGS GRANTED", color = Color(0xFF4CAF50), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun checkSecureSettings(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
private fun checkNotifyPerm(c: Context): Boolean {
    val notificationsEnabled = NotificationManagerCompat.from(c).areNotificationsEnabled()
    val runtimePermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else { true }
    return notificationsEnabled && runtimePermissionGranted
}

private fun checkPhonePerm(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
private fun getRawLogs(c: Context) = c.getSharedPreferences("shift_logs", Context.MODE_PRIVATE).getString("logs_list", "") ?: ""