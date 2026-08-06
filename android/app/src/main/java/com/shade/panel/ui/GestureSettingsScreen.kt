package com.shade.panel.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shade.panel.R
import com.shade.panel.data.NavScreen
import com.shade.panel.data.ShadePreferences
import com.shade.panel.ui.theme.PanelAccent
import com.shade.panel.ui.theme.PanelOnBackgroundMuted
import com.shade.panel.ui.theme.PanelSurface

@Composable
fun GestureSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { ShadePreferences(context) }

    var right by remember { mutableStateOf(preferences.swipeRight) }
    var left by remember { mutableStateOf(preferences.swipeLeft) }
    var up by remember { mutableStateOf(preferences.swipeUp) }
    var down by remember { mutableStateOf(preferences.swipeDown) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.gesture_settings_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.height(24.dp))

        DirectionSection(
            label = stringResource(R.string.gesture_direction_right),
            selected = right,
            onSelect = { right = it; preferences.swipeRight = it },
        )
        Spacer(Modifier.height(20.dp))
        DirectionSection(
            label = stringResource(R.string.gesture_direction_left),
            selected = left,
            onSelect = { left = it; preferences.swipeLeft = it },
        )
        Spacer(Modifier.height(20.dp))
        DirectionSection(
            label = stringResource(R.string.gesture_direction_up),
            selected = up,
            onSelect = { up = it; preferences.swipeUp = it },
        )
        Spacer(Modifier.height(20.dp))
        DirectionSection(
            label = stringResource(R.string.gesture_direction_down),
            selected = down,
            onSelect = { down = it; preferences.swipeDown = it },
        )
    }
}

@Composable
private fun DirectionSection(label: String, selected: NavScreen?, onSelect: (NavScreen?) -> Unit) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = PanelOnBackgroundMuted)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ScreenChip(stringResource(R.string.nav_screen_player), selected == NavScreen.PLAYER) { onSelect(NavScreen.PLAYER) }
            ScreenChip(stringResource(R.string.nav_screen_stats), selected == NavScreen.STATS) { onSelect(NavScreen.STATS) }
            ScreenChip(stringResource(R.string.nav_screen_clock), selected == NavScreen.CLOCK) { onSelect(NavScreen.CLOCK) }
            ScreenChip(stringResource(R.string.nav_screen_home), selected == NavScreen.HOME) { onSelect(NavScreen.HOME) }
            ScreenChip(stringResource(R.string.nav_screen_none), selected == null) { onSelect(null) }
        }
    }
}

@Composable
private fun ScreenChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = if (selected) PanelAccent.copy(alpha = 0.15f) else PanelSurface),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, color = MaterialTheme.colorScheme.onBackground)
            if (selected) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Filled.Check, contentDescription = null, tint = PanelAccent, modifier = Modifier.height(16.dp))
            }
        }
    }
}
