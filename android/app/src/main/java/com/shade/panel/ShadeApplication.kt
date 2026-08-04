package com.shade.panel

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory

// Coil 3 doesn't wire up a network fetcher by default; without this, AsyncImage
// can't load http:// URLs and the cover art in PanelScreen would never show.
class ShadeApplication : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .build()
}
