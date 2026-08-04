package com.shade.panel.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.shade.panel.R
import com.shade.panel.data.ConnectionState
import com.shade.panel.ui.theme.PanelError
import com.shade.panel.ui.theme.PanelOnBackgroundMuted
import com.shade.panel.ui.theme.PanelSpectrumIdle
import com.shade.panel.ui.theme.PanelWarning

@Composable
fun PanelScreen(viewModel: PanelViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()

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
                AlbumArt(uiState.artUrl, size = availableHeight - 48.dp)
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
                    Spacer(Modifier.height(12.dp))
                    VolumeRow(uiState, viewModel)
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
                AlbumArt(uiState.artUrl, size = 240.dp)
                Spacer(Modifier.height(20.dp))
                TrackInfo(uiState, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                SpectrumProgress(uiState, onSeek = viewModel::seek)
                Spacer(Modifier.height(20.dp))
                ControlsRow(uiState, viewModel)
                Spacer(Modifier.height(16.dp))
                VolumeRow(uiState, viewModel)
            }
        }
    }
}

@Composable
private fun AlbumArt(artUrl: String?, size: Dp) {
    AsyncImage(
        model = artUrl,
        contentDescription = null,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(16.dp)),
        contentScale = ContentScale.Crop,
    )
}

// Every line is locked to a fixed number of lines (title/artist/album to 1,
// lyrics to always reserving 2 via minLines) so the block's total height
// never changes as text comes and goes — otherwise everything below it
// (progress, controls, volume) visibly jumps every time the lyric line or
// the track changes.
@Composable
private fun TrackInfo(uiState: PanelUiState, textAlign: TextAlign) {
    Column(horizontalAlignment = if (textAlign == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start) {
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
        Spacer(Modifier.height(12.dp))
        Text(
            text = uiState.currentLyricsLine.orEmpty(),
            style = MaterialTheme.typography.bodyLarge,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.primary,
            textAlign = textAlign,
            minLines = 2,
            maxLines = 2,
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
                .pointerInput(durationMs) {
                    if (durationMs <= 0) return@pointerInput
                    detectTapGestures { offset ->
                        onSeek(((offset.x / size.width).coerceIn(0f, 1f) * durationMs).toLong())
                    }
                }
                .pointerInput(durationMs) {
                    if (durationMs <= 0) return@pointerInput
                    detectDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            onSeek((dragFraction * durationMs).toLong())
                            isDragging = false
                        },
                        onDragCancel = { isDragging = false },
                    ) { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
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
        IconButton(onClick = viewModel::prev, modifier = Modifier.size(64.dp)) {
            Icon(
                Icons.Filled.SkipPrevious,
                contentDescription = stringResource(R.string.action_previous),
                modifier = Modifier.size(36.dp),
            )
        }
        IconButton(onClick = viewModel::playPause, modifier = Modifier.size(80.dp)) {
            Icon(
                imageVector = if (uiState.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.action_play_pause),
                modifier = Modifier.size(48.dp),
            )
        }
        IconButton(onClick = viewModel::next, modifier = Modifier.size(64.dp)) {
            Icon(
                Icons.Filled.SkipNext,
                contentDescription = stringResource(R.string.action_next),
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

@Composable
private fun VolumeRow(uiState: PanelUiState, viewModel: PanelViewModel) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconButton(onClick = viewModel::volumeDown, modifier = Modifier.size(56.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.VolumeDown,
                contentDescription = stringResource(R.string.action_volume_down),
                modifier = Modifier.size(30.dp),
            )
        }
        Text(
            text = uiState.volume?.let { "${(it * 100).toInt()}%" } ?: "—",
            style = MaterialTheme.typography.labelLarge,
            color = PanelOnBackgroundMuted,
            modifier = Modifier.width(48.dp),
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = viewModel::volumeUp, modifier = Modifier.size(56.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = stringResource(R.string.action_volume_up),
                modifier = Modifier.size(30.dp),
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
