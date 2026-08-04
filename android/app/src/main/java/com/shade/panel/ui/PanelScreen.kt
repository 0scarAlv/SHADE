package com.shade.panel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.shade.panel.R
import com.shade.panel.data.ConnectionState
import com.shade.panel.ui.theme.PanelError
import com.shade.panel.ui.theme.PanelOnBackgroundMuted
import com.shade.panel.ui.theme.PanelSurface
import com.shade.panel.ui.theme.PanelWarning

@Composable
fun PanelScreen(viewModel: PanelViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ConnectionBadge(uiState.connection)

            Spacer(Modifier.height(24.dp))

            AsyncImage(
                model = uiState.artUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(240.dp)
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop,
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = uiState.title.ifBlank { stringResource(R.string.no_track) },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = uiState.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = PanelOnBackgroundMuted,
            )
            Text(
                text = uiState.album,
                style = MaterialTheme.typography.bodySmall,
                color = PanelOnBackgroundMuted,
            )

            Spacer(Modifier.height(16.dp))

            LinearProgressIndicator(
                progress = {
                    if (uiState.durationMs > 0) uiState.positionMs.toFloat() / uiState.durationMs else 0f
                },
                modifier = Modifier.fillMaxWidth(),
                trackColor = PanelSurface,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatMs(uiState.positionMs), style = MaterialTheme.typography.labelSmall, color = PanelOnBackgroundMuted)
                Text(formatMs(uiState.durationMs), style = MaterialTheme.typography.labelSmall, color = PanelOnBackgroundMuted)
            }

            Spacer(Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                IconButton(onClick = viewModel::prev) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.action_previous))
                }
                IconButton(onClick = viewModel::playPause) {
                    Icon(
                        imageVector = if (uiState.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.action_play_pause),
                    )
                }
                IconButton(onClick = viewModel::next) {
                    Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.action_next))
                }
            }
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
