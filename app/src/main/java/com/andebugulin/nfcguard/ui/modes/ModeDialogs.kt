package com.andebugulin.nfcguard.ui.modes

import com.andebugulin.nfcguard.ui.formatDuration
import androidx.compose.ui.res.pluralStringResource
import com.andebugulin.nfcguard.R
import androidx.compose.ui.res.stringResource
import com.andebugulin.nfcguard.Mode
import com.andebugulin.nfcguard.ui.GuardianTheme
import com.andebugulin.nfcguard.ui.TestTags

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ModeNameDialog(
    existingNames: List<String> = emptyList(),  // FIX #6
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }

    // FIX #6: Check for duplicate names
    val nameExists = existingNames.any { it.equals(name.trim(), ignoreCase = true) }

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
                stringResource(R.string.modes_new_mode),
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    modifier = Modifier.testTag(TestTags.Modes.NAME_INPUT),
                    onValueChange = { if (it.length <= 30) name = it },  // FIX #7: Max length
                    placeholder = { Text(stringResource(R.string.modes_mode_name), fontSize = 12.sp, letterSpacing = 1.sp) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = GuardianTheme.InputBackground,
                        unfocusedContainerColor = GuardianTheme.InputBackground,
                        focusedIndicatorColor = GuardianTheme.BorderFocused,
                        unfocusedIndicatorColor = GuardianTheme.BorderSubtle,
                        cursorColor = GuardianTheme.InputCursor,
                        focusedTextColor = GuardianTheme.InputText,
                        unfocusedTextColor = GuardianTheme.InputText
                    ),
                    shape = RoundedCornerShape(0.dp),
                    supportingText = {
                        // FIX #6: Duplicate name feedback
                        if (nameExists && name.isNotBlank()) {
                            Text(
                                stringResource(R.string.modes_a_mode_with_this_name),
                                fontSize = 10.sp,
                                color = GuardianTheme.Error,
                                letterSpacing = 0.5.sp
                            )
                        } else {
                            Text(
                                stringResource(R.string.modes_name_length, name.length, 30),
                                fontSize = 10.sp,
                                color = GuardianTheme.TextTertiary,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank() && !nameExists) onSave(name.trim()) },
                enabled = name.isNotBlank() && !nameExists,  // FIX #6
                modifier = Modifier.testTag(TestTags.Modes.NAME_CONFIRM)
            ) {
                Text(stringResource(R.string.modes_create), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_cancel), color = GuardianTheme.TextSecondary, letterSpacing = 1.sp)
            }
        },
    )
}

@Composable
fun ActivationOptionsDialog(
    mode: Mode,
    hasLinkedSchedules: Boolean,
    onDismiss: () -> Unit,
    onActivate: (timedUntilMillis: Long?) -> Unit
) {
    var selectedOption by remember { mutableStateOf(0) } // 0 = until schedule/tag, 1 = timed
    var timedHours by remember { mutableStateOf("0") }
    var timedMinutes by remember { mutableStateOf("30") }

    // Compute normalized total minutes from hours + minutes input
    val totalMinutes = run {
        val h = timedHours.toLongOrNull() ?: 0L
        val m = timedMinutes.toLongOrNull() ?: 0L
        h * 60 + m
    }
    // Display normalized for user clarity (e.g. 0h 130m → "2H 10M")
    val normalizedH = totalMinutes / 60
    val normalizedM = totalMinutes % 60

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
                stringResource(R.string.modes_activate_named, mode.name.uppercase()),
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                fontSize = 14.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.modes_how_should_this_mode_end),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = GuardianTheme.TextSecondary,
                    letterSpacing = 1.sp
                )

                // Option 1: Until schedule/tag
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(0.dp),
                    color = if (selectedOption == 0) Color.White else GuardianTheme.SurfaceDim,
                    onClick = { selectedOption = 0 }
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            if (hasLinkedSchedules) stringResource(R.string.modes_until_schedule_ends_nfc_tag) else stringResource(R.string.modes_until_nfc_tag),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedOption == 0) Color.Black else Color.White,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (hasLinkedSchedules)
                                stringResource(R.string.modes_mode_will_deactivate_when_a)
                            else
                                stringResource(R.string.modes_mode_stays_active_until_you),
                            fontSize = 10.sp,
                            color = if (selectedOption == 0) GuardianTheme.OnLightSurfaceSecondaryText else GuardianTheme.TextTertiary,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                // Option 2: Timed
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(0.dp),
                    color = if (selectedOption == 1) Color.White else GuardianTheme.SurfaceDim,
                    onClick = { selectedOption = 1 }
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.modes_for_a_set_duration),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedOption == 1) Color.Black else Color.White,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (hasLinkedSchedules)
                                stringResource(R.string.modes_mode_will_stay_active_for)
                            else
                                stringResource(R.string.modes_mode_will_automatically_deactivate_after),
                            fontSize = 10.sp,
                            color = if (selectedOption == 1) GuardianTheme.OnLightSurfaceSecondaryText else GuardianTheme.TextTertiary,
                            letterSpacing = 0.5.sp
                        )

                        if (selectedOption == 1) {
                            Spacer(Modifier.height(12.dp))

                            // Quick presets
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    Triple("0", "15", "15M"),
                                    Triple("0", "30", "30M"),
                                    Triple("1", "0", "1H"),
                                    Triple("2", "0", "2H")
                                ).forEach { (h, m, label) ->
                                    val isSelected = timedHours == h && timedMinutes == m
                                    Surface(
                                        shape = RoundedCornerShape(0.dp),
                                        color = if (isSelected) Color.Black else Color(0xFFEEEEEE),
                                        onClick = { timedHours = h; timedMinutes = m }
                                    ) {
                                        Text(
                                            label,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) Color.White else Color.Black,
                                            letterSpacing = 1.sp,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            // Hours + Minutes inputs
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = timedHours,
                                    onValueChange = { newValue ->
                                        if (newValue.all { it.isDigit() } && newValue.length <= 3) {
                                            timedHours = newValue
                                        }
                                    },
                                    placeholder = { Text("0", fontSize = 11.sp) },
                                    label = { Text(stringResource(R.string.modes_hours), fontSize = 8.sp, letterSpacing = 1.sp) },
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.White,
                                        unfocusedContainerColor = Color(0xFFF0F0F0),
                                        focusedIndicatorColor = Color.Black,
                                        unfocusedIndicatorColor = GuardianTheme.OnLightSurfaceBorder,
                                        cursorColor = Color.Black,
                                        focusedTextColor = Color.Black,
                                        unfocusedTextColor = Color.Black,
                                        focusedLabelColor = GuardianTheme.OnLightSurfaceSecondaryText,
                                        unfocusedLabelColor = GuardianTheme.TextSecondary
                                    ),
                                    shape = RoundedCornerShape(0.dp),
                                    modifier = Modifier.width(72.dp),
                                    singleLine = true
                                )
                                Text(
                                    ":",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = GuardianTheme.OnLightSurfaceSecondaryText
                                )
                                OutlinedTextField(
                                    value = timedMinutes,
                                    onValueChange = { newValue ->
                                        if (newValue.all { it.isDigit() } && newValue.length <= 3) {
                                            timedMinutes = newValue
                                        }
                                    },
                                    placeholder = { Text("30", fontSize = 11.sp) },
                                    label = { Text(stringResource(R.string.modes_minutes), fontSize = 8.sp, letterSpacing = 1.sp) },
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.White,
                                        unfocusedContainerColor = Color(0xFFF0F0F0),
                                        focusedIndicatorColor = Color.Black,
                                        unfocusedIndicatorColor = GuardianTheme.OnLightSurfaceBorder,
                                        cursorColor = Color.Black,
                                        focusedTextColor = Color.Black,
                                        unfocusedTextColor = Color.Black,
                                        focusedLabelColor = GuardianTheme.OnLightSurfaceSecondaryText,
                                        unfocusedLabelColor = GuardianTheme.TextSecondary
                                    ),
                                    shape = RoundedCornerShape(0.dp),
                                    modifier = Modifier.width(80.dp),
                                    singleLine = true
                                )
                            }

                            // Show normalized result if minutes overflow
                            if (totalMinutes > 0 && ((timedMinutes.toLongOrNull() ?: 0) >= 60)) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.modes_equals_duration, formatDuration(totalMinutes.toLong())),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = GuardianTheme.OnLightSurfaceSecondaryText,
                                    letterSpacing = 1.sp
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
                    if (selectedOption == 1) {
                        val deactivateAt = System.currentTimeMillis() + (totalMinutes * 60 * 1000)
                        onActivate(deactivateAt)
                    } else {
                        onActivate(null)
                    }
                },
                enabled = selectedOption == 0 || totalMinutes > 0,
                modifier = Modifier.testTag(TestTags.Modes.ACTIVATE_CONFIRM)
            ) {
                Text(stringResource(R.string.modes_activate), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_cancel), color = GuardianTheme.TextSecondary, letterSpacing = 1.sp)
            }
        },
    )
}

/** Mode info for the unlock dialog */
data class UnlockModeInfo(
    val id: String,
    val name: String,
    val limitMinutes: Long? // null = permanent allowed
)

@Composable
fun UnlockDurationDialog(
    modes: List<UnlockModeInfo>,
    onDismiss: () -> Unit,
    onConfirm: (reactivateAtMillis: Long?, selectedModeIds: Set<String>) -> Unit
) {
    var selectedModeIds by remember { mutableStateOf(modes.map { it.id }.toSet()) }

    // Compute effective limit from selected modes only
    val effectiveLimit: Long? = remember(selectedModeIds) {
        val selectedModes = modes.filter { selectedModeIds.contains(it.id) }
        if (selectedModes.isEmpty()) return@remember null
        val limits = selectedModes.map { it.limitMinutes }
        if (limits.all { it == null }) null // all permanent
        else limits.filterNotNull().minOrNull() // most restrictive
    }

    val initialOption = if (effectiveLimit != null) 1 else 0
    var selectedOption by remember(effectiveLimit) { mutableStateOf(initialOption) }

    val defaultMins = if (effectiveLimit != null) minOf(5L, effectiveLimit) else 5L
    var timedHours by remember { mutableStateOf((defaultMins / 60).toString()) }
    var timedMinutes by remember { mutableStateOf((defaultMins % 60).toString()) }

    val totalMinutes = run {
        val h = timedHours.toLongOrNull() ?: 0L
        val m = timedMinutes.toLongOrNull() ?: 0L
        h * 60 + m
    }

    val cappedMinutes = if (effectiveLimit != null) totalMinutes.coerceAtMost(effectiveLimit) else totalMinutes
    val normalizedH = cappedMinutes / 60
    val normalizedM = cappedMinutes % 60

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
                stringResource(if (modes.size > 1) R.string.modes_unlock_modes else R.string.modes_unlock_mode),
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                fontSize = 14.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Mode selection (only show if more than 1 mode)
                if (modes.size > 1) {
                    Text(
                        stringResource(R.string.modes_select_modes_to_unlock),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = GuardianTheme.TextSecondary,
                        letterSpacing = 1.sp
                    )
                    modes.forEach { mode ->
                        val isSelected = selectedModeIds.contains(mode.id)
                        Surface(
                            modifier = Modifier.fillMaxWidth().testTag(TestTags.Unlock.modeRow(mode.id)),
                            shape = RoundedCornerShape(0.dp),
                            color = if (isSelected) Color.White else Color.Black,
                            onClick = {
                                selectedModeIds = if (isSelected) {
                                    val newSet = selectedModeIds - mode.id
                                    if (newSet.isEmpty()) selectedModeIds // keep at least one
                                    else newSet
                                } else {
                                    selectedModeIds + mode.id
                                }
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (isSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                    contentDescription = null,
                                    tint = if (isSelected) Color.Black else Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    mode.name.uppercase(),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.Black else Color.White,
                                    letterSpacing = 1.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    if (mode.limitMinutes == null) stringResource(R.string.modes_permanent) else stringResource(R.string.modes_limit_max, formatDuration(mode.limitMinutes)),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) GuardianTheme.OnLightSurfaceSecondaryText else GuardianTheme.TextTertiary,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        modes.firstOrNull()?.name?.uppercase() ?: "",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = GuardianTheme.TextSecondary,
                        letterSpacing = 1.sp
                    )
                }

                Text(
                    stringResource(R.string.modes_how_long_should_it_stay),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = GuardianTheme.TextSecondary,
                    letterSpacing = 1.sp
                )

                // Option 1: Permanent unlock (only if no limit on selected modes)
                if (effectiveLimit == null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().testTag(TestTags.Unlock.PERMANENT_OPTION),
                        shape = RoundedCornerShape(0.dp),
                        color = if (selectedOption == 0) Color.White else GuardianTheme.SurfaceDim,
                        onClick = { selectedOption = 0 }
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.modes_permanently),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedOption == 0) Color.Black else Color.White,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.modes_mode_stays_off_until_re),
                                fontSize = 10.sp,
                                color = if (selectedOption == 0) GuardianTheme.OnLightSurfaceSecondaryText else GuardianTheme.TextTertiary,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                } else {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(0.dp),
                        color = GuardianTheme.WarningBackground.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.LockClock,
                                contentDescription = null,
                                tint = GuardianTheme.Warning,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                if (modes.size > 1) stringResource(R.string.modes_permanent_disabled_multi, formatDuration(effectiveLimit ?: 0))
                                else stringResource(R.string.modes_permanent_disabled_single, formatDuration(effectiveLimit ?: 0)),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                color = GuardianTheme.Warning,
                                letterSpacing = 1.sp,
                                lineHeight = 12.sp
                            )
                        }
                    }
                }

                // Option 2: Timed unlock
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(0.dp),
                    color = if (selectedOption == 1) Color.White else GuardianTheme.SurfaceDim,
                    onClick = { selectedOption = 1 }
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.modes_temporary_break),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedOption == 1) Color.Black else Color.White,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (effectiveLimit != null) stringResource(R.string.modes_unlocks_limited_max, formatDuration(effectiveLimit))
                            else stringResource(R.string.modes_mode_will_automatically_re_enable),
                            fontSize = 10.sp,
                            color = if (selectedOption == 1) GuardianTheme.OnLightSurfaceSecondaryText else GuardianTheme.TextTertiary,
                            letterSpacing = 0.5.sp
                        )

                        if (selectedOption == 1) {
                            Spacer(Modifier.height(12.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    Triple("0", "5", "5M"),
                                    Triple("0", "15", "15M"),
                                    Triple("0", "30", "30M"),
                                    Triple("1", "0", "1H")
                                ).forEach { (h, m, label) ->
                                    val presetMins = h.toLong() * 60 + m.toLong()
                                    if (effectiveLimit == null || presetMins <= effectiveLimit) {
                                        val isPresetSelected = timedHours == h && timedMinutes == m
                                        Surface(
                                            shape = RoundedCornerShape(0.dp),
                                            color = if (isPresetSelected) Color.Black else Color(0xFFEEEEEE),
                                            onClick = { timedHours = h; timedMinutes = m }
                                        ) {
                                            Text(
                                                label,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isPresetSelected) Color.White else Color.Black,
                                                letterSpacing = 1.sp,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = timedHours,
                                    onValueChange = { timedHours = it.filter { c -> c.isDigit() }.take(2) },
                                    label = { Text(stringResource(R.string.modes_hours), fontSize = 9.sp, letterSpacing = 1.sp) },
                                    modifier = Modifier.weight(1f).testTag(TestTags.Unlock.HOURS),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Black,
                                        unfocusedBorderColor = GuardianTheme.OnLightSurfaceBorder,
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
                                    label = { Text(stringResource(R.string.modes_minutes), fontSize = 9.sp, letterSpacing = 1.sp) },
                                    modifier = Modifier.weight(1f).testTag(TestTags.Unlock.MINUTES),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Black,
                                        unfocusedBorderColor = GuardianTheme.OnLightSurfaceBorder,
                                        focusedTextColor = Color.Black,
                                        unfocusedTextColor = Color.Black,
                                        focusedLabelColor = Color.Black,
                                        cursorColor = Color.Black
                                    ),
                                    shape = RoundedCornerShape(0.dp)
                                )
                            }

                            if (cappedMinutes > 0) {
                                val capped = effectiveLimit != null && totalMinutes > effectiveLimit
                                Text(
                                    stringResource(
                                        if (capped) R.string.modes_will_reenable_in_capped else R.string.modes_will_reenable_in,
                                        formatDuration(cappedMinutes.toLong())
                                    ),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (capped) GuardianTheme.Warning else GuardianTheme.OnLightSurfaceSecondaryText,
                                    letterSpacing = 1.sp,
                                    modifier = Modifier.padding(top = 4.dp)
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
                    if (selectedOption == 1 && cappedMinutes > 0) {
                        val reactivateAt = System.currentTimeMillis() + (cappedMinutes * 60 * 1000)
                        onConfirm(reactivateAt, selectedModeIds)
                    } else if (selectedOption == 0) {
                        onConfirm(null, selectedModeIds)
                    }
                },
                enabled = selectedModeIds.isNotEmpty() && ((selectedOption == 0 && effectiveLimit == null) || (selectedOption == 1 && cappedMinutes > 0)),
                modifier = Modifier.testTag(TestTags.Unlock.CONFIRM)
            ) {
                Text(stringResource(R.string.modes_unlock), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_cancel), color = GuardianTheme.TextSecondary, letterSpacing = 1.sp)
            }
        },
    )
}
