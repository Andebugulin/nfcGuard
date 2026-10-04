package com.andebugulin.nfcguard.ui.schedules

import com.andebugulin.nfcguard.ScheduleClock
import com.andebugulin.nfcguard.DayTime
import com.andebugulin.nfcguard.Mode
import com.andebugulin.nfcguard.Schedule
import com.andebugulin.nfcguard.TimeSlot
import com.andebugulin.nfcguard.ui.GuardianTheme
import com.andebugulin.nfcguard.ui.TestTags

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.foundation.layout.heightIn

/** Default end for a start time: eight hours later, so 09:00 gives 17:00. */
private fun defaultEndFor(start: Pair<Int, Int>): Pair<Int, Int> {
    val end = (start.first * 60 + start.second + 8 * 60) % ScheduleClock.MINUTES_PER_DAY
    return end / 60 to end % 60
}

@Composable
fun ScheduleEditorDialog(
    existingSchedule: Schedule? = null,
    modes: List<Mode>,
    activeModeIds: Set<String> = emptySet(),
    safeRegimeEnabled: Boolean = false,
    existingNames: List<String> = emptyList(),  // FIX #6
    onDismiss: () -> Unit,
    onSave: (String, TimeSlot, List<String>, Boolean) -> Unit
) {
    var name by remember { mutableStateOf(existingSchedule?.name ?: "") }
    var selectedDays by remember { mutableStateOf(existingSchedule?.timeSlot?.days?.toSet() ?: setOf<Int>()) }
    var dayTimes by remember {
        mutableStateOf<MutableMap<Int, Pair<Int, Int>>>(
            if (existingSchedule != null) {
                existingSchedule.timeSlot.dayTimes.associate {
                    it.day to Pair(it.startHour, it.startMinute)
                }.toMutableMap()
            } else {
                mutableMapOf()
            }
        )
    }
    var dayEndTimes by remember {
        mutableStateOf<MutableMap<Int, Pair<Int, Int>>>(
            if (existingSchedule != null) {
                existingSchedule.timeSlot.dayTimes.associate {
                    it.day to Pair(it.endHour, it.endMinute)
                }.toMutableMap()
            } else {
                mutableMapOf()
            }
        )
    }
    var hasEndTime by remember { mutableStateOf(existingSchedule?.hasEndTime ?: false) }
    var selectedModeIds by remember { mutableStateOf(existingSchedule?.linkedModeIds?.toSet() ?: setOf<String>()) }
    var showTimePickerForDay by remember { mutableStateOf<Int?>(null) }
    var showEndTimePickerForDay by remember { mutableStateOf<Int?>(null) }

    // FIX #4: Track end-time validation error
    var endTimeError by remember { mutableStateOf(false) }

    // FIX #6: Duplicate name check
    val nameExists = name.isNotBlank() && existingNames.any { it.equals(name.trim(), ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GuardianTheme.BackgroundSurface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier.border(
            width = GuardianTheme.DialogBorderWidth,
            color = GuardianTheme.DialogBorderEdit,
            shape = RoundedCornerShape(0.dp)
        ),
        confirmButton = {
            TextButton(
                onClick = {
                    // FIX #4: Validate end times before saving
                    if (hasEndTime) {
                        val hasInvalidEndTime = selectedDays.any { day ->
                            val start = dayTimes[day] ?: (9 to 0)
                            val end = dayEndTimes[day] ?: defaultEndFor(start)
                            end == start
                        }
                        if (hasInvalidEndTime) {
                            endTimeError = true
                            return@TextButton
                        }
                    }
                    endTimeError = false

                    if (name.isNotBlank() && selectedDays.isNotEmpty() && selectedModeIds.isNotEmpty() && !nameExists) {
                        val dayTimesList = selectedDays.sorted().map { day ->
                            val (startH, startM) = dayTimes[day] ?: (9 to 0)
                            val (endH, endM) = dayEndTimes[day] ?: defaultEndFor(startH to startM)
                            DayTime(day, startH, startM, endH, endM)
                        }
                        val timeSlot = TimeSlot(dayTimesList)
                        onSave(name.trim(), timeSlot, selectedModeIds.toList(), hasEndTime)
                    }
                },
                enabled = name.isNotBlank() && selectedDays.isNotEmpty() && selectedModeIds.isNotEmpty() && !nameExists,
                modifier = Modifier.testTag(TestTags.Schedules.EDITOR_CONFIRM)
            ) {
                Text(if (existingSchedule != null) "SAVE" else "CREATE", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = GuardianTheme.TextSecondary, letterSpacing = 1.sp)
            }
        },
        title = {
            Text(
                if (existingSchedule != null) "EDIT SCHEDULE" else "NEW SCHEDULE",
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
        },
        text = {
            Box(modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Warning if editing active schedule
                    if (existingSchedule != null && isInScheduleTime(existingSchedule)) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(0.dp),
                                color = GuardianTheme.WarningBackground
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        "NOTICE:",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black,
                                        color = GuardianTheme.Warning,
                                        letterSpacing = 1.sp
                                    )
                                    Text(
                                        "This schedule is within its active time window. Changes will take effect immediately.",
                                        fontSize = 11.sp,
                                        color = GuardianTheme.Warning,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        }
                    }

                    item {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { if (it.length <= 30) name = it },  // FIX #7: Max length
                            placeholder = { Text("SCHEDULE NAME", fontSize = 12.sp, letterSpacing = 1.sp) },
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
                            modifier = Modifier.fillMaxWidth().testTag(TestTags.Schedules.EDITOR_NAME),
                            supportingText = {
                                // FIX #6: Duplicate name feedback
                                if (nameExists) {
                                    Text(
                                        "A schedule with this name already exists",
                                        fontSize = 10.sp,
                                        color = GuardianTheme.Error,
                                        letterSpacing = 0.5.sp
                                    )
                                } else {
                                    Text(
                                        "${name.length}/30",
                                        fontSize = 10.sp,
                                        color = GuardianTheme.TextTertiary,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        )
                    }

                    item {
                        Column {
                            Text("DAYS & TIMES", fontSize = 11.sp, color = GuardianTheme.TextSecondary, letterSpacing = 1.sp)
                            Spacer(Modifier.height(8.dp))
                            (1..7).forEach { day ->
                                Surface(
                                    onClick = {
                                        if (selectedDays.contains(day)) {
                                            selectedDays = selectedDays - day
                                            dayTimes = dayTimes.toMutableMap().apply { remove(day) }
                                            dayEndTimes = dayEndTimes.toMutableMap().apply { remove(day) }
                                        } else {
                                            selectedDays = selectedDays + day
                                            dayTimes = dayTimes.toMutableMap().apply {
                                                this[day] = Pair(9, 0)
                                            }
                                            dayEndTimes = dayEndTimes.toMutableMap().apply {
                                                this[day] = defaultEndFor(9 to 0)
                                            }
                                        }
                                        endTimeError = false  // Reset error on change
                                    },
                                    shape = RoundedCornerShape(0.dp),
                                    color = if (selectedDays.contains(day)) Color.White else Color.Black,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                        .testTag(TestTags.Schedules.day(day))
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                getDayName(day),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (selectedDays.contains(day)) Color.Black else Color.White,
                                                letterSpacing = 1.sp,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (selectedDays.contains(day)) {
                                                TextButton(
                                                    modifier = Modifier.testTag(TestTags.Schedules.startTime(day)),
                                                    onClick = { showTimePickerForDay = day },
                                                    colors = ButtonDefaults.textButtonColors(
                                                        contentColor = GuardianTheme.ButtonPrimaryText
                                                    )
                                                ) {
                                                    val (h, m) = dayTimes[day] ?: (9 to 0)
                                                    Text(
                                                        String.format("%02d:%02d", h, m),
                                                        fontSize = 16.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        letterSpacing = 1.sp
                                                    )
                                                }
                                            }
                                        }

                                        if (selectedDays.contains(day) && hasEndTime) {
                                            Spacer(Modifier.height(4.dp))

                                            // FIX #4: Show per-day end time error
                                            val start = dayTimes[day] ?: (9 to 0)
                                            val (endH, endM) = dayEndTimes[day] ?: defaultEndFor(start)
                                            val endBeforeStart = (endH to endM) == start
                                            val overnight = endH * 60 + endM < start.first * 60 + start.second

                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    if (overnight) "UNTIL (NEXT DAY)" else "UNTIL",
                                                    fontSize = 10.sp,
                                                    color = if (endBeforeStart && endTimeError) GuardianTheme.ErrorTextEmphasized else GuardianTheme.TextTertiary,
                                                    letterSpacing = 1.sp,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                TextButton(
                                                    modifier = Modifier.testTag(TestTags.Schedules.endTime(day)),
                                                    onClick = { showEndTimePickerForDay = day },
                                                    colors = ButtonDefaults.textButtonColors(
                                                        contentColor = if (endBeforeStart && endTimeError) GuardianTheme.ErrorTextEmphasized else GuardianTheme.ButtonPrimaryText
                                                    )
                                                ) {
                                                    Text(
                                                        String.format("%02d:%02d", endH, endM),
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        letterSpacing = 1.sp
                                                    )
                                                }
                                            }

                                            // FIX #4: Inline error
                                            if (endBeforeStart && endTimeError) {
                                                Text(
                                                    "End time must differ from start time",
                                                    fontSize = 9.sp,
                                                    color = GuardianTheme.ErrorTextEmphasized,
                                                    letterSpacing = 0.5.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "CUSTOM END TIMES",
                                fontSize = 11.sp,
                                color = GuardianTheme.TextSecondary,
                                letterSpacing = 1.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                modifier = Modifier.testTag(TestTags.Schedules.EDITOR_CUSTOM_END_TIMES),
                                checked = hasEndTime,
                                onCheckedChange = {
                                    hasEndTime = it
                                    endTimeError = false  // Reset error
                                    if (it) {
                                        dayEndTimes = dayEndTimes.toMutableMap().apply {
                                            selectedDays.forEach { day ->
                                                if (!this.containsKey(day)) {
                                                    this[day] = defaultEndFor(dayTimes[day] ?: (9 to 0))
                                                }
                                            }
                                        }
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = Color.White
                                )
                            )
                        }
                        if (hasEndTime) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Set custom end time for each day above",
                                fontSize = 10.sp,
                                color = GuardianTheme.TextTertiary,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }

                    // FIX #4: Show global end-time error banner
                    if (endTimeError) {
                        item {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(0.dp),
                                color = GuardianTheme.ErrorDark
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Error,
                                        contentDescription = null,
                                        tint = GuardianTheme.ErrorText,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        "Start and end times cannot be the same",
                                        fontSize = 11.sp,
                                        color = GuardianTheme.ErrorText,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Column {
                            Text("LINKED MODES", fontSize = 11.sp, color = GuardianTheme.TextSecondary, letterSpacing = 1.sp)
                            Spacer(Modifier.height(8.dp))

                            // Warning when active modes are selected and safe regime is on
                            val selectedActiveModes = selectedModeIds.filter { activeModeIds.contains(it) }
                            if (safeRegimeEnabled && selectedActiveModes.isNotEmpty()) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                    shape = RoundedCornerShape(0.dp),
                                    color = GuardianTheme.WarningBackground
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Default.Shield, null, tint = GuardianTheme.Warning, modifier = Modifier.size(14.dp))
                                        Text(
                                            "Safe regime: saving will require a 1.5-min challenge",
                                            fontSize = 9.sp,
                                            color = GuardianTheme.Warning,
                                            letterSpacing = 0.3.sp
                                        )
                                    }
                                }
                            }

                            if (modes.isEmpty()) {
                                Text(
                                    "No modes created yet",
                                    fontSize = 10.sp,
                                    color = GuardianTheme.TextTertiary,
                                    letterSpacing = 1.sp
                                )
                            } else {
                                modes.forEach { mode ->
                                    val isActive = activeModeIds.contains(mode.id)
                                    Surface(
                                        onClick = {
                                            selectedModeIds = if (selectedModeIds.contains(mode.id)) {
                                                selectedModeIds - mode.id
                                            } else {
                                                selectedModeIds + mode.id
                                            }
                                        },
                                        shape = RoundedCornerShape(0.dp),
                                        color = if (selectedModeIds.contains(mode.id)) Color.White else Color.Black,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                            .testTag(TestTags.Schedules.linkedMode(mode.id))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    mode.name.uppercase(),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (selectedModeIds.contains(mode.id)) Color.Black else Color.White,
                                                    letterSpacing = 1.sp
                                                )
                                                if (isActive) {
                                                    Text(
                                                        "CURRENTLY ACTIVE",
                                                        fontSize = 8.sp,
                                                        fontWeight = FontWeight.Black,
                                                        color = if (selectedModeIds.contains(mode.id)) GuardianTheme.ButtonDisabledText else GuardianTheme.WarningAccent,
                                                        letterSpacing = 1.sp
                                                    )
                                                }
                                            }
                                            if (selectedModeIds.contains(mode.id)) {
                                                Icon(Icons.Default.Check, null, tint = Color.Black)
                                            }
                                            if (isActive && !selectedModeIds.contains(mode.id)) {
                                                Icon(Icons.Default.Shield, null, tint = GuardianTheme.WarningAccent, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    )

    showTimePickerForDay?.let { day ->
        val (currentH, currentM) = dayTimes[day] ?: (9 to 0)
        ModernTimePickerDialog(
            initialHour = currentH,
            initialMinute = currentM,
            onDismiss = { showTimePickerForDay = null },
            onConfirm = { h, m ->
                dayTimes = dayTimes.toMutableMap().apply {
                    this[day] = Pair(h, m)
                }
                endTimeError = false
                showTimePickerForDay = null
            }
        )
    }

    showEndTimePickerForDay?.let { day ->
        val (currentH, currentM) = dayEndTimes[day] ?: defaultEndFor(dayTimes[day] ?: (9 to 0))
        ModernTimePickerDialog(
            initialHour = currentH,
            initialMinute = currentM,
            onDismiss = { showEndTimePickerForDay = null },
            onConfirm = { h, m ->
                dayEndTimes = dayEndTimes.toMutableMap().apply {
                    this[day] = Pair(h, m)
                }
                endTimeError = false
                showEndTimePickerForDay = null
            }
        )
    }
}
