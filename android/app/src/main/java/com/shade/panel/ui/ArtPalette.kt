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

// Colors pulled from the current cover, the way most players do it: the
// accent (controls) is its most prominent colorful color, second..fourth the
// next dominant distinct ones, all re-lit to read on near-black while keeping
// their hue and saturation; backdrop is a deep tint. Computed by hand instead
// of pulling in androidx.palette — one less dependency to keep in step with the
// pinned Kotlin/Coil toolchain. Same algorithm as Nation's ArtPalette.cs.
@Immutable
data class ArtPalette(
    val accent: Color,
    val backdrop: Color,
    val second: Color = accent,
    val third: Color = accent,
    val fourth: Color = accent,
)

val DefaultArtPalette = ArtPalette(
    accent = PanelAccent,
    backdrop = Color(0xFF0B1F18),
    second = Color(0xFF5EB8E0),
    third = Color(0xFFB7E4C7),
    fourth = Color(0xFF2E8B6A),
)

// Nothing usable on the cover (transparent, pure black/white): soft greys
// instead of inventing a color.
private val MonochromeArtPalette = ArtPalette(
    accent = Color(0xFFE4E4E7),
    backdrop = Color(0xFF1A1A1E),
    second = Color(0xFFA1A1AA),
    third = Color(0xFFC8C8CE),
    fourth = Color(0xFF7A7A84),
)

// The cover's colors for the cyberpunk effects: [ink, second, third, fourth].
val LocalArtColors = androidx.compose.runtime.staticCompositionLocalOf {
    listOf(DefaultArtPalette.accent, DefaultArtPalette.second, DefaultArtPalette.third, DefaultArtPalette.fourth)
}

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

// k-means over the sampled pixels (seeded from the most common coarse colors),
// then the most populated clusters that are clearly different from each other.
// Near-black and near-white clusters are skipped, as androidx.palette does.
internal fun extractPalette(bitmap: Bitmap): ArtPalette {
    val width = bitmap.width
    val height = bitmap.height
    if (width == 0 || height == 0) return DefaultArtPalette

    val raw = IntArray(width * height)
    bitmap.getPixels(raw, 0, width, 0, 0, width, height)
    val pixels = raw.filter { (it ushr 24) >= 128 }
    if (pixels.isEmpty()) return MonochromeArtPalette

    // Seeds: centers of the most populated 3-bit-per-channel boxes.
    val boxes = HashMap<Int, Int>()
    for (p in pixels) {
        val key = (((p shr 16) and 0xFF) shr 5 shl 6) or (((p shr 8) and 0xFF) shr 5 shl 3) or ((p and 0xFF) shr 5)
        boxes[key] = (boxes[key] ?: 0) + 1
    }
    val centers = boxes.entries.sortedByDescending { it.value }.take(CLUSTERS).map { (key, _) ->
        floatArrayOf(((key shr 6 and 7) * 32 + 16).toFloat(), ((key shr 3 and 7) * 32 + 16).toFloat(), ((key and 7) * 32 + 16).toFloat())
    }
    val k = centers.size
    val counts = IntArray(k)
    repeat(ITERATIONS) {
        val sum = Array(k) { FloatArray(3) }
        counts.fill(0)
        for (p in pixels) {
            val r = ((p shr 16) and 0xFF).toFloat()
            val g = ((p shr 8) and 0xFF).toFloat()
            val b = (p and 0xFF).toFloat()
            var best = 0
            var bestD = Float.MAX_VALUE
            for (i in 0 until k) {
                val d = distance2(centers[i][0], centers[i][1], centers[i][2], r, g, b)
                if (d < bestD) { bestD = d; best = i }
            }
            sum[best][0] += r; sum[best][1] += g; sum[best][2] += b
            counts[best]++
        }
        for (i in 0 until k) if (counts[i] > 0) for (c in 0..2) centers[i][c] = sum[i][c] / counts[i]
    }

    class Pick(val hsl: FloatArray, val count: Int, val rgb: FloatArray)
    val picked = mutableListOf<Pick>()
    (0 until k)
        .filter { counts[it] >= pixels.size * 0.02f }
        .map { i ->
            val hsl = FloatArray(3)
            ColorUtils.RGBToHSL(centers[i][0].toInt(), centers[i][1].toInt(), centers[i][2].toInt(), hsl)
            Pick(hsl, counts[i], centers[i])
        }
        .filter { it.hsl[2] > 0.1f && it.hsl[2] < 0.93f }
        .sortedByDescending { it.count }
        .forEach { c ->
            if (picked.size < 4 && picked.all { distance2(it.rgb[0], it.rgb[1], it.rgb[2], c.rgb[0], c.rgb[1], c.rgb[2]) > MIN_DISTANCE2 }) picked += c
        }
    if (picked.isEmpty()) return MonochromeArtPalette

    // Accent = the most prominent *colorful* one; the rest keep population order.
    val accent = picked.maxBy { it.count * (0.25f + it.hsl[1]) }
    picked.remove(accent)

    // Greys stay grey; colored ones get enough saturation and lightness to read on black.
    fun relight(hsl: FloatArray, isAccent: Boolean): Int {
        val s = if (hsl[1] < 0.12f) hsl[1] else hsl[1].coerceIn(if (isAccent) 0.5f else 0.4f, 0.9f)
        return ColorUtils.HSLToColor(floatArrayOf(hsl[0], s, hsl[2].coerceIn(if (isAccent) 0.6f else 0.55f, 0.78f)))
    }
    val accentColor = relight(accent.hsl, true)
    val colors = mutableListOf<Int>()
    // Compared after relighting: dark colors brighten and can land on top of each other.
    fun tryAdd(c: Int) {
        if (shownDistance2(c, accentColor) < MIN_SHOWN2 || colors.any { shownDistance2(it, c) < MIN_SHOWN2 }) return
        colors += c
    }
    picked.forEach { tryAdd(relight(it.hsl, false)) }
    // Fewer distinct colors than slots: lighter, then deeper shades of the accent and the others.
    val sources = listOf(accent) + picked
    val shades = floatArrayOf(0.84f, 0.45f, 0.7f, 0.35f)
    var i = 0
    while (colors.size < 3 && i < sources.size * shades.size) {
        val src = sources[i % sources.size].hsl
        val l = shades[i / sources.size]
        val s = if (src[1] < 0.12f) src[1] else (src[1] * if (l > 0.6f) 0.75f else 1f).coerceIn(0.3f, 0.85f)
        tryAdd(ColorUtils.HSLToColor(floatArrayOf(src[0], s, l)))
        i++
    }
    while (colors.size < 3) colors += accentColor // flat single-color cover

    val backdropSat = if (accent.hsl[1] < 0.12f) accent.hsl[1] else accent.hsl[1].coerceIn(0.25f, 0.55f)
    val backdrop = ColorUtils.HSLToColor(floatArrayOf(accent.hsl[0], backdropSat, 0.13f))
    return ArtPalette(
        accent = Color(accentColor),
        backdrop = Color(backdrop),
        second = Color(colors[0]),
        third = Color(colors[1]),
        fourth = Color(colors[2]),
    )
}

// Weighted RGB (2/4/3) tracks perceived difference better than plain RGB distance.
private fun distance2(r1: Float, g1: Float, b1: Float, r2: Float, g2: Float, b2: Float): Float {
    val dr = r1 - r2
    val dg = g1 - g2
    val db = b1 - b2
    return 2 * dr * dr + 4 * dg * dg + 3 * db * db
}

private fun shownDistance2(a: Int, b: Int) = distance2(
    ((a shr 16) and 0xFF).toFloat(), ((a shr 8) and 0xFF).toFloat(), (a and 0xFF).toFloat(),
    ((b shr 16) and 0xFF).toFloat(), ((b shr 8) and 0xFF).toFloat(), (b and 0xFF).toFloat(),
)

private const val SAMPLE_SIZE_PX = 64
private const val CLUSTERS = 8
private const val ITERATIONS = 8
// Weighted squared distances: between raw cluster colors, and between final relit colors.
private const val MIN_DISTANCE2 = 9f * 55f * 55f
private const val MIN_SHOWN2 = 9f * 28f * 28f

