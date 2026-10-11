package com.shade.panel.ui

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.shade.panel.ui.theme.Manrope
import com.shade.panel.ui.theme.PanelBackground
import com.shade.panel.ui.theme.PanelOnBackgroundMuted
import com.shade.panel.ui.theme.PanelOnBackgroundSubtle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Bedside/desk clock: oversized light-weight digits that roll over one by
// one, the date above in the accent color, and what's playing below.
// Content drifts a few dp every minute (burn-in protection) since this is
// the screen most likely to be left on for hours.
@Composable
fun ClockScreen(viewModel: PanelViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val settings = LocalAppSettings.current
    val burnIn = rememberBurnInOffset(settings.burnInProtection, maxShift = 12.dp)
    val accent = MaterialTheme.colorScheme.primary

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            // Re-align to the next whole second so the digits flip in step
            // with the system clock rather than drifting a fraction behind.
            delay(1_000 - nowMs % 1_000)
        }
    }

    val is24h = DateFormat.is24HourFormat(context)
    val locale = Locale.getDefault()
    val timeFormat = remember(is24h) { SimpleDateFormat(if (is24h) "HH:mm" else "h:mm", locale) }
    val secondsFormat = remember { SimpleDateFormat("ss", locale) }
    val amPmFormat = remember { SimpleDateFormat("a", locale) }
    val dateFormat = remember { SimpleDateFormat("EEEE, d 'de' MMMM", locale) }
    val now = Date(nowMs)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.10f), PanelBackground),
                    radius = 1400f,
                ),
            ),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .offset(burnIn.x, burnIn.y)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val landscape = maxWidth > maxHeight
            // "00:00" in Manrope Light is roughly 2.9em wide with tabular figures.
            val byWidth = maxWidth / (if (settings.clockSeconds) 3.9f else 3.1f)
            val byHeight = maxHeight * (if (landscape) 0.48f else 0.30f)
            val digitSize = min(byWidth, byHeight)

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = dateFormat.format(now).replaceFirstChar { it.titlecase(locale) },
                    style = MaterialTheme.typography.titleMedium,
                    color = accent,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    RollingText(
                        text = timeFormat.format(now),
                        style = clockStyle(digitSize.toSp(), FontWeight.Light),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Column(
                        modifier = Modifier.padding(start = 10.dp, bottom = digitSize * 0.18f),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        if (!is24h) {
                            Text(
                                text = amPmFormat.format(now).uppercase(locale),
                                style = clockStyle((digitSize * 0.16f).toSp(), FontWeight.SemiBold),
                                color = PanelOnBackgroundMuted,
                            )
                        }
                        if (settings.clockSeconds) {
                            RollingText(
                                text = secondsFormat.format(now),
                                style = clockStyle((digitSize * 0.30f).toSp(), FontWeight.Normal),
                                color = PanelOnBackgroundSubtle,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(if (landscape) 12.dp else 28.dp))
                ClockFooter(uiState)
            }
        }
    }
}

// Through Density so the user's font-scale setting can't push the digits
// past the width they were sized for.
@Composable
private fun Dp.toSp(): TextUnit = with(LocalDensity.current) { this@toSp.toSp() }

private fun clockStyle(size: TextUnit, weight: FontWeight) = TextStyle(
    fontFamily = Manrope,
    fontWeight = weight,
    fontSize = size,
    lineHeight = size,
    letterSpacing = (-0.02f * size.value).sp,
    // Tabular figures: every digit is the same width, so the clock doesn't
    // wobble sideways as "1" and "8" swap places.
    fontFeatureSettings = "tnum",
)

// Each character animates independently, so only digits that actually
// changed roll (the minute ticking over doesn't re-animate the hour).
@Composable
private fun RollingText(text: String, style: TextStyle, color: Color) {
    Row {
        text.forEachIndexed { index, char ->
            AnimatedContent(
                targetState = char,
                transitionSpec = {
                    (slideInVertically(tween(420)) { it / 2 } + fadeIn(tween(420)))
                        .togetherWith(slideOutVertically(tween(420)) { -it / 2 } + fadeOut(tween(300)))
                },
                label = "clockChar$index",
            ) { shown ->
                Text(text = shown.toString(), style = style, color = color)
            }
        }
    }
}

@Composable
private fun ClockFooter(uiState: PanelUiState) {
    val resource = uiState.resource
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(visible = uiState.hasTrack, enter = fadeIn(), exit = fadeOut()) {
            NowPlayingChip(uiState)
        }
        if (resource != null && resource.hasBattery) {
            val charging = resource.batteryCharging == true
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (charging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.Computer,
                    contentDescription = null,
                    tint = PanelOnBackgroundMuted,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${resource.batteryPercent ?: 0}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = PanelOnBackgroundMuted,
                )
            }
        }
    }
}

@Composable
private fun NowPlayingChip(uiState: PanelUiState) {
    Row(
        modifier = Modifier
            .widthIn(max = 420.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.06f))
            .padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtImage(model = uiState.art, modifier = Modifier.size(32.dp), shape = CircleShape)
        Spacer(Modifier.width(10.dp))
        Text(
            text = listOf(uiState.title, uiState.artist).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (uiState.playing) {
            Spacer(Modifier.width(10.dp))
            MiniEqualizer()
        }
    }
}

// Three bouncing bars — the universal "this is playing" glyph.
@Composable
fun MiniEqualizer(color: Color = MaterialTheme.colorScheme.primary, height: Dp = 14.dp) {
    val transition = rememberInfiniteTransition(label = "miniEq")
    val durations = listOf(520, 380, 640)
    Row(
        modifier = Modifier.height(height),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        durations.forEachIndexed { i, duration ->
            val fraction by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(duration), RepeatMode.Reverse),
                label = "miniEqBar$i",
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .graphicsLayer {
                        scaleY = fraction
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                    }
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}
