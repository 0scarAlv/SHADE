package com.shade.panel.ui

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import coil3.compose.AsyncImage
import com.shade.panel.R
import com.shade.panel.data.ConnectionState
import com.shade.panel.data.LyricsLine
import com.shade.panel.ui.theme.PanelBackground
import com.shade.panel.ui.theme.PanelOnBackgroundMuted
import com.shade.panel.ui.theme.PanelOnBackgroundSubtle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.abs

@Composable
fun PanelScreen(viewModel: PanelViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val settings = LocalAppSettings.current
    val burnIn = rememberBurnInOffset(settings.burnInProtection, maxShift = 4.dp)
    var lyricsVisible by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        PlayerBackdrop(art = uiState.art)

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .offset(burnIn.x, burnIn.y),
        ) {
            val isLandscape = maxWidth > maxHeight
            val topBar = @Composable {
                PlayerTopBar(
                    uiState = uiState,
                    lyricsVisible = lyricsVisible,
                    onToggleLyrics = { lyricsVisible = !lyricsVisible },
                    keepScreenOn = settings.keepScreenOn,
                    onToggleKeepScreenOn = { settings.keepScreenOn = !settings.keepScreenOn },
                )
            }

            if (isLandscape) {
                val compact = maxHeight < 420.dp
                val artSize = min(maxHeight - 48.dp, maxWidth * 0.44f)
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AlbumArt(
                        art = uiState.art,
                        size = artSize,
                        playing = uiState.playing,
                        lyricsVisible = lyricsVisible,
                        onToggleLyrics = { lyricsVisible = !lyricsVisible },
                        lyricsLines = uiState.lyricsLines,
                        currentLyricsIndex = uiState.currentLyricsIndex,
                    )
                    Spacer(Modifier.width(36.dp))
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        topBar()
                        Spacer(Modifier.weight(1f))
                        TrackInfo(uiState, textAlign = TextAlign.Start)
                        Spacer(Modifier.height(if (compact) 10.dp else 18.dp))
                        SpectrumSeekBar(
                            spectrum = viewModel.spectrum,
                            positionMs = uiState.positionMs,
                            durationMs = uiState.durationMs,
                            onSeek = viewModel::seek,
                            barAreaHeight = if (compact) 44.dp else 56.dp,
                        )
                        Spacer(Modifier.height(if (compact) 6.dp else 12.dp))
                        ControlsRow(uiState, viewModel, compact = compact)
                        Spacer(Modifier.height(if (compact) 4.dp else 10.dp))
                        VolumeRow(uiState, viewModel)
                        Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                // Everything below the art has a fairly fixed height (~470dp),
                // so the art takes whatever is left, capped by the width.
                val artSize = min(maxWidth - 48.dp, maxHeight - 470.dp).coerceAtLeast(140.dp)
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    topBar()
                    Spacer(Modifier.weight(1f))
                    AlbumArt(
                        art = uiState.art,
                        size = artSize,
                        playing = uiState.playing,
                        lyricsVisible = lyricsVisible,
                        onToggleLyrics = { lyricsVisible = !lyricsVisible },
                        lyricsLines = uiState.lyricsLines,
                        currentLyricsIndex = uiState.currentLyricsIndex,
                    )
                    Spacer(Modifier.height(28.dp))
                    TrackInfo(uiState, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    SpectrumSeekBar(
                        spectrum = viewModel.spectrum,
                        positionMs = uiState.positionMs,
                        durationMs = uiState.durationMs,
                        onSeek = viewModel::seek,
                        barAreaHeight = 60.dp,
                    )
                    Spacer(Modifier.height(16.dp))
                    ControlsRow(uiState, viewModel, compact = false)
                    Spacer(Modifier.height(12.dp))
                    VolumeRow(uiState, viewModel)
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

// Full-bleed, heavily blurred copy of the cover tinted by the art's own
// backdrop color, with a darkening gradient so text always stays readable.
// RenderEffect blur needs API 31+; older devices just get the tinted gradient.
@Composable
private fun PlayerBackdrop(art: Any?) {
    val palette = LocalArtPalette.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.backdrop),
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Crossfade(targetState = art, animationSpec = tween(900), label = "backdropArt") { model ->
                if (model != null) {
                    AsyncImage(
                        model = model,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = 1.35f
                                scaleY = 1.35f
                                alpha = 0.55f
                            }
                            .blur(80.dp),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to PanelBackground.copy(alpha = 0.25f),
                        0.55f to PanelBackground.copy(alpha = 0.55f),
                        1f to PanelBackground.copy(alpha = 0.92f),
                    ),
                ),
        )
    }
}

@Composable
private fun PlayerTopBar(
    uiState: PanelUiState,
    lyricsVisible: Boolean,
    onToggleLyrics: () -> Unit,
    keepScreenOn: Boolean,
    onToggleKeepScreenOn: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Leaves room for the shell's home button in the top-right corner.
            .padding(end = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val label = if (uiState.connection == ConnectionState.CONNECTED && uiState.appName != null) {
            stringResource(R.string.playing_on, uiState.appName)
        } else null
        ConnectionPill(state = uiState.connection, label = label)
        Spacer(Modifier.weight(1f))
        CircleIconButton(
            icon = Icons.Rounded.Lyrics,
            contentDescription = stringResource(R.string.action_lyrics),
            onClick = onToggleLyrics,
            size = 44.dp,
            iconSize = 22.dp,
            tint = if (lyricsVisible) accent else PanelOnBackgroundMuted,
        )
        CircleIconButton(
            icon = Icons.Rounded.LightMode,
            contentDescription = stringResource(
                if (keepScreenOn) R.string.action_keep_screen_on_off else R.string.action_keep_screen_on,
            ),
            onClick = onToggleKeepScreenOn,
            size = 44.dp,
            iconSize = 22.dp,
            tint = if (keepScreenOn) accent else PanelOnBackgroundMuted,
        )
    }
}

// Double-tap (or the lyrics button) toggles a blurred cover with a
// scrolling lyrics list over it. The art eases down a little while paused,
// so play state reads at a glance from across the desk.
@Composable
private fun AlbumArt(
    art: Any?,
    size: Dp,
    playing: Boolean,
    lyricsVisible: Boolean,
    onToggleLyrics: () -> Unit,
    lyricsLines: List<LyricsLine>,
    currentLyricsIndex: Int,
) {
    val accent = MaterialTheme.colorScheme.primary
    val haptics = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        targetValue = if (playing || lyricsVisible) 1f else 0.9f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 220f),
        label = "artScale",
    )
    val shape = RoundedCornerShape(24.dp)
    val blurRadius by animateDpAsState(if (lyricsVisible) 24.dp else 0.dp, tween(300), label = "artBlur")

    Box(
        modifier = Modifier
            .size(size)
            .scale(scale)
            .shadow(elevation = 36.dp, shape = shape, ambientColor = accent, spotColor = accent)
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onToggleLyrics()
                })
            },
    ) {
        Crossfade(targetState = art, animationSpec = tween(500), label = "albumArt") { model ->
            ArtImage(
                model = model,
                shape = shape,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (blurRadius > 0.dp) Modifier.blur(blurRadius) else Modifier),
            )
        }
        AnimatedVisibility(
            visible = lyricsVisible,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(250)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f), shape),
                contentAlignment = Alignment.Center,
            ) {
                LyricsList(lyricsLines, currentLyricsIndex, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

// Karaoke-style scroller: the active line sits centered and full-size, the
// rest fade/shrink with distance from it, and the list glides to the new
// active line instead of just swapping text — reads as "following along"
// instead of a delayed jump-cut every time the line changes. Top and bottom
// edges fade out through a gradient mask instead of being hard-clipped.
@Composable
private fun LyricsList(lines: List<LyricsLine>, currentIndex: Int, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.no_lyrics_available),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(20.dp),
            )
        }
        return
    }

    val listState = rememberLazyListState()
    val safeIndex = currentIndex.coerceIn(0, lines.lastIndex)

    BoxWithConstraints(
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.18f to Color.Black,
                        0.82f to Color.Black,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        val containerHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
        // The ground truth for centering: the active line's own actual
        // rendered vertical center, in the LazyColumn's coordinate space,
        // reported by onGloballyPositioned. LazyListItemInfo.offset looked
        // like it should give the same number but didn't match what was
        // actually on screen (verified by comparing against a debug
        // reference line drawn at the true container center) — measuring
        // the real composed position sidesteps whatever assumption was
        // wrong there.
        var activeLineCenterY by remember(safeIndex) { mutableStateOf<Float?>(null) }

        LaunchedEffect(safeIndex) {
            listState.animateScrollToItem(index = safeIndex, scrollOffset = 0)
            // The jump above is fully settled at this point, but
            // activeLineCenterY may still hold a stale reading from
            // mid-animation (onGloballyPositioned fires on every layout
            // pass while scrolling, not just the final one). Clear it and
            // wait for the next report, which is guaranteed to reflect the
            // post-settle position.
            activeLineCenterY = null
            val centerY = snapshotFlow { activeLineCenterY }.filterNotNull().first()
            val delta = centerY - containerHeightPx / 2
            if (abs(delta) > 1f) listState.animateScrollBy(delta, tween(260))
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = maxHeight / 2),
        ) {
            itemsIndexed(lines) { index, line ->
                val distance = abs(index - safeIndex)
                val isCurrent = index == safeIndex
                val alpha by animateFloatAsState(
                    targetValue = when {
                        isCurrent -> 1f
                        distance == 1 -> 0.5f
                        distance == 2 -> 0.28f
                        else -> 0.14f
                    },
                    animationSpec = tween(260),
                    label = "lyricsLineAlpha",
                )
                val scale by animateFloatAsState(
                    targetValue = if (isCurrent) 1f else 0.86f,
                    animationSpec = tween(260),
                    label = "lyricsLineScale",
                )
                Text(
                    text = line.text.ifBlank { "♪" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isCurrent) {
                                Modifier.onGloballyPositioned { coords ->
                                    activeLineCenterY = coords.boundsInParent().center.y
                                }
                            } else {
                                Modifier
                            },
                        )
                        .graphicsLayer {
                            this.alpha = alpha
                            scaleX = scale
                            scaleY = scale
                        }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }
}

// Every line is locked to a single line so the block's total height never
// changes as text comes and goes — otherwise everything below it visibly
// jumps every time the track changes. Long titles scroll as a marquee
// instead of being cut off with an ellipsis.
@Composable
private fun TrackInfo(uiState: PanelUiState, textAlign: TextAlign) {
    val title = uiState.title.ifBlank { stringResource(R.string.no_track) }
    val subtitle = when {
        uiState.hasTrack -> listOf(uiState.artist, uiState.album).filter { it.isNotBlank() }.joinToString("  ·  ")
        uiState.connection == ConnectionState.CONNECTED -> stringResource(R.string.no_track_hint)
        else -> stringResource(R.string.waiting_for_agent)
    }
    val alignment = if (textAlign == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start

    AnimatedContent(
        targetState = title to subtitle,
        transitionSpec = {
            (fadeIn(tween(350, delayMillis = 90)) + slideInVertically(tween(350)) { it / 4 })
                .togetherWith(fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it / 4 })
        },
        modifier = Modifier.fillMaxWidth(),
        label = "trackInfo",
    ) { (shownTitle, shownSubtitle) ->
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
            Text(
                text = shownTitle,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = textAlign,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2_500),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = shownSubtitle,
                style = MaterialTheme.typography.titleMedium,
                color = PanelOnBackgroundMuted,
                textAlign = textAlign,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// Doubles as the seek control: tapping or dragging anywhere across the bars
// jumps to that fraction of the track. Bars left of the playhead take the
// accent color, bars to the right stay dim — same information a flat
// progress bar carried, riding on top of the live spectrum.
//
// The bars animate at display refresh rate with a fast attack and a slower
// release (like a hardware VU meter), interpolating between the agent's
// ~45 frames/sec. Drawing reads a frame counter only inside the draw phase,
// so this never triggers recomposition — and the loop parks itself once
// everything has decayed to silence.
@Composable
private fun SpectrumSeekBar(
    spectrum: StateFlow<FloatArray>,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    barAreaHeight: Dp,
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val haptics = LocalHapticFeedback.current

    val duration = durationMs.coerceAtLeast(0L)
    val progressFraction = when {
        isDragging -> dragFraction
        duration > 0 -> (positionMs.toFloat() / duration).coerceIn(0f, 1f)
        else -> 0f
    }
    val displayedPositionMs = if (isDragging) (dragFraction * duration).toLong() else positionMs

    val playedColor = MaterialTheme.colorScheme.primary
    val unplayedColor = Color.White.copy(alpha = 0.16f)

    val bars = remember { FloatArray(BAND_COUNT) }
    var frameTick by remember { mutableLongStateOf(0L) }
    val currentProgress by rememberUpdatedState(progressFraction)

    LaunchedEffect(spectrum) {
        var lastNanos = 0L
        while (true) {
            val target = spectrum.value
            val idle = bars.all { it < 0.002f } && target.all { it < 0.002f }
            if (idle) {
                // Nothing moving: park until the agent sends real audio again.
                spectrum.first { frame -> frame.any { it >= 0.002f } }
                lastNanos = 0L
            }
            withFrameNanos { now ->
                val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                lastNanos = now
                val latest = spectrum.value
                for (i in bars.indices) {
                    val t = latest.getOrElse(i) { 0f }
                    val rate = if (t > bars[i]) ATTACK_RATE else RELEASE_RATE
                    bars[i] += (t - bars[i]) * (rate * dt).coerceAtMost(1f)
                }
                frameTick = now
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(barAreaHeight)
                // Tap-or-drag in one gesture loop: two separate pointerInput blocks
                // (one with detectTapGestures, one with detectDragGestures) fight over
                // the same pointer stream and drag never wins, so both are handled here
                // together — down starts the scrub, every move updates it, up commits it.
                .pointerInput(duration) {
                    if (duration <= 0) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        isDragging = true
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        dragFraction = (down.position.x / size.width).coerceIn(0f, 1f)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                            if (!change.pressed) break
                        }
                        onSeek((dragFraction * duration).toLong())
                        isDragging = false
                    }
                },
        ) {
            @Suppress("UNUSED_EXPRESSION") frameTick // read only to invalidate the draw each frame
            val barCount = bars.size
            val gap = 3.dp.toPx()
            val barWidth = (size.width - gap * (barCount - 1)) / barCount
            val progressX = size.width * currentProgress
            val centerY = size.height / 2
            val minHalf = barWidth / 2

            for (index in 0 until barCount) {
                // Mirrored around the center line — reads like a waveform
                // scrubber rather than a bottom-heavy equalizer.
                val half = (size.height / 2 * bars[index].coerceIn(0f, 1f)).coerceAtLeast(minHalf)
                val x = index * (barWidth + gap)
                val played = x + barWidth / 2 <= progressX
                drawRoundRect(
                    color = if (played) playedColor else unplayedColor,
                    topLeft = Offset(x, centerY - half),
                    size = Size(barWidth, half * 2),
                    cornerRadius = CornerRadius(barWidth / 2),
                )
            }

            // Thin playhead so the exact position is visible even when the
            // bars are quiet.
            drawRoundRect(
                color = Color.White.copy(alpha = if (isDragging) 1f else 0.85f),
                topLeft = Offset((progressX - 1.5.dp.toPx()).coerceIn(0f, size.width - 3.dp.toPx()), 0f),
                size = Size(3.dp.toPx(), size.height),
                cornerRadius = CornerRadius(1.5.dp.toPx()),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatMs(displayedPositionMs),
                style = MaterialTheme.typography.labelMedium,
                color = if (isDragging) playedColor else PanelOnBackgroundMuted,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (duration > 0) "-" + formatMs(duration - displayedPositionMs) else "--:--",
                style = MaterialTheme.typography.labelMedium,
                color = PanelOnBackgroundSubtle,
            )
        }
    }
}

@Composable
private fun ControlsRow(uiState: PanelUiState, viewModel: PanelViewModel, compact: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val playSize = if (compact) 68.dp else 84.dp
    val skipSize = if (compact) 56.dp else 64.dp
    val sideSize = 48.dp

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        // Slots stay reserved even when the source app doesn't support
        // shuffle/repeat, so the transport buttons never shift sideways.
        if (uiState.shuffle != null) {
            CircleIconButton(
                icon = Icons.Rounded.Shuffle,
                contentDescription = stringResource(R.string.action_shuffle),
                onClick = viewModel::toggleShuffle,
                size = sideSize,
                iconSize = 22.dp,
                tint = if (uiState.shuffle) accent else PanelOnBackgroundSubtle,
            )
        } else {
            Spacer(Modifier.size(sideSize))
        }
        CircleIconButton(
            icon = Icons.Rounded.SkipPrevious,
            contentDescription = stringResource(R.string.action_previous),
            onClick = viewModel::prev,
            size = skipSize,
            iconSize = if (compact) 34.dp else 40.dp,
        )
        PlayPauseButton(playing = uiState.playing, size = playSize, onClick = viewModel::playPause)
        CircleIconButton(
            icon = Icons.Rounded.SkipNext,
            contentDescription = stringResource(R.string.action_next),
            onClick = viewModel::next,
            size = skipSize,
            iconSize = if (compact) 34.dp else 40.dp,
        )
        if (uiState.repeat != null) {
            CircleIconButton(
                icon = if (uiState.repeat == "track") Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                contentDescription = stringResource(R.string.action_repeat),
                onClick = viewModel::cycleRepeat,
                size = sideSize,
                iconSize = 22.dp,
                tint = if (uiState.repeat != "none") accent else PanelOnBackgroundSubtle,
            )
        } else {
            Spacer(Modifier.size(sideSize))
        }
    }
}

@Composable
private fun PlayPauseButton(playing: Boolean, size: Dp, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.action_play_pause)
    PressableCircle(
        onClick = onClick,
        size = size,
        container = accent,
        modifier = Modifier
            .shadow(elevation = 20.dp, shape = CircleShape, ambientColor = accent, spotColor = accent)
            .semantics { contentDescription = description },
    ) {
        // Icon swap with a quick scale pop so the state change is obvious.
        AnimatedContent(
            targetState = playing,
            transitionSpec = { (scaleIn(tween(160)) + fadeIn(tween(160))) togetherWith (scaleOut(tween(120)) + fadeOut(tween(120))) },
            label = "playPauseIcon",
        ) { isPlaying ->
            Icon(
                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

// Mute toggle + draggable volume track + percentage. Dragging sends
// absolute setVolume commands (throttled), replacing the old ±5% buttons
// as the primary control; the step buttons remain at either end for
// precise nudges.
@Composable
private fun VolumeRow(uiState: PanelUiState, viewModel: PanelViewModel) {
    val accent = MaterialTheme.colorScheme.primary
    val volume = uiState.volume?.toFloat()
    val muted = uiState.muted

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconButton(
            icon = when {
                muted || volume == 0f -> Icons.AutoMirrored.Rounded.VolumeOff
                (volume ?: 0f) < 0.5f -> Icons.AutoMirrored.Rounded.VolumeDown
                else -> Icons.AutoMirrored.Rounded.VolumeUp
            },
            contentDescription = stringResource(R.string.action_mute),
            onClick = viewModel::toggleMute,
            size = 44.dp,
            iconSize = 22.dp,
            tint = if (muted) accent else PanelOnBackgroundMuted,
        )
        Spacer(Modifier.width(8.dp))
        VolumeTrack(
            level = volume ?: 0f,
            enabled = volume != null,
            muted = muted,
            onLevelChange = viewModel::setVolume,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = volume?.let { "${(it * 100).toInt()}" } ?: "—",
            style = MaterialTheme.typography.labelLarge,
            color = PanelOnBackgroundMuted,
            textAlign = TextAlign.End,
            modifier = Modifier.width(32.dp),
        )
    }
}

@Composable
private fun VolumeTrack(
    level: Float,
    enabled: Boolean,
    muted: Boolean,
    onLevelChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    var dragging by remember { mutableStateOf(false) }
    val thickness by animateDpAsState(if (dragging) 12.dp else 6.dp, spring(stiffness = 500f), label = "volumeThickness")
    val shownLevel by animateFloatAsState(level, tween(if (dragging) 0 else 200), label = "volumeLevel")
    val currentOnChange by rememberUpdatedState(onLevelChange)

    Canvas(
        modifier = modifier
            .height(40.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    dragging = true
                    var lastSent = 0L
                    fun send(x: Float, force: Boolean) {
                        val now = System.currentTimeMillis()
                        if (force || now - lastSent >= VOLUME_SEND_INTERVAL_MS) {
                            lastSent = now
                            currentOnChange((x / size.width).coerceIn(0f, 1f))
                        }
                    }
                    send(down.position.x, force = true)
                    var lastX = down.position.x
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        lastX = change.position.x
                        if (!change.pressed) break
                        send(lastX, force = false)
                    }
                    send(lastX, force = true)
                    dragging = false
                }
            },
    ) {
        val h = thickness.toPx()
        val top = (size.height - h) / 2
        drawRoundRect(
            color = Color.White.copy(alpha = 0.14f),
            topLeft = Offset(0f, top),
            size = Size(size.width, h),
            cornerRadius = CornerRadius(h / 2),
        )
        val filled = size.width * shownLevel.coerceIn(0f, 1f)
        if (filled > 0f) {
            drawRoundRect(
                color = if (muted) PanelOnBackgroundSubtle else if (dragging) accent else accent.copy(alpha = 0.9f),
                topLeft = Offset(0f, top),
                size = Size(filled.coerceAtLeast(h), h),
                cornerRadius = CornerRadius(h / 2),
            )
        }
    }
}

private const val ATTACK_RATE = 28f
private const val RELEASE_RATE = 7f
private const val VOLUME_SEND_INTERVAL_MS = 45L
