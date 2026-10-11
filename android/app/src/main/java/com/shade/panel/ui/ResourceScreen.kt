package com.shade.panel.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shade.panel.R
import com.shade.panel.data.ProcessEntry
import kotlin.math.roundToInt

private val WarnColor = Color(0xFFFFB13B)
private val AlertColor = Color(0xFFFF4B3E)

// System monitor: four display panels (CPU, memory, network, disk),
// each with a large readout, a segmented level bar and a
// dot-matrix history of the last minute. Sized to fill the screen without
// scrolling, so vertical swipes stay free for the gesture shell. History
// lives in the ViewModel, so it's already populated when you arrive.
@Composable
fun ResourceScreen(viewModel: PanelViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val history by viewModel.resourceHistory.collectAsState()
    val resource = uiState.resource

    ScreenBase(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            TopStrip(model = "SM-1 · System monitor", modifier = Modifier.fillMaxWidth().padding(end = 56.dp)) {
                if (resource != null && resource.hasBattery) {
                    val percent = resource.batteryPercent ?: 0
                    StatusDot(
                        label = "BAT $percent%",
                        on = true,
                        color = when {
                            resource.batteryCharging == true -> LocalInk.current
                            percent >= 50 -> LocalInk.current
                            percent >= 20 -> WarnColor
                            else -> AlertColor
                        },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            if (resource == null) {
                DisplayPanel(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        DotText(stringResource(R.string.resource_waiting).uppercase(), 18.sp)
                    }
                }
            } else {
                val ramFraction = if (resource.ramTotalBytes > 0) {
                    (resource.ramUsedBytes.toFloat() / resource.ramTotalBytes.toFloat()).coerceIn(0f, 1f)
                } else 0f
                val cpuFraction = (resource.cpuUsagePercent.toFloat() / 100f).coerceIn(0f, 1f)
                val ink = LocalInk.current
                val diskTotal = history.diskRead.zip(history.diskWrite) { r, w -> r + w }

                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MeterWindow(
                        label = "CPU",
                        readout = "${resource.cpuUsagePercent.roundToInt()}",
                        detail = stringResource(R.string.resource_tap_for_processes),
                        level = cpuFraction,
                        levelColor = levelColor(cpuFraction, ink),
                        history = history.cpu,
                        onClick = { viewModel.requestProcesses("cpu") },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    MeterWindow(
                        label = "MEM",
                        readout = "${(ramFraction * 100).roundToInt()}",
                        detail = "%.1f / %.1f GB".format(resource.ramUsedBytes / GB, resource.ramTotalBytes / GB),
                        level = ramFraction,
                        levelColor = levelColor(ramFraction, ink),
                        history = history.ram,
                        onClick = { viewModel.requestProcesses("ram") },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ThroughputWindow(
                        label = "NET",
                        topTag = "DN",
                        top = resource.netDownBytesPerSec,
                        bottomTag = "UP",
                        bottom = resource.netUpBytesPerSec,
                        history = history.netDown,
                        historyMax = autoScaleMax(history.netDown, history.netUp),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    ThroughputWindow(
                        label = "DISK",
                        topTag = "RD",
                        top = resource.diskReadBytesPerSec,
                        bottomTag = "WR",
                        bottom = resource.diskWriteBytesPerSec,
                        history = diskTotal,
                        historyMax = autoScaleMax(diskTotal),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }

        val drilldownMetric = uiState.processDrilldownMetric
        // Last non-null metric, so the overlay keeps its title while fading out.
        var shownMetric by remember { mutableStateOf("cpu") }
        if (drilldownMetric != null) shownMetric = drilldownMetric
        AnimatedVisibility(visible = drilldownMetric != null, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
            ProcessOverlay(
                metric = shownMetric,
                entries = uiState.processDrilldownEntries,
                onRefresh = { viewModel.requestProcesses(shownMetric) },
                onDismiss = viewModel::dismissProcessDrilldown,
            )
        }
    }
}

@Composable
private fun MeterWindow(
    label: String,
    readout: String,
    detail: String,
    level: Float,
    levelColor: Color,
    history: List<Float>,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    DisplayPanel(modifier = modifier.clickableNoRipple(onClick)) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.Bottom) {
                DotText(label, 14.sp)
                Spacer(Modifier.weight(1f))
                Readout(readout, 34.sp, template = "888")
                Spacer(Modifier.width(4.dp))
                DotText("%", 16.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            Spacer(Modifier.height(8.dp))
            SegmentBar(level, segments = 30, color = levelColor, modifier = Modifier.fillMaxWidth().height(8.dp))
            Spacer(Modifier.height(6.dp))
            DotText(detail.uppercase(), 11.sp, weight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            DotHistory(history, 1f, modifier = Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun ThroughputWindow(
    label: String,
    topTag: String,
    top: Double,
    bottomTag: String,
    bottom: Double,
    history: List<Float>,
    historyMax: Float,
    modifier: Modifier,
) {
    DisplayPanel(modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            DotText(label, 14.sp)
            Spacer(Modifier.height(6.dp))
            RateLine(topTag, top)
            RateLine(bottomTag, bottom)
            Spacer(Modifier.height(8.dp))
            DotHistory(history, historyMax, modifier = Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun RateLine(tag: String, bytesPerSec: Double) {
    val (value, unit) = splitRate(bytesPerSec)
    Row(verticalAlignment = Alignment.Bottom) {
        DotText(tag, 12.sp, weight = FontWeight.Medium, lit = false)
        Spacer(Modifier.weight(1f))
        Readout(value, 22.sp, template = "888.8")
        Spacer(Modifier.width(6.dp))
        DotText(unit, 12.sp, modifier = Modifier.padding(bottom = 2.dp))
    }
}

// Value + unit split so the number can sit on the large readout,
// always as a fixed "###.#" so the digits never shift sideways.
private fun splitRate(bytesPerSec: Double): Pair<String, String> {
    val kb = bytesPerSec / 1024.0
    val mb = kb / 1024.0
    val (value, unit) = if (mb >= 1) mb to "MB/S" else kb to "KB/S"
    return "%.1f".format(java.util.Locale.US, value.coerceAtMost(999.9)) to unit
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

// Scrim + a panel listing the top processes for whichever meter was
// tapped. entries == null means the reply hasn't arrived yet.
@Composable
private fun ProcessOverlay(metric: String, entries: List<ProcessEntry>?, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .clickableNoRipple(onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        DisplayPanel(
            contentPadding = 0.dp,
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 460.dp)
                .heightIn(max = 520.dp)
                // Swallows taps so the list itself doesn't dismiss.
                .clickableNoRipple {},
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Caption(stringResource(if (metric == "cpu") R.string.resource_top_cpu else R.string.resource_top_ram), modifier = Modifier.weight(1f))
                    KeyButton(onClick = onRefresh, width = 56.dp, height = 32.dp, contentDescription = stringResource(R.string.action_refresh)) {
                        CapLabel("RFSH", 10.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    KeyButton(onClick = onDismiss, width = 56.dp, height = 32.dp, contentDescription = stringResource(R.string.action_close)) {
                        CapLabel("CLOSE", 10.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                DisplayPanel(Modifier.fillMaxWidth()) {
                    if (entries == null) {
                        DotText("LOADING", 16.sp)
                    } else {
                        val peak = entries.maxOfOrNull { if (metric == "cpu") it.cpuPercent else it.ramBytes.toDouble() } ?: 0.0
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(entries) { index, entry ->
                                val raw = if (metric == "cpu") entry.cpuPercent else entry.ramBytes.toDouble()
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        DotText("%02d".format(index + 1), 12.sp, lit = false)
                                        Spacer(Modifier.width(8.dp))
                                        DotText(entry.name.uppercase(), 14.sp, modifier = Modifier.weight(1f))
                                        DotText(
                                            if (metric == "cpu") "%.1f%%".format(entry.cpuPercent) else formatBytes(entry.ramBytes).uppercase(),
                                            13.sp,
                                        )
                                    }
                                    Spacer(Modifier.height(3.dp))
                                    SegmentBar(
                                        fraction = if (peak > 0) (raw / peak).toFloat() else 0f,
                                        segments = 40,
                                        modifier = Modifier.fillMaxWidth().height(4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Display color under 70% used, amber under 90%, red above.
private fun levelColor(fraction: Float, normal: Color) = when {
    fraction < 0.7f -> normal
    fraction < 0.9f -> WarnColor
    else -> AlertColor
}

private const val GB = 1073741824.0

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
