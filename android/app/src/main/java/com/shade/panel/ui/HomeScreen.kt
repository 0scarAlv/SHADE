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
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shade.panel.R
import com.shade.panel.ui.theme.PanelOnBackgroundMuted
import com.shade.panel.ui.theme.PanelSurface

private const val TOTAL_TILES = 6

// Dashboard for the panel: tile 1 is the real music controller, the rest are
// placeholders reserved for whatever gets built next (clock, notifications,
// quick actions, ...). Adaptive columns so it reflows sanely in landscape too.
@Composable
fun HomeScreen(onOpenPlayer: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
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
            items(TOTAL_TILES - 1) {
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
