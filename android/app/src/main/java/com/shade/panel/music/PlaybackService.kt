package com.shade.panel.music

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import android.provider.MediaStore
import androidx.core.os.bundleOf
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import androidx.media3.common.util.BitmapLoader
import androidx.media3.datasource.DataSourceBitmapLoader
import java.util.concurrent.Executors
import com.shade.panel.MainActivity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Plays the phone's own files in the background. Media3 turns the session into
// the media notification, lock-screen controls and headset-button handling, and
// keeps the service in the foreground (type mediaPlayback) while playing.
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var recorder: ListenRecorder
    private val scope = MainScope()

    override fun onCreate() {
        super.onCreate()
        // Every PCM buffer also goes to LocalSpectrum, for the player's visualizer.
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(TeeAudioProcessor(LocalSpectrum)))
                    .build()
        }
        player = ExoPlayer.Builder(this, renderers)
            // true: ExoPlayer requests audio focus and pauses for calls/other apps.
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(ResolveItems)
            .setBitmapLoader(EmbeddedArtLoader(this))
            .build()

        recorder = ListenRecorder(ListenDb.get(this))
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_POSITION_DISCONTINUITY)) {
                    recorder.observe(player)
                }
                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) && !player.isPlaying) LocalSpectrum.clear()
            }
        })
        scope.launch {
            while (isActive) {
                recorder.observe(player)
                delay(1_000)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // Swiping the app away while paused ends the service; while playing, music keeps going.
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        recorder.close()
        scope.cancel()
        session?.release()
        session = null
        player.release()
        super.onDestroy()
    }

    // Items sent from the app's controller arrive without their URI (it isn't part
    // of what crosses the session boundary); rebuild it from the MediaStore id.
    private object ResolveItems : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { item ->
                if (item.localConfiguration != null) item
                else item.buildUpon().setUri(contentUri(item.mediaId)).build()
            }.toMutableList(),
        )
    }

    // Notification/lock-screen art: the items' artwork URI is the song file
    // itself (see Song.artUri), so read its embedded cover like AudioArtFetcher.
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private class EmbeddedArtLoader(private val context: android.content.Context) : BitmapLoader {
        private val fallback = DataSourceBitmapLoader(context)
        private val executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

        override fun supportsMimeType(mimeType: String) = fallback.supportsMimeType(mimeType)
        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = fallback.decodeBitmap(data)
        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
            if (android.os.Build.VERSION.SDK_INT >= 29 && uri.authority == "media" && uri.path?.contains("/audio/media/") == true) {
                executor.submit<Bitmap> { context.contentResolver.loadThumbnail(uri, Size(512, 512), null) }
            } else {
                fallback.loadBitmap(uri)
            }
    }

    companion object {
        fun contentUri(mediaId: String) =
            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId.toLongOrNull() ?: -1)
    }
}

/** [queued]: added by hand to play next — the UI lists these as "la cola". */
fun Song.toMediaItem(queued: Boolean = false): MediaItem = MediaItem.Builder()
    .setMediaId(id.toString())
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setGenre(genre)
            .setArtworkUri(artUri)
            .setDurationMs(durationMs)
            .setExtras(bundleOf(ListenRecorder.EXTRA_ISRC to isrc, ListenRecorder.EXTRA_PATH to path, EXTRA_QUEUED to queued))
            .build(),
    )
    .build()

private const val EXTRA_QUEUED = "shade.queued"

val MediaItem.isQueued: Boolean get() = mediaMetadata.extras?.getBoolean(EXTRA_QUEUED) == true
