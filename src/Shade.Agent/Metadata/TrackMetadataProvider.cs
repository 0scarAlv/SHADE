using System.Collections.Concurrent;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Serialization;
using Shade.Agent.Smtc;

namespace Shade.Agent.Metadata;

public sealed record TrackEnrichment(string? Album, long DurationMs, byte[]? ArtBytes, string? ArtHash, string? ArtContentType);

// Some players (Tauon Music Box, several browser-based ones) publish only a
// title and artist to SMTC — no thumbnail, album or duration. This fills
// the gaps from the iTunes Search API (free, no key), matched by
// artist + title. Results (including "nothing found") are cached per track
// so SMTC's repeated change events don't trigger repeated lookups.
public sealed class TrackMetadataProvider
{
    private const int ArtSize = 600;

    private readonly HttpClient _http;
    private readonly ILogger<TrackMetadataProvider> _logger;
    private readonly ConcurrentDictionary<string, TrackEnrichment?> _cache = new();

    public TrackMetadataProvider(ILogger<TrackMetadataProvider> logger)
    {
        _logger = logger;
        _http = new HttpClient { Timeout = TimeSpan.FromSeconds(8) };
        _http.DefaultRequestHeaders.UserAgent.ParseAdd("ShadeAgent/1.0 (+https://github.com/0scarAlv/SHADE)");
    }

    public static bool NeedsEnrichment(TrackInfo track) =>
        !string.IsNullOrWhiteSpace(track.Title) &&
        (track.ArtBytes is null || string.IsNullOrWhiteSpace(track.Album) || track.DurationMs <= 0);

    public static string KeyOf(TrackInfo track) => $"{track.Title}|{track.Artist}";

    public bool TryGetCached(TrackInfo track, out TrackEnrichment? enrichment) =>
        _cache.TryGetValue(KeyOf(track), out enrichment);

    // Fills only what the player left empty; anything SMTC did provide wins.
    public static TrackInfo Apply(TrackInfo track, TrackEnrichment? enrichment)
    {
        if (enrichment is null) return track;
        var useArt = track.ArtBytes is null && enrichment.ArtBytes is not null;
        return track with
        {
            Album = string.IsNullOrWhiteSpace(track.Album) ? enrichment.Album ?? track.Album : track.Album,
            DurationMs = track.DurationMs > 0 ? track.DurationMs : enrichment.DurationMs,
            ArtBytes = useArt ? enrichment.ArtBytes : track.ArtBytes,
            ArtHash = useArt ? enrichment.ArtHash : track.ArtHash,
            ArtContentType = useArt ? enrichment.ArtContentType : track.ArtContentType,
        };
    }

    public async Task<TrackEnrichment?> LookupAsync(TrackInfo track, CancellationToken ct = default)
    {
        var key = KeyOf(track);
        if (_cache.TryGetValue(key, out var cached)) return cached;

        TrackEnrichment? result = null;
        try
        {
            result = await QueryItunesAsync(track.Title, track.Artist, ct);
            if (result is null)
                _logger.LogInformation("Sin metadatos extra para '{Title}' de '{Artist}'.", track.Title, track.Artist);
            else
                _logger.LogInformation("Metadatos completados para '{Title}' (álbum '{Album}').", track.Title, result.Album);
        }
        catch (Exception ex) when (ex is not OperationCanceledException || !ct.IsCancellationRequested)
        {
            // Network hiccup: don't cache, so the next play of this track retries.
            _logger.LogWarning(ex, "Fallo buscando metadatos de '{Title}'.", track.Title);
            return null;
        }

        _cache[key] = result;
        return result;
    }

    private async Task<TrackEnrichment?> QueryItunesAsync(string title, string artist, CancellationToken ct)
    {
        var term = Uri.EscapeDataString($"{artist} {title}".Trim());
        var url = $"https://itunes.apple.com/search?term={term}&entity=song&limit=10";
        var response = await _http.GetFromJsonAsync<ItunesResponse>(url, ct);
        if (response?.Results is not { Count: > 0 } results) return null;

        var wantedTitle = Normalize(title);
        var wantedArtist = Normalize(artist);
        // Prefer a result whose title matches and whose artist shares a word
        // with ours ("Marcin, RJ Pasin" vs "Marcin"); otherwise accept a
        // title-only match. Never take an unrelated first hit.
        var best = results
            .Select(r => (Result: r, Score: Score(r, wantedTitle, wantedArtist)))
            .Where(x => x.Score > 0)
            .OrderByDescending(x => x.Score)
            .Select(x => x.Result)
            .FirstOrDefault();
        if (best is null) return null;

        byte[]? artBytes = null;
        string? artHash = null;
        string? contentType = null;
        if (best.ArtworkUrl100 is { } artUrl)
        {
            var large = artUrl.Replace("100x100bb", $"{ArtSize}x{ArtSize}bb", StringComparison.Ordinal);
            using var artResponse = await _http.GetAsync(large, ct);
            if (artResponse.IsSuccessStatusCode)
            {
                artBytes = await artResponse.Content.ReadAsByteArrayAsync(ct);
                artHash = Convert.ToHexString(SHA1.HashData(artBytes)).ToLowerInvariant();
                contentType = artResponse.Content.Headers.ContentType?.MediaType ?? "image/jpeg";
            }
        }

        return new TrackEnrichment(best.CollectionName, best.TrackTimeMillis ?? 0, artBytes, artHash, contentType);
    }

    private static int Score(ItunesResult result, string wantedTitle, string wantedArtist)
    {
        var title = Normalize(result.TrackName ?? "");
        var artist = Normalize(result.ArtistName ?? "");
        var titleMatch = title == wantedTitle ? 2 : (title.Contains(wantedTitle) || wantedTitle.Contains(title)) && title.Length > 0 ? 1 : 0;
        if (titleMatch == 0) return 0;
        var artistWords = wantedArtist.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        var artistMatch = artistWords.Any(w => w.Length > 1 && artist.Contains(w)) ? 2 : 0;
        return titleMatch + artistMatch;
    }

    // Lowercase, accents stripped, punctuation collapsed to spaces.
    private static string Normalize(string text)
    {
        var decomposed = text.Normalize(NormalizationForm.FormD);
        var sb = new StringBuilder(decomposed.Length);
        foreach (var c in decomposed)
        {
            if (CharUnicodeInfo.GetUnicodeCategory(c) == UnicodeCategory.NonSpacingMark) continue;
            sb.Append(char.IsLetterOrDigit(c) ? char.ToLowerInvariant(c) : ' ');
        }
        return string.Join(' ', sb.ToString().Split(' ', StringSplitOptions.RemoveEmptyEntries));
    }

    private sealed record ItunesResponse([property: JsonPropertyName("results")] List<ItunesResult>? Results);

    private sealed record ItunesResult(
        [property: JsonPropertyName("trackName")] string? TrackName,
        [property: JsonPropertyName("artistName")] string? ArtistName,
        [property: JsonPropertyName("collectionName")] string? CollectionName,
        [property: JsonPropertyName("trackTimeMillis")] long? TrackTimeMillis,
        [property: JsonPropertyName("artworkUrl100")] string? ArtworkUrl100);
}
