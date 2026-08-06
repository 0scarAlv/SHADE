package com.shade.panel.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shade.panel.R
import com.shade.panel.data.ResourceMessage
import com.shade.panel.ui.theme.PanelAccent
import com.shade.panel.ui.theme.PanelError
import com.shade.panel.ui.theme.PanelOnBackgroundMuted
import com.shade.panel.ui.theme.PanelSpectrumIdle
import com.shade.panel.ui.theme.PanelSurface
import com.shade.panel.ui.theme.PanelWarning
import kotlin.math.roundToInt

// Speedtest.net-flavored dashboard: three equal rings (RAM / down / up) side
// by side. Landscape is this screen's primary orientation (the panel is
// meant to sit mounted sideways) — portrait gets the same content, just
// smaller rings, rather than a different layout.
@Composable
fun ResourceScreen(viewModel: PanelViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val resource = uiState.resource

    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        val isLandscape = maxWidth > maxHeight
        val ringSize = if (isLandscape) 148.dp else 96.dp

        if (resource == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.resource_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PanelOnBackgroundMuted,
                )
            }
            return@BoxWithConstraints
        }

        Column(modifier = Modifier.fillMaxSize()) {
            if (resource.hasBattery) {
                BatteryBadge(resource, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(16.dp))
            }
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                RingRow(resource, ringSize)
            }
        }
    }
}

@Composable
private fun RingRow(resource: ResourceMessage, ringSize: Dp) {
    val usedFraction = if (resource.ramTotalBytes > 0) {
        (resource.ramUsedBytes.toFloat() / resource.ramTotalBytes.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        GaugeRing(
            size = ringSize,
            icon = Icons.Filled.Memory,
            label = stringResource(R.string.resource_ram),
            valueText = "${(usedFraction * 100).roundToInt()}%",
            detailText = "${formatBytes(resource.ramUsedBytes)} / ${formatBytes(resource.ramTotalBytes)}",
            fraction = usedFraction,
            ringColor = levelColor(usedFraction),
        )
        GaugeRing(
            size = ringSize,
            icon = Icons.Filled.ArrowDownward,
            label = stringResource(R.string.resource_network_down),
            valueText = formatBytesPerSec(resource.netDownBytesPerSec),
            detailText = null,
            fraction = null,
            ringColor = PanelAccent,
        )
        GaugeRing(
            size = ringSize,
            icon = Icons.Filled.ArrowUpward,
            label = stringResource(R.string.resource_network_up),
            valueText = formatBytesPerSec(resource.netUpBytesPerSec),
            detailText = null,
            fraction = null,
            ringColor = PanelAccent,
        )
    }
}

// fraction == null draws a full decorative ring (down/up have no natural
// 100% ceiling to be proportional against) — fraction != null draws a
// proportional arc from the top, clockwise (RAM's actual used%).
@Composable
private fun GaugeRing(
    size: Dp,
    icon: ImageVector,
    label: String,
    valueText: String,
    detailText: String?,
    fraction: Float?,
    ringColor: Color,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = size.toPx() * 0.09f
                val inset = strokeWidth / 2
                val arcSize = Size(this.size.width - strokeWidth, this.size.height - strokeWidth)
                val topLeft = Offset(inset, inset)
                val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)

                drawArc(
                    color = PanelSpectrumIdle,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )
                val sweep = (fraction?.coerceIn(0f, 1f) ?: 1f) * 360f
                if (sweep > 0f) {
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = stroke,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, contentDescription = null, tint = ringColor, modifier = Modifier.size(size * 0.16f))
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (detailText != null) {
                    Text(text = detailText, style = MaterialTheme.typography.labelSmall, color = PanelOnBackgroundMuted)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = PanelOnBackgroundMuted)
    }
}

@Composable
private fun BatteryBadge(resource: ResourceMessage, modifier: Modifier = Modifier) {
    val charging = resource.batteryCharging == true
    val percent = resource.batteryPercent ?: 0

    Card(
        colors = CardDefaults.cardColors(containerColor = PanelSurface),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (charging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryFull,
                contentDescription = null,
                tint = levelColor(percent / 100f),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "${stringResource(R.string.resource_battery)}: $percent%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

// Green under 70% used, amber under 90%, red above.
private fun levelColor(fraction: Float) = when {
    fraction < 0.7f -> PanelAccent
    fraction < 0.9f -> PanelWarning
    else -> PanelError
}

private fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> "%.1f GB".format(gb)
        mb >= 1 -> "%.0f MB".format(mb)
        else -> "%.0f KB".format(kb)
    }
}

private fun formatBytesPerSec(bytesPerSec: Double): String {
    val kbps = bytesPerSec / 1024.0
    val mbps = kbps / 1024.0
    return when {
        mbps >= 1 -> "%.1f MB/s".format(mbps)
        else -> "%.0f KB/s".format(kbps)
    }
}
