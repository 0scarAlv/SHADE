package com.shade.panel.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shade.panel.R
import com.shade.panel.data.NavScreen
import com.shade.panel.data.ShadePreferences
import com.shade.panel.ui.theme.PanelOnBackgroundMuted
import com.shade.panel.ui.theme.PanelOutline

@Composable
fun GestureSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { ShadePreferences(context) }
    BackHandler(onBack = onBack)

    var right by remember { mutableStateOf(preferences.swipeRight) }
    var left by remember { mutableStateOf(preferences.swipeLeft) }
    var up by remember { mutableStateOf(preferences.swipeUp) }
    var down by remember { mutableStateOf(preferences.swipeDown) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
            ScreenHeader(
                title = stringResource(R.string.gesture_settings_title),
                subtitle = stringResource(R.string.gesture_settings_subtitle),
                onBack = onBack,
            )
            Spacer(Modifier.height(28.dp))

            DirectionCard(
                icon = Icons.AutoMirrored.Rounded.ArrowForward,
                label = stringResource(R.string.gesture_direction_right),
                selected = right,
                onSelect = { right = it; preferences.swipeRight = it },
            )
            Spacer(Modifier.height(10.dp))
            DirectionCard(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                label = stringResource(R.string.gesture_direction_left),
                selected = left,
                onSelect = { left = it; preferences.swipeLeft = it },
            )
            Spacer(Modifier.height(10.dp))
            DirectionCard(
                icon = Icons.Rounded.ArrowUpward,
                label = stringResource(R.string.gesture_direction_up),
                selected = up,
                onSelect = { up = it; preferences.swipeUp = it },
            )
            Spacer(Modifier.height(10.dp))
            DirectionCard(
                icon = Icons.Rounded.ArrowDownward,
                label = stringResource(R.string.gesture_direction_down),
                selected = down,
                onSelect = { down = it; preferences.swipeDown = it },
            )
        }
    }
}

@Composable
private fun DirectionCard(icon: ImageVector, label: String, selected: NavScreen?, onSelect: (NavScreen?) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    ShadeCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon, tint = if (selected != null) accent else PanelOnBackgroundMuted, size = 36.dp)
                Spacer(Modifier.width(12.dp))
                Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
            }
            Spacer(Modifier.height(12.dp))
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
}

@Composable
private fun ScreenChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val container by animateColorAsState(if (selected) accent else Color.Transparent, tween(200), label = "chipContainer")
    val content by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground,
        tween(200),
        label = "chipContent",
    )
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = content,
        modifier = Modifier
            .clip(CircleShape)
            .background(container)
            .border(1.dp, if (selected) Color.Transparent else PanelOutline, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}
