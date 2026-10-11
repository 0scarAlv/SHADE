package com.shade.panel.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.shade.panel.R
import com.shade.panel.data.ConnectionState
import com.shade.panel.ui.theme.PanelError
import com.shade.panel.ui.theme.PanelOnBackgroundMuted
import com.shade.panel.ui.theme.PanelOnBackgroundSubtle
import com.shade.panel.ui.theme.PanelOutline
import com.shade.panel.ui.theme.PanelSurface
import com.shade.panel.ui.theme.PanelSurfaceHigh
import com.shade.panel.ui.theme.PanelWarning
import kotlinx.coroutines.delay

// Shared building blocks so every screen speaks the same visual language:
// rounded 20-24dp cards with a hairline border, accent only where something
// is active, and a consistent header with a back affordance.

@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, subtitle: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircleIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.action_back),
            onClick = onBack,
            size = 48.dp,
            iconSize = 22.dp,
            container = PanelSurface,
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = PanelOnBackgroundMuted)
            }
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = PanelOnBackgroundSubtle,
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

@Composable
fun ShadeCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    shape: Shape = RoundedCornerShape(22.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val border = if (selected) BorderStroke(1.5.dp, accent.copy(alpha = 0.7f)) else BorderStroke(1.dp, PanelOutline.copy(alpha = 0.6f))
    val container = if (selected) accent.copy(alpha = 0.10f) else PanelSurface
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = 600f), label = "cardPress")
    val haptics = LocalHapticFeedback.current

    Surface(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = ripple()) {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onClick()
                    }
                } else Modifier,
            ),
        shape = shape,
        color = container,
        border = border,
    ) {
        Column(content = content)
    }
}

// Round button with a springy press and a haptic tick — the panel is
// usually operated without looking closely, so feedback has to be felt too.
@Composable
fun PressableCircle(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    container: Color = Color.Transparent,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, spring(dampingRatio = 0.5f, stiffness = 700f), label = "buttonPress")
    val haptics = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(container)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true),
                enabled = enabled,
                role = Role.Button,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    iconSize: Dp = 26.dp,
    container: Color = Color.Transparent,
    tint: Color = MaterialTheme.colorScheme.onBackground,
    enabled: Boolean = true,
) {
    PressableCircle(onClick = onClick, modifier = modifier, size = size, container = container, enabled = enabled) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.3f),
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
fun ToggleRow(icon: ImageVector, title: String, subtitle: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    ShadeCard(onClick = { onCheckedChange(!checked) }, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(icon, tint = if (checked) accent else PanelOnBackgroundMuted)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = PanelOnBackgroundMuted)
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = accent,
                    uncheckedThumbColor = PanelOnBackgroundMuted,
                    uncheckedTrackColor = PanelSurfaceHigh,
                    uncheckedBorderColor = PanelOutline,
                ),
            )
        }
    }
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color, size: Dp = 40.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

// Small "● Conectado" pill. The dot pulses while (re)connecting so a stuck
// reconnect loop still looks alive rather than frozen.
@Composable
fun ConnectionPill(state: ConnectionState, label: String? = null, modifier: Modifier = Modifier) {
    val color = when (state) {
        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
        ConnectionState.CONNECTING -> PanelWarning
        ConnectionState.DISCONNECTED -> PanelError
    }
    val text = label ?: stringResource(
        when (state) {
            ConnectionState.CONNECTED -> R.string.status_connected
            ConnectionState.CONNECTING -> R.string.status_connecting
            ConnectionState.DISCONNECTED -> R.string.status_disconnected
        },
    )
    val pulse = rememberInfiniteTransition(label = "pillPulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pillPulseAlpha",
    )

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.07f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .graphicsLayer { alpha = if (state == ConnectionState.CONNECTED) 1f else pulseAlpha }
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Cover art with a tasteful placeholder for "nothing playing" / art still
// loading, instead of an empty transparent box.
@Composable
fun ArtImage(model: Any?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(16.dp)) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(listOf(accent.copy(alpha = 0.35f), PanelSurfaceHigh)),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.35f),
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
            delay(60_000)
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

fun formatMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
