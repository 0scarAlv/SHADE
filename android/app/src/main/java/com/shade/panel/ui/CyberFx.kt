package com.shade.panel.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.random.Random

// Opt-in cyberpunk layer over the flat instrument kit (Settings → Pantalla →
// Modo ciberpunk): text that decodes in from noise, glitch bursts, two-tone
// neon (the ink plus a saturated counterpart) and a light circling the
// player's display. Anything that loops only runs while music is playing.

val CyberWhite = Color(0xFFF4F7FF)
val CyberCyan = Color(0xFF00F0FF)
private val FallbackNeon = Color(0xFFFF2BD6)

// Glyphs the Doto face has, so scrambled text keeps its dot-matrix look and width.
private const val NOISE = "0123456789ABCDEF#$%&*+=<>/\\|?"
private const val DECODE_MS = 700

@Composable
fun cyberOn(): Boolean = LocalAppSettings.current.cyberpunk

// Saturated counterpart of the ink: hue rotated toward magenta/cyan. Grey
// inks (ICE) have no hue to rotate, so they get a fixed magenta.
fun neonOf(ink: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(ink.toArgb(), hsv)
    if (hsv[1] < 0.15f) return FallbackNeon
    return Color(android.graphics.Color.HSVToColor(floatArrayOf((hsv[0] + 150f) % 360f, maxOf(hsv[1], 0.75f), maxOf(hsv[2], 0.95f))))
}

private fun hash(a: Int, b: Int): Int {
    var h = a * -0x61c88647 xor b * -0x7a143589
    h = h xor (h ushr 15); h *= 0x2C1B3C6D
    h = h xor (h ushr 12); h *= 0x297A2D39
    return h xor (h ushr 15)
}

private fun noise01(a: Int, b: Int) = (hash(a, b) ushr 8) / 16_777_216f

// Milliseconds that tick every frame while `running` and freeze otherwise.
// Read it inside draw lambdas so only drawing (not composition) repeats.
@Composable
fun rememberFrameClock(running: Boolean): State<Long> {
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val start = withFrameMillis { it } - clock.longValue
        while (true) withFrameMillis { clock.longValue = it - start }
    }
    return clock
}

// Dot-matrix text that resolves out of scrambled glyphs whenever `text`
// changes (each character flashing white as it locks in), with RGB-split
// glitch bursts: on change when `burst`, and every 5–14 s when `ambient`.
@Composable
fun CyberText(
    text: String,
    size: TextUnit,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Bold,
    maxLines: Int = 1,
    burst: Boolean = true,
    ambient: Boolean = false,
) {
    val ink = LocalInk.current
    val neon = neonOf(ink)
    val decode = remember { Animatable(1f) }
    var glitch by remember { mutableIntStateOf(0) }

    LaunchedEffect(text) {
        decode.snapTo(0f)
        decode.animateTo(1f, tween(DECODE_MS, easing = LinearEasing))
    }
    suspend fun stutter(frames: Int) {
        repeat(frames) {
            glitch = Random.nextInt(1, Int.MAX_VALUE)
            delay(40)
        }
        glitch = 0
    }
    LaunchedEffect(text) { if (burst) stutter(5) }
    LaunchedEffect(ambient) {
        while (ambient) {
            delay(5_000L + Random.nextLong(9_000L))
            stutter(2 + Random.nextInt(4))
        }
    }

    val p = decode.value
    val shown = if (p >= 1f) buildAnnotatedString { append(text) } else buildAnnotatedString {
        val n = (text.length - 1).coerceAtLeast(1)
        val tick = (p * DECODE_MS / 50).toInt()
        text.forEachIndexed { i, ch ->
            val appearAt = 0.3f * i / n
            val lockAt = minOf(0.93f, 0.15f + 0.55f * i / n + noise01(i, text.hashCode()) * 0.2f)
            when {
                p >= lockAt -> if (p - lockAt < 0.1f) withStyle(SpanStyle(color = CyberWhite)) { append(ch) } else append(ch)
                p >= appearAt && ch != ' ' -> withStyle(SpanStyle(color = neon.copy(alpha = 0.8f))) {
                    append(NOISE[Math.floorMod(hash(i, tick), NOISE.length)])
                }
                else -> append(' ')
            }
        }
    }

    val style = TextStyle(fontFamily = Doto, fontWeight = weight, fontSize = size, lineHeight = size * 1.1f)
    @Composable
    fun layer(color: Color, dx: Dp) = Text(
        text = shown,
        modifier = Modifier.offset(x = dx),
        style = style.copy(color = color),
        maxLines = maxLines,
        softWrap = maxLines > 1,
        overflow = TextOverflow.Clip,
    )

    Box(modifier) {
        if (glitch != 0) {
            val split = (1 + glitch % 3).dp
            layer(CyberCyan.copy(alpha = 0.7f), -split)
            layer(neon.copy(alpha = 0.7f), split)
            layer(ink, ((glitch / 7) % 5 - 2).dp)
        } else {
            layer(ink, 0.dp)
        }
    }
}

// Draws a bright packet with a fading neon tail circling the element's
// rounded border, plus faint CRT scanlines over the content. `phase` (0..1)
// is read at draw time so the animation never recomposes.
fun Modifier.cyberDisplay(phase: () -> Float, running: Boolean, head: Color, tail: Color, corner: Dp): Modifier = drawWithContent {
    drawContent()
    val line = 1.dp.toPx()
    var y = 0f
    while (y < size.height) {
        drawRect(Color.Black.copy(alpha = 0.18f), Offset(0f, y), androidx.compose.ui.geometry.Size(size.width, line))
        y += line * 3
    }
    if (!running) return@drawWithContent

    val stroke = 2.dp.toPx()
    val inset = stroke / 2
    val path = Path().apply {
        addRoundRect(RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(corner.toPx() - inset)))
    }
    val measure = PathMeasure().apply { setPath(path, false) }
    val length = measure.length
    val headAt = phase() * length
    val tailLength = length * 0.14f
    val steps = 10
    for (k in 0 until steps) {
        val t = 1f - k / steps.toFloat()
        val color = if (k == 0) head else lerp(tail.copy(alpha = 0f), tail, t * t)
        drawSegment(measure, length, headAt - tailLength * (k + 1) / steps, headAt - tailLength * k / steps, color, stroke)
    }
}

// Segment [from, to] of a closed path, wrapping across its start.
private fun DrawScope.drawSegment(measure: PathMeasure, length: Float, from: Float, to: Float, color: Color, stroke: Float) {
    var a = from
    var b = to
    if (a < 0f) { a += length; b += length }
    if (b > length) {
        drawSegment(measure, length, a, length, color, stroke)
        drawSegment(measure, length, 0f, b - length, color, stroke)
        return
    }
    val segment = Path()
    if (measure.getSegment(a, b, segment, true)) drawPath(segment, color, style = Stroke(stroke, cap = StrokeCap.Round))
}
