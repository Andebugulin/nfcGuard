package com.andebugulin.nfcguard.ui.schedules

import com.andebugulin.nfcguard.R
import androidx.compose.ui.res.stringResource
import com.andebugulin.nfcguard.ui.GuardianTheme
import com.andebugulin.nfcguard.ui.TestTags

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.roundToInt

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
            TextButton(
                onClick = { onConfirm(hour, minute) },
                modifier = Modifier.testTag(TestTags.TimePicker.SET)
            ) {
                Text(stringResource(R.string.sched_set), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.testTag(TestTags.TimePicker.CANCEL),onClick = onDismiss) {
                Text(stringResource(R.string.home_cancel), color = GuardianTheme.TextSecondary, letterSpacing = 1.sp)
            }
        },
        title = {
            Text(
                if (selectingHour) stringResource(R.string.sched_select_hour) else stringResource(R.string.sched_select_minute),
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

/**
 * The clock value at [position] within a face of [size].
 *
 * Rounds to the nearest mark rather than truncating, so each number's
 * catchment is centred on its own label. Truncating put the band between a
 * label and the next one — harmless while the face was drag-only, because the
 * indicator snaps and the user keeps adjusting, but wrong the moment a tap
 * commits in one shot: a press on "3" would have set 2.
 */
private fun valueForPosition(position: Offset, size: IntSize, maxValue: Int): Int {
    val x = position.x - size.width / 2f
    val y = position.y - size.height / 2f

    var angle = atan2(y, x) * 180 / PI + 90
    if (angle < 0) angle += 360

    val slots = maxValue + 1
    return ((angle / 360.0) * slots).roundToInt() % slots
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
            // Tapping a number is what most people try first, so the face
            // accepts a tap as well as a drag. Two pointerInput blocks: a
            // single block runs one detector, which would swallow the gesture.
            .pointerInput(maxValue) {
                detectTapGestures { position ->
                    onValueChange(valueForPosition(position, size, maxValue))
                }
            }
            .pointerInput(maxValue) {
                detectDragGestures { change, _ ->
                    onValueChange(valueForPosition(change.position, size, maxValue))
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(220.dp)
                .clip(CircleShape)
                .background(GuardianTheme.SurfaceDim)
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
