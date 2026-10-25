package com.shade.panel.music

import android.content.Context
import android.os.Build
import android.util.Size
import androidx.annotation.RequiresApi
import coil3.ImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.pxOrElse
import coil3.toAndroidUri

// Cover art for a song file (content://media/.../audio/media/{id}). MediaStore's
// album-art URIs come back empty for most FLAC/Opus files; loadThumbnail reads
// the picture embedded in the file itself.
@RequiresApi(Build.VERSION_CODES.Q)
class AudioArtFetcher(private val context: Context, private val uri: Uri, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val side = maxOf(options.size.width.pxOrElse { 512 }, options.size.height.pxOrElse { 512 }).coerceIn(96, 1024)
        val bitmap = context.contentResolver.loadThumbnail(uri.toAndroidUri(), Size(side, side), null)
        return ImageFetchResult(bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            if (data.scheme != "content" || data.authority != "media" || data.path?.contains("/audio/media/") != true) return null
            return AudioArtFetcher(context, data, options)
        }
    }
}
