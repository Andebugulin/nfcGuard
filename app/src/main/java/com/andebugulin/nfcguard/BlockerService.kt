package com.andebugulin.nfcguard

import android.app.*
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.content.pm.ServiceInfo

class BlockerService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var blockedApps = setOf<String>()
    private var blockMode = BlockMode.BLOCK_SELECTED
    private var activeModeIds = setOf<String>()
    private var lastCheckedApp: String? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Thread-safe overlay state management - CRITICAL FIX
    private val overlayMutex = Mutex()
    private var isOverlayShowing = false
    private var overlayAnimating = false

    // CRITICAL: Prevent multiple monitoring loops
    private var monitoringJob: Job? = null
    private val monitoringMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("BLOCKER_SERVICE", "═══════════════════════════════════════")
        android.util.Log.d("BLOCKER_SERVICE", "SERVICE CREATED")
        android.util.Log.d("BLOCKER_SERVICE", "═══════════════════════════════════════")
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        android.util.Log.d("BLOCKER_SERVICE", "✓ Service started in foreground")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("BLOCKER_SERVICE", "───────────────────────────────────────")
        android.util.Log.d("BLOCKER_SERVICE", "ON START COMMAND")
        android.util.Log.d("BLOCKER_SERVICE", "───────────────────────────────────────")

        intent?.getStringArrayListExtra("blocked_apps")?.let {
            blockedApps = it.toSet()
            android.util.Log.d("BLOCKER_SERVICE", "📋 Blocked apps updated: ${blockedApps.size} apps")
        }

        intent?.getStringExtra("block_mode")?.let {
            blockMode = BlockMode.valueOf(it)
            android.util.Log.d("BLOCKER_SERVICE", "🔧 Block mode: $blockMode")
        }

        intent?.getStringArrayListExtra("active_mode_ids")?.let {
            activeModeIds = it.toSet()
            android.util.Log.d("BLOCKER_SERVICE", "🎯 Active modes: ${activeModeIds.size}")
        }

        lastCheckedApp = null
        // Don't spawn new coroutine - just check immediately
        startMonitoring()
        return START_STICKY
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "guardian_channel"
        private var isRunning = false

        // CRITICAL: Apps that must NEVER be blocked
        private val CRITICAL_SYSTEM_APPS = setOf(
            "com.android.settings",
            "com.android.systemui",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.providers.settings",
            "com.android.keychain",
            "android",
            "com.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.android.phone",
            "com.android.contacts",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.emergency",
            "com.android.inputmethod.latin",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.andebugulin.nfcguard",
            "com.android.settings.lockscreen",
            "com.android.security",
            "com.miui.securitycenter",
            "com.samsung.android.lool",
            "com.coloros.lockscreen"
        )

        fun start(
            context: Context,
            blockedApps: Set<String>,
            blockMode: BlockMode,
            activeModeIds: Set<String>
        ) {
            android.util.Log.d("BLOCKER_SERVICE", "═══════════════════════════════════════")
            android.util.Log.d("BLOCKER_SERVICE", "START REQUEST RECEIVED")
            android.util.Log.d("BLOCKER_SERVICE", "═══════════════════════════════════════")

            if (!Settings.canDrawOverlays(context)) {
                android.util.Log.e("BLOCKER_SERVICE", "❌ OVERLAY PERMISSION NOT GRANTED!")
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}")
                )
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return
            }
            android.util.Log.d("BLOCKER_SERVICE", "✓ Overlay permission granted")

            val intent = Intent(context, BlockerService::class.java).apply {
                putStringArrayListExtra("blocked_apps", ArrayList(blockedApps))
                putExtra("block_mode", blockMode.name)
                putStringArrayListExtra("active_mode_ids", ArrayList(activeModeIds))
            }
            context.startForegroundService(intent)
            android.util.Log.d("BLOCKER_SERVICE", "✓ Service start intent sent")
        }

        fun stop(context: Context) {
            android.util.Log.d("BLOCKER_SERVICE", "═══════════════════════════════════════")
            android.util.Log.d("BLOCKER_SERVICE", "STOP REQUEST RECEIVED")
            android.util.Log.d("BLOCKER_SERVICE", "═══════════════════════════════════════")
            context.stopService(Intent(context, BlockerService::class.java))
        }

        fun isRunning() = isRunning
    }

    private fun startMonitoring() {
        android.util.Log.d("BLOCKER_SERVICE", "───────────────────────────────────────")
        android.util.Log.d("BLOCKER_SERVICE", "STARTING MONITORING LOOP")
        android.util.Log.d("BLOCKER_SERVICE", "───────────────────────────────────────")

        serviceScope.launch {
            monitoringMutex.withLock {
                // Cancel existing monitoring job if any
                monitoringJob?.cancel()

                // Start NEW monitoring loop
                monitoringJob = serviceScope.launch(Dispatchers.Default) {
                    android.util.Log.d("BLOCKER_SERVICE", "✓ Monitoring loop started (Job ID: ${this.hashCode()})")

                    while (isActive) {
                        try {
                            checkCurrentApp()
                        } catch (e: Exception) {
                            android.util.Log.e("BLOCKER_SERVICE", "Error in monitoring loop: ${e.message}")
                        }
                        delay(500)
                    }

                    android.util.Log.d("BLOCKER_SERVICE", "✗ Monitoring loop ended")
                }
            }
        }
    }

    private suspend fun checkCurrentApp() {
        val currentApp = getForegroundApp()

        android.util.Log.v("BLOCKER_SERVICE", "🔍 Current foreground app: $currentApp")

        if (currentApp == null) {
            android.util.Log.v("BLOCKER_SERVICE", "⚠️  Could not determine foreground app")
            return
        }

        if (currentApp != lastCheckedApp) {
            android.util.Log.d("BLOCKER_SERVICE", "───────────────────────────────────────")
            android.util.Log.d("BLOCKER_SERVICE", "APP SWITCH DETECTED")
            android.util.Log.d("BLOCKER_SERVICE", "───────────────────────────────────────")
            android.util.Log.d("BLOCKER_SERVICE", "📱 Previous app: $lastCheckedApp")
            android.util.Log.d("BLOCKER_SERVICE", "📱 Current app: $currentApp")

            lastCheckedApp = currentApp

            val shouldBlock = when {
                currentApp == packageName -> {
                    android.util.Log.d("BLOCKER_SERVICE", "✓ This is Guardian app - ALLOW")
                    false
                }
                isSystemLauncher(currentApp) -> {
                    android.util.Log.d("BLOCKER_SERVICE", "✓ This is system launcher - ALLOW")
                    false
                }
                isCriticalSystemApp(currentApp) -> {
                    android.util.Log.d("BLOCKER_SERVICE", "✓ Critical system app - ALLOW")
                    false
                }
                else -> {
                    val result = when (blockMode) {
                        BlockMode.BLOCK_SELECTED -> blockedApps.contains(currentApp)
                        BlockMode.ALLOW_SELECTED -> !blockedApps.contains(currentApp)
                    }
                    android.util.Log.d("BLOCKER_SERVICE", "🔧 Block mode: $blockMode")
                    android.util.Log.d("BLOCKER_SERVICE", "📋 App in list: ${blockedApps.contains(currentApp)}")
                    android.util.Log.d("BLOCKER_SERVICE", "🎯 Should block: $result")
                    result
                }
            }

            // Direct call - we're already in the monitoring coroutine
            if (shouldBlock) {
                android.util.Log.w("BLOCKER_SERVICE", "🚫 BLOCKING APP: $currentApp")
                showOverlaySafe()
            } else {
                android.util.Log.d("BLOCKER_SERVICE", "✓ ALLOWING APP: $currentApp")
                hideOverlaySafe()
            }
        }
    }

    private fun isSystemLauncher(packageName: String): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfos = packageManager.queryIntentActivities(intent, 0)
        val isLauncher = resolveInfos.any { it.activityInfo.packageName == packageName }
        if (isLauncher) {
            android.util.Log.d("BLOCKER_SERVICE", "   ✓ Detected as system launcher: $packageName")
        }
        return isLauncher
    }

    private fun isCriticalSystemApp(packageName: String): Boolean {
        return CRITICAL_SYSTEM_APPS.contains(packageName)
    }


private fun getForegroundApp(): String? {
    val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val time = System.currentTimeMillis()
    val stats = usageStatsManager.queryUsageStats(
        UsageStatsManager.INTERVAL_DAILY,
        time - 1000 * 10,
        time
    )
    return stats?.maxByOrNull { it.lastTimeUsed }?.packageName
}

// CRITICAL FIX: Thread-safe overlay showing with mutex
private suspend fun showOverlaySafe() {
    overlayMutex.withLock {
        // Prevent duplicate overlays
        if (isOverlayShowing || overlayAnimating) {
            android.util.Log.w("BLOCKER_SERVICE", "⚠️  Overlay already showing or animating, skipping")
            return
        }

        overlayAnimating = true

        withContext(Dispatchers.Main) {
            try {
                // Double-check in main thread
                if (overlayView != null) {
                    android.util.Log.w("BLOCKER_SERVICE", "⚠️  Overlay view exists, cleaning up first")
                    try {
                        windowManager?.removeView(overlayView)
                    } catch (e: Exception) {
                        android.util.Log.e("BLOCKER_SERVICE", "Error removing old overlay: ${e.message}")
                    }
                    overlayView = null
                }

                showOverlay()
                isOverlayShowing = true
            } finally {
                overlayAnimating = false
            }
        }
    }
}

// CRITICAL FIX: Thread-safe overlay hiding with mutex
private suspend fun hideOverlaySafe() {
    overlayMutex.withLock {
        if (!isOverlayShowing || overlayAnimating) {
            return
        }

        overlayAnimating = true

        withContext(Dispatchers.Main) {
            try {
                hideOverlay()
                isOverlayShowing = false
            } finally {
                overlayAnimating = false
            }
        }
    }
}

private fun showOverlay() {
    android.util.Log.d("BLOCKER_SERVICE", "───────────────────────────────────────")
    android.util.Log.d("BLOCKER_SERVICE", "SHOWING OVERLAY")
    android.util.Log.d("BLOCKER_SERVICE", "───────────────────────────────────────")

    try {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        overlayView = createBlockerView()

        // Start invisible and slightly scaled down
        overlayView?.apply {
            alpha = 0f
            scaleX = 0.95f
            scaleY = 0.95f
        }

        windowManager?.addView(overlayView, params)

        // Buttery smooth fade + scale animation
        overlayView?.animate()
            ?.alpha(1f)
            ?.scaleX(1f)
            ?.scaleY(1f)
            ?.setDuration(400)  // Slightly longer for smoothness
            ?.setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))  // Smooth deceleration
            ?.start()

        android.util.Log.d("BLOCKER_SERVICE", "✓✓✓ OVERLAY SUCCESSFULLY SHOWN ✓✓✓")
    } catch (e: Exception) {
        android.util.Log.e("BLOCKER_SERVICE", "❌❌❌ FAILED TO SHOW OVERLAY ❌❌❌")
        android.util.Log.e("BLOCKER_SERVICE", "   Error: ${e.javaClass.simpleName}")
        android.util.Log.e("BLOCKER_SERVICE", "   Message: ${e.message}")
        android.util.Log.e("BLOCKER_SERVICE", "   Stack trace:", e)
        overlayView = null
        isOverlayShowing = false
    }
}

private fun hideOverlay() {
    android.util.Log.d("BLOCKER_SERVICE", "───────────────────────────────────────")
    android.util.Log.d("BLOCKER_SERVICE", "HIDING OVERLAY")
    android.util.Log.d("BLOCKER_SERVICE", "───────────────────────────────────────")

    overlayView?.let { view ->
        try {
            // Buttery smooth fade + slight scale down
            view.animate()
                ?.alpha(0f)
                ?.scaleX(0.98f)  // Subtle scale down
                ?.scaleY(0.98f)
                ?.setDuration(250)  // Quick but smooth
                ?.setInterpolator(android.view.animation.AccelerateInterpolator(1.2f))
                ?.withEndAction {
                    try {
                        windowManager?.removeView(view)
                        overlayView = null
                        android.util.Log.d("BLOCKER_SERVICE", "✓✓✓ OVERLAY SUCCESSFULLY HIDDEN ✓✓✓")
                    } catch (e: Exception) {
                        android.util.Log.e("BLOCKER_SERVICE", "❌ Failed to remove overlay: ${e.message}")
                        overlayView = null
                    }
                }
                ?.start()
        } catch (e: Exception) {
            android.util.Log.e("BLOCKER_SERVICE", "❌ Failed to animate overlay: ${e.message}")
            try {
                windowManager?.removeView(view)
            } catch (e2: Exception) {
                android.util.Log.e("BLOCKER_SERVICE", "❌ Failed to force remove overlay: ${e2.message}")
            }
            overlayView = null
        }
    } ?: run {
        android.util.Log.d("BLOCKER_SERVICE", "   No overlay to hide (already null)")
    }
}

private fun createBlockerView(): View {
    android.util.Log.d("BLOCKER_SERVICE", "   Creating blocker view UI...")

    return android.widget.FrameLayout(this).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        setBackgroundColor(0xFF000000.toInt())
        isClickable = true
        isFocusable = true

        systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attribs = android.view.WindowManager.LayoutParams()
            attribs.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        setOnTouchListener { _, event ->
            android.util.Log.d("BLOCKER_SERVICE", "👆 TOUCH EVENT DETECTED ON OVERLAY")
            // Launch in monitoring scope to avoid creating extra coroutines
            monitoringJob?.let { job ->
                if (job.isActive) {
                    serviceScope.launch {
                        lastCheckedApp = null
                        checkCurrentApp()
                    }
                }
            }
            true
        }

        val content = android.widget.LinearLayout(this@BlockerService).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(48, 48, 48, 48)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setOnApplyWindowInsetsListener { view, insets ->
                    val topInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        insets.displayCutout?.safeInsetTop ?: insets.systemWindowInsetTop
                    } else {
                        insets.systemWindowInsetTop
                    }
                    view.setPadding(48, 48 + topInset, 48, 48)
                    insets
                }
            }

            addView(android.widget.TextView(this@BlockerService).apply {
                text = "BLOCKED"
                textSize = 48f
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.DEFAULT,
                    android.graphics.Typeface.BOLD
                )
                letterSpacing = 0.2f
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })

            addView(android.widget.TextView(this@BlockerService).apply {
                text = "↓"
                textSize = 32f
                gravity = Gravity.CENTER
                setTextColor(0xFF808080.toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 16
                    bottomMargin = 16
                }
            })

            addView(android.widget.TextView(this@BlockerService).apply {
                text = "TO UNLOCK:"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(0xFF808080.toInt())
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.DEFAULT,
                    android.graphics.Typeface.BOLD
                )
                letterSpacing = 0.15f
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })

            addView(android.widget.TextView(this@BlockerService).apply {
                text = "↓"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(0xFF808080.toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 12
                    bottomMargin = 12
                }
            })

            addView(android.widget.LinearLayout(this@BlockerService).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )

                addView(android.widget.TextView(this@BlockerService).apply {
                    text = "OPEN "
                    textSize = 16f
                    setTextColor(0xFFFFFFFF.toInt())
                    typeface = android.graphics.Typeface.create(
                        android.graphics.Typeface.DEFAULT,
                        android.graphics.Typeface.BOLD
                    )
                    letterSpacing = 0.15f
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                })

                addView(android.widget.Button(this@BlockerService).apply {
                    text = "GUARDIAN"
                    textSize = 16f
                    setTextColor(0xFF000000.toInt())
                    setBackgroundColor(0xFFFFFFFF.toInt())
                    typeface = android.graphics.Typeface.create(
                        android.graphics.Typeface.DEFAULT,
                        android.graphics.Typeface.BOLD
                    )
                    letterSpacing = 0.15f
                    isAllCaps = true
                    setPadding(32, 16, 32, 16)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )

                    setOnClickListener {
                        android.util.Log.d("BLOCKER_SERVICE", "🔘 GUARDIAN BUTTON CLICKED")
                        val intent =
                            Intent(this@BlockerService, MainActivity::class.java).apply {
                                flags =
                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            }
                        startActivity(intent)
                    }
                })
            })

            addView(android.widget.TextView(this@BlockerService).apply {
                text = "↓"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(0xFF808080.toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 12
                    bottomMargin = 12
                }
            })

            addView(android.widget.TextView(this@BlockerService).apply {
                text = "TAP NFC TO UNLOCK"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(0xFF808080.toInt())
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.DEFAULT,
                    android.graphics.Typeface.BOLD
                )
                letterSpacing = 0.15f
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
        }

        addView(content)

        android.util.Log.d("BLOCKER_SERVICE", "   ✓ Blocker view UI complete")
    }
}

private fun createNotification(): Notification {
    val intent = Intent(this, MainActivity::class.java)
    val pendingIntent = PendingIntent.getActivity(
        this, 0, intent,
        PendingIntent.FLAG_IMMUTABLE
    )

    val titleText = if (activeModeIds.isEmpty()) {
        "GUARDIAN MONITORING"
    } else {
        "GUARDIAN ACTIVE"
    }

    val contentText = if (activeModeIds.isEmpty()) {
        "Waiting for scheduled modes"
    } else {
        "${activeModeIds.size} MODE${if (activeModeIds.size > 1) "S" else ""} RUNNING"
    }

    return NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(titleText)
        .setContentText(contentText)
        .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setContentIntent(pendingIntent)
        .build()
}

private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Guardian Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Guardian running"
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
}

override fun onTaskRemoved(rootIntent: Intent?) {
    super.onTaskRemoved(rootIntent)
    android.util.Log.d("BLOCKER_SERVICE", "═══════════════════════════════════════")
    android.util.Log.d("BLOCKER_SERVICE", "TASK REMOVED")
    android.util.Log.d("BLOCKER_SERVICE", "═══════════════════════════════════════")
    scheduleServiceRestart()
}

private fun scheduleServiceRestart() {
    val prefs = applicationContext.getSharedPreferences(
        "guardian_prefs",
        android.content.Context.MODE_PRIVATE
    )
    val stateJson = prefs.getString("app_state", null)

    if (stateJson != null) {
        try {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val appState = json.decodeFromString<AppState>(stateJson)

            if (appState.activeModes.isNotEmpty()) {
                val restartIntent =
                    Intent(applicationContext, ServiceRestartReceiver::class.java)
                val pendingIntent = android.app.PendingIntent.getBroadcast(
                    applicationContext,
                    0,
                    restartIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )

                val alarmManager =
                    applicationContext.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
                alarmManager.setExact(
                    android.app.AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 1000,
                    pendingIntent
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("BLOCKER_SERVICE", "❌ Error scheduling restart: ${e.message}", e)
        }
    }
}

override fun onDestroy() {
    super.onDestroy()
    android.util.Log.d("BLOCKER_SERVICE", "═══════════════════════════════════════")
    android.util.Log.d("BLOCKER_SERVICE", "SERVICE DESTROYED")
    android.util.Log.d("BLOCKER_SERVICE", "═══════════════════════════════════════")
    isRunning = false

    // Cancel monitoring first
    runBlocking {
        monitoringMutex.withLock {
            monitoringJob?.cancel()
            monitoringJob = null
        }
    }

    // Force cleanup on destroy
    runBlocking {
        overlayMutex.withLock {
            overlayView?.let { view ->
                try {
                    withContext(Dispatchers.Main) {
                        windowManager?.removeView(view)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("BLOCKER_SERVICE", "Error cleaning up overlay: ${e.message}")
                }
                overlayView = null
                isOverlayShowing = false
            }
        }
    }

    serviceScope.cancel()

    scheduleServiceRestart()
    android.util.Log.d("BLOCKER_SERVICE", "═══════════════════════════════════════")
}

override fun onBind(intent: Intent?): IBinder? = null
}