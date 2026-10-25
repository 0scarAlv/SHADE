package com.shade.panel.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import com.shade.panel.R
import com.shade.panel.music.MusicViewModel
import com.shade.panel.music.NowPlaying
import com.shade.panel.music.Song
import androidx.compose.ui.res.stringResource
import com.shade.panel.ui.theme.Manrope

// Música: the phone's own files. Same instrument layout as the panel — a
// display with what's playing, a row of keys, then the library list.
@Composable
fun MusicScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val vm: MusicViewModel = viewModel()

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

    CompositionLocalProvider(LocalInk provides ink) {
        ScreenBase(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                TopStrip(model = "SP-1 · Música") {
                    KeyButton(onClick = onBack, width = 64.dp, height = 34.dp, contentDescription = stringResource(R.string.action_back)) {
                        CapLabel("BACK", 11.sp)
                    }
                }
                Spacer(Modifier.height(14.dp))
                if (!hasAccess) {
                    NoAccess { request.launch(wanted) }
                } else {
                    Deck(now, vm)
                    Spacer(Modifier.height(14.dp))
                    Library(vm, now)
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

@Composable
private fun Deck(now: NowPlaying, vm: MusicViewModel) {
    DisplayPanel(Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArtImage(now.artUri, Modifier.size(88.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DotText(if (now.playing) "PLAY" else "STOP", 11.sp, lit = now.hasTrack)
                        DotText("SHF", 11.sp, lit = now.shuffle)
                        DotText(if (now.repeatMode == Player.REPEAT_MODE_ONE) "RPT1" else "RPT", 11.sp, lit = now.repeatMode != Player.REPEAT_MODE_OFF)
                    }
                    Spacer(Modifier.height(8.dp))
                    DotText(
                        now.title.ifBlank { stringResource(R.string.music_idle) }.uppercase(),
                        18.sp,
                        weight = FontWeight.Black,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1_500, velocity = 40.dp),
                    )
                    DotText(
                        listOf(now.artist, now.album).filter { it.isNotBlank() }.joinToString(" · ").uppercase().ifBlank { " " },
                        12.sp,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2_500, velocity = 30.dp),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            SegmentBar(
                fraction = if (now.durationMs > 0) now.positionMs.toFloat() / now.durationMs else 0f,
                segments = 40,
                modifier = Modifier.fillMaxWidth().height(10.dp),
                onSeek = if (now.hasTrack) vm::seekTo else null,
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                Caption(clock(now.positionMs), color = Ink.TextMuted)
                Spacer(Modifier.weight(1f))
                Caption(clock(now.durationMs), color = Ink.TextSubtle)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                KeyButton(onClick = vm::previous, width = 52.dp, contentDescription = stringResource(R.string.action_previous)) { TransportGlyph(Glyph.PREV) }
                KeyButton(onClick = vm::playPause, width = 72.dp, active = now.playing, contentDescription = stringResource(R.string.action_play_pause)) {
                    TransportGlyph(Glyph.PLAY_PAUSE)
                }
                KeyButton(onClick = vm::next, width = 52.dp, contentDescription = stringResource(R.string.action_next)) { TransportGlyph(Glyph.NEXT) }
                KeyButton(onClick = vm::toggleShuffle, width = 52.dp, active = now.shuffle) { CapLabel("SHF", 11.sp) }
                KeyButton(onClick = vm::cycleRepeat, width = 52.dp, active = now.repeatMode != Player.REPEAT_MODE_OFF) {
                    CapLabel(if (now.repeatMode == Player.REPEAT_MODE_ONE) "RPT1" else "RPT", 11.sp)
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Library(vm: MusicViewModel, now: NowPlaying) {
    val songs by vm.shown.collectAsState()
    val all by vm.songs.collectAsState()
    val loading by vm.loading.collectAsState()
    val query by vm.query.collectAsState()

    Row(verticalAlignment = Alignment.CenterVertically) {
        DisplayPanel(Modifier.weight(1f).height(44.dp), contentPadding = 12.dp) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Caption(stringResource(R.string.music_search), color = Ink.TextSubtle, size = 11.sp)
                BasicTextField(
                    value = query,
                    onValueChange = vm::setQuery,
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = Manrope, fontSize = 15.sp, color = Ink.Text),
                    cursorBrush = SolidColor(LocalInk.current),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        KeyButton(onClick = vm::shuffleAll, width = 60.dp, height = 44.dp) { CapLabel("MIX", 11.sp) }
    }
    Spacer(Modifier.height(8.dp))
    Caption(
        when {
            loading && all.isEmpty() -> stringResource(R.string.music_scanning)
            query.isNotBlank() -> "${songs.size} de ${all.size} canciones"
            else -> "${all.size} canciones" + if (loading) " · leyendo etiquetas" else ""
        },
        color = Ink.TextSubtle,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )

    val listState = rememberLazyListState()
    LaunchedEffect(query) { listState.scrollToItem(0) }
    LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
        items(songs, key = { it.id }) { song -> SongRow(song, current = song.id.toString() == now.mediaId) { vm.play(song) } }
    }
}

@Composable
private fun SongRow(song: Song, current: Boolean, onClick: () -> Unit) {
    val ink = LocalInk.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickableNoRipple(onClick)
            .padding(horizontal = 4.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(on = current, color = ink, size = 6.dp)
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
        Caption(clock(song.durationMs), color = Ink.TextSubtle)
    }
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
