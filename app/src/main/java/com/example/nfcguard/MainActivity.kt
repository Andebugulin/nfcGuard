package com.example.nfcguard

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import android.net.Uri

enum class Screen {
    HOME, MODES, SCHEDULES, NFC_TAGS
}

class MainActivity : ComponentActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var scannedNfcTagId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )

        setContent {
            MinimalistTheme {
                val viewModel: GuardianViewModel = viewModel()
                val context = LocalContext.current

                LaunchedEffect(Unit) {
                    viewModel.loadData(context)
                }

                MainNavigation(
                    viewModel = viewModel,
                    scannedNfcTagId = scannedNfcTagId
                )
            }
        }

        checkAndRequestPermissions()
        handleNfcIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    private fun handleNfcIntent(intent: Intent?) {
        if (intent?.action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            intent?.action == NfcAdapter.ACTION_TECH_DISCOVERED ||
            intent?.action == NfcAdapter.ACTION_NDEF_DISCOVERED
        ) {
            val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            tag?.let {
                val tagId = it.id.joinToString("") { byte -> "%02x".format(byte) }
                android.util.Log.d("NFC_SCAN", "Scanned tag: $tagId")
                android.util.Log.d("NFC_SCAN", "Current scannedNfcTagId before: ${scannedNfcTagId.value}")
                scannedNfcTagId.value = tagId
                android.util.Log.d("NFC_SCAN", "Current scannedNfcTagId after: ${scannedNfcTagId.value}")
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val prefs = getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        val hasCompletedInitialSetup = prefs.getBoolean("initial_permissions_granted", false)

        val permissionsNeeded = mutableListOf<PermissionRequest>()

        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val time = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(
            android.app.usage.UsageStatsManager.INTERVAL_DAILY,
            time - 1000,
            time
        )
        if (stats.isEmpty()) {
            permissionsNeeded.add(
                PermissionRequest(
                    "Usage Access",
                    "Required to detect which apps you're using",
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                )
            )
        }

        if (!Settings.canDrawOverlays(this)) {
            permissionsNeeded.add(
                PermissionRequest(
                    "Display Over Apps",
                    "Required to show the block screen",
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            )
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            permissionsNeeded.add(
                PermissionRequest(
                    "Battery Optimization",
                    "Required to run reliably in the background",
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            )
        }

        if (permissionsNeeded.isNotEmpty() && !hasCompletedInitialSetup) {
            showPermissionDialog(permissionsNeeded, 0)
        } else if (permissionsNeeded.isEmpty() && !hasCompletedInitialSetup) {
            prefs.edit().putBoolean("initial_permissions_granted", true).apply()
            showPauseAppReminder()
        }
    }

    private data class PermissionRequest(
        val title: String,
        val description: String,
        val intent: Intent
    )

    private fun showPermissionDialog(permissions: List<PermissionRequest>, index: Int) {
        if (index >= permissions.size) {
            val prefs = getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("initial_permissions_granted", true).apply()
            showPauseAppReminder()
            return
        }

        val permission = permissions[index]

        createStyledDialog(permission.title, permission.description)
            .setPositiveButton("Grant") { _, _ ->
                try {
                    startActivity(permission.intent)
                } catch (e: Exception) {
                    if (permission.title == "Battery Optimization") {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    showPermissionDialog(permissions, index + 1)
                }, 500)
            }
            .setNegativeButton("Skip") { _, _ ->
                showPermissionDialog(permissions, index + 1)
            }
            .setCancelable(false)
            .show()
    }

    private fun showPauseAppReminder() {
        val prefs = getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        val hasShownReminder = prefs.getBoolean("has_shown_pause_reminder", false)

        if (!hasShownReminder) {
            createStyledDialog(
                "Important: Disable 'Pause App if Unused'",
                "To ensure Guardian works reliably:\n\n" +
                        "1. Go to Settings > Apps > Guardian\n" +
                        "2. Find 'Pause app activity if unused'\n" +
                        "3. Turn it OFF\n\n" +
                        "This prevents Android from pausing Guardian in the background."
            )
                .setPositiveButton("Open App Settings") { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } catch (e: Exception) {}
                    prefs.edit().putBoolean("has_shown_pause_reminder", true).apply()
                }
                .setNegativeButton("OK") { _, _ ->
                    prefs.edit().putBoolean("has_shown_pause_reminder", true).apply()
                }
                .show()
        }
    }

    private fun createStyledDialog(
        title: String,
        message: String
    ): android.app.AlertDialog.Builder {
        return android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setTitle(title)
            .setMessage(message)
    }
}

@Composable
fun MinimalistTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF000000),
            surface = Color(0xFF0A0A0A),
            primary = Color(0xFFFFFFFF),
            secondary = Color(0xFF808080),
            onBackground = Color(0xFFFFFFFF),
            onSurface = Color(0xFFFFFFFF),
        ),
        content = content
    )
}

@Composable
fun MainNavigation(
    viewModel: GuardianViewModel,
    scannedNfcTagId: MutableState<String?>
) {
    var currentScreen by remember { mutableStateOf(Screen.HOME) }
    val appState by viewModel.appState.collectAsState()

    // Handle NFC tag scans when modes are active (for unlocking)
    LaunchedEffect(scannedNfcTagId.value, appState.activeModes) {
        val tagId = scannedNfcTagId.value
        if (tagId != null && appState.activeModes.isNotEmpty()) {
            android.util.Log.d("MAIN_NAV", "NFC tag scanned with active modes - unlocking")
            viewModel.handleNfcTag(tagId)
            scannedNfcTagId.value = null
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when (currentScreen) {
            Screen.HOME -> HomeScreen(
                viewModel = viewModel,
                onNavigate = { screen -> currentScreen = screen }
            )
            Screen.MODES -> ModesScreen(
                viewModel = viewModel,
                onBack = { currentScreen = Screen.HOME }
            )
            Screen.SCHEDULES -> SchedulesScreen(
                viewModel = viewModel,
                onBack = { currentScreen = Screen.HOME }
            )
            Screen.NFC_TAGS -> NfcTagsScreen(
                viewModel = viewModel,
                scannedNfcTagId = scannedNfcTagId,
                onBack = { currentScreen = Screen.HOME }
            )
        }
    }
}