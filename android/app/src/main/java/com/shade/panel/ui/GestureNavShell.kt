package com.shade.panel.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shade.panel.R
import com.shade.panel.data.NavScreen
import com.shade.panel.data.ShadePreferences
import com.shade.panel.data.SwipeDirection
import kotlin.math.abs
import kotlin.math.hypot

// Owns the single PanelViewModel/transport connection and switches between
// the three panel screens on a full-screen swipe, per the user's configured
// mapping in ShadePreferences/GestureSettingsScreen. Also owns the shared
// back button that used to live inside PanelScreen — one exit point for the
// whole shell instead of one per screen.
@Composable
fun GestureNavShell(onExit: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { ShadePreferences(context) }
    val viewModel: PanelViewModel = viewModel(factory = panelViewModelFactory(context))

    var current by rememberSaveable { mutableStateOf(NavScreen.PLAYER) }

    BackHandler(onBack = onExit)

    val swipeThresholdPx = with(LocalDensity.current) { SWIPE_THRESHOLD.toPx() }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragOffset = Offset.Zero },
                    onDrag = { _, amount -> dragOffset += amount },
                    onDragEnd = {
                        resolveDirection(dragOffset, swipeThresholdPx)
                            ?.let { preferences.screenFor(it) }
                            ?.let { current = it }
                        dragOffset = Offset.Zero
                    },
                )
            },
    ) {
        when (current) {
            NavScreen.PLAYER -> PanelScreen(viewModel = viewModel)
            NavScreen.STATS -> ResourceScreen(viewModel = viewModel)
            NavScreen.CLOCK -> ClockScreen()
        }

        // Same spot PanelScreen's back button used to occupy: top-right, a bit
        // lower than the very edge, easier to reach with a thumb once the
        // phone is mounted as a fixed panel.
        IconButton(
            onClick = onExit,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 8.dp).size(48.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
        }
    }
}

private val SWIPE_THRESHOLD = 80.dp

private fun resolveDirection(offset: Offset, thresholdPx: Float): SwipeDirection? {
    if (hypot(offset.x, offset.y) < thresholdPx) return null
    return if (abs(offset.x) > abs(offset.y)) {
        if (offset.x > 0) SwipeDirection.RIGHT else SwipeDirection.LEFT
    } else {
        if (offset.y > 0) SwipeDirection.DOWN else SwipeDirection.UP
    }
}
