package com.andebugulin.nfcguard

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.content.pm.ServiceInfo

class BlockerService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var foregroundAppDetector: ForegroundAppDetector
    private lateinit var overlayEnforcer: OverlayEnforcer
    private lateinit var forceCloseEnforcer: ForceCloseEnforcer
    private var blockedApps = setOf<String>()
    private var blockMode = BlockMode.BLOCK_SELECTED
    private var activeModeIds = setOf<String>()
    private var manuallyActivatedModeIds = setOf<String>()
    private var timedModeDeactivations = mapOf<String, Long>()
    private var timedModeReactivations = mapOf<String, Long>()
    private var modeNames = mapOf<String, String>()
    private var lastCheckedApp: String? = null

    // CRITICAL: Prevent multiple monitoring loops
    private var monitoringJob: Job? = null
    private val monitoringMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        isRunning = true
        foregroundAppDetector = ForegroundAppDetector(this)
        forceCloseEnforcer = ForceCloseEnforcer(this)
        overlayEnforcer = OverlayEnforcer(this) {
            // User tapped the overlay — kick off a re-check so it can hide
            // itself if the user has already navigated away.
            monitoringJob?.let { job ->
                if (job.isActive) {
                    serviceScope.launch {
                        lastCheckedApp = null
                        checkCurrentApp()
                    }
                }
            }
        }
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringArrayListExtra("blocked_apps")?.let {
            blockedApps = it.toSet()
            AppLogger.log("SERVICE", "onStartCommand: ${blockedApps.size} apps in blocklist")
        }

        intent?.getStringExtra("block_mode")?.let {
            blockMode = BlockMode.valueOf(it)
            AppLogger.log("SERVICE", "onStartCommand: blockMode=$blockMode")
        }

        intent?.getStringArrayListExtra("active_mode_ids")?.let {
            activeModeIds = it.toSet()
            AppLogger.log("SERVICE", "onStartCommand: activeModeIds=$activeModeIds")
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
        intent?.getSerializableExtra("timed_mode_reactivations")?.let {
            @Suppress("UNCHECKED_CAST")
            timedModeReactivations = (it as? java.util.HashMap<String, Long>)?.toMap() ?: emptyMap()
        }

        lastCheckedApp = null

        // FIX: When no active modes arrive, force-hide any in-flight overlay
        // immediately. This prevents the race condition where the OLD monitoring
        // loop showed the overlay using stale blocklist data right before this
        // intent was processed. Without this, the overlay flashes for ~400ms
        // and on Samsung devices that flash kills the accessibility service.
        if (activeModeIds.isEmpty()) {
            overlayEnforcer.forceHideImmediate()
        }

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

        fun start(
            context: Context,
            blockedApps: Set<String>,
            blockMode: BlockMode,
            activeModeIds: Set<String>,
            manuallyActivatedModeIds: Set<String> = emptySet(),
            timedModeDeactivations: Map<String, Long> = emptyMap(),
            modeNames: Map<String, String> = emptyMap(),
            timedModeReactivations: Map<String, Long> = emptyMap()
        ) {
            if (!Settings.canDrawOverlays(context)) {
                AppLogger.log("SERVICE", "OVERLAY PERMISSION NOT GRANTED - cannot start blocking")
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
                putStringArrayListExtra("manually_activated_mode_ids", ArrayList(manuallyActivatedModeIds))
                putExtra("timed_mode_deactivations", HashMap(timedModeDeactivations))
                putExtra("mode_names", HashMap(modeNames))
                putExtra("timed_mode_reactivations", HashMap(timedModeReactivations))
            }
            context.startForegroundService(intent)
            ScheduleAlarmReceiver.scheduleWatchdog(context)
        }

        fun stop(context: Context) {
            AppLogger.log("SERVICE", "Stop requested")
            context.stopService(Intent(context, BlockerService::class.java))
        }

        fun isRunning() = isRunning
    }

    private fun startMonitoring() {
        serviceScope.launch {
            monitoringMutex.withLock {
                monitoringJob?.cancel()
                monitoringJob = serviceScope.launch(Dispatchers.Default) {
                    AppLogger.log("SERVICE", "Monitoring loop started (job=${this.hashCode()})")
                    while (isActive) {
                        try {
                            checkCurrentApp()
                        } catch (e: Exception) {
                            AppLogger.log("SERVICE", "Error in monitoring loop: ${e.message}")
                        }
                        delay(500)
                    }
                }
            }
        }
    }

    private suspend fun checkCurrentApp() {
        // FIX: If no modes are active, nothing can possibly be blocked.
        // Skip detection entirely to avoid the race condition where we
        // evaluate with stale blocklist data during mode transitions.
        if (activeModeIds.isEmpty() && blockedApps.isEmpty()) {
            // Still hide overlay in case one is lingering from the race
            // (overlay can be showing even in kill mode if accessibility was off → fallback)
            overlayEnforcer.onAllowed(currentApp = "", isLauncher = false)
            return
        }

        val currentApp = foregroundAppDetector.current()

        android.util.Log.v("BLOCKER_SERVICE", "---- Current foreground app: $currentApp")

        if (currentApp == null) {
            android.util.Log.v("BLOCKER_SERVICE", "------  Could not determine foreground app")
            return
        }

        val isLauncher = isSystemLauncher(currentApp)
        val decision = BlockDecider.decide(
            currentApp = currentApp,
            isLauncher = isLauncher,
            activeModeIds = activeModeIds,
            blockedApps = blockedApps,
            blockMode = blockMode
        )
        android.util.Log.d("BLOCKER_SERVICE", "---- Decision for $currentApp: $decision (launcher=$isLauncher, mode=$blockMode, inList=${blockedApps.contains(currentApp)})")

        lastCheckedApp = currentApp

        if (decision == BlockDecider.Decision.BLOCK) {
            // Strategy:
            //   Accessibility ON  → force-close (HOME + kill). Overlay has
            //                       bugs with accessibility (stuck-on-home
            //                       on MIUI, disappearing on Samsung).
            //   Accessibility OFF → overlay. Force-close needs accessibility
            //                       for the HOME action.
            val useForceClose = ForegroundDetectorService.isRunning
            AppLogger.log("SERVICE", "BLOCKING: $currentApp (mode=$blockMode, inList=${blockedApps.contains(currentApp)}, forceClose=$useForceClose)")
            if (useForceClose) {
                forceCloseEnforcer.block(currentApp)
            } else {
                overlayEnforcer.block(currentApp)
            }
        } else {
            // Both enforcers' onAllowed fire — defense in depth. The cooldown
            // reset for force-close and the "hide a stale overlay from a
            // prior fallback" are independent concerns.
            forceCloseEnforcer.onAllowed(currentApp, isLauncher)
            overlayEnforcer.onAllowed(currentApp, isLauncher)
        }
    }

    private fun isSystemLauncher(packageName: String): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfos = packageManager.queryIntentActivities(intent, 0)
        return resolveInfos.any { it.activityInfo.packageName == packageName }
    }


    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val timedDeactivationCount = activeModeIds.count { timedModeDeactivations.containsKey(it) }
        val timedReactivationCount = timedModeReactivations.size

        val titleText = when {
            activeModeIds.isNotEmpty() -> "GUARDIAN ACTIVE"
            timedReactivationCount > 0 -> "GUARDIAN PAUSED"
            else -> "GUARDIAN MONITORING"
        }

        val contentText = if (activeModeIds.isEmpty() && timedReactivationCount == 0) {
            "Waiting for scheduled modes"
        } else {
            val modeCount = activeModeIds.size + timedReactivationCount
            val manualCount = activeModeIds.count { manuallyActivatedModeIds.contains(it) }
            val scheduleCount = modeCount - manualCount - timedReactivationCount

            buildString {
                if (modeCount > 0) {
                    append("$modeCount MODE${if (modeCount > 1) "S" else ""}")
                    val parts = mutableListOf<String>()
                    if (manualCount > 0) parts.add("${manualCount} manual")
                    if (scheduleCount > 0) parts.add("${scheduleCount} scheduled")
                    if (timedReactivationCount > 0) parts.add("${timedReactivationCount} paused")
                    if (parts.isNotEmpty()) append(" (${parts.joinToString(", ")})")
                }
            }
        }

        val bigTextStyle = NotificationCompat.BigTextStyle()

        // Resolve mode names and schedule info from the repository
        var resolvedAppState: AppState? = null
        val resolvedNames = if (modeNames.isNotEmpty()) modeNames else {
            try {
                val state = AppStateRepository.getInstance(this).current
                resolvedAppState = state
                state.modes.associate { it.id to it.name }
            } catch (_: Exception) { emptyMap() }
        }

        // Helper to find schedule end time for a mode
        fun getScheduleEndTimeForMode(modeId: String): String? {
            val state = resolvedAppState ?: run {
                try {
                    AppStateRepository.getInstance(this).current.also { resolvedAppState = it }
                } catch (_: Exception) { return null }
            }
            val cal = java.util.Calendar.getInstance()
            val currentDay = when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
                java.util.Calendar.MONDAY -> 1; java.util.Calendar.TUESDAY -> 2
                java.util.Calendar.WEDNESDAY -> 3; java.util.Calendar.THURSDAY -> 4
                java.util.Calendar.FRIDAY -> 5; java.util.Calendar.SATURDAY -> 6
                java.util.Calendar.SUNDAY -> 7; else -> 1
            }
            return state.schedules.firstOrNull { schedule ->
                schedule.linkedModeIds.contains(modeId) &&
                        schedule.hasEndTime
            }?.timeSlot?.getTimeForDay(currentDay)?.let { dt ->
                String.format("%02d:%02d", dt.endHour, dt.endMinute)
            }
        }

        if (activeModeIds.isNotEmpty() || timedModeReactivations.isNotEmpty()) {
            val details = buildString {
                if (activeModeIds.isNotEmpty()) {
                    activeModeIds.forEach { modeId ->
                        val name = resolvedNames[modeId]?.uppercase() ?: modeId.take(8)
                        val isManual = manuallyActivatedModeIds.contains(modeId)
                        val isTimed = timedModeDeactivations.containsKey(modeId)
                        append("• $name")
                        if (isTimed) {
                            val endTime = timedModeDeactivations[modeId] ?: 0
                            val prefix = if (isManual) "manual" else "active"
                            append(" — $prefix, until ${formatNotificationTime(endTime)}")
                        } else if (isManual) {
                            append(" — manual")
                        } else {
                            val schedEnd = getScheduleEndTimeForMode(modeId)
                            if (schedEnd != null) {
                                append(" — by schedule, until $schedEnd")
                            } else {
                                append(" — by schedule")
                            }
                        }
                        append("\n")
                    }
                }

                if (timedModeReactivations.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    timedModeReactivations.forEach { (modeId, reactivateAt) ->
                        val name = resolvedNames[modeId]?.uppercase() ?: modeId.take(8)
                        append("• $name — resumes at ${formatNotificationTime(reactivateAt)}\n")
                    }
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

    private fun formatNotificationTime(epochMillis: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
        return String.format("%02d:%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), cal.get(java.util.Calendar.SECOND))
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
        AppLogger.log("SERVICE", "Task removed — scheduling restart")
        scheduleServiceRestart()
    }

    private fun scheduleServiceRestart() {
        try {
            val appState = AppStateRepository.getInstance(applicationContext).current

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
            AppLogger.log("SERVICE", "Error scheduling restart: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        AppLogger.log("SERVICE", "BlockerService DESTROYED")

        // Cancel monitoring first
        runBlocking {
            monitoringMutex.withLock {
                monitoringJob?.cancel()
                monitoringJob = null
            }
        }

        overlayEnforcer.onDestroy()
        forceCloseEnforcer.onDestroy()

        serviceScope.cancel()

        scheduleServiceRestart()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}