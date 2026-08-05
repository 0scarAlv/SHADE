package com.shade.panel.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import com.shade.panel.R
import com.shade.panel.data.ConnectionState
import com.shade.panel.data.ShadeBluetoothTransport
import com.shade.panel.data.ShadePreferences
import com.shade.panel.data.ShadeSocket
import com.shade.panel.data.ShadeTransport
import com.shade.panel.data.Transport
import com.shade.panel.ui.theme.PanelError
import com.shade.panel.ui.theme.PanelOnBackgroundMuted
import com.shade.panel.ui.theme.PanelSpectrumIdle
import com.shade.panel.ui.theme.PanelWarning

// Picks the transport based on the user's saved preference (see
// BluetoothSettingsScreen) so PanelViewModel doesn't need to know how it's
// being reached — USB/WebSocket by default, Bluetooth once a PC is paired.
private fun panelViewModelFactory(context: Context) = viewModelFactory {
    initializer {
        val preferences = ShadePreferences(context)
        val transport: ShadeTransport = if (preferences.transport == Transport.BLUETOOTH) {
            ShadeBluetoothTransport(context) { preferences.pairedDeviceAddress }
        } else {
            ShadeSocket()
        }
        PanelViewModel(transport)
    }
}

@Composable
fun PanelScreen(
    viewModel: PanelViewModel = viewModel(factory = panelViewModelFactory(LocalContext.current)),
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val preferences = remember { ShadePreferences(context) }
    var keepScreenOn by remember { mutableStateOf(preferences.keepScreenOn) }

    BackHandler(onBack = onBack)

    // Plain window flag, no permission involved — only keeps the screen on
    // while this screen is visible, and only while the toggle is on.
    val view = LocalView.current
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isLandscape = maxWidth > maxHeight
        val availableHeight = maxHeight

        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                AlbumArt(uiState.artBytes ?: uiState.artUrl, size = availableHeight - 48.dp, lyricsLine = uiState.currentLyricsLine)
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    ConnectionBadge(uiState.connection)
                    Spacer(Modifier.height(12.dp))
                    TrackInfo(uiState, textAlign = TextAlign.Start)
                    Spacer(Modifier.height(16.dp))
                    SpectrumProgress(uiState, onSeek = viewModel::seek)
                    Spacer(Modifier.height(20.dp))
                    ControlsRow(uiState, viewModel)
                    Spacer(Modifier.height(4.dp))
                    VolumeRow(uiState, viewModel, keepScreenOn) {
                        keepScreenOn = !keepScreenOn
                        preferences.keepScreenOn = keepScreenOn
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ConnectionBadge(uiState.connection)
                Spacer(Modifier.height(20.dp))
                AlbumArt(uiState.artBytes ?: uiState.artUrl, size = 240.dp, lyricsLine = uiState.currentLyricsLine)
                Spacer(Modifier.height(20.dp))
                TrackInfo(uiState, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                SpectrumProgress(uiState, onSeek = viewModel::seek)
                Spacer(Modifier.height(20.dp))
                ControlsRow(uiState, viewModel)
                Spacer(Modifier.height(6.dp))
                VolumeRow(uiState, viewModel, keepScreenOn) {
                    keepScreenOn = !keepScreenOn
                    preferences.keepScreenOn = keepScreenOn
                }
            }
        }

        // Top-right (and lower than the very edge) instead of top-left: easier
        // to reach with a thumb once the phone is mounted as a fixed panel.
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 8.dp).size(48.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
        }
    }
}

// Double-tap toggles a blurred cover with the current lyric line over it —
// lets people who want lyrics have them without the info block below
// growing/shrinking every time a line changes (see TrackInfo), and people
// who don't want them just never double-tap.
@Composable
private fun AlbumArt(art: Any?, size: Dp, lyricsLine: String?) {
    var lyricsVisible by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { lyricsVisible = !lyricsVisible })
            },
    ) {
        AsyncImage(
            model = art,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .then(if (lyricsVisible) Modifier.blur(20.dp) else Modifier),
            contentScale = ContentScale.Crop,
        )
        AnimatedVisibility(
            visible = lyricsVisible,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(220)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = lyricsLine?.takeIf { it.isNotBlank() } ?: stringResource(R.string.no_lyrics_available),
                    style = MaterialTheme.typography.titleMedium,
                    fontStyle = FontStyle.Italic,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }
    }
}

// Every line is locked to a single line (maxLines = 1) so the block's total
// height never changes as text comes and goes — otherwise everything below
// it (progress, controls, volume) visibly jumps every time the track changes.
@Composable
private fun TrackInfo(uiState: PanelUiState, textAlign: TextAlign) {
    // fillMaxWidth is what actually keeps this block still — maxLines alone
    // only pins the height. Without a fixed width, a Column just wraps to its
    // widest line, so the whole block still grows/shrinks sideways (and jumps
    // around, since it's centered) every time the title or the lyric changes.
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (textAlign == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Text(
            text = uiState.title.ifBlank { stringResource(R.string.no_track) },
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = textAlign,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = uiState.artist,
            style = MaterialTheme.typography.bodyMedium,
            color = PanelOnBackgroundMuted,
            textAlign = textAlign,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = uiState.album,
            style = MaterialTheme.typography.bodySmall,
            color = PanelOnBackgroundMuted,
            textAlign = textAlign,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Doubles as the seek control: tapping or dragging anywhere across the bars
// jumps to that fraction of the track. Bars left of the playhead are tinted
// with the accent color, bars to the right stay dim — same information a
// flat progress bar carried, just riding on top of the live spectrum.
@Composable
private fun SpectrumProgress(uiState: PanelUiState, onSeek: (Long) -> Unit) {
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val durationMs = uiState.durationMs.coerceAtLeast(0L)
    val progressFraction = when {
        isDragging -> dragFraction
        durationMs > 0 -> (uiState.positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        else -> 0f
    }
    val displayedPositionMs = if (isDragging) (dragFraction * durationMs).toLong() else uiState.positionMs

    val playedColor = MaterialTheme.colorScheme.primary
    val unplayedColor = PanelSpectrumIdle
    val bands = uiState.spectrumBands

    Column(modifier = Modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                // Tap-or-drag in one gesture loop: two separate pointerInput blocks
                // (one with detectTapGestures, one with detectDragGestures) fight over
                // the same pointer stream and drag never wins, so both are handled here
                // together — down starts the scrub, every move updates it, up commits it.
                .pointerInput(durationMs) {
                    if (durationMs <= 0) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        isDragging = true
                        dragFraction = (down.position.x / size.width).coerceIn(0f, 1f)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                            if (!change.pressed) break
                        }
                        onSeek((dragFraction * durationMs).toLong())
                        isDragging = false
                    }
                },
        ) {
            val barCount = bands.size.coerceAtLeast(1)
            val gap = 3.dp.toPx()
            val barWidth = (size.width - gap * (barCount - 1)) / barCount
            val progressX = size.width * progressFraction

            bands.forEachIndexed { index, magnitude ->
                val barHeight = size.height * magnitude.coerceIn(0.06f, 1f)
                val x = index * (barWidth + gap)
                drawRoundRect(
                    color = if (x <= progressX) playedColor else unplayedColor,
                    topLeft = Offset(x, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatMs(displayedPositionMs), style = MaterialTheme.typography.labelSmall, color = PanelOnBackgroundMuted)
            Text(formatMs(durationMs), style = MaterialTheme.typography.labelSmall, color = PanelOnBackgroundMuted)
        }
    }
}

@Composable
private fun ControlsRow(uiState: PanelUiState, viewModel: PanelViewModel) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        IconButton(onClick = viewModel::prev, modifier = Modifier.size(84.dp)) {
            Icon(
                Icons.Filled.SkipPrevious,
                contentDescription = stringResource(R.string.action_previous),
                modifier = Modifier.size(48.dp),
            )
        }
        IconButton(onClick = viewModel::playPause, modifier = Modifier.size(108.dp)) {
            Icon(
                imageVector = if (uiState.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.action_play_pause),
                modifier = Modifier.size(64.dp),
            )
        }
        IconButton(onClick = viewModel::next, modifier = Modifier.size(84.dp)) {
            Icon(
                Icons.Filled.SkipNext,
                contentDescription = stringResource(R.string.action_next),
                modifier = Modifier.size(48.dp),
            )
        }
    }
}

@Composable
private fun VolumeRow(uiState: PanelUiState, viewModel: PanelViewModel, keepScreenOn: Boolean, onToggleKeepScreenOn: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconButton(onClick = viewModel::volumeDown, modifier = Modifier.size(80.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.VolumeDown,
                contentDescription = stringResource(R.string.action_volume_down),
                modifier = Modifier.size(44.dp),
            )
        }
        Text(
            text = uiState.volume?.let { "${(it * 100).toInt()}%" } ?: "—",
            style = MaterialTheme.typography.titleMedium,
            color = PanelOnBackgroundMuted,
            modifier = Modifier.width(56.dp),
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = viewModel::volumeUp, modifier = Modifier.size(80.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = stringResource(R.string.action_volume_up),
                modifier = Modifier.size(44.dp),
            )
        }
        IconButton(onClick = onToggleKeepScreenOn, modifier = Modifier.size(80.dp)) {
            Icon(
                if (keepScreenOn) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                contentDescription = stringResource(
                    if (keepScreenOn) R.string.action_keep_screen_on_off else R.string.action_keep_screen_on,
                ),
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

@Composable
private fun ConnectionBadge(state: ConnectionState) {
    val (textRes, color) = when (state) {
        ConnectionState.CONNECTED -> R.string.status_connected to MaterialTheme.colorScheme.primary
        ConnectionState.CONNECTING -> R.string.status_connecting to PanelWarning
        ConnectionState.DISCONNECTED -> R.string.status_disconnected to PanelError
    }
    Text(stringResource(textRes), color = color, style = MaterialTheme.typography.labelMedium)
}

private fun formatMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
