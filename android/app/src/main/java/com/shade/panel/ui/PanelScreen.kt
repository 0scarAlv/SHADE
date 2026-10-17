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

// Player laid out like an audio component, rendered flat: cover art, a
// display panel with source/state annunciators, a large elapsed-time
// readout, dot-matrix title/artist, a segmented seek bar and the spectrum,
// then a row of transport keys and the volume dial.
@Composable
fun PanelScreen(viewModel: PanelViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val settings = LocalAppSettings.current
    val burnIn = rememberBurnInOffset(settings.burnInProtection, maxShift = 3.dp)
    var lyricsVisible by remember { mutableStateOf(false) }

    ScreenBase(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .offset(burnIn.x, burnIn.y)
                .padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            val landscape = maxWidth > maxHeight
            val strip = @Composable {
                TopStrip(
                    model = "SP-1 · Digital media panel",
                    modifier = Modifier.fillMaxWidth().padding(end = 56.dp),
                ) { StatusDots(uiState) }
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
                        ArtFrame(uiState.art, artSize, onDoubleTap = toggleLyrics)
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
                    ArtFrame(uiState.art, artSize, onDoubleTap = toggleLyrics)
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
private fun StatusDots(uiState: PanelUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(
            label = if (uiState.transport == Transport.BLUETOOTH) "BT" else "USB",
            on = uiState.connection == ConnectionState.CONNECTED,
            color = when (uiState.connection) {
                ConnectionState.CONNECTED -> LocalInk.current
                ConnectionState.CONNECTING -> Color(0xFFFFB13B)
                ConnectionState.DISCONNECTED -> Color(0xFFFF4B3E)
            },
        )
        StatusDot("Play", on = uiState.playing)
    }
}

// Cover art, plain and rounded. Double-tap toggles lyrics.
@Composable
private fun ArtFrame(art: Any?, artSize: Dp, onDoubleTap: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .size(artSize)
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onDoubleTap()
                })
            },
    ) {
        Crossfade(targetState = art, animationSpec = tween(500), label = "art") { model ->
            ArtImage(model = model, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun PlayerDisplay(uiState: PanelUiState, viewModel: PanelViewModel, lyricsVisible: Boolean, modifier: Modifier) {
    val cyber = cyberOn()
    val ink = LocalInk.current
    val runner = rememberFrameClock(cyber && uiState.playing)
    val displayModifier = if (cyber) {
        modifier.cyberDisplay(
            phase = { (runner.value % 4_000L) / 4_000f },
            running = uiState.playing,
            head = CyberWhite,
            tail = neonOf(ink),
            corner = 16.dp,
        )
    } else modifier
    DisplayPanel(modifier = displayModifier) {
        Column(Modifier.fillMaxSize()) {
            // Indicator row: source, transport state, mode annunciators.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DotText("SRC", 11.sp, weight = FontWeight.Medium)
                DotText(
                    (uiState.appName ?: if (uiState.connection == ConnectionState.CONNECTED) "PC" else "----").uppercase(),
                    13.sp,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                DotText(if (uiState.playing) "PLAY" else "PAUSE", 12.sp, lit = uiState.hasTrack)
                DotText("SHUF", 11.sp, lit = uiState.shuffle == true)
                DotText(if (uiState.repeat == "track") "RPT 1" else "RPT", 11.sp, lit = uiState.repeat != null && uiState.repeat != "none")
                DotText("MUTE", 11.sp, lit = uiState.muted)
            }
            Spacer(Modifier.height(10.dp))

            if (lyricsVisible) {
                LyricsReadout(uiState.lyricsLines, uiState.currentLyricsIndex, Modifier.fillMaxWidth().weight(1f), cyber)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Readout(text = formatClock(uiState.positionMs), size = 40.sp, template = "88:88", lit = uiState.hasTrack)
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        val title = uiState.title.ifBlank {
                            stringResource(if (uiState.connection == ConnectionState.CONNECTED) R.string.no_track else R.string.waiting_short)
                        }
                        AnimatedContent(
                            targetState = title.uppercase() to listOf(uiState.artist, uiState.album).filter { it.isNotBlank() }.joinToString(" · ").uppercase(),
                            transitionSpec = {
                                (fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 3 })
                                    .togetherWith(fadeOut(tween(150)))
                            },
                            label = "displayTitle",
                        ) { (shownTitle, shownArtist) ->
                            if (cyber) {
                                Column {
                                    CyberText(
                                        shownTitle,
                                        24.sp,
                                        weight = FontWeight.Black,
                                        ambient = uiState.playing,
                                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2_000, velocity = 40.dp),
                                    )
                                    CyberText(
                                        shownArtist.ifBlank { " " },
                                        15.sp,
                                        weight = FontWeight.Bold,
                                        ambient = uiState.playing,
                                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 3_000, velocity = 30.dp),
                                    )
                                }
                                return@AnimatedContent
                            }
                            Column {
                                DotText(
                                    shownTitle,
                                    24.sp,
                                    weight = FontWeight.Black,
                                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2_000, velocity = 40.dp),
                                )
                                DotText(
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
                    cyber = cyber,
                    pulse = uiState.playing,
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth()) {
                    DotText("TIME", 10.sp, weight = FontWeight.Medium, lit = false)
                    Spacer(Modifier.weight(1f))
                    DotText(if (duration > 0) "-" + formatClock(duration - uiState.positionMs) else "--:--", 11.sp, weight = FontWeight.Medium)
                }
                Spacer(Modifier.height(8.dp))
                BarSpectrum(viewModel.spectrum, modifier = Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

// Karaoke on the display: previous line dim above, current line bright and
// large, next line dim below — the whole block rolls up on each change.
@Composable
private fun LyricsReadout(lines: List<LyricsLine>, currentIndex: Int, modifier: Modifier, cyber: Boolean) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (lines.isEmpty()) {
            DotText(stringResource(R.string.no_lyrics_available).uppercase(), 18.sp)
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
                DotText(lines.getOrNull(i - 1)?.text.orEmpty().ifBlank { " " }, 14.sp, lit = false, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                val current = (lines.getOrNull(i)?.text ?: "♪").ifBlank { "♪" }
                if (cyber) {
                    CyberText(current, 24.sp, weight = FontWeight.Black, maxLines = 2, burst = false)
                } else {
                    DotText(current, 24.sp, weight = FontWeight.Black, maxLines = 2)
                }
                Spacer(Modifier.height(6.dp))
                DotText(lines.getOrNull(i + 1)?.text.orEmpty().ifBlank { " " }, 14.sp, lit = false, maxLines = 1)
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
            KeyButton(onClick = viewModel::prev, caption = "Prev", contentDescription = stringResource(R.string.action_previous)) {
                TransportGlyph(Glyph.PREV)
            }
            KeyButton(
                onClick = viewModel::playPause,
                width = 84.dp,
                active = uiState.playing,
                caption = "Play/Pause",
                contentDescription = stringResource(R.string.action_play_pause),
            ) { TransportGlyph(Glyph.PLAY_PAUSE) }
            KeyButton(onClick = viewModel::next, caption = "Next", contentDescription = stringResource(R.string.action_next)) {
                TransportGlyph(Glyph.NEXT)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (uiState.shuffle != null) {
                KeyButton(onClick = viewModel::toggleShuffle, width = 52.dp, active = uiState.shuffle, caption = "Shuffle") { CapLabel("SHF", 11.sp) }
            }
            if (uiState.repeat != null) {
                KeyButton(onClick = viewModel::cycleRepeat, width = 52.dp, active = uiState.repeat != "none", caption = "Repeat") {
                    CapLabel(if (uiState.repeat == "track") "RPT1" else "RPT", 11.sp)
                }
            }
            KeyButton(onClick = onToggleLyrics, width = 52.dp, active = lyricsVisible, caption = "Lyric") { CapLabel("TXT", 11.sp) }
            KeyButton(
                onClick = { settings.keepScreenOn = !settings.keepScreenOn },
                width = 52.dp,
                active = settings.keepScreenOn,
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
    val mute = @Composable {
        KeyButton(onClick = viewModel::toggleMute, width = 64.dp, height = 36.dp, active = uiState.muted, caption = "Mute") {
            CapLabel("MUTE", 10.sp)
        }
    }
    if (horizontal) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            knob()
            mute()
        }
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            knob()
            Spacer(Modifier.height(10.dp))
            mute()
        }
    }
}

private enum class Glyph { PREV, NEXT, PLAY_PAUSE }

// Transport symbols drawn as plain geometry rather than icon-font glyphs.
@Composable
private fun TransportGlyph(glyph: Glyph) {
    val color = Ink.Text
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

// "m:ss"; the readout right-aligns it inside an "88:88"-wide field.
private fun formatClock(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = (totalSeconds / 60).coerceAtMost(99)
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
