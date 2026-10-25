package com.shade.panel.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Slide-to-switch key: a track with a thumb that has to be dragged to the far
// end, so switching between player and deck never happens by a stray tap.
// `towardsStart` flips the direction (thumb rests right, slides left).
@Composable
fun SlideKey(
    label: String,
    onSlide: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 132.dp,
    height: Dp = 38.dp,
    towardsStart: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val currentOnSlide by rememberUpdatedState(onSlide)
    val ink = LocalInk.current
    val shape = RoundedCornerShape(12.dp)
    val thumb = height - 8.dp
    val travelPx = with(LocalDensity.current) { (width - thumb - 8.dp).toPx() }
    // 0 = at rest, 1 = slid all the way.
    val progress = remember { Animatable(0f) }

    Box(
        modifier
            .size(width, height)
            .clip(shape)
            .background(Ink.Key)
            .border(1.dp, Ink.PanelBorder, shape)
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick { currentOnSlide(); true }
            }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    val step = (if (towardsStart) -delta else delta) / travelPx
                    scope.launch { progress.snapTo((progress.value + step).coerceIn(0f, 1f)) }
                },
                onDragStopped = {
                    if (progress.value > 0.85f) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        progress.animateTo(1f)
                        currentOnSlide()
                        progress.snapTo(0f)
                    } else {
                        progress.animateTo(0f, spring(stiffness = 600f))
                    }
                },
            ),
    ) {
        // The label fades as the thumb covers it.
        Box(
            Modifier
                .fillMaxHeight()
                .padding(start = if (towardsStart) 12.dp else thumb + 14.dp, end = if (towardsStart) thumb + 14.dp else 12.dp)
                .align(if (towardsStart) Alignment.CenterStart else Alignment.CenterEnd)
                .alpha(1f - progress.value),
            contentAlignment = Alignment.Center,
        ) {
            CapLabel(if (towardsStart) "‹ $label" else "$label ›", 11.sp, color = Ink.TextMuted)
        }
        val x = (progress.value * travelPx).roundToInt()
        Box(
            Modifier
                .align(if (towardsStart) Alignment.CenterEnd else Alignment.CenterStart)
                .offset { IntOffset(if (towardsStart) -x else x, 0) }
                .padding(4.dp)
                .size(thumb)
                .clip(RoundedCornerShape(9.dp))
                .background(ink.copy(alpha = 0.25f + 0.6f * progress.value))
                .border(1.dp, ink.copy(alpha = 0.8f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Dot(on = true, color = Ink.Text, size = 5.dp)
        }
    }
}
