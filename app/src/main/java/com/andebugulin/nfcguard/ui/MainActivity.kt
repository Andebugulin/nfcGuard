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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.net.Uri
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.saveable.rememberSaveable

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

        // MinimalistTheme is always dark (darkColorScheme, no system-theme branch),
        // so pin the bar styles to dark instead of the default auto(): auto() would
        // pick dark icons on a light-mode device and make them invisible on our
        // dark background.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

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

        // Notification permission (Android 13+) is requested as an explained,
        // optional step inside the permission onboarding flow — not fired
        // blindly at launch, where it lands before the user knows what it's for.

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
    var setupPending by rememberSaveable {
        mutableStateOf(com.andebugulin.nfcguard.ui.onboarding.needsOnboarding(context))
    }
    // Survives rotation: a plain `remember` sent the user back to Home (and,
    // before that, back into onboarding) on every configuration change.
    var currentScreen by rememberSaveable { mutableStateOf(Screen.HOME) }
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

    // Back from a sub-screen returns to Home instead of exiting to the
    // launcher. On Home, Back is left unhandled so the system exits normally.
    BackHandler(enabled = !setupPending && currentScreen != Screen.HOME) {
        currentScreen = Screen.HOME
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (setupPending) {
            val challengeSeconds by viewModel.challengeDurationSeconds.collectAsState()
            com.andebugulin.nfcguard.ui.onboarding.OnboardingFlow(
                onComplete = { setupPending = false },
                startAtPermissions = remember { onlyPermissionsLeft(context) },
                challengeSeconds = challengeSeconds
            )
        } else {
            when (currentScreen) {
                Screen.HOME -> HomeScreen(
                    viewModel = viewModel,
                    onNavigate = { screen -> currentScreen = screen }
                )
                Screen.MODES -> ModesScreen(
                    viewModel = viewModel,
                    onBack = { currentScreen = Screen.HOME },
                    scannedNfcTagId = scannedNfcTagId,
                    nfcRegistrationMode = nfcRegistrationMode
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

/**
 * True when the tour is already done but permission setup never finished —
 * an install upgrading from a build that had the two as separate flows.
 */
private fun onlyPermissionsLeft(context: Context): Boolean =
    context.getSharedPreferences(
        com.andebugulin.nfcguard.ui.onboarding.PREFS_NAME, Context.MODE_PRIVATE
    ).getBoolean(com.andebugulin.nfcguard.ui.onboarding.KEY_SEEN_ONBOARDING, false)

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
