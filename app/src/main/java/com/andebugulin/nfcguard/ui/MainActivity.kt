package com.andebugulin.nfcguard.ui

import com.andebugulin.nfcguard.data.AppLogger
import com.andebugulin.nfcguard.data.AppStateRepository
import com.andebugulin.nfcguard.Schedule
import com.andebugulin.nfcguard.service.ForegroundDetectorService
import com.andebugulin.nfcguard.ui.home.HomeScreen
import com.andebugulin.nfcguard.ui.info.InfoScreen
import com.andebugulin.nfcguard.ui.modes.ModesScreen
import com.andebugulin.nfcguard.ui.modes.UnlockDurationDialog
import com.andebugulin.nfcguard.ui.modes.UnlockModeInfo
import com.andebugulin.nfcguard.ui.nfc.NfcTagsScreen
import com.andebugulin.nfcguard.ui.schedules.SchedulesScreen

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
import androidx.activity.enableEdgeToEdge
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
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.graphics.FilterQuality

enum class Screen {
    HOME, MODES, SCHEDULES, NFC_TAGS, INFO
}

class MainActivity : ComponentActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var scannedNfcTagId = mutableStateOf<String?>(null)
    private var wrongTagScanned = mutableStateOf(false)
    var nfcRegistrationMode = mutableStateOf(false)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize logger first
        AppLogger.init(this)

        enableEdgeToEdge()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )

        setContent {
            MinimalistTheme {
                val viewModel: GuardianViewModel = viewModel()
                MainNavigation(
                    viewModel = viewModel,
                    scannedNfcTagId = scannedNfcTagId,
                    wrongTagScanned = wrongTagScanned,
                    nfcRegistrationMode = nfcRegistrationMode
                )
            }
        }

        // Notification permission (Android 13+) is a runtime permission, not a
        // settings-page intent like the others, so we ask for it directly from
        // the activity instead of going through the Compose onboarding flow.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }

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
                AppLogger.log("NFC", "Tag scanned: $tagId")

                // Check if this is a valid tag for current active modes
                try {
                    val appState = AppStateRepository.getInstance(this).current
                    val activeModes = appState.modes.filter { appState.activeModes.contains(it.id) }
                    val hasNfcLockedMode = activeModes.any { it.nfcTagIds.isNotEmpty() }

                    if (hasNfcLockedMode && !nfcRegistrationMode.value) {
                        val validTag = activeModes.any { it.nfcTagIds.contains(tagId) || it.nfcTagIds.isEmpty() || it.nfcTagIds.contains("ANY") }
                        if (!validTag && appState.activeModes.isNotEmpty()) {
                            // Wrong tag scanned!
                            AppLogger.log("NFC", "WRONG TAG for active modes (tag=$tagId, activeModes=${appState.activeModes})")
                            wrongTagScanned.value = true
                            this@MainActivity.lifecycleScope.launch {
                                kotlinx.coroutines.delay(2000)
                                wrongTagScanned.value = false
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("NFC_SCAN", "Error validating tag: ${e.message}")
                }

                scannedNfcTagId.value = tagId
            }
        }
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
    wrongTagScanned: MutableState<Boolean>,
    nfcRegistrationMode: MutableState<Boolean>
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE) }
    var hasSeenOnboarding by remember {
        mutableStateOf(prefs.getBoolean("has_seen_onboarding", false))
    }
    var currentScreen by remember { mutableStateOf(Screen.HOME) }
    val appState by viewModel.appState.collectAsState()
    val pendingUnlock by viewModel.pendingUnlock.collectAsState()

    // Handle NFC tag scans when modes are active (for unlocking)
    LaunchedEffect(scannedNfcTagId.value, appState.activeModes) {
        val tagId = scannedNfcTagId.value
        if (tagId != null && appState.activeModes.isNotEmpty() && !nfcRegistrationMode.value) {
            android.util.Log.d("MAIN_NAV", "NFC tag scanned with active modes - showing unlock dialog")
            viewModel.handleNfcTag(tagId)
            scannedNfcTagId.value = null
        }
    }

    // Show unlock duration dialog when pending
    pendingUnlock?.let { pending ->
        val unlockModes = pending.modeIds.mapNotNull { id ->
            val mode = appState.modes.find { it.id == id }
            if (mode != null) UnlockModeInfo(id, mode.name, pending.modeLimits[id])
            else null
        }
        UnlockDurationDialog(
            modes = unlockModes,
            onDismiss = { viewModel.dismissUnlock() },
            onConfirm = { reactivateAtMillis, selectedModeIds ->
                viewModel.confirmUnlock(reactivateAtMillis, selectedModeIds)
            }
        )
    }

    // Show the permission-setup flow after onboarding completes (or on
    // subsequent launches if it was never finished). The `remember` key is
    // `hasSeenOnboarding` so completing OnboardingScreen re-evaluates and
    // triggers the dialog flow without manual postDelayed plumbing.
    var showPermissionOnboarding by remember(hasSeenOnboarding) {
        mutableStateOf(com.andebugulin.nfcguard.ui.onboarding.shouldShowOnboarding(context))
    }
    if (showPermissionOnboarding) {
        com.andebugulin.nfcguard.ui.onboarding.PermissionOnboarding(
            onDone = { showPermissionOnboarding = false }
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (!hasSeenOnboarding) {
            OnboardingScreen(
                onComplete = {
                    prefs.edit().putBoolean("has_seen_onboarding", true).apply()
                    hasSeenOnboarding = true
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
                    nfcRegistrationMode = nfcRegistrationMode,
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
            description = "Create blocking modes for different contexts:\n\n- BLOCK MODE - Block specific distracting apps\n- ALLOW MODE - Block everything except essential apps",
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
            description = "Set modes to activate automatically:\n\n- Weekday work hours (9am-5pm)\n- Sleep schedule (10pm-7am)\n- Weekend deep work sessions",
            icon = "schedule"
        ),
        OnboardingPage(
            title = "READY",
            subtitle = "LET'S GET STARTED",
            description = "Guardian needs a few permissions to work:\n\n- Usage access - Detect which apps you use\n- Display over apps - Show block screen\n- Battery optimization - Run reliably\n\nLet's set them up now.",
            icon = "ready"
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GuardianTheme.BackgroundPrimary)
            .windowInsetsPadding(WindowInsets.systemBars)
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
                // AFTER
                Image(
                    bitmap = appIcon.toBitmap(512, 512).asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    filterQuality = FilterQuality.High
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
