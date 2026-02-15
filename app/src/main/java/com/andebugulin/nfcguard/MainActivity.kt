package com.andebugulin.nfcguard

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
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.net.Uri
import androidx.core.graphics.drawable.toBitmap
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

    fun checkAndRequestPermissions() {
        val prefs = getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        val hasSeenOnboarding = prefs.getBoolean("has_seen_onboarding", false)
        val hasCompletedInitialSetup = prefs.getBoolean("initial_permissions_granted", false)

        if (!hasSeenOnboarding) {
            // Show onboarding first
            return
        }

        if (!hasCompletedInitialSetup) {
            showWelcomeDialog()
        }
    }

    private fun showWelcomeDialog() {
        val builder = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)

        builder.setTitle("WELCOME TO GUARDIAN")
            .setMessage(
                "Guardian needs the following permissions to protect your focus:\n\n\n\n" +
                        "• USAGE ACCESS - Detect which apps you're using\n\n" +
                        "• DISPLAY OVER APPS - Show the block screen\n\n" +
                        "• BATTERY OPTIMIZATION - Run reliably in background\n\n" +
                        "• PAUSE APP ACTIVITY - Must be disabled for Guardian\n\n\n\n" +
                        "Let's set these up now."
            )
            .setPositiveButton("CONTINUE") { _, _ ->
                startPermissionFlow()
            }
            .setNegativeButton("SKIP") { _, _ ->
                val prefs = getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("initial_permissions_granted", true).apply()
            }
            .setCancelable(false)

        val dialog = builder.create()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.black)
            decorView.setBackgroundColor(android.graphics.Color.BLACK)

            // Add border
            val drawable = android.graphics.drawable.GradientDrawable()
            drawable.setColor(android.graphics.Color.BLACK)
            drawable.setStroke(6, android.graphics.Color.parseColor("#340000")) // Dark red border for warning
            setBackgroundDrawable(drawable)
        }
        dialog.show()

        // Style buttons after showing
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.apply {
            setTextColor(android.graphics.Color.parseColor("#808080"))
            setBackgroundColor(android.graphics.Color.BLACK)
        }
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

        val builder = createStyledDialog(permission.title, permission.description)
            .setPositiveButton("GRANT") { _, _ ->
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
            .setNegativeButton("SKIP") { _, _ ->
                showPermissionDialog(permissions, index + 1)
            }
            .setCancelable(false)

        val dialog = builder.create()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.black)
            decorView.setBackgroundColor(android.graphics.Color.BLACK)

            // Add border
            val drawable = android.graphics.drawable.GradientDrawable()
            drawable.setColor(android.graphics.Color.BLACK)
            drawable.setStroke(6, android.graphics.Color.parseColor("#340000")) // Dark red border for warning
            setBackgroundDrawable(drawable)
        }
        dialog.show()

        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.apply {
            setTextColor(android.graphics.Color.parseColor("#808080"))
            setBackgroundColor(android.graphics.Color.BLACK)
        }
    }

    private fun showPauseAppReminder() {
        val builder = createStyledDialog(
            "IMPORTANT: DISABLE 'PAUSE APP IF UNUSED'",
            "To ensure Guardian works reliably:\n\n" +
                    "1. Go to Settings > Apps > Guardian\n" +
                    "2. Find 'Pause app activity if unused'\n" +
                    "3. Turn it OFF\n\n" +
                    "This prevents Android from pausing Guardian in the background."
        )
            .setPositiveButton("OPEN APP SETTINGS") { _, _ ->
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

        val dialog = builder.create()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.black)
            decorView.setBackgroundColor(android.graphics.Color.BLACK)

            // Add border
            val drawable = android.graphics.drawable.GradientDrawable()
            drawable.setColor(android.graphics.Color.BLACK)
            drawable.setStroke(6, android.graphics.Color.parseColor("#340000")) // Dark red border for warning
            setBackgroundDrawable(drawable)
        }
        dialog.show()

        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.apply {
            setTextColor(android.graphics.Color.parseColor("#808080"))
            setBackgroundColor(android.graphics.Color.BLACK)
        }
    }

    private fun createStyledDialog(
        title: String,
        message: String
    ): android.app.AlertDialog.Builder {
        val builder = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)

        // Create custom view using window
        val dialog = builder.create()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.black)
            decorView.setBackgroundColor(android.graphics.Color.BLACK)
        }

        return builder.setTitle(title).setMessage(message)
    }
}

@Composable
fun MinimalistTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = GuardianTheme.BackgroundPrimary,
            surface = GuardianTheme.BackgroundSurface,
            primary = GuardianTheme.ButtonPrimary,
            secondary = GuardianTheme.TextSecondary,
            onBackground = GuardianTheme.TextPrimary,
            onSurface = GuardianTheme.TextPrimary,
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
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE) }
    var hasSeenOnboarding by remember {
        mutableStateOf(prefs.getBoolean("has_seen_onboarding", false))
    }
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
        if (!hasSeenOnboarding) {
            OnboardingScreen(
                onComplete = {
                    prefs.edit().putBoolean("has_seen_onboarding", true).apply()
                    hasSeenOnboarding = true
                    // Trigger permission flow
                    (context as? MainActivity)?.let { activity ->
                        activity.runOnUiThread {
                            val hasCompletedPermissions = prefs.getBoolean("initial_permissions_granted", false)
                            if (!hasCompletedPermissions) {
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    activity.checkAndRequestPermissions()
                                }, 300)
                            }
                        }
                    }
                }
            )
        } else {
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
}

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    var currentPage by remember { mutableStateOf(0) }
    val pages = listOf(
        OnboardingPage(
            title = "GUARDIAN",
            subtitle = "DIGITAL WELLBEING",
            description = "Break free from mindless scrolling. Guardian blocks distracting apps until you physically unlock them with NFC tags.",
            icon = "shield"
        ),
        OnboardingPage(
            title = "MODES",
            subtitle = "FLEXIBLE CONTROL",
            description = "Create blocking modes for different contexts:\n\n• BLOCK MODE - Block specific distracting apps\n• ALLOW MODE - Block everything except essential apps",
            icon = "modes"
        ),
        OnboardingPage(
            title = "NFC LOCKS",
            subtitle = "PHYSICAL FRICTION",
            description = "Optional: Require NFC tags to unlock modes.\n\nPlace tags in inconvenient locations (kitchen, gym, friend's house) to add intentional friction before accessing blocked apps.",
            icon = "nfc"
        ),
        OnboardingPage(
            title = "SCHEDULES",
            subtitle = "AUTOMATION",
            description = "Set modes to activate automatically:\n\n• Weekday work hours (9am-5pm)\n• Sleep schedule (10pm-7am)\n• Weekend deep work sessions",
            icon = "schedule"
        ),
        OnboardingPage(
            title = "READY",
            subtitle = "LET'S GET STARTED",
            description = "Guardian needs a few permissions to work:\n\n• Usage access - Detect which apps you use\n• Display over apps - Show block screen\n• Battery optimization - Run reliably\n\nLet's set them up now.",
            icon = "ready"
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GuardianTheme.BackgroundPrimary)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Page content
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                OnboardingPageContent(pages[currentPage])
            }

            // Progress indicators
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                pages.indices.forEach { index ->
                    if (index == currentPage) {
                        // Active page - filled white circle
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .padding(2.dp)
                                .background(
                                    GuardianTheme.TextPrimary,
                                    shape = androidx.compose.foundation.shape.CircleShape
                                )
                        )
                    } else {
                        // Inactive page - hollow circle with white border
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .padding(2.dp)
                                .border(
                                    width = 1.dp,
                                    color = GuardianTheme.TextPrimary,
                                    shape = androidx.compose.foundation.shape.CircleShape
                                )
                        )
                    }
                }
            }

            // Navigation buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (currentPage > 0) {
                    TextButton(
                        onClick = { currentPage-- },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = GuardianTheme.TextSecondary
                        )
                    ) {
                        Text("BACK", letterSpacing = 1.sp)
                    }
                } else {
                    Spacer(modifier = Modifier.width(80.dp))
                }

                Button(
                    onClick = {
                        if (currentPage < pages.size - 1) {
                            currentPage++
                        } else {
                            onComplete()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GuardianTheme.ButtonPrimary,
                        contentColor = GuardianTheme.ButtonPrimaryText
                    ),
                    shape = RoundedCornerShape(0.dp),
                    modifier = Modifier.height(48.dp).widthIn(min = 120.dp)
                ) {
                    Text(
                        if (currentPage < pages.size - 1) "NEXT" else "GET STARTED",
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

@Composable
fun OnboardingPageContent(page: OnboardingPage) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Icon
        when (page.icon) {
            "shield" -> {
                // Use actual app icon
                val context = LocalContext.current
                val appIcon = remember {
                    context.packageManager.getApplicationIcon(context.applicationInfo)
                }
                Image(
                    bitmap = appIcon.toBitmap(120, 120).asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(80.dp)
                )
            }
            "modes" -> Icon(
                Icons.Default.DarkMode,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = GuardianTheme.IconPrimary
            )
            "nfc" -> Icon(
                Icons.Default.Nfc,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = GuardianTheme.IconPrimary
            )
            "schedule" -> Icon(
                Icons.Default.Schedule,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = GuardianTheme.IconPrimary
            )
            "ready" -> Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = GuardianTheme.IconPrimary
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Title
        Text(
            page.title,
            fontSize = 32.sp,
            fontWeight = FontWeight.Black,
            color = GuardianTheme.TextPrimary,
            letterSpacing = 3.sp,
            textAlign = TextAlign.Center
        )

        // Subtitle
        Text(
            page.subtitle,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = GuardianTheme.TextSecondary,
            letterSpacing = 2.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Description
        Text(
            page.description,
            fontSize = 14.sp,
            color = GuardianTheme.TextPrimary,
            letterSpacing = 0.5.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

data class OnboardingPage(
    val title: String,
    val subtitle: String,
    val description: String,
    val icon: String
)

@Composable
fun WrongTagFeedback() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GuardianTheme.OverlayBackground),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(0.dp),
            color = GuardianTheme.ErrorDark,
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
                    tint = GuardianTheme.TextPrimary,
                    modifier = Modifier.size(64.dp)
                )
                Text(
                    "WRONG TAG",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = GuardianTheme.TextPrimary,
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