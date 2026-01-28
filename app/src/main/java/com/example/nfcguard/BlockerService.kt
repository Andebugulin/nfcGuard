package com.example.nfcguard

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
import android.content.pm.ServiceInfo

class BlockerService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var blockedApps = setOf<String>()
    private var blockMode = BlockMode.BLOCK_SELECTED
    private var activeModeIds = setOf<String>()
    private var lastCheckedApp: String? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringArrayListExtra("blocked_apps")?.let {
            blockedApps = it.toSet()
        }

        intent?.getStringExtra("block_mode")?.let {
            blockMode = BlockMode.valueOf(it)
        }

        intent?.getStringArrayListExtra("active_mode_ids")?.let {
            activeModeIds = it.toSet()
        }

        lastCheckedApp = null
        serviceScope.launch {
            checkCurrentApp()
        }

        startMonitoring()
        return START_STICKY
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "guardian_channel"
        private var isRunning = false

        fun start(
            context: Context,
            blockedApps: Set<String>,
            blockMode: BlockMode,
            activeModeIds: Set<String>
        ) {
            if (!Settings.canDrawOverlays(context)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}")
                )
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return
            }

            val intent = Intent(context, BlockerService::class.java).apply {
                putStringArrayListExtra("blocked_apps", ArrayList(blockedApps))
                putExtra("block_mode", blockMode.name)
                putStringArrayListExtra("active_mode_ids", ArrayList(activeModeIds))
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BlockerService::class.java))
        }

        fun isRunning() = isRunning
    }

    private fun startMonitoring() {
        serviceScope.launch {
            while (isActive) {
                checkCurrentApp()
                delay(500)
            }
        }
    }

    private fun checkCurrentApp() {
        val currentApp = getForegroundApp()

        if (currentApp == null) {
            return
        }

        if (currentApp != lastCheckedApp) {
            lastCheckedApp = currentApp

            val shouldBlock = when {
                currentApp == packageName -> false
                isSystemLauncher(currentApp) -> false
                currentApp == "com.android.systemui" -> false
                else -> when (blockMode) {
                    BlockMode.BLOCK_SELECTED -> blockedApps.contains(currentApp)
                    BlockMode.ALLOW_SELECTED -> !blockedApps.contains(currentApp)
                }
            }

            if (shouldBlock) {
                if (overlayView == null) {
                    showOverlay()
                }
            } else {
                hideOverlay()
            }
        }
    }

    private fun isSystemLauncher(packageName: String): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfos = packageManager.queryIntentActivities(intent, 0)
        return resolveInfos.any { it.activityInfo.packageName == packageName }
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

    private fun showOverlay() {
        if (overlayView != null) return

        mainHandler.post {
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
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.OPAQUE
            )

            params.gravity = Gravity.TOP or Gravity.START
            params.x = 0
            params.y = 0

            overlayView = createBlockerView()
            try {
                windowManager?.addView(overlayView, params)
            } catch (e: Exception) {
                overlayView = null
            }
        }
    }

    private fun hideOverlay() {
        mainHandler.post {
            overlayView?.let {
                try {
                    windowManager?.removeView(it)
                } catch (e: Exception) {
                }
                overlayView = null
            }
        }
    }

    private fun createBlockerView(): View {
        return android.widget.FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            setBackgroundColor(0xFF000000.toInt())
            isClickable = true
            isFocusable = true
            alpha = 1.0f

            val content = android.widget.LinearLayout(this@BlockerService).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
                setPadding(48, 48, 48, 48)

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
        android.util.Log.d("BLOCKER_SERVICE", "Task removed - scheduling restart")
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
                    android.util.Log.d("BLOCKER_SERVICE", "Scheduling restart via AlarmManager")

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
                android.util.Log.e("BLOCKER_SERVICE", "Error scheduling restart: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
        hideOverlay()

        android.util.Log.d("BLOCKER_SERVICE", "Service destroyed")
        scheduleServiceRestart()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}