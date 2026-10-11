package com.shade.panel.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.shade.panel.R
import com.shade.panel.ui.theme.Manrope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// The Hi-Fi kit: everything the player/home/monitor screens are built from,
// modeled on a late-80s/90s stereo component — a dark brushed faceplate,
// recessed VFD display windows with glowing segment digits (and the faint
// unlit "ghost" segments behind them), dot-matrix text, physical buttons with
// status LEDs, silkscreened labels and a rotary volume knob.

// ------------------------------------------------------------------ palette

object Hifi {
    val Faceplate = Color(0xFF141311)
    val FaceplateLight = Color(0xFF1C1A17)
    val FaceplateDark = Color(0xFF0C0B0A)
    val Bezel = Color(0xFF030303)
    val DisplayGlass = Color(0xFF040605)
    val Silkscreen = Color(0xFF8C867C)
    val SilkscreenDim = Color(0xFF55504A)
    val ButtonTop = Color(0xFF2C2925)
    val ButtonBottom = Color(0xFF1B1916)
    val LedOff = Color(0xFF2A2724)
}

enum class VfdColor(val color: Color, val labelRes: Int) {
    CYAN(Color(0xFF6CF6DD), R.string.vfd_cyan),
    AMBER(Color(0xFFFFB13B), R.string.vfd_amber),
    RED(Color(0xFFFF4B3E), R.string.vfd_red),
    GREEN(Color(0xFFA6FF4D), R.string.vfd_green),
    ICE(Color(0xFFDCEBFF), R.string.vfd_ice);

    companion object {
        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: CYAN
    }
}

val LocalVfd = staticCompositionLocalOf { VfdColor.CYAN.color }

// ------------------------------------------------------------------ fonts

val Dseg7 = FontFamily(
    Font(R.font.dseg7_regular, FontWeight.Normal),
    Font(R.font.dseg7_bold, FontWeight.Bold),
)

@OptIn(ExperimentalTextApi::class)
private fun doto(weight: FontWeight) = Font(
    R.font.doto,
    weight = weight,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight.weight),
        // Fully round dots, like an actual dot-matrix VFD.
        FontVariation.Setting("ROND", 100f),
    ),
)

val Doto = FontFamily(doto(FontWeight.Medium), doto(FontWeight.Bold), doto(FontWeight.Black))

fun vfdStyle(family: FontFamily, size: TextUnit, color: Color, weight: FontWeight = FontWeight.Bold, glow: Boolean = true) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size,
    lineHeight = size * 1.15f,
    color = color,
    shadow = if (glow) Shadow(color = color.copy(alpha = 0.75f), blurRadius = 18f) else null,
)

// ------------------------------------------------------------------ surfaces

// Brushed-metal faceplate: a soft vertical light falloff plus fine
// horizontal grain lines (deterministic, so it doesn't shimmer between
// recompositions), with four corner screws.
@Composable
fun Faceplate(modifier: Modifier = Modifier, screws: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier
            .background(Brush.verticalGradient(listOf(Hifi.FaceplateLight, Hifi.Faceplate, Hifi.FaceplateDark)))
            .drawWithCache {
                val random = Random(7)
                val lines = List((size.height / 2.5f).toInt().coerceAtLeast(1)) { i ->
                    Triple(i * 2.5f, random.nextFloat() * 0.035f, random.nextFloat())
                }
                onDrawBehind {
                    lines.forEach { (y, alpha, tone) ->
                        drawLine(
                            color = (if (tone > 0.5f) Color.White else Color.Black).copy(alpha = alpha),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f,
                        )
                    }
                }
            },
    ) {
        if (screws) {
            val inset = 10.dp
            Screw(Modifier.align(Alignment.TopStart).offset(inset, inset))
            Screw(Modifier.align(Alignment.TopEnd).offset(-inset, inset))
            Screw(Modifier.align(Alignment.BottomStart).offset(inset, -inset))
            Screw(Modifier.align(Alignment.BottomEnd).offset(-inset, -inset))
        }
        content()
    }
}

@Composable
private fun Screw(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(9.dp)) {
        val r = size.minDimension / 2
        drawCircle(Brush.linearGradient(listOf(Color(0xFF3A3632), Color(0xFF151311)), Offset.Zero, Offset(size.width, size.height)), r)
        drawCircle(Color.Black.copy(alpha = 0.6f), r, style = Stroke(1f))
        rotate(28f) {
            drawLine(Color.Black.copy(alpha = 0.75f), Offset(center.x - r * 0.6f, center.y), Offset(center.x + r * 0.6f, center.y), 1.4f)
            drawLine(Color.Black.copy(alpha = 0.75f), Offset(center.x, center.y - r * 0.6f), Offset(center.x, center.y + r * 0.6f), 1.4f)
        }
    }
}

// Recessed display window: dark bezel with an inset shadow edge, near-black
// glass with a faint glow of the display color, fine scanlines, and a soft
// diagonal reflection across the glass.
@Composable
fun VfdWindow(modifier: Modifier = Modifier, contentPadding: Dp = 14.dp, content: @Composable BoxScope.() -> Unit) {
    val vfd = LocalVfd.current
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Hifi.Bezel)
            .padding(3.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Hifi.DisplayGlass)
            .background(Brush.radialGradient(listOf(vfd.copy(alpha = 0.07f), Color.Transparent), radius = 900f))
            .drawWithContent {
                drawContent()
                val step = 3.dp.toPx()
                var y = 0f
                while (y < size.height) {
                    drawLine(Color.Black.copy(alpha = 0.22f), Offset(0f, y), Offset(size.width, y), 1f)
                    y += step
                }
                drawRect(
                    Brush.linearGradient(
                        0f to Color.White.copy(alpha = 0.045f),
                        0.35f to Color.Transparent,
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    ),
                )
                // Inset shadow along the top edge sells the "recessed" look.
                drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent), 0f, 10.dp.toPx()))
            }
            .padding(contentPadding),
        content = content,
    )
}

// Silkscreened faceplate label: tiny, wide-tracked uppercase.
@Composable
fun Silk(text: String, modifier: Modifier = Modifier, color: Color = Hifi.Silkscreen, size: TextUnit = 10.sp) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = size, letterSpacing = 0.18.em, color = color),
        maxLines = 1,
        overflow = TextOverflow.Clip,
    )
}

// Dot-matrix text on the display. `lit = false` renders it as a dim,
// unpowered element (used for inactive indicators like SHUFFLE off).
@Composable
fun VfdText(
    text: String,
    size: TextUnit,
    modifier: Modifier = Modifier,
    lit: Boolean = true,
    weight: FontWeight = FontWeight.Bold,
    maxLines: Int = 1,
) {
    val vfd = LocalVfd.current
    Text(
        text = text,
        modifier = modifier,
        style = vfdStyle(Doto, size, if (lit) vfd else vfd.copy(alpha = 0.13f), weight, glow = lit),
        maxLines = maxLines,
        softWrap = maxLines > 1,
        overflow = TextOverflow.Clip,
    )
}

// Seven-segment readout with the unlit "8"s showing faintly behind, exactly
// how a real VFD/LCD looks when you see the off segments under the glass.
// DSEG uses "!" as a full-width blank, so shorter values are padded with it
// to stay aligned over the ghost.
@Composable
fun SegmentReadout(text: String, size: TextUnit, modifier: Modifier = Modifier, ghost: String? = null, lit: Boolean = true) {
    val vfd = LocalVfd.current
    val ghostText = ghost ?: text.map { if (it == ':' || it == '.') it else '8' }.joinToString("")
    val shown = text.padStart(ghostText.length, '!')
    Box(modifier = modifier) {
        Text(ghostText, style = vfdStyle(Dseg7, size, vfd.copy(alpha = 0.07f), glow = false))
        Text(shown, style = vfdStyle(Dseg7, size, if (lit) vfd else vfd.copy(alpha = 0.18f), glow = lit))
    }
}

@Composable
fun Led(on: Boolean, modifier: Modifier = Modifier, color: Color = LocalVfd.current, size: Dp = 6.dp) {
    Canvas(modifier = modifier.size(size * 2)) {
        val r = size.toPx() / 2
        if (on) drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.55f), Color.Transparent), center, r * 2), r * 2)
        drawCircle(if (on) color else Hifi.LedOff, r)
        drawCircle(Color.White.copy(alpha = if (on) 0.5f else 0.08f), r * 0.35f, Offset(center.x - r * 0.3f, center.y - r * 0.3f))
    }
}

// ------------------------------------------------------------------ controls

// A physical push button: raised two-tone cap with a highlight edge that
// sinks a little when pressed, an optional status LED on the cap, and a
// silkscreened caption printed on the faceplate underneath.
@Composable
fun HwButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 64.dp,
    height: Dp = 46.dp,
    led: Boolean? = null,
    ledColor: Color = LocalVfd.current,
    caption: String? = null,
    contentDescription: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val sink by animateFloatAsState(if (pressed) 1f else 0f, spring(stiffness = 1400f), label = "hwSink")
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(5.dp)

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // Dark well the cap sits in.
        Box(
            modifier = Modifier
                .size(width, height)
                .clip(RoundedCornerShape(7.dp))
                .background(Color.Black)
                .padding(2.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(y = (1.5f * sink).dp)
                    .clip(shape)
                    .background(
                        Brush.verticalGradient(
                            if (pressed) listOf(Hifi.ButtonBottom, Hifi.ButtonTop.copy(alpha = 0.9f))
                            else listOf(Hifi.ButtonTop, Hifi.ButtonBottom),
                        ),
                    )
                    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = if (pressed) 0.02f else 0.10f), Color.Transparent)), shape)
                    .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onClick()
                    }
                    .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                content()
                if (led != null) Led(on = led, color = ledColor, size = 5.dp, modifier = Modifier.align(Alignment.TopStart).padding(3.dp))
            }
        }
        if (caption != null) {
            Spacer(Modifier.height(5.dp))
            Silk(caption, size = 9.sp)
        }
    }
}

// Glyph drawn on a button cap — silkscreen-colored, not an icon font, so the
// buttons read as printed hardware rather than app icons.
@Composable
fun CapLabel(text: String, size: TextUnit = 13.sp, color: Color = Color(0xFFCFC8BC)) {
    Text(text, style = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = size, letterSpacing = 0.06.em, color = color))
}

// Rotary volume knob: knurled metal cap with an indicator notch, inside a
// 270° ring of LEDs lit up to the current level. Turn it by dragging around
// the center (or simply up/down); sends throttled absolute levels.
@Composable
fun VolumeKnob(
    level: Float,
    enabled: Boolean,
    muted: Boolean,
    onLevelChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
) {
    val vfd = LocalVfd.current
    val haptics = LocalHapticFeedback.current
    var dragLevel by remember { mutableStateOf<Float?>(null) }
    val shown = dragLevel ?: level
    val currentOnChange by rememberUpdatedState(onLevelChange)
    val currentLevel by rememberUpdatedState(level)

    Canvas(
        modifier = modifier
            .size(size)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var value = currentLevel
                    var lastAngle = angleOf(down.position, this.size.width / 2f, this.size.height / 2f)
                    var lastY = down.position.y
                    var lastSent = 0L
                    var lastTick = (value * LED_COUNT).toInt()
                    dragLevel = value
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        val angle = angleOf(change.position, this.size.width / 2f, this.size.height / 2f)
                        var deltaAngle = angle - lastAngle
                        if (deltaAngle > 180f) deltaAngle -= 360f
                        if (deltaAngle < -180f) deltaAngle += 360f
                        lastAngle = angle
                        // Rotation and vertical drag both count, whichever the
                        // finger is doing more of.
                        val deltaY = (lastY - change.position.y) / this.size.height
                        lastY = change.position.y
                        val fromRotation = deltaAngle / SWEEP_DEGREES
                        value = (value + if (kotlin.math.abs(fromRotation) > kotlin.math.abs(deltaY)) fromRotation else deltaY).coerceIn(0f, 1f)
                        dragLevel = value
                        val tick = (value * LED_COUNT).toInt()
                        if (tick != lastTick) {
                            lastTick = tick
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastSent >= 45) {
                            lastSent = now
                            currentOnChange(value)
                        }
                    }
                    currentOnChange(value)
                    dragLevel = null
                }
            },
    ) {
        val radius = this.size.minDimension / 2
        val ledRadius = radius * 0.9f
        val startAngle = 135f
        val litCount = (shown.coerceIn(0f, 1f) * LED_COUNT).toInt()
        for (i in 0 until LED_COUNT) {
            val angle = Math.toRadians((startAngle + SWEEP_DEGREES * i / (LED_COUNT - 1)).toDouble())
            val pos = Offset(center.x + (ledRadius * cos(angle)).toFloat(), center.y + (ledRadius * sin(angle)).toFloat())
            val lit = i < litCount
            val color = if (muted) vfd.copy(alpha = 0.25f) else vfd
            if (lit && !muted) drawCircle(color.copy(alpha = 0.35f), radius * 0.065f, pos)
            drawCircle(if (lit) color else Hifi.LedOff, radius * 0.032f, pos)
        }

        val knobRadius = radius * 0.7f
        // Shadow + body.
        drawCircle(Color.Black.copy(alpha = 0.7f), knobRadius * 1.06f, center.copy(y = center.y + knobRadius * 0.05f))
        drawCircle(Brush.linearGradient(listOf(Color(0xFF3B3732), Color(0xFF121110)), Offset(center.x - knobRadius, center.y - knobRadius), Offset(center.x + knobRadius, center.y + knobRadius)), knobRadius)
        // Knurled edge.
        for (i in 0 until 60) {
            val a = 2 * PI * i / 60
            val outer = Offset(center.x + (knobRadius * cos(a)).toFloat(), center.y + (knobRadius * sin(a)).toFloat())
            val inner = Offset(center.x + (knobRadius * 0.9f * cos(a)).toFloat(), center.y + (knobRadius * 0.9f * sin(a)).toFloat())
            drawLine(Color.Black.copy(alpha = 0.45f), inner, outer, 1.2f)
        }
        // Brushed cap.
        drawCircle(Brush.sweepGradient(listOf(Color(0xFF2A2724), Color(0xFF3E3A35), Color(0xFF221F1C), Color(0xFF3A3631), Color(0xFF2A2724)), center), knobRadius * 0.86f)
        drawCircle(Color.White.copy(alpha = 0.06f), knobRadius * 0.86f, style = Stroke(1f))
        // Indicator notch.
        val pointer = Math.toRadians((startAngle + SWEEP_DEGREES * shown.coerceIn(0f, 1f)).toDouble())
        val p1 = Offset(center.x + (knobRadius * 0.45f * cos(pointer)).toFloat(), center.y + (knobRadius * 0.45f * sin(pointer)).toFloat())
        val p2 = Offset(center.x + (knobRadius * 0.78f * cos(pointer)).toFloat(), center.y + (knobRadius * 0.78f * sin(pointer)).toFloat())
        drawLine(if (muted) Hifi.SilkscreenDim else Color(0xFFE9E3D8), p1, p2, knobRadius * 0.07f, cap = StrokeCap.Round)
    }
}

private const val LED_COUNT = 21
private const val SWEEP_DEGREES = 270f

private fun angleOf(position: Offset, cx: Float, cy: Float): Float =
    Math.toDegrees(atan2((position.y - cy).toDouble(), (position.x - cx).toDouble())).toFloat()

// ------------------------------------------------------------------ meters

// Classic mini-system spectrum display: one column of stacked segments per
// band, unlit segments faintly visible, a peak-hold segment that floats
// down slowly. Animates at display rate between the agent's frames and
// parks itself once everything has decayed to silence.
@Composable
fun SegmentSpectrum(spectrum: StateFlow<FloatArray>, modifier: Modifier = Modifier, rows: Int = 12) {
    val vfd = LocalVfd.current
    val bands = remember { FloatArray(BAND_COUNT) }
    val peaks = remember { FloatArray(BAND_COUNT) }
    val peakHold = remember { FloatArray(BAND_COUNT) }
    var frameTick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(spectrum) {
        var lastNanos = 0L
        while (true) {
            val idle = bands.all { it < 0.002f } && peaks.all { it < 0.002f } && spectrum.value.all { it < 0.002f }
            if (idle) {
                frameTick = -1L
                spectrum.first { frame -> frame.any { it >= 0.002f } }
                lastNanos = 0L
            }
            withFrameNanos { now ->
                val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                lastNanos = now
                val target = spectrum.value
                for (i in bands.indices) {
                    val t = target.getOrElse(i) { 0f }
                    val rate = if (t > bands[i]) 30f else 8f
                    bands[i] += (t - bands[i]) * (rate * dt).coerceAtMost(1f)
                    if (bands[i] >= peaks[i]) {
                        peaks[i] = bands[i]
                        peakHold[i] = 0.5f
                    } else if (peakHold[i] > 0f) {
                        peakHold[i] -= dt
                    } else {
                        peaks[i] = (peaks[i] - dt * 0.7f).coerceAtLeast(0f)
                    }
                }
                frameTick = now
            }
        }
    }

    Canvas(modifier = modifier) {
        @Suppress("UNUSED_EXPRESSION") frameTick
        val columns = BAND_COUNT
        val gapX = 2.dp.toPx()
        val gapY = 2.dp.toPx()
        val segW = (size.width - gapX * (columns - 1)) / columns
        val segH = (size.height - gapY * (rows - 1)) / rows
        for (c in 0 until columns) {
            val lit = (bands[c].coerceIn(0f, 1f) * rows).toInt()
            val peakRow = (peaks[c].coerceIn(0f, 1f) * rows).toInt().coerceAtMost(rows - 1)
            val x = c * (segW + gapX)
            for (r in 0 until rows) {
                val y = size.height - (r + 1) * segH - r * gapY
                val on = r < lit || (r == peakRow && peaks[c] > 0.04f)
                drawRect(
                    color = if (on) vfd else vfd.copy(alpha = 0.06f),
                    topLeft = Offset(x, y),
                    size = Size(segW, segH),
                )
            }
        }
    }
}

// Horizontal segmented bar (progress / level). With onSeek it doubles as a
// scrubber: tap or drag anywhere to set the fraction.
@Composable
fun SegmentBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    segments: Int = 48,
    color: Color = LocalVfd.current,
    onSeek: ((Float) -> Unit)? = null,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val shown = dragFraction ?: fraction
    val currentOnSeek by rememberUpdatedState(onSeek)
    val gestureModifier = if (onSeek != null) {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                dragFraction = (down.position.x / size.width).coerceIn(0f, 1f)
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    if (!change.pressed) break
                }
                dragFraction?.let { currentOnSeek?.invoke(it) }
                dragFraction = null
            }
        }
    } else Modifier

    Canvas(modifier = modifier.then(gestureModifier)) {
        val gap = 2.dp.toPx()
        val segW = (size.width - gap * (segments - 1)) / segments
        val lit = (shown.coerceIn(0f, 1f) * segments).toInt()
        for (i in 0 until segments) {
            drawRect(
                color = if (i < lit) color else color.copy(alpha = 0.07f),
                topLeft = Offset(i * (segW + gap), 0f),
                size = Size(segW, size.height),
            )
        }
    }
}

// Dot-matrix history graph: one column of dots per sample, lit from the
// bottom up to the value — reads like a VFD bar-graph history.
@Composable
fun DotHistory(values: List<Float>, maxValue: Float, modifier: Modifier = Modifier, color: Color = LocalVfd.current, rows: Int = 8) {
    Canvas(modifier = modifier) {
        val dot = 3.dp.toPx()
        val gap = 2.dp.toPx()
        val columns = ((size.width + gap) / (dot + gap)).toInt().coerceAtLeast(1)
        val rowCount = ((size.height + gap) / (dot + gap)).toInt().coerceIn(1, rows * 3)
        val samples = values.takeLast(columns)
        val offsetCols = columns - samples.size
        val safeMax = maxValue.coerceAtLeast(0.0001f)
        for (c in 0 until columns) {
            val value = samples.getOrNull(c - offsetCols)
            val lit = if (value == null) 0 else ((value / safeMax).coerceIn(0f, 1f) * rowCount).toInt().coerceAtLeast(if (value > 0f) 1 else 0)
            for (r in 0 until rowCount) {
                drawRoundRect(
                    color = if (r < lit) color else color.copy(alpha = 0.06f),
                    topLeft = Offset(c * (dot + gap), size.height - (r + 1) * dot - r * gap),
                    size = Size(dot, dot),
                    cornerRadius = CornerRadius(dot / 2),
                )
            }
        }
    }
}

// Status LED with a silkscreened name beside it ("● LINK").
@Composable
fun LedLabel(label: String, on: Boolean, color: Color = LocalVfd.current) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Led(on = on, color = color, size = 5.dp)
        Silk(label, color = if (on) Hifi.Silkscreen else Hifi.SilkscreenDim)
    }
}

// Top strip shared by every Hi-Fi screen: brand + model silkscreen on the
// left, status LEDs on the right (room left for the shell's home button).
@Composable
fun BrandStrip(model: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            "SHADE",
            style = TextStyle(fontFamily = Doto, fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 0.08.em, color = Color(0xFFD8D1C5)),
        )
        Spacer(Modifier.width(12.dp))
        Box(Modifier.width(1.dp).height(14.dp).background(Hifi.SilkscreenDim))
        Spacer(Modifier.width(12.dp))
        Silk(model, color = Hifi.SilkscreenDim)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

// Plain tap target without Material's ripple — the Hi-Fi surfaces give their
// own feedback (button sink, LEDs), a ripple would look like an app.
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
}
