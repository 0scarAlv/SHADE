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
    private const int BandCount = 32;
    private const int FftLength = 2048;
    private const int FftExponent = 11; // 2^11 = FftLength

    private readonly float[] _sampleBuffer = new float[FftLength];
    private readonly Complex[] _fftBuffer = new Complex[FftLength];
    private int _sampleCount;

    private WasapiLoopbackCapture? _capture;

    public event Action<float[]>? SpectrumAvailable;

    public void Start()
    {
        if (_capture is not null) return;

        _capture = new WasapiLoopbackCapture();
        _capture.DataAvailable += OnDataAvailable;
        _capture.StartRecording();
    }

    public void Stop()
    {
        if (_capture is null) return;

        _capture.DataAvailable -= OnDataAvailable;
        _capture.StopRecording();
        _capture.Dispose();
        _capture = null;
        _sampleCount = 0;
    }

    private void OnDataAvailable(object? sender, WaveInEventArgs e)
    {
        var format = _capture!.WaveFormat;
        var bytesPerFrame = format.BitsPerSample / 8 * format.Channels;

        for (var offset = 0; offset + bytesPerFrame <= e.BytesRecorded; offset += bytesPerFrame)
        {
            // Left channel only — plenty for a visualizer, no need to mix down.
            var sample = BitConverter.ToSingle(e.Buffer, offset);
            _sampleBuffer[_sampleCount] = sample;
            _sampleCount++;

            if (_sampleCount < FftLength) continue;

            ProcessFft();
            _sampleCount = 0;
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
            bands[band] = Math.Clamp(MathF.Log10(1 + magnitude * 50) / 2.5f, 0f, 1f);
        }

        SpectrumAvailable?.Invoke(bands);
    }

    public void Dispose() => Stop();
}
