package com.shade.panel.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shade.panel.R
import com.shade.panel.data.ProcessEntry
import com.shade.panel.data.ResourceMessage
import com.shade.panel.ui.theme.PanelBackground
import com.shade.panel.ui.theme.PanelCyan
import com.shade.panel.ui.theme.PanelError
import com.shade.panel.ui.theme.PanelOnBackgroundMuted
import com.shade.panel.ui.theme.PanelOnBackgroundSubtle
import com.shade.panel.ui.theme.PanelOutline
import com.shade.panel.ui.theme.PanelSurface
import com.shade.panel.ui.theme.PanelSurfaceHigh
import com.shade.panel.ui.theme.PanelViolet
import com.shade.panel.ui.theme.PanelWarning
import kotlin.math.roundToInt

// Task Manager-flavored dashboard: a 2x2 grid of cards (CPU, RAM, network,
// disk), each with a big live number and a ~60s rolling graph. The grid is
// sized to fill the screen instead of scrolling, so vertical swipes stay
// free for the gesture shell. History lives in the ViewModel, so graphs are
// already populated when you swipe here.
@Composable
fun ResourceScreen(viewModel: PanelViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val history by viewModel.resourceHistory.collectAsState()
    val resource = uiState.resource
    val accent = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.07f), PanelBackground, PanelBackground))),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            StatsHeader(resource)
            Spacer(Modifier.height(16.dp))

            if (resource == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = accent, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.resource_waiting),
                            style = MaterialTheme.typography.bodyMedium,
                            color = PanelOnBackgroundMuted,
                        )
                    }
                }
            } else {
                val ramFraction = if (resource.ramTotalBytes > 0) {
                    (resource.ramUsedBytes.toFloat() / resource.ramTotalBytes.toFloat()).coerceIn(0f, 1f)
                } else 0f
                val cpuFraction = (resource.cpuUsagePercent.toFloat() / 100f).coerceIn(0f, 1f)

                Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricCard(
                        icon = Icons.Rounded.Speed,
                        label = stringResource(R.string.resource_cpu),
                        value = "${resource.cpuUsagePercent.roundToInt()}%",
                        detail = stringResource(R.string.resource_tap_for_processes),
                        series = listOf(GraphSeries(history.cpu, levelColor(cpuFraction, accent))),
                        maxValue = 1f,
                        onClick = { viewModel.requestProcesses("cpu") },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    MetricCard(
                        icon = Icons.Rounded.Memory,
                        label = stringResource(R.string.resource_ram),
                        value = "${(ramFraction * 100).roundToInt()}%",
                        detail = "${formatBytes(resource.ramUsedBytes)} / ${formatBytes(resource.ramTotalBytes)}",
                        series = listOf(GraphSeries(history.ram, levelColor(ramFraction, accent))),
                        maxValue = 1f,
                        onClick = { viewModel.requestProcesses("ram") },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricCard(
                        icon = Icons.Rounded.Lan,
                        label = stringResource(R.string.resource_network),
                        value = "↓ " + formatBytesPerSec(resource.netDownBytesPerSec),
                        detail = "↑ " + formatBytesPerSec(resource.netUpBytesPerSec),
                        series = listOf(GraphSeries(history.netDown, accent), GraphSeries(history.netUp, PanelViolet)),
                        maxValue = autoScaleMax(history.netDown, history.netUp),
                        onClick = null,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    MetricCard(
                        icon = Icons.Rounded.Storage,
                        label = stringResource(R.string.resource_disk),
                        value = "R " + formatBytesPerSec(resource.diskReadBytesPerSec),
                        detail = "W " + formatBytesPerSec(resource.diskWriteBytesPerSec),
                        series = listOf(GraphSeries(history.diskRead, PanelCyan), GraphSeries(history.diskWrite, PanelWarning)),
                        maxValue = autoScaleMax(history.diskRead, history.diskWrite),
                        onClick = null,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }

        val drilldownMetric = uiState.processDrilldownMetric
        // Last non-null metric, so the overlay keeps its title while fading out.
        var shownMetric by remember { mutableStateOf("cpu") }
        if (drilldownMetric != null) shownMetric = drilldownMetric
        AnimatedVisibility(
            visible = drilldownMetric != null,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            ProcessDrilldownOverlay(
                metric = shownMetric,
                entries = uiState.processDrilldownEntries,
                onRefresh = { viewModel.requestProcesses(shownMetric) },
                onDismiss = { viewModel.dismissProcessDrilldown() },
            )
        }
    }
}

@Composable
private fun StatsHeader(resource: ResourceMessage?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(end = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.resource_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.resource_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = PanelOnBackgroundMuted,
            )
        }
        if (resource != null && resource.hasBattery) BatteryPill(resource)
    }
}

private data class GraphSeries(val values: List<Float>, val color: Color)

@Composable
private fun MetricCard(
    icon: ImageVector,
    label: String,
    value: String,
    detail: String?,
    series: List<GraphSeries>,
    maxValue: Float,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val primaryColor = series.first().color
    ShadeCard(modifier = modifier, onClick = onClick) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon, tint = primaryColor, size = 32.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = PanelOnBackgroundMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (series.size > 1) series[1].color.copy(alpha = 0.9f) else PanelOnBackgroundSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            LineGraph(
                series = series,
                maxValue = maxValue,
                modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 36.dp),
            )
        }
    }
}

// Network/disk throughput has no natural 100% ceiling like RAM/CPU do, so
// (like Windows' own Task Manager) the graph scales to whatever the busiest
// moment in the visible window was, with a little headroom so the peak
// sample doesn't touch the top edge, and a floor so an idle graph doesn't
// blow up to fill the height chasing tiny fluctuations.
private fun autoScaleMax(vararg histories: List<Float>): Float {
    val peak = histories.maxOf { it.maxOrNull() ?: 0f }
    return (peak * 1.15f).coerceAtLeast(256f * 1024f)
}

// Smooth (monotone-ish cubic) line per series over faint gridlines, the
// first series with a gradient fill underneath, newest sample pinned to the
// right edge with a glowing dot.
@Composable
private fun LineGraph(series: List<GraphSeries>, maxValue: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val gridColor = PanelOutline.copy(alpha = 0.6f)
        for (i in 1..3) {
            val y = h * i / 4
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }

        series.forEachIndexed { seriesIndex, (values, color) ->
            if (values.size < 2) return@forEachIndexed
            val points = toPoints(values, maxValue)
            val line = smoothPath(points)
            if (seriesIndex == 0) {
                val area = Path().apply {
                    addPath(line)
                    lineTo(points.last().x, h)
                    lineTo(points.first().x, h)
                    close()
                }
                drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.32f), color.copy(alpha = 0f)), 0f, h))
            }
            drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            val last = points.last()
            drawCircle(color.copy(alpha = 0.25f), radius = 6.dp.toPx(), center = last)
            drawCircle(color, radius = 3.dp.toPx(), center = last)
        }
    }
}

private fun DrawScope.toPoints(values: List<Float>, maxValue: Float): List<Offset> {
    val safeMax = maxValue.coerceAtLeast(0.0001f)
    // Leave a few px of room so the end dot isn't clipped at the edges.
    val inset = 6.dp.toPx()
    val usableW = size.width - inset
    val usableH = size.height - inset * 2
    val stepX = usableW / (HISTORY_SIZE - 1).toFloat()
    val startIndex = HISTORY_SIZE - values.size
    return values.mapIndexed { i, value ->
        Offset((startIndex + i) * stepX, inset + usableH - (value / safeMax).coerceIn(0f, 1f) * usableH)
    }
}

private fun smoothPath(points: List<Offset>): Path = Path().apply {
    moveTo(points.first().x, points.first().y)
    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val curr = points[i]
        val midX = (prev.x + curr.x) / 2
        cubicTo(midX, prev.y, midX, curr.y, curr.x, curr.y)
    }
}

@Composable
private fun BatteryPill(resource: ResourceMessage) {
    val charging = resource.batteryCharging == true
    val percent = resource.batteryPercent ?: 0
    val color = batteryColor(percent, charging, MaterialTheme.colorScheme.primary)

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (charging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryFull,
            contentDescription = stringResource(R.string.resource_battery),
            tint = color,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(text = "$percent%", style = MaterialTheme.typography.labelLarge, color = color)
    }
}

// Scrim + centered card listing the top processes for whichever card was
// tapped, each with a bar showing its share relative to the busiest one.
// entries == null means the reply hasn't arrived yet (loading state); an
// empty (non-null) list is a legitimate "nothing to show".
@Composable
private fun ProcessDrilldownOverlay(
    metric: String,
    entries: List<ProcessEntry>?,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 420.dp)
                .heightIn(max = 520.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(PanelSurface)
                // Consumes clicks so tapping the list itself doesn't fall
                // through to the scrim's onDismiss.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(if (metric == "cpu") R.string.resource_top_cpu else R.string.resource_top_ram),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                CircleIconButton(
                    icon = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.action_refresh),
                    onClick = onRefresh,
                    size = 40.dp,
                    iconSize = 20.dp,
                    tint = PanelOnBackgroundMuted,
                )
                CircleIconButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.action_close),
                    onClick = onDismiss,
                    size = 40.dp,
                    iconSize = 20.dp,
                    tint = PanelOnBackgroundMuted,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (entries == null) {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = accent, strokeWidth = 3.dp)
                }
            } else {
                val peak = entries.maxOfOrNull { if (metric == "cpu") it.cpuPercent else it.ramBytes.toDouble() } ?: 0.0
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(entries) { index, entry -> ProcessRow(index, metric, entry, peak) }
                }
            }
        }
    }
}

@Composable
private fun ProcessRow(index: Int, metric: String, entry: ProcessEntry, peak: Double) {
    val accent = MaterialTheme.colorScheme.primary
    val raw = if (metric == "cpu") entry.cpuPercent else entry.ramBytes.toDouble()
    val fraction by animateFloatAsState(
        targetValue = if (peak > 0) (raw / peak).toFloat().coerceIn(0f, 1f) else 0f,
        animationSpec = tween(500, delayMillis = index * 30),
        label = "processBar",
    )
    val valueText = if (metric == "cpu") "%.1f%%".format(entry.cpuPercent) else formatBytes(entry.ramBytes)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PanelSurfaceHigh)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(text = valueText, style = MaterialTheme.typography.labelLarge, color = accent)
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(accent),
            )
        }
        Text(
            text = "PID ${entry.pid}",
            style = MaterialTheme.typography.labelSmall,
            color = PanelOnBackgroundSubtle,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// Accent under 70% used, amber under 90%, red above.
private fun levelColor(fraction: Float, accent: Color) = when {
    fraction < 0.7f -> accent
    fraction < 0.9f -> PanelWarning
    else -> PanelError
}

// Cyan while charging (the charge level itself doesn't matter in that
// state); otherwise accent from 50-100%, amber from 20-50%, red below 20%.
private fun batteryColor(percent: Int, charging: Boolean, accent: Color): Color = when {
    charging -> PanelCyan
    percent >= 50 -> accent
    percent >= 20 -> PanelWarning
    else -> PanelError
}

fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> "%.1f GB".format(gb)
        mb >= 1 -> "%.0f MB".format(mb)
        else -> "%.0f KB".format(kb)
    }
}

fun formatBytesPerSec(bytesPerSec: Double): String {
    val kbps = bytesPerSec / 1024.0
    val mbps = kbps / 1024.0
    return when {
        mbps >= 1 -> "%.1f MB/s".format(mbps)
        else -> "%.0f KB/s".format(kbps)
    }
}
