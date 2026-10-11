package com.shade.panel.ui

import android.os.Build
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.shade.panel.R
import com.shade.panel.data.ConnectionState
import com.shade.panel.data.NavScreen
import com.shade.panel.data.Transport
import com.shade.panel.ui.theme.PanelBackground
import com.shade.panel.ui.theme.PanelOnBackgroundMuted
import com.shade.panel.ui.theme.PanelOnBackgroundSubtle
import com.shade.panel.ui.theme.PanelViolet
import com.shade.panel.ui.theme.PanelWarning
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// Same threshold GestureNavShell uses for its own swipe detection — kept in
// sync by convention rather than shared, since the two screens' gesture
// detectors serve different destinations (this one only opens the player).
private val SWIPE_UP_THRESHOLD = 80.dp

// Dashboard: a live header (greeting, time, connection), a hero card for
// whatever is playing, and tiles that each preview their screen's data so
// Home is useful at a glance instead of being just a menu.
@Composable
fun HomeScreen(
    viewModel: PanelViewModel,
    onOpenPanel: (NavScreen) -> Unit,
    onOpenConnection: () -> Unit,
    onOpenGestureSettings: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val accent = MaterialTheme.colorScheme.primary
    val swipeThresholdPx = with(LocalDensity.current) { SWIPE_UP_THRESHOLD.toPx() }
    var overscroll by remember { mutableFloatStateOf(0f) }
    // Rides on the scroll container's own nested-scroll dispatch instead of
    // a competing drag detector: the column consumes the scroll normally,
    // and only the leftover it can't consume (i.e. already at the bottom)
    // reaches here — that's exactly "keep swiping past the end".
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PanelBackground)
            .background(
                Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(0f, 0f),
                    radius = 1600f,
                ),
            ),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            val landscape = maxWidth > maxHeight
            val tiles = homeTiles(uiState, onOpenPanel, onOpenConnection, onOpenGestureSettings, onOpenSettings)

            if (landscape) {
                Row(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        HomeHeader(uiState)
                        Spacer(Modifier.weight(1f))
                        NowPlayingCard(uiState, viewModel, onClick = { onOpenPanel(NavScreen.PLAYER) })
                    }
                    Spacer(Modifier.width(20.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .nestedScroll(nestedScrollConnection)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TileGrid(tiles, columns = 2)
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(nestedScrollConnection)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                ) {
                    HomeHeader(uiState)
                    Spacer(Modifier.height(24.dp))
                    NowPlayingCard(uiState, viewModel, onClick = { onOpenPanel(NavScreen.PLAYER) })
                    Spacer(Modifier.height(20.dp))
                    SectionLabel(stringResource(R.string.home_section_panel))
                    TileGrid(tiles, columns = 2)
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = stringResource(R.string.home_swipe_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = PanelOnBackgroundSubtle,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(uiState: PanelUiState) {
    val context = LocalContext.current
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(60_000 - nowMs % 60_000)
        }
    }
    val locale = Locale.getDefault()
    val is24h = DateFormat.is24HourFormat(context)
    val time = remember(nowMs, is24h) { SimpleDateFormat(if (is24h) "HH:mm" else "h:mm a", locale).format(Date(nowMs)) }
    val date = remember(nowMs) {
        SimpleDateFormat("EEEE d 'de' MMMM", locale).format(Date(nowMs)).replaceFirstChar { it.titlecase(locale) }
    }
    val hour = remember(nowMs) { Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.HOUR_OF_DAY) }
    val greeting = stringResource(
        when (hour) {
            in 5..11 -> R.string.greeting_morning
            in 12..18 -> R.string.greeting_afternoon
            else -> R.string.greeting_evening
        },
    )

    Row(verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Text(greeting, style = MaterialTheme.typography.titleMedium, color = PanelOnBackgroundMuted)
            Text(time, style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onBackground)
            Text(date, style = MaterialTheme.typography.bodyMedium, color = PanelOnBackgroundSubtle)
        }
        ConnectionPill(uiState.connection)
    }
}

// Hero card: blurred cover behind, the cover itself, title/artist, a thin
// progress line and an inline play/pause — the most common action never
// needs to leave Home.
@Composable
private fun NowPlayingCard(uiState: PanelUiState, viewModel: PanelViewModel, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val palette = LocalArtPalette.current

    ShadeCard(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().height(148.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(palette.backdrop)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Crossfade(targetState = uiState.art, animationSpec = tween(700), label = "heroBackdrop") { model ->
                    if (model != null) {
                        AsyncImage(
                            model = model,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = 1.4f
                                    scaleY = 1.4f
                                    alpha = 0.6f
                                }
                                .blur(40.dp),
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.6f)))),
            )
            Row(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Crossfade(targetState = uiState.art, label = "heroArt") { model ->
                    ArtImage(model = model, modifier = Modifier.size(116.dp), shape = RoundedCornerShape(18.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (uiState.playing) {
                            MiniEqualizer(height = 12.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            text = (uiState.appName ?: stringResource(R.string.tile_player)).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    AnimatedContent(
                        targetState = uiState.title.ifBlank { stringResource(R.string.no_track) } to uiState.artist,
                        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                        label = "heroText",
                    ) { (title, artist) ->
                        Column {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = artist.ifBlank { stringResource(R.string.no_track_hint) },
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    val progress = if (uiState.durationMs > 0) {
                        (uiState.positionMs.toFloat() / uiState.durationMs).coerceIn(0f, 1f)
                    } else 0f
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.18f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(accent),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                PressableCircle(onClick = viewModel::playPause, size = 52.dp, container = accent) {
                    Icon(
                        if (uiState.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.action_play_pause),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}

private data class HomeTileModel(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val tint: Color,
    val onClick: () -> Unit,
)

@Composable
private fun homeTiles(
    uiState: PanelUiState,
    onOpenPanel: (NavScreen) -> Unit,
    onOpenConnection: () -> Unit,
    onOpenGestureSettings: () -> Unit,
    onOpenSettings: () -> Unit,
): List<HomeTileModel> {
    val accent = MaterialTheme.colorScheme.primary
    val resource = uiState.resource
    val statsSubtitle = if (resource != null) {
        val ram = if (resource.ramTotalBytes > 0) (resource.ramUsedBytes * 100 / resource.ramTotalBytes) else 0
        stringResource(R.string.home_stats_live, resource.cpuUsagePercent.roundToInt(), ram)
    } else {
        stringResource(R.string.home_stats_idle)
    }
    val transportName = stringResource(
        if (uiState.transport == Transport.BLUETOOTH) R.string.connection_transport_bluetooth_short else R.string.connection_transport_usb_short,
    )
    val stateName = stringResource(
        when (uiState.connection) {
            ConnectionState.CONNECTED -> R.string.status_connected
            ConnectionState.CONNECTING -> R.string.status_connecting
            ConnectionState.DISCONNECTED -> R.string.status_disconnected
        },
    )
    return listOf(
        HomeTileModel(stringResource(R.string.tile_stats), statsSubtitle, Icons.Rounded.Insights, accent) { onOpenPanel(NavScreen.STATS) },
        HomeTileModel(stringResource(R.string.tile_clock), stringResource(R.string.home_clock_subtitle), Icons.Rounded.Schedule, PanelViolet) {
            onOpenPanel(NavScreen.CLOCK)
        },
        HomeTileModel(
            stringResource(R.string.tile_connection),
            "$transportName · $stateName",
            if (uiState.transport == Transport.BLUETOOTH) Icons.Rounded.Bluetooth else Icons.Rounded.Usb,
            if (uiState.connection == ConnectionState.CONNECTED) accent else PanelWarning,
            onOpenConnection,
        ),
        HomeTileModel(stringResource(R.string.tile_gestures), stringResource(R.string.home_gestures_subtitle), Icons.Rounded.Swipe, PanelOnBackgroundMuted, onOpenGestureSettings),
        HomeTileModel(stringResource(R.string.tile_settings), stringResource(R.string.home_settings_subtitle), Icons.Rounded.Tune, PanelOnBackgroundMuted, onOpenSettings),
    )
}

@Composable
private fun ColumnScope.TileGrid(tiles: List<HomeTileModel>, columns: Int) {
    tiles.chunked(columns).forEach { row ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { tile -> HomeTile(tile, Modifier.weight(1f)) }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun HomeTile(tile: HomeTileModel, modifier: Modifier = Modifier) {
    ShadeCard(onClick = tile.onClick, modifier = modifier.height(116.dp)) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            IconBadge(tile.icon, tint = tile.tint)
            Spacer(Modifier.weight(1f))
            Text(tile.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
            Text(
                text = tile.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = PanelOnBackgroundMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
