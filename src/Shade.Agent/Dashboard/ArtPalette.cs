using System.Collections.Concurrent;
using Windows.Graphics.Imaging;
using Windows.Storage.Streams;

namespace Shade.Agent.Dashboard;

public readonly record struct Rgb(byte R, byte G, byte B)
{
    public static Rgb Hex(uint v) => new((byte)(v >> 16), (byte)(v >> 8), (byte)v);

    public static Rgb Lerp(Rgb a, Rgb b, double t)
    {
        t = Math.Clamp(t, 0, 1);
        static byte Mix(byte x, byte y, double t) => (byte)Math.Round(x + (y - x) * t, MidpointRounding.AwayFromZero);
        return new(Mix(a.R, b.R, t), Mix(a.G, b.G, t), Mix(a.B, b.B, t));
    }

    // Spectre markup color, e.g. "#10b981".
    public string Markup => $"#{R:x2}{G:x2}{B:x2}";
}

// Colors pulled from the cover, the way most players do it: Accent and
// Second..Fourth are the cover's dominant distinct colors (Accent the most
// prominent colorful one), re-lit to stay readable on a dark terminal while
// keeping their hue and saturation; Backdrop is a deep tint. Same algorithm
// as Nation's ArtPalette.cs and the phone's ArtPalette.kt.
public readonly record struct ArtPalette(Rgb Accent, Rgb Backdrop, Rgb Second, Rgb Third, Rgb Fourth)
{
    // The dashboard's original emerald, used when nothing is playing or the
    // player publishes no cover.
    public static readonly ArtPalette Default = new(Rgb.Hex(0x10B981), Rgb.Hex(0x12302A), Rgb.Hex(0x22D3EE), Rgb.Hex(0xA78BFA), Rgb.Hex(0x6EE7B7));

    // Fallback when the cover has nothing usable (fully transparent, pure black/white).
    public static readonly ArtPalette Monochrome = new(Rgb.Hex(0xE4E4E7), Rgb.Hex(0x26262B), Rgb.Hex(0xA1A1AA), Rgb.Hex(0xC8C8CE), Rgb.Hex(0x7A7A84));

    private const uint SampleSize = 64;
    private const int Clusters = 8;
    private const int Iterations = 8;
    // Weighted squared RGB distance under which two dominant colors count as the same one.
    private const float MinDistance2 = 9f * 55f * 55f;
    // Minimum weighted distance between the final, relit colors.
    private const float MinShown2 = 9f * 28f * 28f;

    // Covers repeat across an album, so cache by the art hash the agent already computes.
    private static readonly ConcurrentDictionary<string, ArtPalette> Cache = new();

    public static async Task<ArtPalette> FromCoverAsync(string hash, byte[] image)
    {
        if (Cache.TryGetValue(hash, out var cached)) return cached;
        try
        {
            var palette = Extract(await DecodeAsync(image), (int)(SampleSize * SampleSize));
            Cache[hash] = palette;
            return palette;
        }
        catch (Exception)
        {
            // Corrupt/unsupported image: keep the default accent.
            return Default;
        }
    }

    // Decodes with the built-in WinRT imaging codecs, downscaled to a 64x64 BGRA buffer.
    private static async Task<byte[]> DecodeAsync(byte[] image)
    {
        using var stream = new InMemoryRandomAccessStream();
        using (var writer = new DataWriter(stream.GetOutputStreamAt(0)))
        {
            writer.WriteBytes(image);
            await writer.StoreAsync();
            await writer.FlushAsync();
            writer.DetachStream();
        }
        stream.Seek(0);

        var decoder = await BitmapDecoder.CreateAsync(stream);
        var transform = new BitmapTransform
        {
            ScaledWidth = SampleSize,
            ScaledHeight = SampleSize,
            InterpolationMode = BitmapInterpolationMode.Linear,
        };
        var data = await decoder.GetPixelDataAsync(
            BitmapPixelFormat.Bgra8, BitmapAlphaMode.Straight, transform,
            ExifOrientationMode.IgnoreExifOrientation, ColorManagementMode.DoNotColorManage);
        return data.DetachPixelData();
    }

    // k-means over the sampled pixels (seeded from the most common coarse
    // colors), then the most populated clusters that are clearly different
    // from each other. Near-black and near-white clusters are skipped.
    public static ArtPalette Extract(byte[] bgra, int pixelCount)
    {
        var px = new List<(float R, float G, float B)>(pixelCount);
        for (var p = 0; p < pixelCount; p++)
        {
            var o = p * 4;
            if (bgra[o + 3] >= 128) px.Add((bgra[o + 2], bgra[o + 1], bgra[o]));
        }
        if (px.Count == 0) return Monochrome;

        // Seeds: centers of the most populated 3-bit-per-channel boxes.
        var boxes = new Dictionary<int, int>();
        foreach (var (r, g, b) in px)
        {
            var key = ((int)r >> 5) << 6 | ((int)g >> 5) << 3 | (int)b >> 5;
            boxes[key] = boxes.GetValueOrDefault(key) + 1;
        }
        var centers = boxes.OrderByDescending(kv => kv.Value).Take(Clusters)
            .Select(kv => ((float)((kv.Key >> 6 & 7) * 32 + 16), (float)((kv.Key >> 3 & 7) * 32 + 16), (float)((kv.Key & 7) * 32 + 16)))
            .ToArray();
        var k = centers.Length;
        var counts = new int[k];

        for (var it = 0; it < Iterations; it++)
        {
            var sum = new (float R, float G, float B)[k];
            Array.Clear(counts);
            foreach (var c in px)
            {
                var best = Nearest(centers, c);
                sum[best] = (sum[best].R + c.R, sum[best].G + c.G, sum[best].B + c.B);
                counts[best]++;
            }
            for (var i = 0; i < k; i++)
                if (counts[i] > 0) centers[i] = (sum[i].R / counts[i], sum[i].G / counts[i], sum[i].B / counts[i]);
        }

        // Candidates by population; skip near-black/near-white and tiny specks.
        var candidates = Enumerable.Range(0, k)
            .Where(i => counts[i] >= px.Count * 0.02f)
            .Select(i => (Color: centers[i], Count: counts[i], Hsl: ToHsl(centers[i].Item1, centers[i].Item2, centers[i].Item3)))
            .Where(c => c.Hsl.L is > 0.1f and < 0.93f)
            .OrderByDescending(c => c.Count)
            .ToList();
        var picked = new List<(float H, float S, float L, int Count, (float, float, float) Rgb)>();
        foreach (var c in candidates)
        {
            if (picked.Count == 4) break;
            if (picked.All(p => Distance2(p.Rgb, c.Color) > MinDistance2))
                picked.Add((c.Hsl.H, c.Hsl.S, c.Hsl.L, c.Count, c.Color));
        }
        if (picked.Count == 0) return Monochrome;

        // Accent = the most prominent *colorful* one; the rest keep population order.
        var accentAt = 0;
        for (var i = 1; i < picked.Count; i++)
            if (picked[i].Count * (0.25f + picked[i].S) > picked[accentAt].Count * (0.25f + picked[accentAt].S)) accentAt = i;
        var accent = picked[accentAt];
        picked.RemoveAt(accentAt);

        // Greys stay grey; colored ones get enough saturation and lightness to read on black.
        static Rgb Relight(float h, float s, float l, bool accent) =>
            FromHsl(h, s < 0.12f ? s : Math.Clamp(s, accent ? 0.5f : 0.4f, 0.9f), Math.Clamp(l, accent ? 0.6f : 0.55f, 0.78f));

        // Compare after relighting: dark colors brighten and can land on top of each other.
        var accentColor = Relight(accent.H, accent.S, accent.L, true);
        var colors = new List<Rgb>();
        bool TryAdd(Rgb c)
        {
            if (Distance2(Rgb3(c), Rgb3(accentColor)) < MinShown2 || colors.Any(o => Distance2(Rgb3(o), Rgb3(c)) < MinShown2)) return false;
            colors.Add(c);
            return true;
        }
        foreach (var p in picked) TryAdd(Relight(p.H, p.S, p.L, false));
        // Fewer distinct colors than slots: lighter, then deeper shades of the accent and the others.
        var sources = new List<(float H, float S)> { (accent.H, accent.S) };
        sources.AddRange(picked.Select(p => (p.H, p.S)));
        float[] shades = { 0.84f, 0.45f, 0.7f, 0.35f };
        for (var i = 0; colors.Count < 3 && i < sources.Count * shades.Length; i++)
        {
            var (h, sat) = sources[i % sources.Count];
            var l = shades[i / sources.Count];
            TryAdd(FromHsl(h, sat < 0.12f ? sat : Math.Clamp(sat * (l > 0.6f ? 0.75f : 1f), 0.3f, 0.85f), l));
        }
        while (colors.Count < 3) colors.Add(accentColor); // flat single-color cover

        var backdrop = FromHsl(accent.H, accent.S < 0.12f ? accent.S : Math.Clamp(accent.S, 0.2f, 0.5f), 0.17f);
        return new ArtPalette(accentColor, backdrop, colors[0], colors[1], colors[2]);
    }

    public static ArtPalette Lerp(ArtPalette a, ArtPalette b, double t) => new(
        Rgb.Lerp(a.Accent, b.Accent, t), Rgb.Lerp(a.Backdrop, b.Backdrop, t),
        Rgb.Lerp(a.Second, b.Second, t), Rgb.Lerp(a.Third, b.Third, t), Rgb.Lerp(a.Fourth, b.Fourth, t));

    private static int Nearest((float R, float G, float B)[] centers, (float R, float G, float B) c)
    {
        var best = 0;
        var bestD = float.MaxValue;
        for (var i = 0; i < centers.Length; i++)
        {
            var d = Distance2(centers[i], c);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    private static (float, float, float) Rgb3(Rgb c) => (c.R, c.G, c.B);

    // Weighted RGB (2/4/3) tracks perceived difference better than plain RGB distance.
    private static float Distance2((float R, float G, float B) a, (float R, float G, float B) b)
    {
        float dr = a.R - b.R, dg = a.G - b.G, db = a.B - b.B;
        return 2 * dr * dr + 4 * dg * dg + 3 * db * db;
    }

    private static (float H, float S, float L) ToHsl(float r, float g, float b)
    {
        r /= 255f; g /= 255f; b /= 255f;
        float max = Math.Max(r, Math.Max(g, b)), min = Math.Min(r, Math.Min(g, b)), d = max - min;
        var l = (max + min) / 2;
        var s = d == 0 ? 0 : d / (1 - Math.Abs(2 * l - 1));
        return (Hue(r, g, b, max, d), s, l);
    }

    private static float Hue(float r, float g, float b, float max, float d)
    {
        if (d == 0) return 0;
        var h = max == r ? (g - b) / d % 6 : max == g ? (b - r) / d + 2 : (r - g) / d + 4;
        h *= 60;
        return h < 0 ? h + 360 : h;
    }

    private static Rgb FromHsl(float h, float s, float l)
    {
        var c = (1 - Math.Abs(2 * l - 1)) * s;
        var x = c * (1 - Math.Abs(h / 60 % 2 - 1));
        var m = l - c / 2;
        var (r, g, b) = (h / 60) switch
        {
            < 1 => (c, x, 0f),
            < 2 => (x, c, 0f),
            < 3 => (0f, c, x),
            < 4 => (0f, x, c),
            < 5 => (x, 0f, c),
            _ => (c, 0f, x),
        };
        return new Rgb((byte)Math.Round((r + m) * 255), (byte)Math.Round((g + m) * 255), (byte)Math.Round((b + m) * 255));
    }
}
