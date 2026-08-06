package com.shade.panel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SwipeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.shade.panel.R
import com.shade.panel.ui.theme.PanelOnBackgroundMuted
import com.shade.panel.ui.theme.PanelSurface

private const val TOTAL_TILES = 6

// Same threshold GestureNavShell uses for its own swipe detection — kept in
// sync by convention rather than shared, since the two screens' gesture
// detectors serve different destinations (this one only opens the player).
private val SWIPE_UP_THRESHOLD = 80.dp

// Dashboard for the panel: tile 1 is the real music controller, the rest are
// placeholders reserved for whatever gets built next (clock, notifications,
// quick actions, ...). Adaptive columns so it reflows sanely in landscape too.
@Composable
fun HomeScreen(onOpenPlayer: () -> Unit, onOpenConnection: () -> Unit, onOpenGestureSettings: () -> Unit) {
    val swipeThresholdPx = with(LocalDensity.current) { SWIPE_UP_THRESHOLD.toPx() }
    var overscroll by remember { mutableFloatStateOf(0f) }
    // Rides on the grid's own nested-scroll dispatch instead of a competing
    // drag detector: the grid consumes the scroll normally, and only the
    // leftover it can't consume (i.e. already at the bottom) reaches here —
    // that's exactly "keep swiping past the end of the list".
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0f) overscroll += available.y
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (-overscroll > swipeThresholdPx) onOpenPlayer()
                overscroll = 0f
                return Velocity.Zero
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .nestedScroll(nestedScrollConnection),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(20.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                HomeTile(
                    title = stringResource(R.string.tile_player),
                    icon = Icons.Filled.MusicNote,
                    enabled = true,
                    onClick = onOpenPlayer,
                )
            }
            item {
                HomeTile(
                    title = stringResource(R.string.tile_connection),
                    icon = Icons.Filled.Bluetooth,
                    enabled = true,
                    onClick = onOpenConnection,
                )
            }
            item {
                HomeTile(
                    title = stringResource(R.string.tile_gestures),
                    icon = Icons.Filled.SwipeUp,
                    enabled = true,
                    onClick = onOpenGestureSettings,
                )
            }
            items(TOTAL_TILES - 3) {
                HomeTile(
                    title = stringResource(R.string.tile_coming_soon),
                    icon = Icons.Filled.Add,
                    enabled = false,
                    onClick = {},
                )
            }
        }
    }
}

@Composable
private fun HomeTile(title: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        colors = CardDefaults.cardColors(containerColor = PanelSurface),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = if (enabled) MaterialTheme.colorScheme.primary else PanelOnBackgroundMuted,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) MaterialTheme.colorScheme.onBackground else PanelOnBackgroundMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}
