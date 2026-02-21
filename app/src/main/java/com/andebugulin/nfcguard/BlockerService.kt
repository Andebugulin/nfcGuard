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
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
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
    private var manuallyActivatedModeIds = setOf<String>()
    private var timedModeDeactivations = mapOf<String, Long>()
    private var modeNames = mapOf<String, String>()
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
        android.util.Log.d("BLOCKER_SERVICE", "-•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•-")
        android.util.Log.d("BLOCKER_SERVICE", "SERVICE CREATED")
        android.util.Log.d("BLOCKER_SERVICE", "-•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•-")
        AppLogger.init(this)
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        AppLogger.log("SERVICE", "BlockerService CREATED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        android.util.Log.d("BLOCKER_SERVICE", "--- Service started in foreground")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("BLOCKER_SERVICE", "------------------------------------------------------------------------------")
        android.util.Log.d("BLOCKER_SERVICE", "ON START COMMAND")
        android.util.Log.d("BLOCKER_SERVICE", "------------------------------------------------------------------------------")

        intent?.getStringArrayListExtra("blocked_apps")?.let {
            blockedApps = it.toSet()
            android.util.Log.d("BLOCKER_SERVICE", "---- Blocked apps updated: ${blockedApps.size} apps")
            AppLogger.log("SERVICE", "onStartCommand: ${blockedApps.size} apps in blocklist")
        }

        intent?.getStringExtra("block_mode")?.let {
            blockMode = BlockMode.valueOf(it)
            AppLogger.log("SERVICE", "onStartCommand: blockMode=$blockMode")
            android.util.Log.d("BLOCKER_SERVICE", "---- Block mode: $blockMode")
        }

        intent?.getStringArrayListExtra("active_mode_ids")?.let {
            activeModeIds = it.toSet()
            AppLogger.log("SERVICE", "onStartCommand: activeModeIds=$activeModeIds")
            android.util.Log.d("BLOCKER_SERVICE", "---- Active modes: ${activeModeIds.size}")
        }

        intent?.getStringArrayListExtra("manually_activated_mode_ids")?.let {
            manuallyActivatedModeIds = it.toSet()
        }

        intent?.getSerializableExtra("timed_mode_deactivations")?.let {
            @Suppress("UNCHECKED_CAST")
            timedModeDeactivations = (it as? java.util.HashMap<String, Long>)?.toMap() ?: emptyMap()
        }

        intent?.getSerializableExtra("mode_names")?.let {
            @Suppress("UNCHECKED_CAST")
            modeNames = (it as? java.util.HashMap<String, String>)?.toMap() ?: emptyMap()
        }

        lastCheckedApp = null
        // Don't spawn new coroutine - just check immediately
        startMonitoring()

        // Refresh notification to reflect current mode state
        try {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, createNotification())
        } catch (_: Exception) {}

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
            activeModeIds: Set<String>,
            manuallyActivatedModeIds: Set<String> = emptySet(),
            timedModeDeactivations: Map<String, Long> = emptyMap(),
            modeNames: Map<String, String> = emptyMap()
        ) {
            android.util.Log.d("BLOCKER_SERVICE", "-•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•-")
            android.util.Log.d("BLOCKER_SERVICE", "START REQUEST RECEIVED")
            android.util.Log.d("BLOCKER_SERVICE", "-•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•-")

            if (!Settings.canDrawOverlays(context)) {
                android.util.Log.e("BLOCKER_SERVICE", "--- OVERLAY PERMISSION NOT GRANTED!")
                AppLogger.log("SERVICE", "OVERLAY PERMISSION NOT GRANTED - cannot start blocking")
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}")
                )
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return
            }
            android.util.Log.d("BLOCKER_SERVICE", "--- Overlay permission granted")

            val intent = Intent(context, BlockerService::class.java).apply {
                putStringArrayListExtra("blocked_apps", ArrayList(blockedApps))
                putExtra("block_mode", blockMode.name)
                putStringArrayListExtra("active_mode_ids", ArrayList(activeModeIds))
                putStringArrayListExtra("manually_activated_mode_ids", ArrayList(manuallyActivatedModeIds))
                putExtra("timed_mode_deactivations", HashMap(timedModeDeactivations))
                putExtra("mode_names", HashMap(modeNames))
            }
            context.startForegroundService(intent)
            ScheduleAlarmReceiver.scheduleWatchdog(context)
            android.util.Log.d("BLOCKER_SERVICE", "--- Service start intent sent")
        }

        fun stop(context: Context) {
            android.util.Log.d("BLOCKER_SERVICE", "-•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•-")
            android.util.Log.d("BLOCKER_SERVICE", "STOP REQUEST RECEIVED")
            android.util.Log.d("BLOCKER_SERVICE", "-•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•-")
            context.stopService(Intent(context, BlockerService::class.java))
        }

        fun isRunning() = isRunning
    }

    private fun startMonitoring() {
        android.util.Log.d("BLOCKER_SERVICE", "------------------------------------------------------------------------------")
        android.util.Log.d("BLOCKER_SERVICE", "STARTING MONITORING LOOP")
        android.util.Log.d("BLOCKER_SERVICE", "------------------------------------------------------------------------------")

        serviceScope.launch {
            monitoringMutex.withLock {
                // Cancel existing monitoring job if any
                monitoringJob?.cancel()

                // Start NEW monitoring loop
                monitoringJob = serviceScope.launch(Dispatchers.Default) {
                    android.util.Log.d("BLOCKER_SERVICE", "--- Monitoring loop started (Job ID: ${this.hashCode()})")

                    while (isActive) {
                        try {
                            checkCurrentApp()
                        } catch (e: Exception) {
                            android.util.Log.e("BLOCKER_SERVICE", "Error in monitoring loop: ${e.message}")
                        }
                        delay(500)
                    }

                    android.util.Log.d("BLOCKER_SERVICE", "--— Monitoring loop ended")
                }
            }
        }
    }

    private suspend fun checkCurrentApp() {
        val currentApp = getForegroundApp()

        android.util.Log.v("BLOCKER_SERVICE", "---- Current foreground app: $currentApp")

        if (currentApp == null) {
            android.util.Log.v("BLOCKER_SERVICE", "------  Could not determine foreground app")
            return
        }

        val shouldBlock = when {
            currentApp == packageName -> {
                android.util.Log.d("BLOCKER_SERVICE", "--- This is Guardian app - ALLOW")
                false
            }
            isSystemLauncher(currentApp) -> {
                android.util.Log.d("BLOCKER_SERVICE", "--- This is system launcher - ALLOW")
                false
            }
            isCriticalSystemApp(currentApp) -> {
                android.util.Log.d("BLOCKER_SERVICE", "--- Critical system app - ALLOW")
                false
            }
            else -> {
                val result = when (blockMode) {
                    BlockMode.BLOCK_SELECTED -> blockedApps.contains(currentApp)
                    BlockMode.ALLOW_SELECTED -> !blockedApps.contains(currentApp)
                }
                android.util.Log.d("BLOCKER_SERVICE", "---- Block mode: $blockMode")
                android.util.Log.d("BLOCKER_SERVICE", "---- App in list: ${blockedApps.contains(currentApp)}")
                android.util.Log.d("BLOCKER_SERVICE", "---- Should block: $result")
                result
            }
        }

        lastCheckedApp = currentApp

        if (shouldBlock) {
            // showOverlaySafe() is already a no-op when overlay is showing (isOverlayShowing guard
            // inside the mutex), so calling it every tick is safe and avoids any race condition
            // where lastCheckedApp was poisoned by the previous monitoring job's last tick.
            android.util.Log.w("BLOCKER_SERVICE", "---- BLOCKING APP: $currentApp")
            AppLogger.log("SERVICE", "BLOCKING: $currentApp (mode=$blockMode, inList=${blockedApps.contains(currentApp)})")
            showOverlaySafe()
        } else {
            // hideOverlaySafe() is a no-op when overlay is not showing — no performance cost.
            android.util.Log.d("BLOCKER_SERVICE", "--- ALLOWING APP: $currentApp")
            hideOverlaySafe()
        }
    }

    private fun isSystemLauncher(packageName: String): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfos = packageManager.queryIntentActivities(intent, 0)
        val isLauncher = resolveInfos.any { it.activityInfo.packageName == packageName }
        if (isLauncher) {
            android.util.Log.d("BLOCKER_SERVICE", "   --- Detected as system launcher: $packageName")
        }
        return isLauncher
    }

    private fun isCriticalSystemApp(packageName: String): Boolean {
        return CRITICAL_SYSTEM_APPS.contains(packageName)
    }


    // Primary: original queryUsageStats (proven reliable and consistent)
// Secondary: queryEvents ONLY to detect recents/system navigation
    private fun getForegroundApp(): String? {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val time = System.currentTimeMillis()

        // Step 1: Query events for the last 60s to find the most recently resumed activity.
        // We use queryEvents (not queryUsageStats) as the primary source because it reflects
        // the actual foreground state even when the user is idle — queryUsageStats with a narrow
        // window returns null after ~10s of inactivity, causing 25s+ activation delays.
        try {
            val usageEvents = usageStatsManager.queryEvents(time - 60_000, time)
            var lastResumedApp: String? = null
            var lastResumedTime = 0L
            var lastPausedApp: String? = null
            val event = android.app.usage.UsageEvents.Event()

            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                when (event.eventType) {
                    android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED -> {
                        if (event.timeStamp >= lastResumedTime) {
                            lastResumedApp = event.packageName
                            lastResumedTime = event.timeStamp
                        }
                    }
                    android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED -> {
                        lastPausedApp = event.packageName
                    }
                }
            }

            // If the most recently resumed app is currently paused (user left it), fall through.
            // If it's still resumed (no matching pause after it), it's the foreground app.
            if (lastResumedApp != null && lastResumedApp != lastPausedApp) {
                // System/launcher = user is in recents/home, not in a blocked app
                if (isCriticalSystemApp(lastResumedApp) || isSystemLauncher(lastResumedApp)) {
                    return lastResumedApp
                }
                return lastResumedApp
            }
        } catch (e: Exception) {
            android.util.Log.e("BLOCKER_SERVICE", "queryEvents (primary) failed: ${e.message}")
        }

        // Step 2: Fallback — queryUsageStats with a wider 5-minute window.
        // Covers edge cases where queryEvents returns nothing (e.g. MIUI permission quirks).
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            time - 1000 * 60 * 5,
            time
        )
        return stats?.maxByOrNull { it.lastTimeUsed }?.packageName
    }

    // CRITICAL FIX: Thread-safe overlay showing with mutex
    private suspend fun showOverlaySafe() {
        overlayMutex.withLock {
            // Prevent duplicate overlays
            if (isOverlayShowing || overlayAnimating) {
                android.util.Log.w("BLOCKER_SERVICE", "------  Overlay already showing or animating, skipping")
                return
            }

            overlayAnimating = true

            withContext(Dispatchers.Main + NonCancellable) {
                try {
                    // Double-check in main thread
                    if (overlayView != null) {
                        android.util.Log.w("BLOCKER_SERVICE", "------  Overlay view exists, cleaning up first")
                        try {
                            windowManager?.removeView(overlayView)
                        } catch (e: Exception) {
                            android.util.Log.e("BLOCKER_SERVICE", "Error removing old overlay: ${e.message}")
                        }
                        overlayView = null
                    }

                    val shown = showOverlay()
                    // Only mark as showing if addView actually succeeded.
                    // showOverlay() returns false on failure (e.g. MIUI PermissionDenied),
                    // so we must NOT set isOverlayShowing=true — otherwise all future
                    // ticks see "already showing" and skip indefinitely.
                    isOverlayShowing = shown
                } finally {
                    overlayAnimating = false
                }
            }
        }
    }

    // CRITICAL FIX: Thread-safe overlay hiding with mutex.
    // State flags are cleared ONLY inside the onComplete callback — i.e. after
    // removeView actually executes — not before. This prevents the overlay from
    // getting permanently stuck when withEndAction throws or is never called.
    private suspend fun hideOverlaySafe() {
        overlayMutex.withLock {
            if (!isOverlayShowing || overlayAnimating) {
                return
            }
            overlayAnimating = true
            // NonCancellable: prevents CancellationException (from double onStartCommand cancelling
            // the old monitoring job) from aborting the hide mid-way and leaving overlayAnimating=true
            // permanently. Without this, job A sets overlayAnimating=true, gets cancelled before
            // hideOverlay() runs, and job B sees overlayAnimating=true forever.
            //
            // suspendCoroutine: holds the mutex until onComplete fires (animation done ~250ms),
            // so state flags are guaranteed reset before the next hideOverlaySafe() call can enter.
            withContext(Dispatchers.Main + NonCancellable) {
                suspendCoroutine { cont ->
                    hideOverlay(onComplete = {
                        isOverlayShowing = false
                        overlayAnimating = false
                        cont.resume(Unit)
                    })
                }
            }
        }
    }

    // Returns true if addView succeeded and the overlay is visible, false otherwise.
    private fun showOverlay(): Boolean {
        android.util.Log.d("BLOCKER_SERVICE", "------------------------------------------------------------------------------")
        android.util.Log.d("BLOCKER_SERVICE", "SHOWING OVERLAY")
        android.util.Log.d("BLOCKER_SERVICE", "------------------------------------------------------------------------------")

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

            android.util.Log.d("BLOCKER_SERVICE", "------- OVERLAY SUCCESSFULLY SHOWN -------")
            AppLogger.log("SERVICE", "OVERLAY SHOWN successfully")
            return true
        } catch (e: Exception) {
            android.util.Log.e("BLOCKER_SERVICE", "--------- FAILED TO SHOW OVERLAY ---------")
            AppLogger.log("SERVICE", "OVERLAY FAILED: ${e.javaClass.simpleName} - ${e.message}")
            android.util.Log.e("BLOCKER_SERVICE", "   Error: ${e.javaClass.simpleName}")
            android.util.Log.e("BLOCKER_SERVICE", "   Message: ${e.message}")
            android.util.Log.e("BLOCKER_SERVICE", "   Stack trace:", e)
            overlayView = null
            return false
        }
    }

    // onComplete is guaranteed to be called exactly once, whether removal succeeds or fails.
    private fun hideOverlay(onComplete: () -> Unit) {
        android.util.Log.d("BLOCKER_SERVICE", "------------------------------------------------------------------------------")
        android.util.Log.d("BLOCKER_SERVICE", "HIDING OVERLAY")
        android.util.Log.d("BLOCKER_SERVICE", "------------------------------------------------------------------------------")

        val view = overlayView
        if (view == null) {
            android.util.Log.d("BLOCKER_SERVICE", "   No overlay to hide (already null)")
            onComplete()
            return
        }

        fun forceRemove() {
            try { windowManager?.removeView(view) } catch (e: Exception) {
                android.util.Log.e("BLOCKER_SERVICE", "--- Failed to force remove overlay: ${e.message}")
            }
            overlayView = null
            onComplete()
        }

        try {
            view.animate()
                ?.alpha(0f)
                ?.scaleX(0.98f)
                ?.scaleY(0.98f)
                ?.setDuration(250)
                ?.setInterpolator(android.view.animation.AccelerateInterpolator(1.2f))
                ?.withEndAction {
                    // Runs on main thread ~250ms later. State is cleared here.
                    try {
                        windowManager?.removeView(view)
                        overlayView = null
                        android.util.Log.d("BLOCKER_SERVICE", "------- OVERLAY SUCCESSFULLY HIDDEN -------")
                        AppLogger.log("SERVICE", "OVERLAY HIDDEN successfully")
                    } catch (e: Exception) {
                        android.util.Log.e("BLOCKER_SERVICE", "--- Failed to remove overlay in withEndAction: ${e.message}")
                        overlayView = null
                    }
                    onComplete()
                }
                ?.start()
        } catch (e: Exception) {
            android.util.Log.e("BLOCKER_SERVICE", "--- Failed to start hide animation: ${e.message}")
            forceRemove()
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
                android.util.Log.d("BLOCKER_SERVICE", "--‘† TOUCH EVENT DETECTED ON OVERLAY")
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
                            android.util.Log.d("BLOCKER_SERVICE", "---˜ GUARDIAN BUTTON CLICKED")
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

            android.util.Log.d("BLOCKER_SERVICE", "   --- Blocker view UI complete")
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
            val modeCount = activeModeIds.size
            val manualCount = activeModeIds.count { manuallyActivatedModeIds.contains(it) }
            val scheduleCount = modeCount - manualCount
            val timedCount = activeModeIds.count { timedModeDeactivations.containsKey(it) }

            buildString {
                append("$modeCount MODE${if (modeCount > 1) "S" else ""}")
                val parts = mutableListOf<String>()
                if (manualCount > 0) parts.add("${manualCount} manual")
                if (scheduleCount > 0) parts.add("${scheduleCount} scheduled")
                if (parts.isNotEmpty()) append(" (${parts.joinToString(", ")})")
                if (timedCount > 0) {
                    val nextExpiry = activeModeIds
                        .mapNotNull { timedModeDeactivations[it] }
                        .minOrNull()
                    if (nextExpiry != null) {
                        val remaining = (nextExpiry - System.currentTimeMillis()) / 60000
                        if (remaining > 0) append(" · ${remaining}m left")
                    }
                }
            }
        }

        // Build expanded style with mode details
        val bigTextStyle = NotificationCompat.BigTextStyle()
        if (activeModeIds.isNotEmpty()) {
            val details = buildString {
                activeModeIds.forEach { modeId ->
                    val name = modeNames[modeId]?.uppercase() ?: modeId.take(8)
                    val isManual = manuallyActivatedModeIds.contains(modeId)
                    val isTimed = timedModeDeactivations.containsKey(modeId)
                    append("• $name")
                    if (isManual && isTimed) {
                        val remaining = ((timedModeDeactivations[modeId] ?: 0) - System.currentTimeMillis()) / 60000
                        append(" — manual, ${remaining}m left")
                    } else if (isManual) {
                        append(" — manual (NFC to unlock)")
                    } else {
                        append(" — by schedule")
                    }
                    append("\n")
                }
            }.trimEnd()
            bigTextStyle.bigText(details)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(titleText)
            .setContentText(contentText)
            .setStyle(bigTextStyle)
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
        android.util.Log.d("BLOCKER_SERVICE", "-•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•-")
        android.util.Log.d("BLOCKER_SERVICE", "TASK REMOVED")
        android.util.Log.d("BLOCKER_SERVICE", "-•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•-")
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
                android.util.Log.e("BLOCKER_SERVICE", "--- Error scheduling restart: ${e.message}", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d("BLOCKER_SERVICE", "-•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•-")
        android.util.Log.d("BLOCKER_SERVICE", "SERVICE DESTROYED")
        android.util.Log.d("BLOCKER_SERVICE", "-•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•-")
        isRunning = false
        AppLogger.log("SERVICE", "BlockerService DESTROYED")

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
        android.util.Log.d("BLOCKER_SERVICE", "-•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•--•-")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}