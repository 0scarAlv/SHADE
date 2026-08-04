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

        var durationSec = (int)Math.Round(durationMs / 1000.0);

        var exact = await TryGetExactAsync(title, artist, album, durationSec, ct);
        if (exact is not null)
        {
            _logger.LogInformation("Letras encontradas (match exacto) para '{Title}'.", title);
            return exact;
        }

        var searched = await TrySearchAsync(title, artist, durationSec, ct);
        if (searched is not null)
        {
            _logger.LogInformation("Letras encontradas (búsqueda difusa) para '{Title}'.", title);
            return searched;
        }

        _logger.LogInformation("Sin letras en LRCLIB para '{Title}' de '{Artist}'.", title, artist);
        return null;
    }

    // /api/get requires title+artist+album+duration to match closely — great
    // when it hits, but locally-tagged files rarely match LRCLIB's metadata
    // precisely enough for this to succeed.
    private async Task<LyricsResult?> TryGetExactAsync(string title, string artist, string album, int durationSec, CancellationToken ct)
    {
        try
        {
            var url = $"api/get?track_name={Uri.EscapeDataString(title)}&artist_name={Uri.EscapeDataString(artist)}" +
                      $"&album_name={Uri.EscapeDataString(album)}&duration={durationSec}";

            using var response = await _http.GetAsync(url, ct);
            if (!response.IsSuccessStatusCode) return null;

            var payload = await response.Content.ReadFromJsonAsync<LrcLibResponse>(ct);
            return payload is null ? null : ToResult(payload);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Fallo consultando LRCLIB (api/get) para '{Title}'.", title);
            return null;
        }
    }

    // /api/search is fuzzy (no album/duration requirement) and returns several
    // candidates; used as a fallback when the exact lookup above 404s. Picks
    // whichever candidate's duration is closest to what SMTC reported.
    private async Task<LyricsResult?> TrySearchAsync(string title, string artist, int durationSec, CancellationToken ct)
    {
        try
        {
            var url = $"api/search?track_name={Uri.EscapeDataString(title)}&artist_name={Uri.EscapeDataString(artist)}";

            using var response = await _http.GetAsync(url, ct);
            if (!response.IsSuccessStatusCode) return null;

            var candidates = await response.Content.ReadFromJsonAsync<List<LrcLibResponse>>(ct);
            if (candidates is not { Count: > 0 }) return null;

            var best = candidates
                .OrderBy(c => Math.Abs(c.Duration.GetValueOrDefault() - durationSec))
                .First();

            return ToResult(best);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Fallo consultando LRCLIB (api/search) para '{Title}'.", title);
            return null;
        }
    }

    private static LyricsResult? ToResult(LrcLibResponse payload)
    {
        var lines = payload.SyncedLyrics is { Length: > 0 } synced ? ParseLrc(synced) : null;
        if (lines is null && string.IsNullOrEmpty(payload.PlainLyrics)) return null;
        return new LyricsResult(lines, payload.PlainLyrics);
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
        [property: JsonPropertyName("plainLyrics")] string? PlainLyrics,
        [property: JsonPropertyName("duration")] double? Duration);
}
