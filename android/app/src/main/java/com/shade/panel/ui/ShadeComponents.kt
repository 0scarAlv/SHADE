package com.shade.panel.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

// Pieces shared by the Hi-Fi screens and the clock.

// Cover art with a placeholder for "nothing playing" / art still loading,
// instead of an empty transparent box.
@Composable
fun ArtImage(model: Any?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(16.dp)) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF26231F), Color(0xFF0E0D0C)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.18f),
            modifier = Modifier.fillMaxSize(0.38f),
        )
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

// Burn-in guard for an always-on OLED panel: every minute, nudge the static
// layout to the next point of a small 3x3 grid (±maxShift). The move is
// animated slowly enough that it's imperceptible in normal use.
@Composable
fun rememberBurnInOffset(enabled: Boolean, maxShift: Dp = 6.dp): DpOffset {
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(enabled) {
        if (!enabled) {
            step = 0
            return@LaunchedEffect
        }
        while (true) {
            kotlinx.coroutines.delay(60_000)
            step = (step + 1) % BURN_IN_PATTERN.size
        }
    }
    val (fx, fy) = if (enabled) BURN_IN_PATTERN[step] else 0 to 0
    val x by animateDpAsState(maxShift * fx, tween(4_000), label = "burnInX")
    val y by animateDpAsState(maxShift * fy, tween(4_000), label = "burnInY")
    return DpOffset(x, y)
}

private val BURN_IN_PATTERN = listOf(
    0 to 0, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1,
)
