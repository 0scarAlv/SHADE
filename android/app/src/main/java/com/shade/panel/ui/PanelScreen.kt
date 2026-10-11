package com.shade.panel.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.shade.panel.R
import com.shade.panel.data.ConnectionState
import com.shade.panel.data.LyricsLine
import com.shade.panel.data.Transport

// The player as a stereo component: cover art in a recessed bezel, a VFD
// window carrying source/state indicators, a seven-segment elapsed time,
// dot-matrix title/artist, a segmented seek bar and the spectrum display,
// then a deck of physical transport buttons and a rotary volume knob.
@Composable
fun PanelScreen(viewModel: PanelViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val settings = LocalAppSettings.current
    val burnIn = rememberBurnInOffset(settings.burnInProtection, maxShift = 3.dp)
    var lyricsVisible by remember { mutableStateOf(false) }

    Faceplate(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .offset(burnIn.x, burnIn.y)
                .padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            val landscape = maxWidth > maxHeight
            val strip = @Composable {
                BrandStrip(
                    model = "SP-1 · Digital media panel",
                    modifier = Modifier.fillMaxWidth().padding(end = 56.dp),
                ) { StatusLeds(uiState) }
            }
            val toggleLyrics = { lyricsVisible = !lyricsVisible }

            if (landscape) {
                val deckHeight = 72.dp
                val mainHeight = maxHeight - deckHeight - 44.dp
                val artSize = min(mainHeight, maxWidth * 0.3f)
                Column(Modifier.fillMaxSize()) {
                    strip()
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth().height(mainHeight), verticalAlignment = Alignment.CenterVertically) {
                        ArtBezel(uiState.art, artSize, onDoubleTap = toggleLyrics)
                        Spacer(Modifier.width(16.dp))
                        PlayerDisplay(uiState, viewModel, lyricsVisible, Modifier.weight(1f).fillMaxHeight())
                        Spacer(Modifier.width(16.dp))
                        KnobColumn(uiState, viewModel, knobSize = min(mainHeight - 40.dp, 132.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    TransportDeck(uiState, viewModel, lyricsVisible, toggleLyrics, Modifier.fillMaxWidth())
                }
            } else {
                val artSize = min(maxWidth, maxHeight * 0.32f)
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    strip()
                    Spacer(Modifier.height(14.dp))
                    ArtBezel(uiState.art, artSize, onDoubleTap = toggleLyrics)
                    Spacer(Modifier.height(14.dp))
                    PlayerDisplay(uiState, viewModel, lyricsVisible, Modifier.fillMaxWidth().weight(1f))
                    Spacer(Modifier.height(16.dp))
                    TransportDeck(uiState, viewModel, lyricsVisible, toggleLyrics, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KnobColumn(uiState, viewModel, knobSize = 112.dp, horizontal = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLeds(uiState: PanelUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        LedLabel(
            label = if (uiState.transport == Transport.BLUETOOTH) "BT" else "USB",
            on = uiState.connection == ConnectionState.CONNECTED,
            color = when (uiState.connection) {
                ConnectionState.CONNECTED -> LocalVfd.current
                ConnectionState.CONNECTING -> Color(0xFFFFB13B)
                ConnectionState.DISCONNECTED -> Color(0xFFFF4B3E)
            },
        )
        LedLabel("Play", on = uiState.playing)
    }
}

// Cover art seated in a black bezel with a thin inner lip, like the window
// of a cassette deck. Double-tap still toggles lyrics.
@Composable
private fun ArtBezel(art: Any?, artSize: Dp, onDoubleTap: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .size(artSize)
            .clip(RoundedCornerShape(6.dp))
            .background(Hifi.Bezel)
            .padding(4.dp)
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onDoubleTap()
                })
            },
    ) {
        Crossfade(targetState = art, animationSpec = tween(500), label = "art") { model ->
            ArtImage(
                model = model,
                shape = RoundedCornerShape(3.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        drawContent()
                        // Faint glass reflection over the cover.
                        drawRect(
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                0f to Color.White.copy(alpha = 0.08f),
                                0.4f to Color.Transparent,
                                start = Offset.Zero,
                                end = Offset(size.width, size.height),
                            ),
                        )
                    },
            )
        }
    }
}

@Composable
private fun PlayerDisplay(uiState: PanelUiState, viewModel: PanelViewModel, lyricsVisible: Boolean, modifier: Modifier) {
    VfdWindow(modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            // Indicator row: source, transport state, mode annunciators.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                VfdText("SRC", 11.sp, weight = FontWeight.Medium)
                VfdText(
                    (uiState.appName ?: if (uiState.connection == ConnectionState.CONNECTED) "PC" else "----").uppercase(),
                    13.sp,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                VfdText(if (uiState.playing) "PLAY" else "PAUSE", 12.sp, lit = uiState.hasTrack)
                VfdText("SHUF", 11.sp, lit = uiState.shuffle == true)
                VfdText(if (uiState.repeat == "track") "RPT 1" else "RPT", 11.sp, lit = uiState.repeat != null && uiState.repeat != "none")
                VfdText("MUTE", 11.sp, lit = uiState.muted)
            }
            Spacer(Modifier.height(10.dp))

            if (lyricsVisible) {
                LyricsReadout(uiState.lyricsLines, uiState.currentLyricsIndex, Modifier.fillMaxWidth().weight(1f))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SegmentReadout(text = formatClock(uiState.positionMs), size = 40.sp, ghost = "88:88", lit = uiState.hasTrack)
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        val title = uiState.title.ifBlank {
                            stringResource(if (uiState.connection == ConnectionState.CONNECTED) R.string.no_track else R.string.waiting_short)
                        }
                        AnimatedContent(
                            targetState = title.uppercase() to uiState.artist.uppercase(),
                            transitionSpec = {
                                (fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 3 })
                                    .togetherWith(fadeOut(tween(150)))
                            },
                            label = "vfdTitle",
                        ) { (shownTitle, shownArtist) ->
                            Column {
                                VfdText(
                                    shownTitle,
                                    24.sp,
                                    weight = FontWeight.Black,
                                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2_000, velocity = 40.dp),
                                )
                                VfdText(
                                    shownArtist.ifBlank { " " },
                                    15.sp,
                                    weight = FontWeight.Bold,
                                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 3_000, velocity = 30.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                val duration = uiState.durationMs
                SegmentBar(
                    fraction = if (duration > 0) uiState.positionMs.toFloat() / duration else 0f,
                    segments = 60,
                    modifier = Modifier.fillMaxWidth().height(9.dp),
                    onSeek = if (duration > 0) { f -> viewModel.seek((f * duration).toLong()) } else null,
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth()) {
                    VfdText("TIME", 10.sp, weight = FontWeight.Medium, lit = false)
                    Spacer(Modifier.weight(1f))
                    VfdText(if (duration > 0) "-" + formatClock(duration - uiState.positionMs) else "--:--", 11.sp, weight = FontWeight.Medium)
                }
                Spacer(Modifier.height(8.dp))
                SegmentSpectrum(viewModel.spectrum, modifier = Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

// Karaoke on the VFD: previous line dim above, current line bright and
// large, next line dim below — the whole block rolls up on each change.
@Composable
private fun LyricsReadout(lines: List<LyricsLine>, currentIndex: Int, modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (lines.isEmpty()) {
            VfdText(stringResource(R.string.no_lyrics_available).uppercase(), 18.sp)
            return@Box
        }
        val index = currentIndex.coerceIn(-1, lines.lastIndex)
        AnimatedContent(
            targetState = index,
            transitionSpec = {
                (slideInVertically(tween(320)) { it / 3 } + fadeIn(tween(320)))
                    .togetherWith(slideOutVertically(tween(320)) { -it / 3 } + fadeOut(tween(200)))
            },
            label = "lyricsLine",
        ) { i ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                VfdText(lines.getOrNull(i - 1)?.text.orEmpty().ifBlank { " " }, 14.sp, lit = false, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                VfdText(
                    (lines.getOrNull(i)?.text ?: "♪").ifBlank { "♪" },
                    24.sp,
                    weight = FontWeight.Black,
                    maxLines = 2,
                )
                Spacer(Modifier.height(6.dp))
                VfdText(lines.getOrNull(i + 1)?.text.orEmpty().ifBlank { " " }, 14.sp, lit = false, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TransportDeck(
    uiState: PanelUiState,
    viewModel: PanelViewModel,
    lyricsVisible: Boolean,
    onToggleLyrics: () -> Unit,
    modifier: Modifier,
) {
    val settings = LocalAppSettings.current
    Row(modifier = modifier, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HwButton(onClick = viewModel::prev, caption = "Prev", contentDescription = stringResource(R.string.action_previous)) {
                TransportGlyph(Glyph.PREV)
            }
            HwButton(
                onClick = viewModel::playPause,
                width = 84.dp,
                led = uiState.playing,
                caption = "Play/Pause",
                contentDescription = stringResource(R.string.action_play_pause),
            ) { TransportGlyph(Glyph.PLAY_PAUSE) }
            HwButton(onClick = viewModel::next, caption = "Next", contentDescription = stringResource(R.string.action_next)) {
                TransportGlyph(Glyph.NEXT)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (uiState.shuffle != null) {
                HwButton(onClick = viewModel::toggleShuffle, width = 52.dp, led = uiState.shuffle, caption = "Shuffle") { CapLabel("SHF", 11.sp) }
            }
            if (uiState.repeat != null) {
                HwButton(onClick = viewModel::cycleRepeat, width = 52.dp, led = uiState.repeat != "none", caption = "Repeat") {
                    CapLabel(if (uiState.repeat == "track") "RPT1" else "RPT", 11.sp)
                }
            }
            HwButton(onClick = onToggleLyrics, width = 52.dp, led = lyricsVisible, caption = "Lyric") { CapLabel("TXT", 11.sp) }
            HwButton(
                onClick = { settings.keepScreenOn = !settings.keepScreenOn },
                width = 52.dp,
                led = settings.keepScreenOn,
                caption = "Hold",
                contentDescription = stringResource(R.string.action_keep_screen_on),
            ) { CapLabel("DSP", 11.sp) }
        }
    }
}

@Composable
private fun KnobColumn(uiState: PanelUiState, viewModel: PanelViewModel, knobSize: Dp, horizontal: Boolean = false) {
    val volume = uiState.volume?.toFloat()
    val knob = @Composable {
        VolumeKnob(
            level = volume ?: 0f,
            enabled = volume != null,
            muted = uiState.muted,
            onLevelChange = viewModel::setVolume,
            size = knobSize,
        )
    }
    val readout = @Composable {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Silk("Volume")
            Spacer(Modifier.height(4.dp))
            VfdWindow(contentPadding = 6.dp) {
                SegmentReadout(volume?.let { "${(it * 100).toInt()}" } ?: "--", 18.sp, ghost = "888", lit = !uiState.muted)
            }
            Spacer(Modifier.height(8.dp))
            HwButton(onClick = viewModel::toggleMute, width = 56.dp, height = 34.dp, led = uiState.muted, ledColor = Color(0xFFFF4B3E), caption = "Mute") {
                CapLabel("MUTE", 10.sp)
            }
        }
    }
    if (horizontal) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            knob()
            readout()
        }
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            knob()
            Spacer(Modifier.height(4.dp))
            readout()
        }
    }
}

private enum class Glyph { PREV, NEXT, PLAY_PAUSE }

// Transport symbols drawn as plain geometry in silkscreen color, the way
// they're printed on real deck buttons.
@Composable
private fun TransportGlyph(glyph: Glyph) {
    val color = Color(0xFFCFC8BC)
    Canvas(Modifier.size(width = if (glyph == Glyph.PLAY_PAUSE) 34.dp else 24.dp, height = 14.dp)) {
        val h = size.height
        fun triangle(x: Float, w: Float, pointRight: Boolean) {
            val path = Path().apply {
                if (pointRight) {
                    moveTo(x, 0f); lineTo(x + w, h / 2); lineTo(x, h)
                } else {
                    moveTo(x + w, 0f); lineTo(x, h / 2); lineTo(x + w, h)
                }
                close()
            }
            drawPath(path, color)
        }
        val bar = h * 0.22f
        when (glyph) {
            Glyph.PREV -> {
                drawRect(color, Offset(0f, 0f), Size(bar, h))
                triangle(bar, h * 0.75f, pointRight = false)
                triangle(bar + h * 0.75f, h * 0.75f, pointRight = false)
            }
            Glyph.NEXT -> {
                triangle(0f, h * 0.75f, pointRight = true)
                triangle(h * 0.75f, h * 0.75f, pointRight = true)
                drawRect(color, Offset(h * 1.5f, 0f), Size(bar, h))
            }
            Glyph.PLAY_PAUSE -> {
                triangle(0f, h * 0.85f, pointRight = true)
                val x = h * 1.25f
                drawLine(color, Offset(x - h * 0.2f, h), Offset(x + h * 0.15f, 0f), 2f)
                drawRect(color, Offset(x + h * 0.4f, 0f), Size(bar, h))
                drawRect(color, Offset(x + h * 0.4f + bar * 1.8f, 0f), Size(bar, h))
            }
        }
    }
}

// "mm:ss" with minutes space-padded (DSEG's "!" blank), so the readout
// sits right-aligned over its "88:88" ghost.
private fun formatClock(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = (totalSeconds / 60).coerceAtMost(99)
    val seconds = totalSeconds % 60
    return "%2d:%02d".format(minutes, seconds).replace(' ', '!')
}
