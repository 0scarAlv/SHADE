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

// Cyberpunk layer over the flat instrument kit (Settings → Pantalla → Modo
// ciberpunk, on by default): text that lights up letter by letter, glitch
// bursts and flashes driven by the beat, the cover's own colors and
// a light circling the player's display. Anything that loops only runs while
// music is playing.

val CyberWhite = Color(0xFFF4F7FF)

private const val DECODE_MS = 700

@Composable
fun cyberOn(): Boolean = LocalAppSettings.current.cyberpunk

// Gradient running through the cover's four colors, 16 steps, for spectra that sweep color across their width.
fun coverGradient(colors: List<Color>): List<Color> = List(16) { i ->
    val t = i / 15f * (colors.size - 1)
    val a = minOf(t.toInt(), colors.size - 2)
    lerp(colors[a], colors[a + 1], t - a)
}

enum class SpectrumStyle {
    BARS, MIRROR, WAVE;

    companion object {
        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: BARS
    }
}

// Beat detection on the agent's spectrum frames: bass-weighted spectral flux
// against an adaptive threshold (same tuning as Nation). The envelope is
// computed at read time from the last hit, so nothing ticks between beats.
@androidx.compose.runtime.Stable
class BeatState {
    private var lastHitMs = 0L
    private var strength = 0f
    var strongHits by mutableIntStateOf(0)
        private set

    fun level(nowMs: Long = android.os.SystemClock.uptimeMillis()): Float {
        if (lastHitMs == 0L) return 0f
        val v = strength * kotlin.math.exp(-(nowMs - lastHitMs) / 140f)
        return if (v < 0.02f) 0f else v
    }

    internal fun hit(s: Float, nowMs: Long) {
        lastHitMs = nowMs
        strength = maxOf(level(nowMs), s)
        if (s > 0.65f) strongHits++
    }
}

@Composable
fun rememberBeat(spectrum: kotlinx.coroutines.flow.StateFlow<FloatArray>, enabled: Boolean): BeatState {
    val beat = remember { BeatState() }
    LaunchedEffect(spectrum, enabled) {
        if (!enabled) return@LaunchedEffect
        val prev = FloatArray(BAND_COUNT)
        var avg = 0f
        var lastBeat = 0L
        spectrum.collect { bands ->
            var flux = 0f
            for (i in 0 until minOf(8, bands.size)) {
                flux += maxOf(0f, bands[i] - prev[i]) * if (i < 4) 1f else 0.5f
            }
            bands.copyInto(prev, endIndex = minOf(bands.size, prev.size))
            avg += (flux - avg) * 0.08f
            val now = android.os.SystemClock.uptimeMillis()
            if (flux > avg * 1.6f + 0.08f && now - lastBeat > 220) {
                lastBeat = now
                beat.hit(((flux - avg) / 0.6f).coerceIn(0.35f, 1f), now)
            }
        }
    }
    return beat
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

// Dot-matrix text that lights up letter by letter whenever `text` changes
// (a dim neon ghost first, then a white flash as each character locks in),
// with RGB-split glitch bursts: on change when `burst`, on strong beats
// (`beatHits` changing, at most every 3.5 s) and every 10–20 s when `ambient`.
@Composable
fun CyberText(
    text: String,
    size: TextUnit,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Bold,
    maxLines: Int = 1,
    burst: Boolean = true,
    ambient: Boolean = false,
    beatHits: Int = 0,
) {
    val ink = LocalInk.current
    val colors = LocalArtColors.current
    val neon = colors[1]
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
    var lastBeatGlitch by remember { mutableLongStateOf(0L) }
    LaunchedEffect(beatHits) {
        val now = android.os.SystemClock.uptimeMillis()
        if (beatHits > 0 && now - lastBeatGlitch > 3_500) {
            lastBeatGlitch = now
            stutter(3)
        }
    }
    LaunchedEffect(ambient) {
        while (ambient) {
            delay(10_000L + Random.nextLong(10_000L))
            stutter(2 + Random.nextInt(4))
        }
    }

    val p = decode.value
    val shown = if (p >= 1f) buildAnnotatedString { append(text) } else buildAnnotatedString {
        val n = (text.length - 1).coerceAtLeast(1)
        text.forEachIndexed { i, ch ->
            val appearAt = 0.3f * i / n
            val lockAt = minOf(0.93f, 0.15f + 0.55f * i / n + noise01(i, text.hashCode()) * 0.2f)
            when {
                p >= lockAt -> if (p - lockAt < 0.1f) withStyle(SpanStyle(color = CyberWhite)) { append(ch) } else append(ch)
                p >= appearAt -> withStyle(SpanStyle(color = neon.copy(alpha = 0.55f))) { append(ch) }
                else -> withStyle(SpanStyle(color = Color.Transparent)) { append(ch) }
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
            layer(colors[3].copy(alpha = 0.7f), -split)
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
fun Modifier.cyberDisplay(phase: () -> Float, running: Boolean, head: Color, tail: Color, corner: Dp, beat: () -> Float = { 0f }): Modifier = drawWithContent {
    drawContent()
    // The whole border flashes with each beat.
    val flash = beat()
    if (flash > 0f) {
        val w = 1.5.dp.toPx()
        drawRoundRect(
            color = tail.copy(alpha = flash * 0.85f),
            topLeft = Offset(w / 2, w / 2),
            size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
            cornerRadius = CornerRadius(corner.toPx() - w / 2),
            style = Stroke(w),
        )
    }
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
