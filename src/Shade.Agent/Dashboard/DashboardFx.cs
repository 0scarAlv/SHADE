using System.Text.Json;

namespace Shade.Agent.Dashboard;

// Deterministic noise for the cyberpunk effects: keyed on (seed, tick) so a
// glitch frame is stable within a tick and nothing allocates.
public static class Fx
{
    public static readonly Rgb White = Rgb.Hex(0xF4F7FF);

    public static uint Hash(int a, int b)
    {
        var h = (uint)a * 0x9E3779B1u ^ (uint)b * 0x85EBCA77u;
        h ^= h >> 15; h *= 0x2C1B3C6Du;
        h ^= h >> 12; h *= 0x297A2D39u;
        return h ^ (h >> 15);
    }

    // Uniform value in [0, 1).
    public static double Noise(int a, int b) => (Hash(a, b) & 0xFFFFFF) / (double)0x1000000;
}

// Beat = bass-weighted spectral flux against an adaptive threshold, fed from
// the analyzer's own thread on every FFT frame. Same tuning as Nation and the
// phone's rememberBeat, so all three pulse on the same hits.
public sealed class BeatDetector
{
    private readonly object _gate = new();
    private readonly float[] _prev = new float[16];
    private double _fluxAvg;
    private double _beat;
    private double _beatAt = double.NegativeInfinity;
    private double _lastBeatAt = double.NegativeInfinity;
    private double _lastHardHitAt = double.NegativeInfinity;
    private int _hardHits;

    public void Feed(float[] bands, double now)
    {
        lock (_gate)
        {
            // Rising energy only, weighted toward the kick/bass bands.
            double flux = 0;
            var n = Math.Min(8, bands.Length);
            for (var i = 0; i < n; i++)
                flux += Math.Max(0, bands[i] - _prev[i]) * (i < 4 ? 1.0 : 0.5);
            Array.Copy(bands, _prev, Math.Min(bands.Length, _prev.Length));
            _fluxAvg += (flux - _fluxAvg) * 0.08;
            if (flux <= _fluxAvg * 1.6 + 0.08 || now - _lastBeatAt <= 0.22) return;

            var strength = Math.Clamp((flux - _fluxAvg) / 0.6, 0.35, 1);
            _beat = Math.Max(Envelope(now), strength);
            _beatAt = now;
            _lastBeatAt = now;
            // Hard hits glitch the dashboard, with a cooldown so it never turns into strobe.
            if (strength > 0.65 && now - _lastHardHitAt > 3.5)
            {
                _lastHardHitAt = now;
                _hardHits++;
            }
        }
    }

    // Beat envelope in [0, 1], decaying with a 140 ms time constant.
    public double Level(double now)
    {
        lock (_gate) return Envelope(now);
    }

    public int HardHits
    {
        get { lock (_gate) return _hardHits; }
    }

    public void Reset()
    {
        lock (_gate)
        {
            _beat = 0;
            _fluxAvg = 0;
            Array.Clear(_prev);
        }
    }

    private double Envelope(double now)
    {
        var level = _beat * Math.Exp(-(now - _beatAt) / 0.14);
        return level < 0.02 ? 0 : level;
    }
}

public enum SpectrumStyle { Bars, Mirror, Wave }

// Dashboard preferences that survive restarts: %LOCALAPPDATA%\ShadeServer\dashboard.json.
public sealed record DashboardSettings(bool Cyber = true, SpectrumStyle Spectrum = SpectrumStyle.Bars)
{
    private static readonly string FilePath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ShadeServer", "dashboard.json");

    public static DashboardSettings Load()
    {
        try
        {
            return File.Exists(FilePath)
                ? JsonSerializer.Deserialize<DashboardSettings>(File.ReadAllText(FilePath)) ?? new()
                : new();
        }
        catch (Exception)
        {
            return new();
        }
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(this));
        }
        catch (Exception)
        {
            // Read-only profile or similar: the toggle still works for this session.
        }
    }
}
