package com.shade.panel.ui

import android.text.format.DateFormat
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shade.panel.R
import com.shade.panel.data.ConnectionState
import com.shade.panel.data.NavScreen
import com.shade.panel.data.Transport
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// Same threshold GestureNavShell uses for its own swipe detection — kept in
// sync by convention rather than shared, since the two screens' gesture
// detectors serve different destinations (this one only opens the player).
private val SWIPE_UP_THRESHOLD = 80.dp

// Home as the panel at rest: a display with the time, link
// status and what's playing, and a row of source-selector buttons below,
// each with its status dot, a caption and a one-line readout.
@Composable
fun HomeScreen(
    viewModel: PanelViewModel,
    onOpenPanel: (NavScreen) -> Unit,
    onOpenConnection: () -> Unit,
    onOpenGestureSettings: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMusic: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val swipeThresholdPx = with(LocalDensity.current) { SWIPE_UP_THRESHOLD.toPx() }
    var overscroll by remember { mutableFloatStateOf(0f) }
    // Rides on the scroll container's own nested-scroll dispatch instead of
    // a competing drag detector: only the leftover scroll it can't consume
    // (already at the bottom) reaches here — "keep swiping past the end".
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0f) overscroll += available.y
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (-overscroll > swipeThresholdPx) onOpenPanel(NavScreen.PLAYER)
                overscroll = 0f
                return Velocity.Zero
            }
        }
    }

    ScreenBase(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            val landscape = maxWidth > maxHeight
            val selectors = selectorModels(uiState, onOpenPanel, onOpenConnection, onOpenGestureSettings, onOpenSettings, onOpenMusic)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(nestedScrollConnection)
                    .verticalScroll(rememberScrollState()),
            ) {
                TopStrip(model = "SP-1 · Standby") {
                    StatusDot(
                        label = if (uiState.transport == Transport.BLUETOOTH) "BT" else "USB",
                        on = uiState.connection == ConnectionState.CONNECTED,
                        color = if (uiState.connection == ConnectionState.CONNECTED) LocalInk.current else Color(0xFFFF4B3E),
                    )
                }
                Spacer(Modifier.height(16.dp))
                StandbyDisplay(uiState, onOpenPlayer = { onOpenPanel(NavScreen.PLAYER) }, tall = !landscape)
                Spacer(Modifier.height(22.dp))
                SelectorGrid(selectors, columns = if (landscape) 6 else 3)
                Spacer(Modifier.height(18.dp))
                Caption(
                    stringResource(R.string.home_swipe_hint),
                    color = Ink.TextSubtle,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

@Composable
private fun StandbyDisplay(uiState: PanelUiState, onOpenPlayer: () -> Unit, tall: Boolean) {
    val context = LocalContext.current
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000 - nowMs % 1_000)
        }
    }
    val locale = Locale.getDefault()
    val is24h = DateFormat.is24HourFormat(context)
    val now = Date(nowMs)
    val time = SimpleDateFormat(if (is24h) "HH:mm" else "hh:mm", locale).format(now)
    val seconds = SimpleDateFormat("ss", locale).format(now)
    val date = SimpleDateFormat("EEE dd MMM", locale).format(now).uppercase(locale).replace(".", "")

    Pressable(onClick = onOpenPlayer) {
        DisplayPanel(modifier = Modifier.fillMaxWidth().height(if (tall) 190.dp else 150.dp)) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Readout(time, 54.sp, template = "88:88")
                        Spacer(Modifier.width(6.dp))
                        Readout(seconds, 20.sp, template = "88", modifier = Modifier.padding(bottom = 6.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    DotText(date, 15.sp)
                }
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DotText(if (uiState.playing) "PLAY" else "STOP", 12.sp, lit = uiState.hasTrack)
                        DotText("LINK", 12.sp, lit = uiState.connection == ConnectionState.CONNECTED)
                    }
                    Spacer(Modifier.height(14.dp))
                    DotText(
                        (uiState.title.ifBlank { stringResource(R.string.no_track) }).uppercase(),
                        20.sp,
                        weight = FontWeight.Black,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1_500, velocity = 40.dp),
                    )
                    DotText(
                        listOf(uiState.artist, uiState.album).filter { it.isNotBlank() }.joinToString(" · ").uppercase().ifBlank { " " },
                        14.sp,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2_500, velocity = 30.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    SegmentBar(
                        fraction = if (uiState.durationMs > 0) uiState.positionMs.toFloat() / uiState.durationMs else 0f,
                        segments = 32,
                        modifier = Modifier.fillMaxWidth(0.85f).height(6.dp),
                    )
                }
            }
        }
    }
}

// Lets a whole display panel act as one big button.
@Composable
private fun Pressable(onClick: () -> Unit, content: @Composable () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier.clickableNoRipple {
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            onClick()
        },
    ) { content() }
}

private data class SelectorModel(
    val caption: String,
    val cap: String,
    val readout: String,
    val led: Boolean,
    val activeColor: Color?,
    val onClick: () -> Unit,
)

@Composable
private fun selectorModels(
    uiState: PanelUiState,
    onOpenPanel: (NavScreen) -> Unit,
    onOpenConnection: () -> Unit,
    onOpenGestureSettings: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMusic: () -> Unit,
): List<SelectorModel> {
    val resource = uiState.resource
    val cpu = resource?.cpuUsagePercent?.roundToInt()
    val connected = uiState.connection == ConnectionState.CONNECTED
    return listOf(
        SelectorModel(stringResource(R.string.tile_player), "PLAY", if (uiState.playing) "ON AIR" else "IDLE", uiState.playing, null) {
            onOpenPanel(NavScreen.PLAYER)
        },
        SelectorModel(stringResource(R.string.tile_stats), "SYS", cpu?.let { "CPU $it%" } ?: "----", resource != null, null) {
            onOpenPanel(NavScreen.STATS)
        },
        SelectorModel(stringResource(R.string.tile_clock), "CLK", "TIME", false, null) { onOpenPanel(NavScreen.CLOCK) },
        // The phone's own files, not the PC: its own player and listening journal.
        SelectorModel(stringResource(R.string.tile_music), "MUS", "LOCAL", false, null, onOpenMusic),
        SelectorModel(
            stringResource(R.string.tile_connection),
            "LINK",
            if (uiState.transport == Transport.BLUETOOTH) "BT" else "USB",
            true,
            if (connected) null else Color(0xFFFF4B3E),
            onOpenConnection,
        ),
        SelectorModel(stringResource(R.string.tile_gestures), "GEST", "SWIPE", false, null, onOpenGestureSettings),
        SelectorModel(stringResource(R.string.tile_settings), "SET", "SETUP", false, null, onOpenSettings),
    )
}

@Composable
private fun ColumnScope.SelectorGrid(selectors: List<SelectorModel>, columns: Int) {
    selectors.chunked(columns).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { model ->
                BoxWithConstraints(Modifier.weight(1f)) {
                    KeyButton(
                        onClick = model.onClick,
                        width = maxWidth,
                        height = 64.dp,
                        active = model.led,
                        activeColor = model.activeColor ?: LocalInk.current,
                        caption = model.caption,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CapLabel(model.cap, 15.sp)
                            Caption(model.readout, color = Ink.TextSubtle, size = 8.sp)
                        }
                    }
                }
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(14.dp))
    }
}
