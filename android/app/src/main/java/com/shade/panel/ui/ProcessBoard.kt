package com.shade.panel.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shade.panel.data.ProcessEntry
import com.shade.panel.data.ResourceMessage

private const val MAP_APPS = 6
private const val BOARD_ROWS = 8

// Live process board: a memory map (one segmented bar split between the
// heaviest apps, then "others" and free) above an htop-style list where each
// app carries its own dot-matrix CPU trace. Apps that suddenly spike blink.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProcessBoard(
    sort: String,
    apps: List<ProcessEntry>?,
    cpuHistory: Map<String, List<Float>>,
    resource: ResourceMessage?,
    onSort: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // One color, like a monochrome display: the ink at falling intensity down the ranking.
    val ink = LocalInk.current
    val wheel = remember(ink) { SHADES.map { ink.copy(alpha = it) } }
    // Colors follow the memory ranking so an app keeps its color between the map and its row.
    val byRam = apps.orEmpty().sortedByDescending { it.ramBytes }
    val colorOf = remember(byRam.map { it.name }, wheel) {
        byRam.take(MAP_APPS).mapIndexed { i, app -> app.name to wheel[i] }.toMap()
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)).clickableNoRipple(onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        DisplayPanel(
            contentPadding = 16.dp,
            modifier = Modifier
                .padding(18.dp)
                .widthIn(max = 620.dp)
                .heightIn(max = 760.dp)
                // Swallows taps so the board itself doesn't dismiss.
                .clickableNoRipple {},
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveDot()
                    Spacer(Modifier.width(8.dp))
                    DotText("PROCESOS", 15.sp, weight = FontWeight.Black, modifier = Modifier.weight(1f))
                    KeyButton(onClick = { onSort("ram") }, width = 52.dp, height = 32.dp, active = sort == "ram") { CapLabel("RAM", 10.sp) }
                    Spacer(Modifier.width(6.dp))
                    KeyButton(onClick = { onSort("cpu") }, width = 52.dp, height = 32.dp, active = sort == "cpu") { CapLabel("CPU", 10.sp) }
                    Spacer(Modifier.width(6.dp))
                    KeyButton(onClick = onDismiss, width = 56.dp, height = 32.dp) { CapLabel("CLOSE", 10.sp) }
                }
                Spacer(Modifier.height(14.dp))
                if (apps == null) {
                    DotText("ESPERANDO AL PC…", 15.sp, lit = false)
                    Caption("Si no aparece nada, actualiza Server Shade a 1.6.1", color = Ink.TextSubtle, modifier = Modifier.padding(top = 6.dp))
                    return@Column
                }

                MemoryMap(byRam, colorOf, resource)
                Spacer(Modifier.height(16.dp))

                val rows = if (sort == "cpu") apps.sortedByDescending { it.cpuPercent } else byRam
                val peakRam = rows.maxOfOrNull { it.ramBytes } ?: 1L
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(rows.take(BOARD_ROWS), key = { _, app -> app.name }) { i, app ->
                        AppRow(i + 1, app, cpuHistory[app.name].orEmpty(), colorOf[app.name], app.ramBytes.toFloat() / peakRam)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryMap(byRam: List<ProcessEntry>, colorOf: Map<String, Color>, resource: ResourceMessage?) {
    val total = resource?.ramTotalBytes?.takeIf { it > 0 } ?: return
    val used = resource.ramUsedBytes
    val top = byRam.take(MAP_APPS)
    // Working sets overlap (shared pages), so the named slices are capped at what's in use.
    var left = used
    val slices = top.map { app ->
        val bytes = minOf(app.ramBytes, left).coerceAtLeast(0)
        left -= bytes
        colorOf[app.name]!! to bytes
    }
    val ink = LocalInk.current

    Caption("Memoria · %.1f / %.1f GB".format(used / GB_F, total / GB_F), color = Ink.TextMuted)
    Spacer(Modifier.height(8.dp))
    Canvas(Modifier.fillMaxWidth().height(22.dp)) {
        val segments = 48
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (segments - 1)) / segments
        // Each segment takes the color of the slice its midpoint falls in.
        val bounds = ArrayList<Pair<Float, Color>>()
        var acc = 0f
        slices.forEach { (color, bytes) ->
            acc += bytes.toFloat() / total
            bounds += acc to color
        }
        val usedFraction = used.toFloat() / total
        var previous: Int? = null
        for (i in 0 until segments) {
            val mid = (i + 0.5f) / segments
            val slice = bounds.indexOfFirst { mid <= it.first }.takeIf { it >= 0 }
            var color = slice?.let { bounds[it].second }
                ?: if (mid <= usedFraction) ink.copy(alpha = OTHERS_ALPHA) else Color.White.copy(alpha = 0.06f)
            // The first segment of each app burns brighter: the boundaries read without extra colors.
            if (slice != null && slice != previous) color = lerp(color.copy(alpha = 1f), Color.White, 0.55f)
            previous = slice
            drawRoundRect(color, Offset(i * (w + gap), 0f), Size(w, size.height), CornerRadius(w / 3))
        }
    }
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        top.forEach { app -> Legend(colorOf[app.name]!!, app.name) }
        Legend(ink.copy(alpha = OTHERS_ALPHA), "otros")
        Legend(Color.White.copy(alpha = 0.12f), "libre")
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(5.dp))
        Caption(label, color = Ink.TextSubtle, size = 9.sp)
    }
}

@Composable
private fun AppRow(rank: Int, app: ProcessEntry, history: List<Float>, mapColor: Color?, ramShare: Float) {
    // A spike: well above this app's own recent average, and not trivially small.
    val previous = history.dropLast(1)
    val average = if (previous.isEmpty()) 0f else previous.average().toFloat()
    val spiking = previous.size >= 3 && app.cpuPercent > maxOf(15.0, average * 2.5)
    val blink by rememberInfiniteTransition(label = "spike").animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(380), RepeatMode.Reverse),
        label = "spikeAlpha",
    )
    val cpuColor = if (spiking) AlertColor else LocalInk.current

    Row(Modifier.fillMaxWidth().alpha(if (spiking) blink else 1f), verticalAlignment = Alignment.CenterVertically) {
        DotText("%02d".format(rank), 12.sp, lit = rank <= 3, modifier = Modifier.width(26.dp))
        Box(Modifier.size(width = 4.dp, height = 26.dp).clip(RoundedCornerShape(2.dp)).background(mapColor ?: Color.White.copy(alpha = 0.08f)))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                DotText(app.name.uppercase(), 13.sp, weight = FontWeight.Black)
                if (app.instances > 1) {
                    Spacer(Modifier.width(6.dp))
                    Caption("×${app.instances}", color = Ink.TextSubtle, size = 8.sp)
                }
            }
            Spacer(Modifier.height(3.dp))
            SegmentBar(fraction = ramShare, segments = 24, modifier = Modifier.fillMaxWidth(0.9f).height(3.dp), color = mapColor ?: LocalInk.current)
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(64.dp)) {
            Caption(formatBytes(app.ramBytes), color = Ink.TextMuted)
        }
        Spacer(Modifier.width(8.dp))
        DotHistory(history, maxValue = maxOf(10f, history.maxOrNull() ?: 0f), modifier = Modifier.width(78.dp).height(22.dp), color = cpuColor, rows = 5)
        Spacer(Modifier.width(8.dp))
        DotText("%3.0f%%".format(app.cpuPercent), 12.sp, modifier = Modifier.width(42.dp))
    }
}

// Pulsing "on air" dot: the board is live, not a snapshot.
@Composable
private fun LiveDot() {
    val pulse by rememberInfiniteTransition(label = "live").animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "liveAlpha",
    )
    Dot(on = true, color = AlertColor, size = 8.dp, modifier = Modifier.alpha(pulse))
}

private const val GB_F = 1073741824f

private val SHADES = listOf(1f, 0.78f, 0.6f, 0.46f, 0.34f, 0.24f)
private const val OTHERS_ALPHA = 0.12f
