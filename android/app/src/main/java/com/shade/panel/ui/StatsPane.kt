package com.shade.panel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shade.panel.music.ListenSync
import com.shade.panel.music.MusicViewModel
import com.shade.panel.music.SyncState
import com.shade.panel.music.StatsEntry
import com.shade.panel.music.StatsPeriod
import com.shade.panel.ui.theme.Manrope
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private enum class StatsSection(val label: String) { ARTISTS("Artistas"), GENRES("Géneros"), SONGS("Canciones") }

private val PERIOD_LABELS = mapOf(
    StatsPeriod.HOY to "HOY",
    StatsPeriod.SEMANA to "SEM",
    StatsPeriod.MES to "MES",
    StatsPeriod.ANIO to "AÑO",
    StatsPeriod.TODO to "TODO",
)

// Listening KPIs from the phone's own journal, same rules as Nation's stats
// panel: totals for the period, when in the day you listen, and the rankings.
@Composable
fun StatsPane(vm: MusicViewModel, bottomPadding: Dp, modifier: Modifier = Modifier) {
    val stats by vm.stats.collectAsState()
    val period by vm.statsPeriod.collectAsState()
    var section by rememberSaveable { mutableStateOf(StatsSection.ARTISTS) }
    // The recorder saves the open listen every 30 s; follow it while this is on screen.
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshStats()
            delay(30_000)
        }
    }
    val entries = when (section) {
        StatsSection.ARTISTS -> stats.artists
        StatsSection.GENRES -> stats.genres
        StatsSection.SONGS -> stats.songs
    }
    val topHours = entries.firstOrNull()?.hours ?: 0.0

    val sync by ListenSync.state.collectAsState()

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding)) {
        item {
            NationStrip(sync)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatsPeriod.entries.forEach { p ->
                    KeyButton(onClick = { vm.setStatsPeriod(p) }, modifier = Modifier.weight(1f), width = 1000.dp, height = 36.dp, active = p == period) {
                        CapLabel(PERIOD_LABELS.getValue(p), 10.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val (value, unit) = hoursReadout(stats.hours)
                Kpi(value, unit, Modifier.weight(1.2f))
                Kpi(stats.plays.toString(), "Plays", Modifier.weight(1f))
                Kpi(stats.listens.toString(), "Escuchas", Modifier.weight(1f))
            }
            if (sync.linked && stats.hours > 0) {
                Spacer(Modifier.height(6.dp))
                Caption(
                    "Teléfono ${hoursText(stats.phoneHours)} · PC ${hoursText(stats.hours - stats.phoneHours)}",
                    color = Ink.TextSubtle,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            DisplayPanel(Modifier.fillMaxWidth(), contentPadding = 12.dp) {
                Column {
                    Caption("Cuándo escuchas", color = Ink.TextSubtle)
                    Spacer(Modifier.height(8.dp))
                    DotHistory(
                        values = stats.byHour.map { it.toFloat() },
                        maxValue = (stats.byHour.maxOrNull() ?: 0.0).toFloat(),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth()) {
                        listOf("0h", "6h", "12h", "18h", "23h").forEachIndexed { i, label ->
                            if (i > 0) Spacer(Modifier.weight(1f))
                            Caption(label, color = Ink.TextSubtle, size = 8.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                StatsSection.entries.forEach { s ->
                    DotText(
                        s.label.uppercase(),
                        13.sp,
                        weight = FontWeight.Black,
                        lit = s == section,
                        modifier = Modifier.clickableNoRipple { section = s },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (stats.isEmpty) {
                Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    DotText("SIN ESCUCHAS", 16.sp)
                    Spacer(Modifier.height(6.dp))
                    Caption("Lo que suene en el teléfono aparece aquí", color = Ink.TextSubtle)
                }
            }
        }
        itemsIndexed(entries, key = { i, e -> "$section:$i:${e.name}" }) { i, entry ->
            RankRow(i + 1, entry, if (topHours > 0) (entry.hours / topHours).toFloat() else 0f)
        }
    }
}

// Link with Nation: the one-time question, then the sync status with a key to sync now.
@Composable
private fun NationStrip(sync: SyncState) {
    val ask = sync.askToLink
    if (ask != null) {
        DisplayPanel(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
            Column {
                DotText("NATION · ${ask.name.uppercase()}", 14.sp, weight = FontWeight.Black)
                Spacer(Modifier.height(6.dp))
                Text(
                    "¿Este teléfono es de ${ask.name}? Al vincularlo, tus escuchas del teléfono y las del PC se juntan en las mismas estadísticas.",
                    style = TextStyle(fontFamily = Manrope, fontSize = 13.sp, color = Ink.TextMuted),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KeyButton(onClick = ListenSync::acceptLink, width = 120.dp, height = 38.dp, active = true) { CapLabel("VINCULAR", 11.sp) }
                    KeyButton(onClick = ListenSync::declineLink, width = 80.dp, height = 38.dp) { CapLabel("NO", 11.sp) }
                }
            }
        }
        return
    }
    val status = when {
        sync.syncing -> "Sincronizando con Nation…"
        sync.message != null -> sync.message
        sync.linked && sync.lastSyncAt > 0 -> "Nation · ${sync.ownerName.orEmpty()} · ${ago(sync.lastSyncAt)}"
        sync.linked -> "Nation · ${sync.ownerName.orEmpty()} · conecta el teléfono al PC"
        else -> "Conecta el teléfono al PC con Nation abierto para juntar estadísticas"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(on = sync.linked && sync.message == null, size = 6.dp)
        Spacer(Modifier.width(8.dp))
        Caption(status, color = Ink.TextMuted, size = 9.sp, modifier = Modifier.weight(1f))
        KeyButton(onClick = ListenSync::syncNow, width = 56.dp, height = 30.dp, active = sync.syncing) { CapLabel("SYNC", 10.sp) }
    }
}

private fun ago(at: Long): String {
    val min = ((System.currentTimeMillis() - at) / 60_000).toInt()
    return when {
        min < 1 -> "al día"
        min < 60 -> "hace $min min"
        else -> "hace ${min / 60} h"
    }
}

@Composable
private fun Kpi(value: String, label: String, modifier: Modifier) {
    DisplayPanel(modifier, contentPadding = 12.dp) {
        Column {
            Readout(value, 28.sp)
            Caption(label, color = Ink.TextSubtle)
        }
    }
}

@Composable
private fun RankRow(rank: Int, entry: StatsEntry, share: Float) {
    val ink = LocalInk.current
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        DotText(rank.toString().padStart(2, ' '), 13.sp, lit = rank <= 3, modifier = Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Ink.Text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.detail != null) {
                Text(
                    entry.detail,
                    style = TextStyle(fontFamily = Manrope, fontSize = 11.sp, color = Ink.TextMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Ink.Off)) {
                Box(Modifier.fillMaxWidth(share.coerceIn(0.02f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(ink))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Caption(hoursText(entry.hours), color = Ink.TextMuted)
            Caption("${entry.plays} plays", color = Ink.TextSubtle, size = 8.sp)
        }
    }
}

// Under an hour reads better in minutes.
private fun hoursReadout(hours: Double): Pair<String, String> =
    if (hours < 1) (hours * 60).roundToInt().toString() to "Minutos" else "%.1f".format(hours) to "Horas"

private fun hoursText(hours: Double): String =
    if (hours < 1) "${(hours * 60).roundToInt()} min" else "%.1f h".format(hours)
