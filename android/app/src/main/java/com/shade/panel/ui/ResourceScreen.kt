package com.shade.panel.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shade.panel.R
import com.shade.panel.data.ProcessEntry
import com.shade.panel.data.ResourceMessage
import com.shade.panel.ui.theme.PanelAccent
import com.shade.panel.ui.theme.PanelCyan
import com.shade.panel.ui.theme.PanelError
import com.shade.panel.ui.theme.PanelOnBackgroundMuted
import com.shade.panel.ui.theme.PanelSpectrumIdle
import com.shade.panel.ui.theme.PanelSurface
import com.shade.panel.ui.theme.PanelWarning
import kotlin.math.roundToInt

// Task Manager-flavored dashboard: each metric is a scrolling line graph
// (~60s rolling window, one sample per second — matches the agent's own tick
// rate) instead of the old speedtest-style rings, so trends over time are
// visible instead of just an instantaneous number.
private const val HISTORY_SIZE = 60

@Composable
fun ResourceScreen(viewModel: PanelViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val resource = uiState.resource

    val cpuHistory = remember { mutableStateListOf<Float>() }
    val ramHistory = remember { mutableStateListOf<Float>() }
    val netDownHistory = remember { mutableStateListOf<Float>() }
    val netUpHistory = remember { mutableStateListOf<Float>() }
    val diskReadHistory = remember { mutableStateListOf<Float>() }
    val diskWriteHistory = remember { mutableStateListOf<Float>() }

    LaunchedEffect(resource) {
        val r = resource ?: return@LaunchedEffect
        val ramFraction = if (r.ramTotalBytes > 0) {
            (r.ramUsedBytes.toFloat() / r.ramTotalBytes.toFloat()).coerceIn(0f, 1f)
        } else 0f
        pushHistory(cpuHistory, (r.cpuUsagePercent.toFloat() / 100f).coerceIn(0f, 1f))
        pushHistory(ramHistory, ramFraction)
        pushHistory(netDownHistory, r.netDownBytesPerSec.toFloat())
        pushHistory(netUpHistory, r.netUpBytesPerSec.toFloat())
        pushHistory(diskReadHistory, r.diskReadBytesPerSec.toFloat())
        pushHistory(diskWriteHistory, r.diskWriteBytesPerSec.toFloat())
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (resource == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.resource_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PanelOnBackgroundMuted,
                )
            }
            return@Box
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            if (resource.hasBattery) {
                BatteryBadge(resource, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(16.dp))
            }

            val usedFraction = if (resource.ramTotalBytes > 0) {
                (resource.ramUsedBytes.toFloat() / resource.ramTotalBytes.toFloat()).coerceIn(0f, 1f)
            } else 0f

            GraphRow(
                a = {
                    GraphCard(
                        icon = Icons.Filled.Speed,
                        label = stringResource(R.string.resource_cpu),
                        valueText = "${resource.cpuUsagePercent.roundToInt()}%",
                        detailText = null,
                        history = cpuHistory,
                        maxValue = 1f,
                        color = levelColor(resource.cpuUsagePercent.toFloat() / 100f),
                        onClick = { viewModel.requestProcesses("cpu") },
                    )
                },
                b = {
                    GraphCard(
                        icon = Icons.Filled.Memory,
                        label = stringResource(R.string.resource_ram),
                        valueText = "${(usedFraction * 100).roundToInt()}%",
                        detailText = "${formatBytes(resource.ramUsedBytes)} / ${formatBytes(resource.ramTotalBytes)}",
                        history = ramHistory,
                        maxValue = 1f,
                        color = levelColor(usedFraction),
                        onClick = { viewModel.requestProcesses("ram") },
                    )
                },
            )
            Spacer(Modifier.height(12.dp))
            GraphRow(
                a = {
                    GraphCard(
                        icon = Icons.Filled.ArrowDownward,
                        label = stringResource(R.string.resource_network_down),
                        valueText = formatBytesPerSec(resource.netDownBytesPerSec),
                        detailText = null,
                        history = netDownHistory,
                        maxValue = autoScaleMax(netDownHistory),
                        color = PanelAccent,
                        onClick = null,
                    )
                },
                b = {
                    GraphCard(
                        icon = Icons.Filled.ArrowUpward,
                        label = stringResource(R.string.resource_network_up),
                        valueText = formatBytesPerSec(resource.netUpBytesPerSec),
                        detailText = null,
                        history = netUpHistory,
                        maxValue = autoScaleMax(netUpHistory),
                        color = PanelAccent,
                        onClick = null,
                    )
                },
            )
            Spacer(Modifier.height(12.dp))
            GraphRow(
                a = {
                    GraphCard(
                        icon = Icons.Filled.Storage,
                        label = stringResource(R.string.resource_disk_read),
                        valueText = formatBytesPerSec(resource.diskReadBytesPerSec),
                        detailText = null,
                        history = diskReadHistory,
                        maxValue = autoScaleMax(diskReadHistory),
                        color = PanelAccent,
                        onClick = null,
                    )
                },
                b = {
                    GraphCard(
                        icon = Icons.Filled.Storage,
                        label = stringResource(R.string.resource_disk_write),
                        valueText = formatBytesPerSec(resource.diskWriteBytesPerSec),
                        detailText = null,
                        history = diskWriteHistory,
                        maxValue = autoScaleMax(diskWriteHistory),
                        color = PanelAccent,
                        onClick = null,
                    )
                },
            )
        }

        val drilldownMetric = uiState.processDrilldownMetric
        if (drilldownMetric != null) {
            ProcessDrilldownOverlay(
                metric = drilldownMetric,
                entries = uiState.processDrilldownEntries,
                onRefresh = { viewModel.requestProcesses(drilldownMetric) },
                onDismiss = { viewModel.dismissProcessDrilldown() },
            )
        }
    }
}

private fun pushHistory(history: MutableList<Float>, value: Float) {
    history.add(value)
    if (history.size > HISTORY_SIZE) history.removeAt(0)
}

// Network/disk throughput has no natural 100% ceiling like RAM/CPU do, so
// (like Windows' own Task Manager) the graph scales to whatever the busiest
// moment in the visible window was, with a little headroom so the peak
// sample doesn't touch the top edge, and a floor so an idle graph doesn't
// blow up to fill the height chasing tiny fluctuations.
private fun autoScaleMax(history: List<Float>): Float {
    val peak = history.maxOrNull() ?: 0f
    return (peak * 1.15f).coerceAtLeast(256f * 1024f)
}

@Composable
private fun GraphRow(a: @Composable () -> Unit, b: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(modifier = Modifier.weight(1f)) { a() }
        Box(modifier = Modifier.weight(1f)) { b() }
    }
}

@Composable
private fun GraphCard(
    icon: ImageVector,
    label: String,
    valueText: String,
    detailText: String?,
    history: List<Float>,
    maxValue: Float,
    color: Color,
    onClick: (() -> Unit)?,
) {
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Card(
        colors = CardDefaults.cardColors(containerColor = PanelSurface),
        shape = RoundedCornerShape(16.dp),
        modifier = clickModifier,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = PanelOnBackgroundMuted,
                    modifier = Modifier.weight(1f),
                )
                Text(text = valueText, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
            }
            if (detailText != null) {
                Text(text = detailText, style = MaterialTheme.typography.labelSmall, color = PanelOnBackgroundMuted)
            }
            Spacer(Modifier.height(6.dp))
            LineGraph(
                history = history,
                maxValue = maxValue,
                color = color,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            )
        }
    }
}

// Windows Task Manager-style line graph: faint horizontal gridlines, a
// gradient-filled area under the line that fades to nothing toward the
// bottom, and the line itself right-aligned so the newest sample is always
// at the right edge (matches the agent's ~1 sample/sec cadence scrolling
// left as it ages).
@Composable
private fun LineGraph(history: List<Float>, maxValue: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val gridColor = PanelSpectrumIdle.copy(alpha = 0.5f)
        val gridDivisions = 3
        for (i in 1 until gridDivisions) {
            val y = h * i / gridDivisions
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx())
        }

        if (history.size < 2) return@Canvas

        val safeMax = maxValue.coerceAtLeast(0.0001f)
        val stepX = w / (HISTORY_SIZE - 1).toFloat()
        val startIndex = HISTORY_SIZE - history.size
        val points = history.mapIndexed { i, value ->
            val x = (startIndex + i) * stepX
            val y = h - (value / safeMax).coerceIn(0f, 1f) * h
            Offset(x, y)
        }

        val linePath = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (p in points.drop(1)) lineTo(p.x, p.y)
        }
        val areaPath = Path().apply {
            addPath(linePath)
            lineTo(points.last().x, h)
            lineTo(points.first().x, h)
            close()
        }

        drawPath(
            path = areaPath,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0f)),
                startY = 0f,
                endY = h,
            ),
        )
        drawPath(
            path = linePath,
            color = color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun BatteryBadge(resource: ResourceMessage, modifier: Modifier = Modifier) {
    val charging = resource.batteryCharging == true
    val percent = resource.batteryPercent ?: 0
    val color = batteryColor(percent, charging)

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
                tint = color,
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

// Scrim + centered Card listing the top processes for whichever ring was
// tapped. entries == null means the reply hasn't arrived yet (loading state);
// an empty (non-null) list is a legitimate "nothing to show" — only distinct
// from loading by the null check, not by emptiness.
@Composable
private fun ProcessDrilldownOverlay(
    metric: String,
    entries: List<ProcessEntry>?,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = PanelSurface),
            shape = RoundedCornerShape(20.dp),
            // Consumes clicks so tapping the list itself doesn't fall through
            // to the scrim's onDismiss.
            modifier = Modifier
                .clickable(onClick = {})
                .widthIn(max = 340.dp)
                .heightIn(max = 420.dp)
                .padding(4.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(if (metric == "cpu") R.string.resource_top_cpu else R.string.resource_top_ram),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, tint = PanelOnBackgroundMuted)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = null, tint = PanelOnBackgroundMuted)
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (entries == null) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = PanelAccent)
                    }
                } else {
                    LazyColumn {
                        items(entries) { entry -> ProcessRow(metric, entry) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessRow(metric: String, entry: ProcessEntry) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = entry.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
            Text(text = "PID ${entry.pid}", style = MaterialTheme.typography.labelSmall, color = PanelOnBackgroundMuted)
        }
        val valueText = if (metric == "cpu") "${entry.cpuPercent.roundToInt()}%" else formatBytes(entry.ramBytes)
        Text(text = valueText, style = MaterialTheme.typography.bodyMedium, color = PanelAccent)
    }
}

// Green under 70% used, amber under 90%, red above.
private fun levelColor(fraction: Float) = when {
    fraction < 0.7f -> PanelAccent
    fraction < 0.9f -> PanelWarning
    else -> PanelError
}

// Cyan while charging (the charge level itself doesn't matter in that
// state); otherwise green from 50-100%, yellow from 20-50%, red below 20%.
private fun batteryColor(percent: Int, charging: Boolean): Color = when {
    charging -> PanelCyan
    percent >= 50 -> PanelAccent
    percent >= 20 -> PanelWarning
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
