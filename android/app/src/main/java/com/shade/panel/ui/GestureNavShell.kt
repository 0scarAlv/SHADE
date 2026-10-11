package com.shade.panel.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shade.panel.R
import com.shade.panel.data.NavScreen
import com.shade.panel.data.ShadePreferences
import com.shade.panel.data.SwipeDirection
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot

// Switches between the three panel screens on a full-screen swipe, per the
// user's configured mapping in ShadePreferences/GestureSettingsScreen. The
// new screen slides in from the side the finger came from, so the mapping
// feels spatial. Also owns the single home button for the whole shell
// (auto-hidden on the clock, which is meant to be pure, glanceable chrome-
// free display) and a brief page indicator after each switch.
@Composable
fun GestureNavShell(viewModel: PanelViewModel, initialScreen: NavScreen, onExit: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { ShadePreferences(context) }
    val haptics = LocalHapticFeedback.current

    var current by rememberSaveable { mutableStateOf(initialScreen.takeUnless { it == NavScreen.HOME } ?: NavScreen.PLAYER) }
    var lastDirection by remember { mutableStateOf<SwipeDirection?>(null) }
    var navigationCount by remember { mutableIntStateOf(0) }

    BackHandler(onBack = onExit)

    val swipeThresholdPx = with(LocalDensity.current) { SWIPE_THRESHOLD.toPx() }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    // Any touch brings the chrome back; on the clock it fades away again.
    var lastTouchMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var chromeVisible by remember { mutableStateOf(true) }
    LaunchedEffect(lastTouchMs, current) {
        chromeVisible = true
        if (current == NavScreen.CLOCK) {
            delay(CHROME_AUTO_HIDE_MS)
            chromeVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Observes on the Initial pass without consuming, so child
                // gestures (seek, volume, lyrics) are unaffected.
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastTouchMs = System.currentTimeMillis()
                    }
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragOffset = Offset.Zero },
                    onDrag = { _, amount -> dragOffset += amount },
                    onDragEnd = {
                        val direction = resolveDirection(dragOffset, swipeThresholdPx)
                        val target = direction?.let { preferences.screenFor(it) }
                        if (target != null) {
                            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                            if (target == NavScreen.HOME) {
                                onExit()
                            } else if (target != current) {
                                lastDirection = direction
                                current = target
                                navigationCount++
                            }
                        }
                        dragOffset = Offset.Zero
                    },
                )
            },
    ) {
        AnimatedContent(
            targetState = current,
            transitionSpec = { transitionFor(lastDirection) },
            label = "panelScreen",
        ) { screen ->
            when (screen) {
                NavScreen.PLAYER -> PanelScreen(viewModel = viewModel)
                NavScreen.STATS -> ResourceScreen(viewModel = viewModel)
                NavScreen.CLOCK -> ClockScreen(viewModel = viewModel)
                // Never actually assigned to `current` — HOME is intercepted in
                // onDragEnd above and exits the shell instead. Kept here only so
                // this `when` stays exhaustive over NavScreen.
                NavScreen.HOME -> Unit
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(600)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 14.dp, end = 14.dp),
        ) {
            CircleIconButton(
                icon = Icons.Rounded.Home,
                contentDescription = stringResource(R.string.action_home),
                onClick = onExit,
                size = 44.dp,
                iconSize = 22.dp,
                container = Color.White.copy(alpha = 0.08f),
            )
        }

        PageIndicator(
            current = current,
            trigger = navigationCount,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(bottom = 10.dp),
        )
    }
}

// Shows which of the three screens is active for a moment after each swipe,
// then gets out of the way.
@Composable
private fun PageIndicator(current: NavScreen, trigger: Int, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        visible = true
        delay(1_600)
        visible = false
    }
    val accent = MaterialTheme.colorScheme.primary

    AnimatedVisibility(visible = visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(500)), modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(current.labelRes()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
            )
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PANEL_SCREENS.forEach { screen ->
                    val selected = screen == current
                    val width by animateDpAsState(if (selected) 20.dp else 6.dp, tween(250), label = "dotWidth")
                    val color by animateColorAsState(
                        if (selected) accent else Color.White.copy(alpha = 0.3f),
                        tween(250),
                        label = "dotColor",
                    )
                    Box(
                        modifier = Modifier
                            .height(6.dp)
                            .width(width)
                            .clip(CircleShape)
                            .background(color),
                    )
                }
            }
        }
    }
}

private val PANEL_SCREENS = listOf(NavScreen.PLAYER, NavScreen.STATS, NavScreen.CLOCK)

fun NavScreen.labelRes(): Int = when (this) {
    NavScreen.PLAYER -> R.string.nav_screen_player
    NavScreen.STATS -> R.string.nav_screen_stats
    NavScreen.CLOCK -> R.string.nav_screen_clock
    NavScreen.HOME -> R.string.nav_screen_home
}

// The incoming screen enters from the side the finger moved away from
// (swipe right -> it slides in from the left), with a short crossfade so
// the two backgrounds never show a hard seam.
private fun transitionFor(direction: SwipeDirection?): ContentTransform {
    val spec = tween<androidx.compose.ui.unit.IntOffset>(380)
    val fade = tween<Float>(300)
    return when (direction) {
        SwipeDirection.RIGHT -> (slideInHorizontally(spec) { -it / 3 } + fadeIn(fade))
            .togetherWith(slideOutHorizontally(spec) { it / 3 } + fadeOut(fade))
        SwipeDirection.LEFT -> (slideInHorizontally(spec) { it / 3 } + fadeIn(fade))
            .togetherWith(slideOutHorizontally(spec) { -it / 3 } + fadeOut(fade))
        SwipeDirection.UP -> (slideInVertically(spec) { it / 3 } + fadeIn(fade))
            .togetherWith(slideOutVertically(spec) { -it / 3 } + fadeOut(fade))
        SwipeDirection.DOWN -> (slideInVertically(spec) { -it / 3 } + fadeIn(fade))
            .togetherWith(slideOutVertically(spec) { it / 3 } + fadeOut(fade))
        null -> fadeIn(fade).togetherWith(fadeOut(fade))
    }
}

private val SWIPE_THRESHOLD = 80.dp
private const val CHROME_AUTO_HIDE_MS = 4_000L

private fun resolveDirection(offset: Offset, thresholdPx: Float): SwipeDirection? {
    if (hypot(offset.x, offset.y) < thresholdPx) return null
    return if (abs(offset.x) > abs(offset.y)) {
        if (offset.x > 0) SwipeDirection.RIGHT else SwipeDirection.LEFT
    } else {
        if (offset.y > 0) SwipeDirection.DOWN else SwipeDirection.UP
    }
}
