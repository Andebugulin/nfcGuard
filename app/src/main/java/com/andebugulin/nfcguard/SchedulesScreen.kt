package com.andebugulin.nfcguard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import java.util.Calendar

enum class ScheduleState {
    NONE,        // Not in schedule time OR schedule ended
    ACTIVE,      // Schedule activated modes (currently running)
    DEACTIVATED  // User deactivated during schedule time
}

// Convert Calendar.DAY_OF_WEEK to schedule day format (1=Monday..7=Sunday)
private fun calendarDayToScheduleDay(): Int {
    val calendar = Calendar.getInstance()
    return when (calendar.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> 1
        Calendar.TUESDAY -> 2
        Calendar.WEDNESDAY -> 3
        Calendar.THURSDAY -> 4
        Calendar.FRIDAY -> 5
        Calendar.SATURDAY -> 6
        Calendar.SUNDAY -> 7
        else -> 1
    }
}

// Get current schedule state
private fun isInScheduleTime(schedule: Schedule): Boolean {
    val calendar = Calendar.getInstance()
    val currentDay = calendarDayToScheduleDay()
    val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
    val currentMinute = calendar.get(Calendar.MINUTE)
    val currentTimeInMinutes = currentHour * 60 + currentMinute

    val dayTime = schedule.timeSlot.getTimeForDay(currentDay) ?: return false
    val startTimeInMinutes = dayTime.startHour * 60 + dayTime.startMinute

    return if (schedule.hasEndTime) {
        val endTimeInMinutes = dayTime.endHour * 60 + dayTime.endMinute
        currentTimeInMinutes in startTimeInMinutes..endTimeInMinutes
    } else {
        currentTimeInMinutes >= startTimeInMinutes
    }
}


private fun getScheduleState(schedule: Schedule, appState: AppState): ScheduleState {
    val calendar = Calendar.getInstance()
    val currentDay = calendarDayToScheduleDay()
    val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
    val currentMinute = calendar.get(Calendar.MINUTE)
    val currentTimeInMinutes = currentHour * 60 + currentMinute

    val dayTime = schedule.timeSlot.getTimeForDay(currentDay) ?: return ScheduleState.NONE
    val startTimeInMinutes = dayTime.startHour * 60 + dayTime.startMinute

    // Check if we're in schedule time
    val inScheduleTime = if (schedule.hasEndTime) {
        val endTimeInMinutes = dayTime.endHour * 60 + dayTime.endMinute
        currentTimeInMinutes in startTimeInMinutes..endTimeInMinutes
    } else {
        currentTimeInMinutes >= startTimeInMinutes
    }

    if (!inScheduleTime) {
        return ScheduleState.NONE
    }

    // In schedule time - check if deactivated by user
    if (appState.deactivatedSchedules.contains(schedule.id)) {
        return ScheduleState.DEACTIVATED
    }

    // In schedule time and not deactivated - check if THIS SCHEDULE is active
    // Primary check: activeSchedules flag set by AlarmReceiver
    // Fallback: if linked modes are active, schedule is effectively active
    val inActiveSchedules = appState.activeSchedules.contains(schedule.id)
    val linkedModesActive = schedule.linkedModeIds.isNotEmpty() &&
            schedule.linkedModeIds.any { appState.activeModes.contains(it) }

    return if (inActiveSchedules || linkedModesActive) {
        ScheduleState.ACTIVE
    } else {
        ScheduleState.NONE
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulesScreen(
    viewModel: GuardianViewModel,
    onBack: () -> Unit
) {
    val appState by viewModel.appState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingSchedule by remember { mutableStateOf<Schedule?>(null) }
    var showDeleteDialog by remember { mutableStateOf<Schedule?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(GuardianTheme.BackgroundPrimary)) {
        Column(Modifier.fillMaxSize()) {
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
                    "SCHEDULES",
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                    fontSize = 24.sp,
                    color = GuardianTheme.TextPrimary,
                    modifier = Modifier.weight(1f)
                )
            }

            if (appState.schedules.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "NO SCHEDULES",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = GuardianTheme.TextDisabled,
                            letterSpacing = 2.sp
                        )
                        Spacer(Modifier.height(16.dp))

                        // FIX #12: Disable button if no modes exist, show guidance
                        if (appState.modes.isEmpty()) {
                            Text(
                                "Create at least one mode first",
                                fontSize = 11.sp,
                                color = GuardianTheme.TextTertiary,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(Modifier.height(8.dp))
                        }

                        Button(
                            onClick = { showAddDialog = true },
                            enabled = appState.modes.isNotEmpty(),  // FIX #12
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GuardianTheme.ButtonPrimary,
                                contentColor = GuardianTheme.ButtonPrimaryText,
                                disabledContainerColor = Color(0xFF333333),
                                disabledContentColor = Color(0xFF666666)
                            ),
                            shape = RoundedCornerShape(0.dp),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text(
                                if (appState.modes.isNotEmpty()) "CREATE SCHEDULE" else "CREATE MODES FIRST",
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(appState.schedules.size) { index ->
                        ScheduleCard(
                            schedule = appState.schedules[index],
                            modes = appState.modes,
                            scheduleState = getScheduleState(appState.schedules[index], appState),
                            onEdit = { editingSchedule = appState.schedules[index] },
                            onDelete = { showDeleteDialog = appState.schedules[index] }
                        )
                    }

                    item {
                        // FIX #12: Also disable "+ NEW SCHEDULE" if no modes
                        Button(
                            onClick = { showAddDialog = true },
                            enabled = appState.modes.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GuardianTheme.BackgroundSurface,
                                contentColor = GuardianTheme.ButtonSecondaryText,
                                disabledContainerColor = Color(0xFF1A1A1A),
                                disabledContentColor = Color(0xFF555555)
                            ),
                            shape = RoundedCornerShape(0.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        ) {
                            Text(
                                if (appState.modes.isNotEmpty()) "+ NEW SCHEDULE" else "CREATE MODES FIRST",
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        ScheduleEditorDialog(
            modes = appState.modes,
            existingNames = appState.schedules.map { it.name },  // FIX #6
            onDismiss = { showAddDialog = false },
            onSave = { name, timeSlot, linkedModeIds, hasEndTime ->
                viewModel.addSchedule(name, timeSlot, linkedModeIds, hasEndTime)
                showAddDialog = false
            }
        )
    }

    editingSchedule?.let { schedule ->
        ScheduleEditorDialog(
            existingSchedule = schedule,
            modes = appState.modes,
            existingNames = appState.schedules.filter { it.id != schedule.id }.map { it.name },  // FIX #6
            onDismiss = { editingSchedule = null },
            onSave = { name, timeSlot, linkedModeIds, hasEndTime ->
                viewModel.updateSchedule(schedule.id, name, timeSlot, linkedModeIds, hasEndTime)
                editingSchedule = null
            }
        )
    }

    showDeleteDialog?.let { schedule ->
        val scheduleState = getScheduleState(schedule, appState)
        val scheduleIsActive = scheduleState == ScheduleState.ACTIVE

        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            containerColor = GuardianTheme.ButtonSecondary,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(0.dp),
            modifier = Modifier.border(
                width = GuardianTheme.DialogBorderWidth,
                color = GuardianTheme.DialogBorderDelete,
                shape = RoundedCornerShape(0.dp)
            ),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = GuardianTheme.Error,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        "DELETE SCHEDULE?",
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        color = GuardianTheme.TextPrimary
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(
                        shape = RoundedCornerShape(0.dp),
                        color = GuardianTheme.BackgroundSurface
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                schedule.name.uppercase(),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = GuardianTheme.TextPrimary,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${schedule.linkedModeIds.size} linked mode${if (schedule.linkedModeIds.size != 1) "s" else ""}",
                                fontSize = 11.sp,
                                color = GuardianTheme.TextSecondary,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }

                    if (scheduleIsActive && schedule.linkedModeIds.isNotEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(0.dp),
                            color = GuardianTheme.WarningBackground
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "IMPORTANT:",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    color = GuardianTheme.Warning,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    "Linked modes will stay ACTIVE and switch to manual state",
                                    fontSize = 11.sp,
                                    color = GuardianTheme.Warning,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(0.dp),
                        color = GuardianTheme.ErrorDark
                    ) {
                        Text(
                            "This action cannot be undone",
                            fontSize = 12.sp,
                            color = Color(0xFFFF8888),
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteSchedule(schedule.id)
                        showDeleteDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GuardianTheme.Error,
                        contentColor = GuardianTheme.ButtonSecondaryText
                    ),
                    shape = RoundedCornerShape(0.dp)
                ) {
                    Text(
                        "DELETE",
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = null },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color(0xFF808080)
                    )
                ) {
                    Text("CANCEL", letterSpacing = 1.sp)
                }
            }
        )
    }
}

@Composable
fun ScheduleCard(
    schedule: Schedule,
    modes: List<Mode>,
    scheduleState: ScheduleState,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(0.dp),
        color = GuardianTheme.BackgroundSurface
    ) {
        Column(Modifier.padding(20.dp)) {
            // Title with state indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    schedule.name.uppercase(),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = GuardianTheme.TextPrimary,
                    letterSpacing = 1.sp,
                    modifier = Modifier.weight(1f)
                )

                when (scheduleState) {
                    ScheduleState.ACTIVE -> {
                        Surface(
                            shape = RoundedCornerShape(0.dp),
                            color = GuardianTheme.TextPrimary
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(GuardianTheme.BackgroundPrimary)
                                )
                                Text(
                                    "ACTIVE",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Black,
                                    color = GuardianTheme.BackgroundSurface,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }
                    ScheduleState.DEACTIVATED -> {
                        Surface(
                            shape = RoundedCornerShape(0.dp),
                            color = GuardianTheme.BackgroundSurface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF404040))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF808080))
                                )
                                Text(
                                    "DEACTIVATED",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Black,
                                    color = GuardianTheme.TextSecondary,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }
                    ScheduleState.NONE -> {
                        // No badge
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // Show each day's time
            schedule.timeSlot.dayTimes.sortedBy { it.day }.forEach { dayTime ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        tint = GuardianTheme.IconSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        buildString {
                            append("${getDayName(dayTime.day).take(3)} ")
                            append(String.format("%02d:%02d", dayTime.startHour, dayTime.startMinute))
                            if (schedule.hasEndTime) {
                                append(" - ${String.format("%02d:%02d", dayTime.endHour, dayTime.endMinute)}")
                            }
                        },
                        fontSize = 11.sp,
                        color = GuardianTheme.TextSecondary,
                        letterSpacing = 1.sp
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (schedule.linkedModeIds.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    schedule.linkedModeIds.forEach { modeId ->
                        modes.find { it.id == modeId }?.let { mode ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = GuardianTheme.TextTertiary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    mode.name.uppercase(),
                                    fontSize = 10.sp,
                                    color = GuardianTheme.TextTertiary,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    "NO MODES LINKED",
                    fontSize = 10.sp,
                    color = GuardianTheme.TextDisabled,
                    letterSpacing = 1.sp
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onEdit) {
                    Text("EDIT", fontSize = 11.sp, color = GuardianTheme.TextPrimary, letterSpacing = 1.sp)
                }
                TextButton(onClick = onDelete) {
                    Text("DELETE", fontSize = 11.sp, color = GuardianTheme.TextSecondary, letterSpacing = 1.sp)
                }
            }
        }
    }
}

@Composable
fun ScheduleEditorDialog(
    existingSchedule: Schedule? = null,
    modes: List<Mode>,
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
                            val (startH, startM) = dayTimes[day] ?: (9 to 0)
                            val (endH, endM) = dayEndTimes[day] ?: (23 to 59)
                            (endH * 60 + endM) <= (startH * 60 + startM)
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
                            val (endH, endM) = dayEndTimes[day] ?: (23 to 59)
                            DayTime(day, startH, startM, endH, endM)
                        }
                        val timeSlot = TimeSlot(dayTimesList)
                        onSave(name.trim(), timeSlot, selectedModeIds.toList(), hasEndTime)
                    }
                },
                enabled = name.isNotBlank() && selectedDays.isNotEmpty() && selectedModeIds.isNotEmpty() && !nameExists
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
                            modifier = Modifier.fillMaxWidth(),
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
                                                this[day] = Pair(23, 59)
                                            }
                                        }
                                        endTimeError = false  // Reset error on change
                                    },
                                    shape = RoundedCornerShape(0.dp),
                                    color = if (selectedDays.contains(day)) Color.White else Color.Black,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
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
                                            val (startH, startM) = dayTimes[day] ?: (9 to 0)
                                            val (endH, endM) = dayEndTimes[day] ?: (23 to 59)
                                            val endBeforeStart = (endH * 60 + endM) <= (startH * 60 + startM)

                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    "UNTIL",
                                                    fontSize = 10.sp,
                                                    color = if (endBeforeStart && endTimeError) Color(0xFFFF6666) else GuardianTheme.TextTertiary,
                                                    letterSpacing = 1.sp,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                TextButton(
                                                    onClick = { showEndTimePickerForDay = day },
                                                    colors = ButtonDefaults.textButtonColors(
                                                        contentColor = if (endBeforeStart && endTimeError) Color(0xFFFF6666) else GuardianTheme.ButtonPrimaryText
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
                                                    "End time must be after start time",
                                                    fontSize = 9.sp,
                                                    color = Color(0xFFFF6666),
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
                                checked = hasEndTime,
                                onCheckedChange = {
                                    hasEndTime = it
                                    endTimeError = false  // Reset error
                                    if (it) {
                                        dayEndTimes = dayEndTimes.toMutableMap().apply {
                                            selectedDays.forEach { day ->
                                                if (!this.containsKey(day)) {
                                                    this[day] = Pair(23, 59)
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
                                        tint = Color(0xFFFF8888),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        "Fix end times that are before start times",
                                        fontSize = 11.sp,
                                        color = Color(0xFFFF8888),
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
                            if (modes.isEmpty()) {
                                Text(
                                    "No modes created yet",
                                    fontSize = 10.sp,
                                    color = GuardianTheme.TextTertiary,
                                    letterSpacing = 1.sp
                                )
                            } else {
                                modes.forEach { mode ->
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
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                mode.name.uppercase(),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (selectedModeIds.contains(mode.id)) Color.Black else Color.White,
                                                letterSpacing = 1.sp,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (selectedModeIds.contains(mode.id)) {
                                                Icon(Icons.Default.Check, null, tint = Color.Black)
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
        val (currentH, currentM) = dayEndTimes[day] ?: (23 to 59)
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

@Composable
fun ModernTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    var hour by remember { mutableStateOf(initialHour) }
    var minute by remember { mutableStateOf(initialMinute) }
    var selectingHour by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(hour, minute) }) {
                Text("SET", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = GuardianTheme.TextSecondary, letterSpacing = 1.sp)
            }
        },
        title = {
            Text(
                if (selectingHour) "SELECT HOUR" else "SELECT MINUTE",
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = { selectingHour = true },
                        color = if (selectingHour) Color.White else Color.Black,
                        shape = RoundedCornerShape(0.dp)
                    ) {
                        Text(
                            String.format("%02d", hour),
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selectingHour) Color.Black else Color.White,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    Text(":", fontSize = 48.sp, modifier = Modifier.padding(horizontal = 8.dp))
                    Surface(
                        onClick = { selectingHour = false },
                        color = if (!selectingHour) Color.White else Color.Black,
                        shape = RoundedCornerShape(0.dp)
                    ) {
                        Text(
                            String.format("%02d", minute),
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (!selectingHour) Color.Black else Color.White,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }

                if (selectingHour) {
                    ClockFace(
                        value = hour,
                        maxValue = 23,
                        onValueChange = { hour = it }
                    )
                } else {
                    ClockFace(
                        value = minute,
                        maxValue = 59,
                        onValueChange = { minute = it },
                        displayStep = 5
                    )
                }
            }
        },
        containerColor = GuardianTheme.BackgroundSurface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(0.dp)
    )
}

@Composable
fun ClockFace(
    value: Int,
    maxValue: Int,
    onValueChange: (Int) -> Unit,
    displayStep: Int = 1
) {
    Box(
        modifier = Modifier
            .size(220.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { },
                    onDragEnd = { },
                    onDragCancel = { }
                ) { change, _ ->
                    val centerX = size.width / 2f
                    val centerY = size.height / 2f
                    val x = change.position.x - centerX
                    val y = change.position.y - centerY

                    var angle = atan2(y, x) * 180 / PI + 90
                    if (angle < 0) angle += 360

                    val newValue = ((angle / 360.0) * (maxValue + 1)).toInt() % (maxValue + 1)
                    onValueChange(newValue)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(220.dp)
                .clip(CircleShape)
                .background(Color(0xFF1A1A1A))
        )

        (0..maxValue step displayStep).forEach { num ->
            val angle = (num.toDouble() / (maxValue + 1)) * 360 - 90
            val radius = 90.0
            val x = (cos(angle * PI / 180) * radius).toFloat()
            val y = (sin(angle * PI / 180) * radius).toFloat()

            Box(
                modifier = Modifier.offset(x.dp, y.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    num.toString(),
                    fontSize = 14.sp,
                    color = if (num == value) Color.White else GuardianTheme.TextTertiary,
                    fontWeight = if (num == value) FontWeight.Bold else FontWeight.Normal
                )
            }
        }

        val selectedAngle = (value.toDouble() / (maxValue + 1)) * 360 - 90
        val indicatorRadius = 80.0
        val indicatorX = (cos(selectedAngle * PI / 180) * indicatorRadius).toFloat()
        val indicatorY = (sin(selectedAngle * PI / 180) * indicatorRadius).toFloat()

        Box(
            modifier = Modifier
                .offset(indicatorX.dp, indicatorY.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Text(
                value.toString(),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = GuardianTheme.BackgroundSurface
            )
        }
    }
}

fun getDayName(day: Int): String = when (day) {
    1 -> "MONDAY"
    2 -> "TUESDAY"
    3 -> "WEDNESDAY"
    4 -> "THURSDAY"
    5 -> "FRIDAY"
    6 -> "SATURDAY"
    7 -> "SUNDAY"
    else -> "UNKNOWN"
}