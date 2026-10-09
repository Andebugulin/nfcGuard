package com.andebugulin.nfcguard.ui.onboarding

import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.andebugulin.nfcguard.R
import com.andebugulin.nfcguard.ui.GuardianTheme
import com.andebugulin.nfcguard.ui.components.FitText
import com.andebugulin.nfcguard.ui.components.GuardianType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The kinds of tag shown, in the order the demo cycles through them. */
private enum class TagKind(@StringRes val label: Int) {
    Sticker(R.string.onb_tag_sticker),
    KeyFob(R.string.onb_tag_key_fob),
    Card(R.string.onb_tag_card)
}

private val PHONE_WIDTH = 76.dp
private val PHONE_HEIGHT = 140.dp
private val SCENE_HEIGHT = 250.dp

/** Where the tag lies, and where the phone comes to rest on it. */
private val CONTACT_Y = 16.dp

private val TagFill = Color(0xFF1C1C1C)
private val TagLine = Color(0xFF9A9A9A)
private val PhoneBody = Color(0xFF151515)
private val PhoneFrame = Color(0xFF8A8A8A)
private val LockedScreen = Color(0xFF1F0505)

/**
 * The tap itself, as it really looks.
 *
 * The earlier version slid an NFC glyph into an outlined rectangle, and people
 * who had never used NFC did not read it as a phone touching anything. Most
 * people do not know what an NFC tag *is*, so this shows the actual objects (a
 * sticker, a key fob, a card) lying flat, and a phone coming down onto one with
 * its back, the way you really hold it. The phone's screen shows a blocked app
 * until the tag is read, then unlocks.
 *
 * Seen from above: lifted, the phone is a little bigger and tilted; on the tag
 * it lies flat and covers it, and the read radiates out from under it.
 */
@Composable
fun NfcPreview(modifier: Modifier = Modifier) {
    val lift = remember { Animatable(1f) }
    val ripple = remember { Animatable(0f) }
    var unlocked by remember { mutableStateOf(false) }
    var tagIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(700)
            lift.animateTo(0f, tween(950, easing = FastOutSlowInEasing))
            launch {
                ripple.snapTo(0f)
                ripple.animateTo(1f, tween(1100, easing = LinearEasing))
            }
            delay(350)
            unlocked = true
            delay(1400)
            lift.animateTo(1f, tween(750, easing = FastOutSlowInEasing))
            delay(900)
            // Next tag, and the phone is locked again: every kind works the same.
            tagIndex = (tagIndex + 1) % TagKind.entries.size
            unlocked = false
        }
    }

    val tag = TagKind.entries[tagIndex]

    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(SCENE_HEIGHT),
            contentAlignment = Alignment.Center
        ) {
            Crossfade(targetState = tag, animationSpec = tween(400), label = "tag") { kind ->
                Box(Modifier.graphicsLayer { translationY = CONTACT_Y.toPx() }) {
                    TagDrawing(kind)
                }
            }

            ReadWaves(progress = ripple.value, visible = ripple.value in 0.001f..0.999f)

            Phone(
                unlocked = unlocked,
                modifier = Modifier.graphicsLayer {
                    val l = lift.value
                    translationX = lerp(0f, 64.dp.toPx(), l)
                    translationY = lerp(CONTACT_Y.toPx(), (-46).dp.toPx(), l)
                    rotationZ = lerp(0f, -14f, l)
                    val s = lerp(0.97f, 1.07f, l)
                    scaleX = s
                    scaleY = s
                }
            )
        }

        Spacer(Modifier.height(12.dp))
        TagKinds(current = tag)
        Spacer(Modifier.height(16.dp))

        Text(
            stringResource(R.string.onb_hold_back_of_phone),
            style = GuardianType.Label,
            color = if (unlocked) GuardianTheme.Success else GuardianTheme.TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.onb_any_nfc_tag_works),
            style = GuardianType.EmptyHint,
            color = GuardianTheme.TextTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** The three kinds as a row of labels, the one on screen lit. */
@Composable
private fun TagKinds(current: TagKind) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TagKind.entries.forEach { kind ->
            val active = kind == current
            val color by animateColorAsState(
                if (active) GuardianTheme.TextPrimary else GuardianTheme.TextDisabled,
                animationSpec = tween(300),
                label = "kind"
            )
            Box(
                Modifier
                    .weight(1f, fill = false)
                    .border(1.dp, color, RoundedCornerShape(0.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                FitText(stringResource(kind.label), style = GuardianType.Meta, color = color, maxLines = 1)
            }
        }
    }
}

/** A phone seen from the front, showing either the block screen or the unlock. */
@Composable
private fun Phone(unlocked: Boolean, modifier: Modifier = Modifier) {
    val screen by animateColorAsState(
        if (unlocked) GuardianTheme.SuccessBackground else LockedScreen,
        animationSpec = tween(250),
        label = "screen"
    )
    Box(
        modifier
            .size(PHONE_WIDTH, PHONE_HEIGHT)
            .background(PhoneBody, RoundedCornerShape(14.dp))
            .border(2.dp, PhoneFrame, RoundedCornerShape(14.dp))
            .padding(5.dp)
            .background(screen, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        // Camera hole, so it reads as a phone and not as a card.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 6.dp)
                .size(5.dp)
                .background(PhoneFrame, CircleShape)
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Icon(
                if (unlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                contentDescription = null,
                tint = if (unlocked) GuardianTheme.Success else GuardianTheme.ErrorText,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.height(8.dp))
            FitText(
                stringResource(if (unlocked) R.string.onb_unlocked else R.string.overlay_blocked),
                style = GuardianType.Meta,
                color = if (unlocked) GuardianTheme.Success else GuardianTheme.ErrorText,
                maxLines = 1
            )
        }
    }
}

/** Rings spreading out from under the phone while the tag is read. */
@Composable
private fun ReadWaves(progress: Float, visible: Boolean) {
    if (!visible) return
    val contactY = with(LocalDensity.current) { CONTACT_Y.toPx() }
    Canvas(Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2, size.height / 2 + contactY)
        repeat(3) { ring ->
            val t = ((progress - ring * 0.18f) / 0.64f).coerceIn(0f, 1f)
            if (t <= 0f || t >= 1f) return@repeat
            drawCircle(
                color = GuardianTheme.Success.copy(alpha = (1f - t) * 0.7f),
                radius = lerp(PHONE_WIDTH.toPx() * 0.6f, PHONE_HEIGHT.toPx() * 0.85f, t),
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

@Composable
private fun TagDrawing(kind: TagKind) {
    when (kind) {
        TagKind.Sticker -> TagCanvas(60.dp, 60.dp) { drawSticker() }
        TagKind.KeyFob -> TagCanvas(56.dp, 92.dp) { drawKeyFob() }
        TagKind.Card -> TagCanvas(132.dp, 84.dp) { drawCard() }
    }
}

@Composable
private fun TagCanvas(width: Dp, height: Dp, draw: DrawScope.() -> Unit) {
    Canvas(Modifier.size(width, height), onDraw = draw)
}

/** A round sticker with its antenna coil showing through. */
private fun DrawScope.drawSticker() {
    val c = center
    val r = size.minDimension / 2
    drawCircle(TagFill, r, c)
    drawCircle(TagLine, r - 1.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
    listOf(0.72f, 0.58f, 0.44f).forEach { f ->
        drawCircle(TagLine.copy(alpha = 0.6f), r * f, c, style = Stroke(1.dp.toPx()))
    }
    val chip = 7.dp.toPx()
    drawRect(TagLine, Offset(c.x - chip / 2, c.y - chip / 2), Size(chip, chip))
}

/** A teardrop key fob on its ring. */
private fun DrawScope.drawKeyFob() {
    val ringR = 11.dp.toPx()
    val bodyTop = ringR * 1.6f
    val bodyW = size.width
    val bodyH = size.height - bodyTop
    // Key ring.
    drawCircle(TagLine, ringR, Offset(size.width / 2, ringR + 1.dp.toPx()), style = Stroke(2.dp.toPx()))
    // Body.
    drawRoundRect(
        TagFill,
        topLeft = Offset(0f, bodyTop),
        size = Size(bodyW, bodyH),
        cornerRadius = CornerRadius(bodyW / 2, bodyW / 2)
    )
    drawRoundRect(
        TagLine,
        topLeft = Offset(0f, bodyTop),
        size = Size(bodyW, bodyH),
        cornerRadius = CornerRadius(bodyW / 2, bodyW / 2),
        style = Stroke(1.5.dp.toPx())
    )
    // Hole the ring goes through.
    val hole = Offset(size.width / 2, bodyTop + 9.dp.toPx())
    drawCircle(Color.Black, 4.dp.toPx(), hole)
    drawCircle(TagLine, 4.dp.toPx(), hole, style = Stroke(1.dp.toPx()))
    // Coil.
    val coilCenter = Offset(size.width / 2, bodyTop + bodyH * 0.6f)
    listOf(0.36f, 0.26f).forEach { f ->
        drawCircle(TagLine.copy(alpha = 0.6f), bodyW * f, coilCenter, style = Stroke(1.dp.toPx()))
    }
}

/** A plastic card: chip on the left, the contactless mark on the right. */
private fun DrawScope.drawCard() {
    val corner = CornerRadius(8.dp.toPx(), 8.dp.toPx())
    drawRoundRect(TagFill, cornerRadius = corner)
    drawRoundRect(TagLine, cornerRadius = corner, style = Stroke(1.5.dp.toPx()))

    val chipSize = Size(22.dp.toPx(), 17.dp.toPx())
    val chipAt = Offset(14.dp.toPx(), size.height / 2 - chipSize.height / 2)
    drawRoundRect(TagLine, chipAt, chipSize, CornerRadius(3.dp.toPx(), 3.dp.toPx()))
    val mid = chipAt.y + chipSize.height / 2
    drawLine(TagFill, Offset(chipAt.x, mid), Offset(chipAt.x + chipSize.width, mid), 1.dp.toPx())
    drawLine(
        TagFill,
        Offset(chipAt.x + chipSize.width / 2, chipAt.y),
        Offset(chipAt.x + chipSize.width / 2, chipAt.y + chipSize.height),
        1.dp.toPx()
    )

    val waveCenter = Offset(size.width - 30.dp.toPx(), size.height / 2)
    listOf(6.dp, 11.dp, 16.dp).forEach { r ->
        val radius = r.toPx()
        drawArc(
            TagLine,
            startAngle = -45f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(waveCenter.x - radius, waveCenter.y - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(1.5.dp.toPx())
        )
    }
    // A stripe of "printing", so it reads as a card rather than a box.
    drawRoundRect(
        TagLine.copy(alpha = 0.35f),
        topLeft = Offset(14.dp.toPx(), size.height - 16.dp.toPx()),
        size = Size(size.width * 0.45f, 4.dp.toPx()),
        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
    )
}
