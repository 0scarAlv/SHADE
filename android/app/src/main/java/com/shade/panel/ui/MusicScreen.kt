package com.shade.panel.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import com.shade.panel.R
import com.shade.panel.music.LocalSpectrum
import com.shade.panel.music.Lyrics
import com.shade.panel.music.MusicViewModel
import com.shade.panel.music.NowPlaying
import com.shade.panel.music.QueueEntry
import com.shade.panel.music.Song
import com.shade.panel.ui.theme.Manrope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val MINI_HEIGHT = 76.dp
private val QUEUE_SWIPE = 96.dp
private val SKIP_SWIPE = 64.dp
private val SCREEN_PADDING = 20.dp

private enum class PlayerView { ART, LYRICS, QUEUE }

private enum class LibraryTab { SONGS, STATS }

// The app's main screen: the phone's own songs, with a mini player docked at
// the bottom that drags (or taps) open into the full player, Poweramp style.
// The deck — the PC panel and the rest — is one slide of the DECK key away.
@Composable
fun MusicScreen(vm: MusicViewModel, onOpenDeck: () -> Unit) {
    val context = LocalContext.current
    val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    fun granted() = ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED
    var hasAccess by remember { mutableStateOf(granted()) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { hasAccess = granted() }
    // Notifications only matter for the media notification; music plays without them.
    val wanted = buildList {
        add(audioPermission)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    LaunchedEffect(Unit) { if (!hasAccess) request.launch(wanted) }
    LaunchedEffect(hasAccess) { if (hasAccess) vm.start() }

    val now by vm.now.collectAsState()
    // Like the clock, this screen takes its ink from the cover of what it's playing (when ink is on AUTO).
    val settings = LocalAppSettings.current
    val palette = rememberArtPalette(now.artUri)
    val auto by animateColorAsState(palette.accent, tween(900), label = "musicInk")
    val ink = if (settings.inkColor == InkColor.AUTO && now.hasTrack) auto else LocalInk.current

    // 0 = mini player, 1 = full player.
    val expand = rememberDragValue()
    BackHandler(enabled = expand.target > 0.5f) { expand.animateTo(0f) }

    CompositionLocalProvider(LocalInk provides ink) {
        ScreenBase(Modifier.fillMaxSize()) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = SCREEN_PADDING, vertical = 18.dp),
            ) {
                val sheetHeight = maxHeight
                Column(Modifier.fillMaxSize()) {
                    TopStrip(model = "SP-1 · Música") { SlideKey("DECK", onSlide = onOpenDeck) }
                    Spacer(Modifier.height(14.dp))
                    if (!hasAccess) NoAccess { request.launch(wanted) } else Library(vm, now)
                }
                if (hasAccess && now.hasTrack) {
                    PlayerSheet(now, vm, expand.value, sheetHeight, onDrag = { fraction ->
                        expand.snapTo((expand.value - fraction).coerceIn(0f, 1f))
                    }, onSettle = { velocityUp ->
                        val open = if (velocityUp != 0f) velocityUp > 0f else expand.value > 0.5f
                        expand.animateTo(if (open) 1f else 0f, spring(stiffness = 500f))
                    }, onToggle = {
                        expand.animateTo(if (expand.value > 0.5f) 0f else 1f, spring(stiffness = 500f))
                    })
                }
            }
        }
    }
}

@Composable
private fun NoAccess(onAsk: () -> Unit) {
    DisplayPanel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DotText("SIN ACCESO", 20.sp, weight = FontWeight.Black)
            Text(stringResource(R.string.music_permission), style = TextStyle(fontFamily = Manrope, fontSize = 14.sp, color = Ink.TextMuted))
            KeyButton(onClick = onAsk, width = 120.dp, height = 40.dp) { CapLabel("PERMITIR", 11.sp) }
        }
    }
}

// ------------------------------------------------------------------ library

@Composable
private fun ColumnScope.Library(vm: MusicViewModel, now: NowPlaying) {
    val songs by vm.songs.collectAsState()
    val loading by vm.loading.collectAsState()
    val upNext by vm.upNext.collectAsState()
    // Queue slot of each hand-queued song, for the badge on its row.
    val queuedAt = remember(upNext) {
        upNext.filter { it.queued }.withIndex().associate { (i, e) -> e.mediaId to i + 1 }
    }
    var added by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(added) {
        if (added != null) {
            delay(1_600)
            added = null
        }
    }

    var tab by rememberSaveable { mutableStateOf(LibraryTab.SONGS) }
    val bottomPadding = if (now.hasTrack) MINI_HEIGHT + 12.dp else 0.dp

    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            DotText("CANCIONES", 15.sp, weight = FontWeight.Black, lit = tab == LibraryTab.SONGS, modifier = Modifier.clickableNoRipple { tab = LibraryTab.SONGS })
            DotText("STATS", 15.sp, weight = FontWeight.Black, lit = tab == LibraryTab.STATS, modifier = Modifier.clickableNoRipple { tab = LibraryTab.STATS })
        }
        if (tab == LibraryTab.SONGS) {
            KeyButton(onClick = vm::shuffleAll, width = 64.dp, height = 40.dp, active = now.shuffle) { CapLabel("MIX", 11.sp) }
        }
    }
    Spacer(Modifier.height(4.dp))
    if (tab == LibraryTab.STATS) {
        Spacer(Modifier.height(8.dp))
        StatsPane(vm, bottomPadding, Modifier.weight(1f))
        return
    }
    Caption(
        when {
            loading && songs.isEmpty() -> stringResource(R.string.music_scanning)
            loading -> "${songs.size} canciones · leyendo etiquetas…"
            else -> "${songs.size} canciones · " + stringResource(R.string.music_swipe_hint)
        },
        color = Ink.TextSubtle,
        size = 9.sp,
    )
    Spacer(Modifier.height(10.dp))

    Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomPadding),
        ) {
            items(songs, key = { it.id }) { song ->
                SongRow(
                    song,
                    current = song.id.toString() == now.mediaId,
                    queuedAt = queuedAt[song.id.toString()],
                    onClick = { vm.play(song) },
                    onQueue = {
                        vm.addToQueue(song)
                        added = song.title
                    },
                )
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = added != null,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(300)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (now.hasTrack) MINI_HEIGHT + 10.dp else 10.dp),
        ) {
            DisplayPanel(contentPadding = 10.dp) {
                DotText("+ COLA · ${(added ?: "").uppercase()}", 12.sp, modifier = Modifier.width(260.dp))
            }
        }
    }
}

// A song: tap plays it, swipe left queues it (the row springs back).
@Composable
private fun SongRow(song: Song, current: Boolean, queuedAt: Int?, onClick: () -> Unit, onQueue: () -> Unit) {
    val ink = LocalInk.current
    val haptics = LocalHapticFeedback.current
    val limitPx = with(LocalDensity.current) { QUEUE_SWIPE.toPx() }
    val offset = rememberDragValue()
    val pull = (-offset.value / limitPx).coerceIn(0f, 1f)

    Box(Modifier.fillMaxWidth()) {
        // Revealed behind the row as it slides.
        Row(
            Modifier.matchParentSize().padding(end = 12.dp).alpha(pull),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DotText("+ COLA", 13.sp, weight = FontWeight.Black, lit = pull >= 1f)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .background(Ink.Background)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        offset.snapTo((offset.value + delta).coerceIn(-limitPx * 1.3f, 0f))
                    },
                    onDragStopped = {
                        if (offset.value <= -limitPx) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onQueue()
                        }
                        offset.animateTo(0f, spring(stiffness = 700f))
                    },
                )
                .clickableNoRipple(onClick)
                .padding(horizontal = 2.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtImage(song.artUri, Modifier.size(44.dp), shape = RoundedCornerShape(8.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    style = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (current) ink else Ink.Text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    song.artist,
                    style = TextStyle(fontFamily = Manrope, fontSize = 12.sp, color = Ink.TextMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            when {
                current -> DotText("PLAY", 10.sp)
                queuedAt != null -> DotText("Q$queuedAt", 10.sp)
                else -> Caption(clock(song.durationMs), color = Ink.TextSubtle)
            }
        }
    }
}

// ------------------------------------------------------------------ player sheet

// One surface that rides up from the bottom: at rest only its top (the mini
// player) shows; dragged or tapped open it covers the screen as the full player.
@Composable
private fun PlayerSheet(
    now: NowPlaying,
    vm: MusicViewModel,
    expanded: Float,
    height: Dp,
    onDrag: (fraction: Float) -> Unit,
    onSettle: (velocityUp: Float) -> Unit,
    onToggle: () -> Unit,
) {
    val travelPx = with(LocalDensity.current) { (height - MINI_HEIGHT).toPx() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .offset { IntOffset(0, ((1f - expanded) * travelPx).roundToInt()) }
            .background(Ink.Background.copy(alpha = expanded.coerceIn(0f, 1f)))
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> onDrag(delta / travelPx) },
                onDragStopped = { velocity ->
                    // Only a real fling picks the direction; a slow drag settles on the nearest end.
                    onSettle(if (kotlin.math.abs(velocity) > 800f) -velocity else 0f)
                },
            ),
    ) {
        if (expanded < 0.98f) {
            MiniPlayer(now, vm, onToggle, Modifier.alpha((1f - expanded * 3f).coerceIn(0f, 1f)))
        }
        if (expanded > 0.02f) {
            FullPlayer(now, vm, onCollapse = onToggle, modifier = Modifier.alpha(((expanded - 0.25f) / 0.75f).coerceIn(0f, 1f)))
        }
    }
}

@Composable
private fun MiniPlayer(now: NowPlaying, vm: MusicViewModel, onOpen: () -> Unit, modifier: Modifier) {
    // Sideways on the mini player skips too; the song info slides with the finger.
    val (swipe, trackSwipe) = rememberTrackSwipe(vm)
    Box(modifier.fillMaxWidth().height(MINI_HEIGHT).then(trackSwipe).clickableNoRipple(onOpen)) {
        DisplayPanel(Modifier.fillMaxSize(), contentPadding = 10.dp) {
            Column {
                Row(Modifier.weight(1f).graphicsLayer { translationX = swipe.value }, verticalAlignment = Alignment.CenterVertically) {
                    ArtImage(now.artUri, Modifier.size(46.dp), shape = RoundedCornerShape(8.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        DotText(
                            now.title.uppercase(),
                            14.sp,
                            weight = FontWeight.Black,
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2_000, velocity = 30.dp),
                        )
                        Caption(now.artist, color = Ink.TextMuted, size = 10.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    KeyButton(onClick = vm::playPause, width = 54.dp, height = 40.dp, active = now.playing, contentDescription = stringResource(R.string.action_play_pause)) {
                        TransportGlyph(Glyph.PLAY_PAUSE)
                    }
                    Spacer(Modifier.width(8.dp))
                    KeyButton(onClick = vm::next, width = 44.dp, height = 40.dp, contentDescription = stringResource(R.string.action_next)) {
                        TransportGlyph(Glyph.NEXT)
                    }
                }
                Spacer(Modifier.height(6.dp))
                SegmentBar(fraction = now.fraction, segments = 60, modifier = Modifier.fillMaxWidth().height(3.dp))
            }
        }
    }
}

@Composable
private fun FullPlayer(now: NowPlaying, vm: MusicViewModel, onCollapse: () -> Unit, modifier: Modifier) {
    var view by rememberSaveable { mutableStateOf(PlayerView.ART) }
    val toggle = { target: PlayerView -> view = if (view == target) PlayerView.ART else target }
    // Swipe the cover (or lyrics) sideways to skip. Not on the queue view,
    // where a sideways slip while browsing shouldn't change the song.
    val (swipe, trackSwipe) = rememberTrackSwipe(vm, enabled = view != PlayerView.QUEUE)
    val skipPx = with(LocalDensity.current) { SKIP_SWIPE.toPx() }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val header = @Composable {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                KeyButton(onClick = onCollapse, width = 54.dp, height = 34.dp, contentDescription = stringResource(R.string.action_back)) {
                    CapLabel("▾", 14.sp)
                }
                Spacer(Modifier.width(12.dp))
                Caption("Reproduciendo", color = Ink.TextSubtle)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DotText(if (now.playing) "PLAY" else "STOP", 11.sp, lit = now.hasTrack)
                    DotText("SHF", 11.sp, lit = now.shuffle)
                    DotText(if (now.repeatMode == Player.REPEAT_MODE_ONE) "RPT1" else "RPT", 11.sp, lit = now.repeatMode != Player.REPEAT_MODE_OFF)
                }
            }
        }
        val bleed = if (maxWidth > maxHeight) 0.dp else SCREEN_PADDING * 2
        val media = @Composable { m: Modifier ->
            Box(
                m.then(trackSwipe).graphicsLayer {
                    translationX = swipe.value
                    alpha = 1f - (kotlin.math.abs(swipe.value) / (skipPx * 3f)).coerceAtMost(0.5f)
                },
                contentAlignment = Alignment.Center,
            ) {
                when (view) {
                    // Square and square-cornered; in portrait it runs edge to edge, past the screen padding.
                    PlayerView.ART -> BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ArtImage(
                            now.artUri,
                            Modifier
                                .requiredSize(min(maxWidth + bleed, maxHeight))
                                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { toggle(PlayerView.LYRICS) }) },
                            shape = RectangleShape,
                        )
                    }
                    PlayerView.LYRICS -> DisplayPanel(
                        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onDoubleTap = { toggle(PlayerView.LYRICS) }) },
                    ) { LyricsView(vm, now.positionMs) }
                    PlayerView.QUEUE -> DisplayPanel(Modifier.fillMaxSize(), contentPadding = 10.dp) { QueueList(vm) }
                }
            }
        }
        val controls = @Composable { m: Modifier -> Controls(now, vm, view, toggle, m) }

        if (maxWidth > maxHeight) {
            Column(Modifier.fillMaxSize()) {
                header()
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxSize()) {
                    media(Modifier.weight(1f).fillMaxHeight())
                    Spacer(Modifier.width(18.dp))
                    controls(Modifier.weight(1f).fillMaxHeight())
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                header()
                Spacer(Modifier.height(16.dp))
                media(Modifier.fillMaxWidth().weight(1f))
                Spacer(Modifier.height(18.dp))
                controls(Modifier.fillMaxWidth())
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun Controls(now: NowPlaying, vm: MusicViewModel, view: PlayerView, toggle: (PlayerView) -> Unit, modifier: Modifier) {
    val cyber = cyberOn()
    Column(modifier, verticalArrangement = Arrangement.Bottom) {
        if (cyber) {
            CyberText(now.title.uppercase(), 22.sp, weight = FontWeight.Black, modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2_000, velocity = 40.dp))
        } else {
            DotText(
                now.title.uppercase(),
                22.sp,
                weight = FontWeight.Black,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2_000, velocity = 40.dp),
            )
        }
        DotText(
            listOf(now.artist, now.album).filter { it.isNotBlank() }.joinToString(" · ").uppercase().ifBlank { " " },
            13.sp,
            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 3_000, velocity = 30.dp),
        )
        Spacer(Modifier.height(12.dp))
        // The sound itself, from LocalSpectrum; tap to cycle bars → mirror → wave, like the panel.
        val settings = LocalAppSettings.current
        val beat = rememberBeat(LocalSpectrum.bands, cyber && now.playing)
        BarSpectrum(
            LocalSpectrum.bands,
            modifier = Modifier.fillMaxWidth().height(64.dp).clickableNoRipple {
                val styles = SpectrumStyle.entries
                settings.spectrumStyle = styles[(settings.spectrumStyle.ordinal + 1) % styles.size]
            },
            rows = 10,
            style = settings.spectrumStyle,
            beat = beat,
        )
        Spacer(Modifier.height(10.dp))
        SegmentBar(
            fraction = now.fraction,
            segments = 40,
            modifier = Modifier.fillMaxWidth().height(12.dp),
            onSeek = vm::seekTo,
            cyber = cyber,
            pulse = now.playing,
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Caption(clock(now.positionMs), color = Ink.TextMuted)
            Spacer(Modifier.weight(1f))
            Caption(clock(now.durationMs), color = Ink.TextSubtle)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KeyButton(onClick = vm::previous, modifier = Modifier.weight(1f), width = 1000.dp, height = 54.dp, contentDescription = stringResource(R.string.action_previous)) {
                TransportGlyph(Glyph.PREV)
            }
            KeyButton(
                onClick = vm::playPause,
                modifier = Modifier.weight(1.4f),
                width = 1000.dp,
                height = 54.dp,
                active = now.playing,
                contentDescription = stringResource(R.string.action_play_pause),
            ) { TransportGlyph(Glyph.PLAY_PAUSE) }
            KeyButton(onClick = vm::next, modifier = Modifier.weight(1f), width = 1000.dp, height = 54.dp, contentDescription = stringResource(R.string.action_next)) {
                TransportGlyph(Glyph.NEXT)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val key = Modifier.weight(1f)
            KeyButton(onClick = vm::toggleShuffle, modifier = key, width = 1000.dp, active = now.shuffle, caption = "Shuffle") { CapLabel("SHF", 11.sp) }
            KeyButton(onClick = vm::cycleRepeat, modifier = key, width = 1000.dp, active = now.repeatMode != Player.REPEAT_MODE_OFF, caption = "Repeat") {
                CapLabel(if (now.repeatMode == Player.REPEAT_MODE_ONE) "RPT1" else "RPT", 11.sp)
            }
            KeyButton(onClick = { toggle(PlayerView.LYRICS) }, modifier = key, width = 1000.dp, active = view == PlayerView.LYRICS, caption = "Lyric") {
                CapLabel("TXT", 11.sp)
            }
            KeyButton(onClick = { toggle(PlayerView.QUEUE) }, modifier = key, width = 1000.dp, active = view == PlayerView.QUEUE, caption = "Queue") {
                CapLabel("COLA", 11.sp)
            }
        }
    }
}

// Embedded lyrics: synced ones as the panel's karaoke readout, plain ones as scrolling text.
@Composable
private fun LyricsView(vm: MusicViewModel, positionMs: Long) {
    val lyrics: Lyrics by vm.lyrics.collectAsState()
    val offsetMs = LocalAppSettings.current.lyricsOffsetMs
    when {
        lyrics.synced -> LyricsReadout(lyrics.lines, lyrics.indexAt(positionMs + offsetMs), Modifier.fillMaxSize(), cyberOn())
        lyrics.plain != null -> Text(
            lyrics.plain.orEmpty(),
            style = TextStyle(fontFamily = Manrope, fontSize = 16.sp, lineHeight = 24.sp, color = Ink.Text),
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        )
        else -> LyricsReadout(emptyList(), -1, Modifier.fillMaxSize(), false)
    }
}

// What plays next: the hand-queued songs first, then the rest of the list.
@Composable
private fun QueueList(vm: MusicViewModel) {
    val upNext by vm.upNext.collectAsState()
    val queued = upNext.takeWhile { it.queued }
    val rest = upNext.drop(queued.size)
    if (upNext.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            DotText("NADA DESPUÉS", 16.sp)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Caption(if (queued.isEmpty()) "Cola vacía · desliza una canción a la izquierda" else "En cola · ${queued.size}", color = Ink.TextSubtle, modifier = Modifier.padding(4.dp))
        }
        items(queued, key = { "q${it.index}:${it.mediaId}" }) { QueueRow(it, vm) }
        if (rest.isNotEmpty()) {
            item { Caption("A continuación", color = Ink.TextSubtle, modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp)) }
            items(rest, key = { "n${it.index}:${it.mediaId}" }) { QueueRow(it, vm) }
        }
    }
}

@Composable
private fun QueueRow(entry: QueueEntry, vm: MusicViewModel) {
    Row(
        Modifier.fillMaxWidth().clickableNoRipple { vm.jumpTo(entry.index) }.padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtImage(entry.artUri, Modifier.size(36.dp), shape = RoundedCornerShape(6.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                style = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = if (entry.queued) LocalInk.current else Ink.Text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(entry.artist, style = TextStyle(fontFamily = Manrope, fontSize = 11.sp, color = Ink.TextMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (entry.queued) {
            KeyButton(onClick = { vm.removeAt(entry.index) }, width = 36.dp, height = 30.dp, contentDescription = "Quitar de la cola") { CapLabel("×", 13.sp) }
        }
    }
}

// Horizontal drag that skips: left = next song, right = previous. Returns the
// live offset (for the content to follow the finger) and the gesture modifier.
@Composable
private fun rememberTrackSwipe(vm: MusicViewModel, enabled: Boolean = true): Pair<DragValue, Modifier> {
    val haptics = LocalHapticFeedback.current
    val skipPx = with(LocalDensity.current) { SKIP_SWIPE.toPx() }
    val swipe = rememberDragValue()
    val modifier = if (!enabled) Modifier else Modifier.pointerInput(Unit) {
        detectHorizontalDragGestures(
            onDragEnd = {
                val dx = swipe.value
                if (dx <= -skipPx || dx >= skipPx) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (dx < 0) vm.next() else vm.previousTrack()
                }
                swipe.animateTo(0f, spring(stiffness = 500f))
            },
            onDragCancel = { swipe.animateTo(0f) },
        ) { change, delta ->
            change.consume()
            swipe.snapTo((swipe.value + delta).coerceIn(-skipPx * 1.6f, skipPx * 1.6f))
        }
    }
    return swipe to modifier
}

private val NowPlaying.fraction get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
