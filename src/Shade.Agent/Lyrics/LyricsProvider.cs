using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using Shade.Agent.Protocol;

namespace Shade.Agent.Lyrics;

public sealed record LyricsResult(List<LyricsLine>? Lines, string? Plain);

// Queries lrclib.net (free, no API key) for synced/plain lyrics by track
// metadata. SMTC has no concept of lyrics at all, so this is the agent's own
// side channel, unrelated to the SMTC session watcher.
public sealed class LyricsProvider
{
    private static readonly Regex LrcLinePattern = new(@"^\[(\d+):(\d+)\.(\d+)\]", RegexOptions.Compiled);

    private readonly HttpClient _http;
    private readonly ILogger<LyricsProvider> _logger;

    public LyricsProvider(ILogger<LyricsProvider> logger)
    {
        _logger = logger;
        _http = new HttpClient { BaseAddress = new Uri("https://lrclib.net/") };
        _http.DefaultRequestHeaders.UserAgent.ParseAdd("ShadeAgent/1.0 (+https://github.com/0scarAlv/SHADE)");
    }

    public async Task<LyricsResult?> TryFetchAsync(string title, string artist, string album, long durationMs, CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(title)) return null;

        try
        {
            var durationSec = (int)(durationMs / 1000);
            var url = $"api/get?track_name={Uri.EscapeDataString(title)}&artist_name={Uri.EscapeDataString(artist)}" +
                      $"&album_name={Uri.EscapeDataString(album)}&duration={durationSec}";

            using var response = await _http.GetAsync(url, ct);
            if (!response.IsSuccessStatusCode) return null;

            var payload = await response.Content.ReadFromJsonAsync<LrcLibResponse>(ct);
            if (payload is null) return null;

            var lines = payload.SyncedLyrics is { Length: > 0 } synced ? ParseLrc(synced) : null;
            if (lines is null && string.IsNullOrEmpty(payload.PlainLyrics)) return null;

            return new LyricsResult(lines, payload.PlainLyrics);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "No se pudieron obtener las letras de LRCLIB para '{Title}'.", title);
            return null;
        }
    }

    private static List<LyricsLine>? ParseLrc(string lrc)
    {
        var lines = new List<LyricsLine>();

        foreach (var rawLine in lrc.Split('\n'))
        {
            var match = LrcLinePattern.Match(rawLine);
            if (!match.Success) continue;

            var minutes = int.Parse(match.Groups[1].Value);
            var seconds = int.Parse(match.Groups[2].Value);
            var fraction = match.Groups[3].Value;
            var fractionMs = fraction.Length == 2 ? int.Parse(fraction) * 10 : int.Parse(fraction);
            var timeMs = (minutes * 60 + seconds) * 1000L + fractionMs;

            var text = rawLine[match.Length..].Trim();
            if (text.Length > 0)
                lines.Add(new LyricsLine(timeMs, text));
        }

        return lines.Count > 0 ? lines : null;
    }

    private sealed record LrcLibResponse(
        [property: JsonPropertyName("syncedLyrics")] string? SyncedLyrics,
        [property: JsonPropertyName("plainLyrics")] string? PlainLyrics);
}
