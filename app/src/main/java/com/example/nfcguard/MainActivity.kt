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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.net.Uri
import kotlinx.coroutines.launch

enum class Screen {
    HOME, MODES, SCHEDULES, NFC_TAGS, INFO
}

class MainActivity : ComponentActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var scannedNfcTagId = mutableStateOf<String?>(null)
    private var wrongTagScanned = mutableStateOf(false)

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
                    scannedNfcTagId = scannedNfcTagId,
                    wrongTagScanned = wrongTagScanned
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

                // Check if this is a valid tag for current active modes
                val prefs = getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
                val stateJson = prefs.getString("app_state", null)
                if (stateJson != null) {
                    try {
                        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        val appState = json.decodeFromString<AppState>(stateJson)

                        // Check if any active mode requires this specific tag
                        val activeModes = appState.modes.filter { appState.activeModes.contains(it.id) }
                        val hasNfcLockedMode = activeModes.any { it.nfcTagId != null }

                        if (hasNfcLockedMode) {
                            val validTag = activeModes.any { it.nfcTagId == tagId }
                            if (!validTag && appState.activeModes.isNotEmpty()) {
                                // Wrong tag scanned!
                                wrongTagScanned.value = true
                                kotlinx.coroutines.GlobalScope.launch {
                                    kotlinx.coroutines.delay(2000)
                                    wrongTagScanned.value = false
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("NFC_SCAN", "Error validating tag: ${e.message}")
                    }
                }

                scannedNfcTagId.value = tagId
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val prefs = getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        val hasCompletedInitialSetup = prefs.getBoolean("initial_permissions_granted", false)

        if (!hasCompletedInitialSetup) {
            showWelcomeDialog()
        }
    }

    private fun showWelcomeDialog() {
        val builder = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)

        val dialogView = layoutInflater.inflate(
            android.R.layout.select_dialog_item, null
        )

        builder.setTitle("Welcome to Guardian")
            .setMessage(
                "Guardian needs the following permissions to protect your focus:\n\n" +
                        "• USAGE ACCESS - Detect which apps you're using\n" +
                        "• DISPLAY OVER APPS - Show the block screen\n" +
                        "• BATTERY OPTIMIZATION - Run reliably in background\n" +
                        "• PAUSE APP ACTIVITY - Must be disabled for Guardian\n\n" +
                        "Let's set these up now."
            )
            .setPositiveButton("Continue") { _, _ ->
                startPermissionFlow()
            }
            .setNegativeButton("Skip") { _, _ ->
                val prefs = getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("initial_permissions_granted", true).apply()
            }
            .setCancelable(false)
            .show()
    }

    private fun startPermissionFlow() {
        val permissionsNeeded = mutableListOf<PermissionRequest>()

        // Check Usage Access
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

        // Check Display Over Apps
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

        // Check Battery Optimization
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

        if (permissionsNeeded.isNotEmpty()) {
            showPermissionDialog(permissionsNeeded, 0)
        } else {
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

                val prefs = getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("initial_permissions_granted", true).apply()
            }
            .setNegativeButton("OK") { _, _ ->
                val prefs = getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("initial_permissions_granted", true).apply()
            }
            .show()
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
    scannedNfcTagId: MutableState<String?>,
    wrongTagScanned: MutableState<Boolean>
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
            Screen.INFO -> InfoScreen(
                onBack = { currentScreen = Screen.HOME }
            )
        }

        // Show wrong tag feedback
        if (wrongTagScanned.value) {
            WrongTagFeedback()
        }
    }
}

@Composable
fun WrongTagFeedback() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x88000000)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(0.dp),
            color = Color(0xFF8B0000),
            modifier = Modifier.padding(48.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    Icons.Default.Error,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(64.dp)
                )
                Text(
                    "WRONG TAG",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 2.sp
                )
                Text(
                    "This mode requires\na specific NFC tag",
                    fontSize = 14.sp,
                    color = Color(0xFFFFCCCC),
                    textAlign = TextAlign.Center,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}