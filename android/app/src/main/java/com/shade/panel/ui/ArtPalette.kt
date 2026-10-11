package com.shade.panel.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.shade.panel.ui.theme.PanelAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Two colors pulled from the current cover: a readable accent for controls
// and a deep tint for the backdrop behind them. Computed by hand instead of
// pulling in androidx.palette — it's ~40 lines and one less dependency to
// keep in step with the pinned Kotlin/Coil toolchain.
@Immutable
data class ArtPalette(val accent: Color, val backdrop: Color)

val DefaultArtPalette = ArtPalette(accent = PanelAccent, backdrop = Color(0xFF0B1F18))

// Black-and-white covers have no hue worth amplifying — fall back to a soft
// white accent instead of inventing a color.
private val MonochromeArtPalette = ArtPalette(accent = Color(0xFFE4E4E7), backdrop = Color(0xFF1A1A1E))

@Composable
fun rememberArtPalette(model: Any?): ArtPalette {
    val context = LocalContext.current
    var palette by remember { mutableStateOf(DefaultArtPalette) }

    LaunchedEffect(model) {
        if (model == null) {
            palette = DefaultArtPalette
            return@LaunchedEffect
        }
        // On a failed load keep whatever palette is showing rather than
        // flashing back to the default between two tracks.
        val bitmap = withContext(Dispatchers.IO) { loadSmallBitmap(context, model) } ?: return@LaunchedEffect
        palette = withContext(Dispatchers.Default) { extractPalette(bitmap) }
    }
    return palette
}

private suspend fun loadSmallBitmap(context: Context, model: Any): Bitmap? {
    val request = ImageRequest.Builder(context)
        .data(model)
        .size(SAMPLE_SIZE_PX)
        // Hardware bitmaps can't be read pixel by pixel.
        .allowHardware(false)
        .build()
    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
    return result.image.toBitmap()
}

// Hue histogram weighted toward vivid pixels (saturation² × value), smoothed
// across neighboring buckets so a gradient cover doesn't split its vote. The
// winning bucket's average color is then re-lit for each role: bright enough
// to read on near-black for the accent, very dark for the backdrop.
internal fun extractPalette(bitmap: Bitmap): ArtPalette {
    val width = bitmap.width
    val height = bitmap.height
    if (width == 0 || height == 0) return DefaultArtPalette

    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    val score = FloatArray(HUE_BUCKETS)
    val red = FloatArray(HUE_BUCKETS)
    val green = FloatArray(HUE_BUCKETS)
    val blue = FloatArray(HUE_BUCKETS)
    val hsv = FloatArray(3)

    for (pixel in pixels) {
        if ((pixel ushr 24) < 128) continue
        android.graphics.Color.colorToHSV(pixel, hsv)
        val saturation = hsv[1]
        val value = hsv[2]
        if (value < 0.18f || saturation < 0.22f) continue

        val weight = saturation * saturation * value
        val bucket = ((hsv[0] / 360f) * HUE_BUCKETS).toInt().coerceIn(0, HUE_BUCKETS - 1)
        score[bucket] += weight
        red[bucket] += ((pixel shr 16) and 0xFF) * weight
        green[bucket] += ((pixel shr 8) and 0xFF) * weight
        blue[bucket] += (pixel and 0xFF) * weight
    }

    var best = -1
    var bestScore = 0f
    for (i in 0 until HUE_BUCKETS) {
        val smoothed = score[i] + 0.5f * score[(i + HUE_BUCKETS - 1) % HUE_BUCKETS] + 0.5f * score[(i + 1) % HUE_BUCKETS]
        if (smoothed > bestScore) {
            bestScore = smoothed
            best = i
        }
    }
    if (best < 0 || score[best] < pixels.size * MIN_VIVID_SHARE) return MonochromeArtPalette

    val total = score[best]
    val dominant = android.graphics.Color.rgb(
        (red[best] / total).toInt().coerceIn(0, 255),
        (green[best] / total).toInt().coerceIn(0, 255),
        (blue[best] / total).toInt().coerceIn(0, 255),
    )

    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(dominant, hsl)
    val hue = hsl[0]
    val sat = hsl[1]

    val accent = ColorUtils.HSLToColor(floatArrayOf(hue, sat.coerceIn(0.55f, 0.9f), 0.64f))
    val backdrop = ColorUtils.HSLToColor(floatArrayOf(hue, sat.coerceIn(0.25f, 0.55f), 0.13f))
    return ArtPalette(accent = Color(accent), backdrop = Color(backdrop))
}

private const val SAMPLE_SIZE_PX = 64
private const val HUE_BUCKETS = 36
private const val MIN_VIVID_SHARE = 0.015f

