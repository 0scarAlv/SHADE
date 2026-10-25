package com.shade.panel.music

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import com.shade.panel.ui.BAND_COUNT
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spectrum of what the phone itself is playing, in the same 32 bands and
 * scale as the agent's SpectrumAnalyzer.cs, so BarSpectrum looks the same for
 * local music as for the PC. ExoPlayer hands every PCM buffer to this sink
 * (through a TeeAudioProcessor) on its playback thread.
 */
@UnstableApi
object LocalSpectrum : TeeAudioProcessor.AudioBufferSink {
    private const val FFT_LENGTH = 2048
    private const val HOP = FFT_LENGTH / 2

    private val _bands = MutableStateFlow(FloatArray(BAND_COUNT))
    val bands: StateFlow<FloatArray> = _bands.asStateFlow()

    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private val samples = FloatArray(FFT_LENGTH)
    private var count = 0
    private val re = FloatArray(FFT_LENGTH)
    private val im = FloatArray(FFT_LENGTH)
    private val window = FloatArray(FFT_LENGTH) { i -> (0.5 * (1 - cos(2 * PI * i / (FFT_LENGTH - 1)))).toFloat() }

    // Same treble tilt as the agent: up to +18 dB at the top band.
    private val gain = FloatArray(BAND_COUNT) { b -> 10f.pow(b / (BAND_COUNT - 1f) * 18f / 20f) }

    /** Silence: bars fall back down while paused or stopped. */
    fun clear() {
        count = 0
        _bands.value = FloatArray(BAND_COUNT)
    }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        channels = channelCount.coerceAtLeast(1)
        this.encoding = encoding
        count = 0
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val data = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val float = encoding == C.ENCODING_PCM_FLOAT
        if (!float && encoding != C.ENCODING_PCM_16BIT) return
        val frameBytes = (if (float) 4 else 2) * channels
        while (data.remaining() >= frameBytes) {
            // Mono downmix of the first two channels, like the agent.
            val start = data.position()
            var s = if (float) data.getFloat(start) else data.getShort(start) / 32768f
            if (channels > 1) s = (s + if (float) data.getFloat(start + 4) else data.getShort(start + 2) / 32768f) * 0.5f
            data.position(start + frameBytes)
            samples[count++] = s
            if (count == FFT_LENGTH) {
                analyze()
                System.arraycopy(samples, HOP, samples, 0, FFT_LENGTH - HOP)
                count = FFT_LENGTH - HOP
            }
        }
    }

    private fun analyze() {
        for (i in 0 until FFT_LENGTH) {
            re[i] = samples[i] * window[i]
            im[i] = 0f
        }
        fft()
        val usable = FFT_LENGTH / 2
        val out = FloatArray(BAND_COUNT)
        for (band in 0 until BAND_COUNT) {
            // Squared spacing: narrow bass bands, wide treble bands.
            val low = maxOf(1, ((band.toDouble() / BAND_COUNT).pow(2) * usable).toInt())
            val high = maxOf(low + 1, (((band + 1).toDouble() / BAND_COUNT).pow(2) * usable).toInt())
            var sum = 0f
            var n = 0
            var bin = low
            while (bin < high && bin < usable) {
                sum += sqrt(re[bin] * re[bin] + im[bin] * im[bin])
                n++
                bin++
            }
            // NAudio scales its forward FFT by 1/N; match it so the curve below reads the same.
            val magnitude = if (n > 0) sum / n / FFT_LENGTH else 0f
            out[band] = (log10(1 + magnitude * gain[band] * 200) / 1.4f).coerceIn(0f, 1f)
        }
        _bands.value = out
    }

    // In-place iterative radix-2 FFT.
    private fun fft() {
        val n = FFT_LENGTH
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            val wr = cos(angle).toFloat()
            val wi = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
