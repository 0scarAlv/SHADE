using NAudio.Dsp;
using NAudio.Wave;

namespace Shade.Agent.Audio;

// Captures whatever the system is currently playing (WASAPI loopback — no
// SMTC involved, this hears literally the mixed output) and turns it into 32
// log-scaled magnitude bands for the app's visualizer. Only runs while
// something is playing; Program.cs starts/stops it alongside the SMTC
// playing state so it isn't burning CPU on a silent desktop.
public sealed class SpectrumAnalyzer : IDisposable
{
    public const int BandCount = 32;
    private const int FftLength = 2048;
    private const int FftExponent = 11; // 2^11 = FftLength
    // 50% overlap: a new frame every 1024 samples (~47 fps at 48 kHz) instead
    // of every 2048 (~23 fps). Same frequency resolution, but the bars react
    // twice as fast to transients like kick drums.
    private const int HopLength = FftLength / 2;

    private readonly float[] _sampleBuffer = new float[FftLength];
    private readonly Complex[] _fftBuffer = new Complex[FftLength];
    private readonly float[] _bandGain = BuildBandGain();
    private int _sampleCount;

    // Start()/Stop() are called from SMTC's WinRT callbacks, which fire on
    // their own COM threads — with two sessions active (e.g. a browser tab
    // and a music app both playing) it's normal for two of those threads to
    // call Start() close enough together to both pass the "_capture is null"
    // check before either assigns it, which without this lock let a second
    // WasapiLoopbackCapture.StartRecording() collide with the first and throw
    // "Previous recording still in progress".
    private readonly object _captureLock = new();

    // Real music has way more energy in bass than treble (roughly a -6dB/octave
    // tilt), so raw FFT magnitude alone leaves the high bands almost always
    // under the log curve's visible threshold — the bars only ever seem to
    // move on the left. This boosts higher bands progressively (up to +18dB at
    // the top band) so treble that's actually present gets to the same visual
    // range as bass, without inventing movement out of silence (boosting near-
    // zero magnitude is still near zero).
    private static float[] BuildBandGain()
    {
        const float maxBoostDb = 18f;
        var gains = new float[BandCount];
        for (var band = 0; band < BandCount; band++)
        {
            var db = band / (float)(BandCount - 1) * maxBoostDb;
            gains[band] = MathF.Pow(10f, db / 20f);
        }
        return gains;
    }

    private WasapiLoopbackCapture? _capture;

    public event Action<float[]>? SpectrumAvailable;

    public void Start()
    {
        lock (_captureLock)
        {
            if (_capture is not null) return;

            var capture = new WasapiLoopbackCapture();
            capture.DataAvailable += OnDataAvailable;
            try
            {
                capture.StartRecording();
            }
            catch (InvalidOperationException)
            {
                // Lost the race to a concurrent Start() call from another SMTC
                // callback thread; that other capture is already running.
                capture.DataAvailable -= OnDataAvailable;
                capture.Dispose();
                return;
            }

            _capture = capture;
        }
    }

    public void Stop()
    {
        lock (_captureLock)
        {
            if (_capture is null) return;

            _capture.DataAvailable -= OnDataAvailable;
            _capture.StopRecording();
            _capture.Dispose();
            _capture = null;
            _sampleCount = 0;
        }
    }

    private void OnDataAvailable(object? sender, WaveInEventArgs e)
    {
        var format = _capture!.WaveFormat;
        var bytesPerFrame = format.BitsPerSample / 8 * format.Channels;

        var channels = format.Channels;

        for (var offset = 0; offset + bytesPerFrame <= e.BytesRecorded; offset += bytesPerFrame)
        {
            // Mono downmix of the first two channels — left-only missed
            // anything panned hard right (common in older stereo mixes).
            var sample = BitConverter.ToSingle(e.Buffer, offset);
            if (channels > 1) sample = (sample + BitConverter.ToSingle(e.Buffer, offset + 4)) * 0.5f;
            _sampleBuffer[_sampleCount] = sample;
            _sampleCount++;

            if (_sampleCount < FftLength) continue;

            ProcessFft();
            // Keep the newest half as the start of the next window.
            Array.Copy(_sampleBuffer, HopLength, _sampleBuffer, 0, FftLength - HopLength);
            _sampleCount = FftLength - HopLength;
        }
    }

    private void ProcessFft()
    {
        for (var i = 0; i < FftLength; i++)
        {
            // Hann window to keep the FFT from smearing energy across bins.
            var window = 0.5 * (1 - Math.Cos(2 * Math.PI * i / (FftLength - 1)));
            _fftBuffer[i].X = (float)(_sampleBuffer[i] * window);
            _fftBuffer[i].Y = 0;
        }

        FastFourierTransform.FFT(true, FftExponent, _fftBuffer);

        var bands = new float[BandCount];
        var usableBins = FftLength / 2;

        for (var band = 0; band < BandCount; band++)
        {
            // Square the fraction so low bands cover a narrow bass range and
            // high bands cover a wide treble range — matches how music
            // actually distributes energy and how an eq is "supposed" to look.
            var lowBin = Math.Max(1, (int)(Math.Pow((double)band / BandCount, 2) * usableBins));
            var highBin = Math.Max(lowBin + 1, (int)(Math.Pow((double)(band + 1) / BandCount, 2) * usableBins));

            float sum = 0;
            var count = 0;
            for (var bin = lowBin; bin < highBin && bin < usableBins; bin++)
            {
                sum += MathF.Sqrt(_fftBuffer[bin].X * _fftBuffer[bin].X + _fftBuffer[bin].Y * _fftBuffer[bin].Y);
                count++;
            }

            var magnitude = count > 0 ? sum / count : 0f;
            var boosted = magnitude * _bandGain[band];
            // The raw FFT magnitudes for typical music are tiny (~0.01-0.1),
            // so this needs a fairly aggressive gain curve to read as an
            // actual visible equalizer instead of a flat line.
            bands[band] = Math.Clamp(MathF.Log10(1 + boosted * 200) / 1.4f, 0f, 1f);
        }

        SpectrumAvailable?.Invoke(bands);
    }

    public void Dispose() => Stop();
}
