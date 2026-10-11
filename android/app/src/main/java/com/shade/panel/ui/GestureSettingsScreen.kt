package com.shade.panel.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shade.panel.R
import com.shade.panel.data.NavScreen
import com.shade.panel.data.ShadePreferences

@Composable
fun GestureSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { ShadePreferences(context) }
    BackHandler(onBack = onBack)

    var right by remember { mutableStateOf(preferences.swipeRight) }
    var left by remember { mutableStateOf(preferences.swipeLeft) }
    var up by remember { mutableStateOf(preferences.swipeUp) }
    var down by remember { mutableStateOf(preferences.swipeDown) }

    HifiPage(model = "Gesture", title = stringResource(R.string.gesture_settings_title), onBack = onBack) {
        DirectionGroup("→  " + stringResource(R.string.gesture_direction_right), right) { right = it; preferences.swipeRight = it }
        DirectionGroup("←  " + stringResource(R.string.gesture_direction_left), left) { left = it; preferences.swipeLeft = it }
        DirectionGroup("↑  " + stringResource(R.string.gesture_direction_up), up) { up = it; preferences.swipeUp = it }
        DirectionGroup("↓  " + stringResource(R.string.gesture_direction_down), down) { down = it; preferences.swipeDown = it }
    }
}

// One engraved group per swipe direction with a strip of destination
// buttons; the selected destination's LED is lit.
@Composable
private fun DirectionGroup(label: String, selected: NavScreen?, onSelect: (NavScreen?) -> Unit) {
    EngravedGroup(label) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Target("PLAY", stringResource(R.string.nav_screen_player), selected == NavScreen.PLAYER) { onSelect(NavScreen.PLAYER) }
            Target("SYS", stringResource(R.string.nav_screen_stats), selected == NavScreen.STATS) { onSelect(NavScreen.STATS) }
            Target("CLK", stringResource(R.string.nav_screen_clock), selected == NavScreen.CLOCK) { onSelect(NavScreen.CLOCK) }
            Target("HOME", stringResource(R.string.nav_screen_home), selected == NavScreen.HOME) { onSelect(NavScreen.HOME) }
            Target("OFF", stringResource(R.string.nav_screen_none), selected == null) { onSelect(null) }
        }
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun Target(cap: String, caption: String, selected: Boolean, onClick: () -> Unit) {
    HwButton(onClick = onClick, width = 64.dp, height = 40.dp, led = selected, caption = caption) {
        CapLabel(cap, 11.sp)
    }
}
