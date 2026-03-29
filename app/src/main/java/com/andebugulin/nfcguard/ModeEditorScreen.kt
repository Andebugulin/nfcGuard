package com.andebugulin.nfcguard

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    "com.andebugulin.nfcguard",  // Guardian itself (hardcoded)
    // Lock screen / Security apps
    "com.android.settings.lockscreen",
    "com.android.security",
    "com.miui.securitycenter",
    "com.samsung.android.lool",
    "com.coloros.lockscreen"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeEditorScreen(
    mode: Mode,
    availableNfcTags: List<NfcTag>,
    allModes: List<Mode> = emptyList(),  // FIX #8: Pass all modes for NFC usage count
    onBack: () -> Unit,
    onSave: (List<String>, BlockMode, List<String>, Map<String, Long?>) -> Unit
) {
    val context = LocalContext.current
    var selectedApps by remember { mutableStateOf(mode.blockedApps.toSet()) }
    var blockMode by remember { mutableStateOf(mode.blockMode) }
    var selectedNfcTagIds by remember { mutableStateOf(mode.effectiveNfcTagIds.toSet()) }
    var tagUnlockLimits by remember { mutableStateOf(mode.tagUnlockLimits) }
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }

    var tagToConfigureLimit by remember { mutableStateOf<String?>(null) } // tagId or "ANY"
    var showPermanentUnlockWarning by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val apps = loadInstalledApps(context).sortedBy { it.appName }
            withContext(Dispatchers.Main) {
                installedApps = apps
                isLoading = false
            }
        }
    }

    // Ensure that if no tags are selected, the wildcard "ANY" (Any other tag) is selected automatically
    LaunchedEffect(selectedNfcTagIds) {
        if (selectedNfcTagIds.isEmpty()) {
            selectedNfcTagIds = setOf("ANY")
        }
    }

    val filteredApps = remember(installedApps, searchQuery) {
        if (searchQuery.isEmpty()) installedApps
        else installedApps.filter { it.appName.contains(searchQuery, ignoreCase = true) }
    }

    // FIX #8: Count how many OTHER modes use each NFC tag (excluding the current mode being edited)
    val nfcTagUsageCounts = remember(allModes, mode.id) {
        val counts = mutableMapOf<String, Int>()
        allModes.filter { it.id != mode.id }.forEach { m ->
            m.effectiveNfcTagIds.forEach { tagId ->
                counts[tagId] = (counts[tagId] ?: 0) + 1
            }
        }
        counts
    }

    // Check if there's any way to permanently unlock (any selected tag has null limit)
    val hasPermanentUnlockWay = selectedNfcTagIds.any { tagId -> tagUnlockLimits[tagId] == null }

    Box(modifier = Modifier.fillMaxSize().background(GuardianTheme.BackgroundPrimary).windowInsetsPadding(WindowInsets.systemBars)) {
        Column(Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, null, tint = GuardianTheme.IconPrimary)
                }
                Text(
                    mode.name.uppercase(),
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                    color = GuardianTheme.TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                // FIX #5: Disable SAVE when no apps selected
                Button(
                    onClick = {
                        if (!hasPermanentUnlockWay) {
                            showPermanentUnlockWarning = true
                        } else {
                            onSave(selectedApps.toList(), blockMode, selectedNfcTagIds.toList(), tagUnlockLimits)
                        }
                    },
                    enabled = selectedApps.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GuardianTheme.ButtonPrimary,
                        contentColor = GuardianTheme.ButtonPrimaryText,
                        disabledContainerColor = Color(0xFF333333),
                        disabledContentColor = Color(0xFF666666)
                    ),
                    shape = RoundedCornerShape(0.dp)
                ) {
                    Text("SAVE", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }

            // FIX #5: Show hint when no apps selected
            if (selectedApps.isEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    shape = RoundedCornerShape(0.dp),
                    color = GuardianTheme.WarningBackground
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = GuardianTheme.Warning,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            "Select at least one app to save this mode",
                            fontSize = 11.sp,
                            color = GuardianTheme.Warning,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // Block mode selector
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { blockMode = BlockMode.BLOCK_SELECTED },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (blockMode == BlockMode.BLOCK_SELECTED) Color.White else GuardianTheme.BackgroundSurface,
                        contentColor = if (blockMode == BlockMode.BLOCK_SELECTED) Color.Black else Color.White
                    ),
                    shape = RoundedCornerShape(0.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("BLOCK", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
                Button(
                    onClick = { blockMode = BlockMode.ALLOW_SELECTED },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (blockMode == BlockMode.ALLOW_SELECTED) Color.White else GuardianTheme.BackgroundSurface,
                        contentColor = if (blockMode == BlockMode.ALLOW_SELECTED) Color.Black else Color.White
                    ),
                    shape = RoundedCornerShape(0.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("ALLOW ONLY", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }

            Spacer(Modifier.height(16.dp))

            // NFC Tag Selector
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                shape = RoundedCornerShape(0.dp),
                color = GuardianTheme.BackgroundSurface
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Nfc,
                            contentDescription = null,
                            tint = GuardianTheme.IconPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            "NFC TAG LOCK",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = GuardianTheme.TextPrimary,
                            letterSpacing = 1.sp
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Optional: Require specific NFC tag(s) to unlock",
                        fontSize = 10.sp,
                        color = GuardianTheme.TextSecondary,
                        letterSpacing = 0.5.sp
                    )

                    Spacer(Modifier.height(12.dp))

                    // Constrain the height of the tag list to prevent it from pushing app list off screen
                    Column(
                        modifier = Modifier
                            .heightIn(max = 160.dp) // Slightly smaller to be more compact
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // "Any other tag" option (ID is now "ANY")
                        val isAnySelected = selectedNfcTagIds.contains("ANY")
                        TagLimitItem(
                            name = "ANY OTHER NFC TAG",
                            isSelected = isAnySelected,
                            limitMinutes = tagUnlockLimits["ANY"],
                            onToggle = {
                                selectedNfcTagIds = if (isAnySelected) {
                                    selectedNfcTagIds - "ANY"
                                } else {
                                    selectedNfcTagIds + "ANY"
                                }
                            },
                            onConfigureLimit = { tagToConfigureLimit = "ANY" }
                        )

                        // Available tags — multi-select
                        availableNfcTags.forEach { tag ->
                            val usageCount = nfcTagUsageCounts[tag.id] ?: 0  // FIX #8
                            val isSelected = selectedNfcTagIds.contains(tag.id)

                            TagLimitItem(
                                name = tag.name.uppercase(),
                                isSelected = isSelected,
                                limitMinutes = tagUnlockLimits[tag.id],
                                usageCount = usageCount,
                                onToggle = {
                                    selectedNfcTagIds = if (isSelected) {
                                        selectedNfcTagIds - tag.id
                                    } else {
                                        selectedNfcTagIds + tag.id
                                    }
                                },
                                onConfigureLimit = { tagToConfigureLimit = tag.id }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // App search
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("SEARCH APPS...", fontSize = 12.sp, letterSpacing = 1.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = GuardianTheme.BackgroundSurface,
                    unfocusedContainerColor = GuardianTheme.BackgroundSurface,
                    focusedIndicatorColor = GuardianTheme.BorderFocused,
                    unfocusedIndicatorColor = GuardianTheme.BorderSubtle,
                    cursorColor = GuardianTheme.InputCursor,
                    focusedTextColor = GuardianTheme.InputText,
                    unfocusedTextColor = GuardianTheme.InputText
                ),
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            )

            Spacer(Modifier.height(16.dp))

            // Apps list
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("LOADING...", color = GuardianTheme.TextDisabled, letterSpacing = 2.sp)
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Selected apps section at top
                    if (selectedApps.isNotEmpty()) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "SELECTED (${selectedApps.size})",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = GuardianTheme.TextSecondary,
                                    letterSpacing = 1.sp,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )

                                // Show selected apps
                                selectedApps.forEach { packageName ->
                                    val app = filteredApps.find { it.packageName == packageName }
                                    if (app != null) {
                                        AppItem(
                                            app = app,
                                            isSelected = true,
                                            onToggle = {
                                                selectedApps = selectedApps - packageName
                                            }
                                        )
                                    }
                                }

                                // Separator
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    color = GuardianTheme.BackgroundSurface,
                                    shape = RoundedCornerShape(0.dp)
                                ) {
                                    Text(
                                        "ALL APPS",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = GuardianTheme.TextTertiary,
                                        letterSpacing = 1.sp,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                    )
                                }
                            }
                        }
                    }

                    // All apps (in original order, with selection indicator)
                    items(filteredApps.size, key = { filteredApps[it].packageName }) { index ->
                        AppItem(
                            app = filteredApps[index],
                            isSelected = selectedApps.contains(filteredApps[index].packageName),
                            onToggle = {
                                val pkg = filteredApps[index].packageName
                                selectedApps = if (selectedApps.contains(pkg)) {
                                    selectedApps - pkg
                                } else {
                                    selectedApps + pkg
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // Tag limit configuration dialog
    tagToConfigureLimit?.let { tagId ->
        TagLimitConfigDialog(
            currentLimit = tagUnlockLimits[tagId],
            onDismiss = { tagToConfigureLimit = null },
            onConfirm = { limit ->
                tagUnlockLimits = tagUnlockLimits + (tagId to limit)
                tagToConfigureLimit = null
            }
        )
    }

    if (showPermanentUnlockWarning) {
        AlertDialog(
            onDismissRequest = { showPermanentUnlockWarning = false },
            containerColor = GuardianTheme.BackgroundSurface,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(0.dp),
            modifier = Modifier.border(
                width = GuardianTheme.DialogBorderWidth,
                color = GuardianTheme.DialogBorderWarning,
                shape = RoundedCornerShape(0.dp)
            ),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Warning, null, tint = GuardianTheme.Warning)
                    Text(
                        "NO PERMANENT UNLOCK",
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        fontSize = 14.sp
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(
                        color = GuardianTheme.WarningBackground,
                        shape = RoundedCornerShape(0.dp)
                    ) {
                        Text(
                            "DANGER: All selected NFC tags have a time limit. Once this mode activates, you will NOT be able to turn it off permanently until the schedule ends.",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = GuardianTheme.Warning,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Text(
                        "You will only be able to take temporary breaks. Are you sure you want to save this 'inescapable' mode?",
                        fontSize = 11.sp,
                        color = GuardianTheme.TextSecondary,
                        letterSpacing = 0.5.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPermanentUnlockWarning = false
                        onSave(selectedApps.toList(), blockMode, selectedNfcTagIds.toList(), tagUnlockLimits)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GuardianTheme.Warning,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(0.dp)
                ) {
                    Text("SAVE ANYWAY", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermanentUnlockWarning = false }) {
                    Text("CANCEL", color = GuardianTheme.TextSecondary, letterSpacing = 1.sp)
                }
            },
        )
    }
}

@Composable
fun TagLimitItem(
    name: String,
    isSelected: Boolean,
    limitMinutes: Long?,
    usageCount: Int = 0,
    onToggle: () -> Unit,
    onConfigureLimit: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(0.dp),
        color = if (isSelected) Color.White else Color.Black,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left part: Check + Name (Toggles selection)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onToggle() }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (isSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = if (isSelected) Color.Black else Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        name,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) Color.Black else Color.White,
                        letterSpacing = 1.sp
                    )
                    if (usageCount > 0) {
                        Text(
                            "USED BY $usageCount OTHER MODE${if (usageCount > 1) "S" else ""}",
                            fontSize = 9.sp,
                            color = if (isSelected) Color(0xFFE65100) else Color(0xFFFF9800),
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            // Right part: Duration (Configure max limit)
            Surface(
                onClick = onConfigureLimit,
                color = Color.Black,
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier.border(1.dp, Color.White, RoundedCornerShape(0.dp))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Default.Timer,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = Color.White
                    )
                    Text(
                        if (limitMinutes == null) "PERMANENT" else "${limitMinutes / 60}H ${limitMinutes % 60}M",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

@Composable
fun TagLimitConfigDialog(
    currentLimit: Long?,
    onDismiss: () -> Unit,
    onConfirm: (Long?) -> Unit
) {
    var selectedOption by remember { mutableIntStateOf(if (currentLimit == null) 0 else 1) } // 0 = permanent, 1 = timed
    var timedHours by remember { mutableStateOf(currentLimit?.let { (it / 60).toString() } ?: "0") }
    var timedMinutes by remember { mutableStateOf(currentLimit?.let { (it % 60).toString() } ?: "15") }

    val totalMinutes = run {
        val h = timedHours.toLongOrNull() ?: 0L
        val m = timedMinutes.toLongOrNull() ?: 0L
        h * 60 + m
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GuardianTheme.BackgroundSurface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier.border(
            width = GuardianTheme.DialogBorderWidth,
            color = GuardianTheme.DialogBorderInfo,
            shape = RoundedCornerShape(0.dp)
        ),
        title = {
            Text(
                "MAX UNLOCK DURATION",
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                fontSize = 14.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Set the maximum time this tag can unlock this mode for. Leave permanent for no restriction.",
                    fontSize = 10.sp,
                    color = GuardianTheme.TextSecondary,
                    letterSpacing = 0.5.sp
                )

                // Option 1: Permanent
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(0.dp),
                    color = if (selectedOption == 0) Color.White else Color(0xFF1A1A1A),
                    onClick = { selectedOption = 0 }
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "PERMANENT UNLOCK",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedOption == 0) Color.Black else Color.White,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "This tag can unlock this mode indefinitely",
                            fontSize = 10.sp,
                            color = if (selectedOption == 0) Color(0xFF555555) else GuardianTheme.TextTertiary,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                // Option 2: Timed limit
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(0.dp),
                    color = if (selectedOption == 1) Color.White else Color(0xFF1A1A1A),
                    onClick = { selectedOption = 1 }
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "MAX DURATION LIMIT",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedOption == 1) Color.Black else Color.White,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "This tag will only offer unlock durations up to this limit",
                            fontSize = 10.sp,
                            color = if (selectedOption == 1) Color(0xFF555555) else GuardianTheme.TextTertiary,
                            letterSpacing = 0.5.sp
                        )

                        if (selectedOption == 1) {
                            Spacer(Modifier.height(12.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = timedHours,
                                    onValueChange = { timedHours = it.filter { c -> c.isDigit() }.take(2) },
                                    label = { Text("HOURS", fontSize = 9.sp, letterSpacing = 1.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Black,
                                        unfocusedBorderColor = Color(0xFFCCCCCC),
                                        focusedTextColor = Color.Black,
                                        unfocusedTextColor = Color.Black,
                                        focusedLabelColor = Color.Black,
                                        cursorColor = Color.Black
                                    ),
                                    shape = RoundedCornerShape(0.dp)
                                )
                                OutlinedTextField(
                                    value = timedMinutes,
                                    onValueChange = { timedMinutes = it.filter { c -> c.isDigit() }.take(3) },
                                    label = { Text("MINUTES", fontSize = 9.sp, letterSpacing = 1.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Black,
                                        unfocusedBorderColor = Color(0xFFCCCCCC),
                                        focusedTextColor = Color.Black,
                                        unfocusedTextColor = Color.Black,
                                        focusedLabelColor = Color.Black,
                                        cursorColor = Color.Black
                                    ),
                                    shape = RoundedCornerShape(0.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(if (selectedOption == 0) null else totalMinutes)
                },
                enabled = selectedOption == 0 || totalMinutes > 0
            ) {
                Text("APPLY", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = GuardianTheme.TextSecondary, letterSpacing = 1.sp)
            }
        },
    )
}

@Composable
fun AppItem(app: AppInfo, isSelected: Boolean, onToggle: () -> Unit) {
    val imageBitmap = remember(app.packageName) { app.icon.asImageBitmap() }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(0.dp),
        color = if (isSelected) Color.White else GuardianTheme.BackgroundSurface,
        onClick = onToggle
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(0.dp))
            )
            Spacer(Modifier.width(16.dp))
            Text(
                app.appName.uppercase(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) Color.Black else Color.White,
                letterSpacing = 1.sp,
                modifier = Modifier.weight(1f)
            )
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.Black
                )
            }
        }
    }
}

data class AppInfo(
    val appName: String,
    val packageName: String,
    val icon: Bitmap
)

fun loadInstalledApps(context: Context): List<AppInfo> {
    return try {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.content.pm.PackageManager.MATCH_ALL
        } else {
            android.content.pm.PackageManager.GET_META_DATA
        }

        val resolveInfos = pm.queryIntentActivities(intent, flags)

        val apps = resolveInfos.mapNotNull { resolveInfo ->
            try {
                val packageName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null

                // Filter out critical system apps - they should never be selectable
                if (CRITICAL_SYSTEM_APPS.contains(packageName)) {
                    return@mapNotNull null
                }

                val appName = resolveInfo.loadLabel(pm)?.toString() ?: packageName
                val drawable = resolveInfo.loadIcon(pm)
                val bitmap = drawable.toBitmap(96, 96)
                AppInfo(appName, packageName, bitmap)
            } catch (e: Exception) {
                null
            }
        }.distinctBy { it.packageName }

        apps
    } catch (e: Exception) {
        emptyList()
    }
}

fun Drawable.toBitmap(width: Int = intrinsicWidth, height: Int = intrinsicHeight): Bitmap {
    if (this is BitmapDrawable && bitmap != null) {
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
    val w = if (width > 0) width else 1
    val h = if (height > 0) height else 1
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, w, h)
    draw(canvas)
    return bitmap
}