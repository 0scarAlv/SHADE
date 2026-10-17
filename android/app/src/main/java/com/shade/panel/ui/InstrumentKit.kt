package com.shade.panel.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// The instrument kit: the layout language of an audio component (a main
// display with annunciators, big numeric readouts, a segmented spectrum,
// keys with state and a caption under each, a volume dial) rendered flat
// and modern — plain surfaces, hairline borders, rounded geometry, a
// dot-matrix typeface and one accent "ink" color. No textures, bevels,
// glow or fake glass.

// ------------------------------------------------------------------ palette

object Ink {
    val Background = Color(0xFF0A0A0A)
    val Panel = Color(0xFF131313)
    val PanelBorder = Color(0xFF232323)
    val Key = Color(0xFF1A1A1A)
    val KeyPressed = Color(0xFF252525)
    val Text = Color(0xFFEDEDED)
    val TextMuted = Color(0xFF8A8A8A)
    val TextSubtle = Color(0xFF555555)
    val Off = Color(0xFF2E2E2E)
}

// Accent ink used for every "lit" element. Persisted as a name, so the enum
// constants double as the stored values.
// AUTO follows the current cover (see ArtPalette / MainActivity); its
// `color` is only the swatch shown in settings.
enum class InkColor(val color: Color, val labelRes: Int) {
    AUTO(Color(0xFFB794F6), R.string.vfd_auto),
    CYAN(Color(0xFF5EEAD4), R.string.vfd_cyan),
    AMBER(Color(0xFFFFB13B), R.string.vfd_amber),
    RED(Color(0xFFFF4A3D), R.string.vfd_red),
    GREEN(Color(0xFFA3E635), R.string.vfd_green),
    ICE(Color(0xFFE4ECF7), R.string.vfd_ice);

    companion object {
        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: CYAN
    }
}

val LocalInk = staticCompositionLocalOf { InkColor.CYAN.color }

// ------------------------------------------------------------------ fonts

@OptIn(ExperimentalTextApi::class)
private fun doto(weight: FontWeight) = Font(
    R.font.doto,
    weight = weight,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight.weight),
        FontVariation.Setting("ROND", 100f),
    ),
)

// Monospaced dot-matrix face — every glyph the same width, so changing
// numbers never shift the layout.
val Doto = FontFamily(doto(FontWeight.Medium), doto(FontWeight.Bold), doto(FontWeight.Black))

private fun inkStyle(size: TextUnit, color: Color, weight: FontWeight) = TextStyle(
    fontFamily = Doto,
    fontWeight = weight,
    fontSize = size,
    lineHeight = size * 1.1f,
    color = color,
)

// ------------------------------------------------------------------ surfaces

@Composable
fun ScreenBase(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier = modifier.background(Ink.Background), content = content)
}

// The main "display" surface: a flat panel with a hairline border.
@Composable
fun DisplayPanel(modifier: Modifier = Modifier, contentPadding: Dp = 16.dp, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Ink.Panel)
            .border(1.dp, Ink.PanelBorder, shape)
            .padding(contentPadding),
        content = content,
    )
}

// Small, wide-tracked uppercase label.
@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = Ink.TextMuted, size: TextUnit = 10.sp) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = size, letterSpacing = 0.14.em, color = color),
        maxLines = 1,
        overflow = TextOverflow.Clip,
    )
}

// Dot-matrix text in the accent ink. `lit = false` renders it dimmed, for
// inactive annunciators (SHUF off, previous lyric line, ...).
@Composable
fun DotText(
    text: String,
    size: TextUnit,
    modifier: Modifier = Modifier,
    lit: Boolean = true,
    weight: FontWeight = FontWeight.Bold,
    maxLines: Int = 1,
) {
    val ink = LocalInk.current
    Text(
        text = text,
        modifier = modifier,
        style = inkStyle(size, if (lit) ink else ink.copy(alpha = 0.22f), weight),
        maxLines = maxLines,
        softWrap = maxLines > 1,
        overflow = TextOverflow.Clip,
    )
}

// Big numeric readout. `template` sets the field width (e.g. "88:88"): the
// value is right-aligned inside it with spaces, which keeps a stable width
// because the face is monospaced.
@Composable
fun Readout(text: String, size: TextUnit, modifier: Modifier = Modifier, template: String? = null, lit: Boolean = true) {
    val ink = LocalInk.current
    val shown = if (template != null) text.padStart(template.length, ' ') else text
    Text(
        text = shown,
        modifier = modifier,
        style = inkStyle(size, if (lit) ink else ink.copy(alpha = 0.22f), FontWeight.Black),
        maxLines = 1,
        softWrap = false,
    )
}

@Composable
fun Dot(on: Boolean, modifier: Modifier = Modifier, color: Color = LocalInk.current, size: Dp = 6.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (on) color else Ink.Off),
    )
}

// ------------------------------------------------------------------ controls

// Flat key: rounded tile that scales down slightly when pressed. Active
// state (`active = true`) is shown with an accent outline and a small accent
// dot; an optional caption sits underneath, like a printed function name.
@Composable
fun KeyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 64.dp,
    height: Dp = 46.dp,
    active: Boolean? = null,
    activeColor: Color = LocalInk.current,
    caption: String? = null,
    contentDescription: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(stiffness = 900f), label = "keyScale")
    val border by animateColorAsState(if (active == true) activeColor.copy(alpha = 0.8f) else Ink.PanelBorder, tween(180), label = "keyBorder")
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(12.dp)

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(width, height)
                .scale(scale)
                .clip(shape)
                .background(if (pressed) Ink.KeyPressed else Ink.Key)
                .border(1.dp, border, shape)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onClick()
                }
                .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            content()
            if (active == true) Dot(on = true, color = activeColor, size = 5.dp, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        if (caption != null) {
            Spacer(Modifier.height(6.dp))
            Caption(caption, size = 9.sp, color = Ink.TextSubtle)
        }
    }
}

// Text on a key.
@Composable
fun CapLabel(text: String, size: TextUnit = 13.sp, color: Color = Ink.Text) {
    Text(text, style = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = size, letterSpacing = 0.06.em, color = color))
}

// Volume dial: a thin 270° track with the filled arc in accent ink and a
// handle dot at its end. Turn it by dragging around the center (or simply
// up/down); sends throttled absolute levels.
@Composable
fun VolumeKnob(
    level: Float,
    enabled: Boolean,
    muted: Boolean,
    onLevelChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
) {
    val ink = LocalInk.current
    val haptics = LocalHapticFeedback.current
    var dragLevel by remember { mutableStateOf<Float?>(null) }
    val shown = dragLevel ?: level
    val currentOnChange by rememberUpdatedState(onLevelChange)
    val currentLevel by rememberUpdatedState(level)

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var value = currentLevel
                        var lastAngle = angleOf(down.position, this.size.width / 2f, this.size.height / 2f)
                        var lastY = down.position.y
                        var lastSent = 0L
                        var lastTick = (value * TICKS).toInt()
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
                            // Rotation and vertical drag both count, whichever
                            // the finger is doing more of.
                            val deltaY = (lastY - change.position.y) / this.size.height
                            lastY = change.position.y
                            val fromRotation = deltaAngle / SWEEP_DEGREES
                            value = (value + if (abs(fromRotation) > abs(deltaY)) fromRotation else deltaY).coerceIn(0f, 1f)
                            dragLevel = value
                            val tick = (value * TICKS).toInt()
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
            val stroke = this.size.minDimension * 0.06f
            val inset = stroke / 2 + 2f
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(Ink.Off, START_DEGREES, SWEEP_DEGREES, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            val fill = shown.coerceIn(0f, 1f)
            val arcColor = if (muted) Ink.TextSubtle else ink
            if (fill > 0f) {
                drawArc(arcColor, START_DEGREES, SWEEP_DEGREES * fill, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            val radius = arcSize.width / 2
            val angle = Math.toRadians((START_DEGREES + SWEEP_DEGREES * fill).toDouble())
            val handle = Offset(center.x + (radius * cos(angle)).toFloat(), center.y + (radius * sin(angle)).toFloat())
            drawCircle(Ink.Background, stroke * 1.1f, handle)
            drawCircle(arcColor, stroke * 0.75f, handle)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Readout(if (enabled) "${(shown * 100).toInt()}" else "--", (size.value * 0.22f).sp, lit = !muted)
            Caption(if (muted) "Mute" else "Vol", size = 8.sp, color = Ink.TextSubtle)
        }
    }
}

private const val TICKS = 20
private const val START_DEGREES = 135f
private const val SWEEP_DEGREES = 270f

private fun angleOf(position: Offset, cx: Float, cy: Float): Float =
    Math.toDegrees(atan2((position.y - cy).toDouble(), (position.x - cx).toDouble())).toFloat()

// ------------------------------------------------------------------ meters

// Segmented spectrum: one column of rounded cells per band, unlit cells
// faintly visible, plus a peak-hold cell that floats down slowly. Animates
// at display rate between the agent's frames and parks itself once
// everything has decayed to silence.
@Composable
fun BarSpectrum(spectrum: StateFlow<FloatArray>, modifier: Modifier = Modifier, rows: Int = 12) {
    val ink = LocalInk.current
    // Cyberpunk mode: rows fade from the ink into its neon counterpart, peaks burn white, lit cells bloom.
    val cyber = cyberOn()
    val neon = neonOf(ink)
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
        val gapX = 3.dp.toPx()
        val gapY = 3.dp.toPx()
        val cellW = (size.width - gapX * (columns - 1)) / columns
        val cellH = (size.height - gapY * (rows - 1)) / rows
        val radius = CornerRadius(minOf(cellW, cellH) / 2.5f)
        for (c in 0 until columns) {
            val lit = (bands[c].coerceIn(0f, 1f) * rows).toInt()
            val peakRow = (peaks[c].coerceIn(0f, 1f) * rows).toInt().coerceAtMost(rows - 1)
            val x = c * (cellW + gapX)
            for (r in 0 until rows) {
                val y = size.height - (r + 1) * cellH - r * gapY
                val isPeak = r >= lit && r == peakRow && peaks[c] > 0.04f
                val on = r < lit || isPeak
                val litColor = when {
                    !cyber -> ink
                    isPeak -> CyberWhite
                    else -> androidx.compose.ui.graphics.lerp(ink, neon, if (rows > 1) r / (rows - 1f) else 1f)
                }
                if (cyber && on) {
                    val bloom = 2.dp.toPx()
                    drawRoundRect(
                        color = litColor.copy(alpha = 0.18f),
                        topLeft = Offset(x - bloom, y - bloom),
                        size = Size(cellW + bloom * 2, cellH + bloom * 2),
                        cornerRadius = CornerRadius(radius.x + bloom),
                    )
                }
                drawRoundRect(
                    color = if (on) litColor else Color.White.copy(alpha = 0.05f),
                    topLeft = Offset(x, y),
                    size = Size(cellW, cellH),
                    cornerRadius = radius,
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
    color: Color = LocalInk.current,
    onSeek: ((Float) -> Unit)? = null,
    cyber: Boolean = false,
    pulse: Boolean = false,
) {
    // Cyberpunk: color → neon gradient, white-hot head segment and (while `pulse`) a light running along the lit part.
    val neon = neonOf(color)
    val clock = rememberFrameClock(cyber && pulse)
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
        val gap = 3.dp.toPx()
        val segW = (size.width - gap * (segments - 1)) / segments
        val lit = (shown.coerceIn(0f, 1f) * segments).toInt()
        val radius = CornerRadius(minOf(segW, size.height) / 2)
        val pulseAt = if (cyber && pulse) (clock.value % 2_400L) / 2_400f * (lit + 10) - 5 else Float.NaN
        for (i in 0 until segments) {
            val litColor = if (!cyber) color else {
                val base = androidx.compose.ui.graphics.lerp(color, neon, i / (segments - 1f).coerceAtLeast(1f))
                val d = abs(i - pulseAt)
                when {
                    i == lit - 1 -> CyberWhite
                    d < 3f -> androidx.compose.ui.graphics.lerp(base, CyberWhite, (1 - d / 3f) * 0.7f)
                    else -> base
                }
            }
            drawRoundRect(
                color = if (i < lit) litColor else Color.White.copy(alpha = 0.07f),
                topLeft = Offset(i * (segW + gap), 0f),
                size = Size(segW, size.height),
                cornerRadius = radius,
            )
        }
    }
}

// Dot-matrix history graph: one column of dots per sample, lit from the
// bottom up to the value.
@Composable
fun DotHistory(values: List<Float>, maxValue: Float, modifier: Modifier = Modifier, color: Color = LocalInk.current, rows: Int = 8) {
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
                    color = if (r < lit) color else Color.White.copy(alpha = 0.05f),
                    topLeft = Offset(c * (dot + gap), size.height - (r + 1) * dot - r * gap),
                    size = Size(dot, dot),
                    cornerRadius = CornerRadius(dot / 2),
                )
            }
        }
    }
}

// Status dot with a label beside it ("● LINK").
@Composable
fun StatusDot(label: String, on: Boolean, color: Color = LocalInk.current) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Dot(on = on, color = color, size = 6.dp)
        Caption(label, color = if (on) Ink.TextMuted else Ink.TextSubtle)
    }
}

// Top strip shared by every screen: wordmark + section name on the left,
// status on the right (room left for the shell's home key).
@Composable
fun TopStrip(model: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            "SHADE",
            style = TextStyle(fontFamily = Doto, fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 0.06.em, color = Ink.Text),
        )
        Spacer(Modifier.width(12.dp))
        Caption(model, color = Ink.TextSubtle)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

// Plain tap target without Material's ripple — these surfaces give their
// own feedback.
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
}
