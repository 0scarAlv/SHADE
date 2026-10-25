package com.shade.panel

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.shade.panel.music.AudioArtFetcher

// Coil 3 doesn't wire up a network fetcher by default; without this, AsyncImage
// can't load http:// URLs and the cover art in PanelScreen would never show.
// AudioArtFetcher reads the cover embedded in the phone's own song files.
class ShadeApplication : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory())
                add(AudioArtFetcher.Factory(context))
            }
            .build()
}
