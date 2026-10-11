using System.Security.Cryptography;
using System.Text.Json.Serialization;
using Shade.Agent.Smtc;

namespace Shade.Agent.Players;

// Talks to Tauon Music Box's local remote-control API (port 7814, enabled in
// Tauon under Menu > Settings > Connections). Tauon publishes almost nothing
// to SMTC — no art, album, duration or position — but this API exposes all
// of it straight from the user's files, plus the lyrics embedded in them and
// real seek/shuffle/repeat control. Polled; when Tauon is closed or the API
// is off, Available goes false and the agent falls back to SMTC.
public sealed class TauonBridge : BackgroundService, IPlaybackControl
{
    public const string AppName = "Tauon Music Box";

    private static readonly TimeSpan ActivePoll = TimeSpan.FromMilliseconds(500);
    private static readonly TimeSpan IdlePoll = TimeSpan.FromSeconds(3);
    // A position jump bigger than this vs. our own prediction (seek, track
    // restart) is re-broadcast; smaller drift is left to client interpolation.
    private const long DriftToleranceMs = 1_500;

    private readonly HttpClient _http;
    private readonly ILogger<TauonBridge> _logger;
    private readonly SemaphoreSlim _pollNow = new(0, 1);

    private int? _trackId;
    private bool _lastPlaying;
    private bool _lastShuffle;
    private bool _lastRepeat;
    private long _lastProgressMs;
    private long _lastPolledAtMs;

    public bool Available { get; private set; }
    public bool Playing => CurrentState?.Playing == true;
    public bool HasLyrics { get; private set; }
    public TrackInfo? CurrentTrack { get; private set; }
    public PlaybackState? CurrentState { get; private set; }

    // Raised on any change worth re-evaluating: availability, track or a
    // state discontinuity. PlaybackRouter decides what to forward.
    public event Action? Changed;

    public TauonBridge(IConfiguration configuration, ILogger<TauonBridge> logger)
    {
        _logger = logger;
        var port = configuration.GetValue("Tauon:Port", 7814);
        _http = new HttpClient { BaseAddress = new Uri($"http://127.0.0.1:{port}/"), Timeout = TimeSpan.FromSeconds(2) };
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await PollAsync(stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or System.Text.Json.JsonException)
            {
                SetUnavailable();
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Fallo consultando la API de Tauon.");
                SetUnavailable();
            }

            try
            {
                await _pollNow.WaitAsync(Available ? ActivePoll : IdlePoll, stoppingToken);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }

    private async Task PollAsync(CancellationToken ct)
    {
        var status = await _http.GetFromJsonAsync<TauonStatus>("api1/status", ct);
        if (status is null)
        {
            SetUnavailable();
            return;
        }

        var changed = false;
        if (!Available)
        {
            Available = true;
            changed = true;
            _logger.LogInformation("API de Tauon detectada: carátula, letras y posición vendrán directo de Tauon.");
        }

        if (status.Track is not { } track || status.Id is not { } id)
        {
            if (CurrentTrack is not null)
            {
                CurrentTrack = null;
                CurrentState = new PlaybackState(false, 0, NowMs());
                _trackId = null;
                changed = true;
            }
            if (changed) Changed?.Invoke();
            return;
        }

        if (id != _trackId)
        {
            _trackId = id;
            HasLyrics = track.HasLyrics;
            var (artBytes, artHash) = await FetchArtAsync(id, ct);
            CurrentTrack = new TrackInfo(
                Title: Clean(track.Title),
                Artist: Clean(track.Artist),
                Album: Clean(track.Album),
                DurationMs: track.Duration,
                ArtBytes: artBytes,
                ArtHash: artHash,
                ArtContentType: artBytes is null ? null : "image/jpeg",
                AppName: AppName);
            changed = true;
        }

        var now = NowMs();
        var playing = status.Status == "playing";
        var predicted = _lastPlaying ? _lastProgressMs + (now - _lastPolledAtMs) : _lastProgressMs;
        var discontinuity = Math.Abs(status.Progress - predicted) > DriftToleranceMs;
        if (changed || playing != _lastPlaying || status.Shuffle != _lastShuffle || status.Repeat != _lastRepeat || discontinuity)
        {
            CurrentState = new PlaybackState(playing, status.Progress, now, status.Shuffle, status.Repeat ? "list" : "none");
            changed = true;
        }
        _lastPlaying = playing;
        _lastShuffle = status.Shuffle;
        _lastRepeat = status.Repeat;
        _lastProgressMs = status.Progress;
        _lastPolledAtMs = now;

        if (changed) Changed?.Invoke();
    }

    private async Task<(byte[]? Bytes, string? Hash)> FetchArtAsync(int id, CancellationToken ct)
    {
        try
        {
            using var response = await _http.GetAsync($"api1/pic/medium/{id}", ct);
            if (!response.IsSuccessStatusCode) return (null, null);
            var bytes = await response.Content.ReadAsByteArrayAsync(ct);
            return bytes.Length == 0 ? (null, null) : (bytes, Convert.ToHexString(SHA1.HashData(bytes)).ToLowerInvariant());
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
        {
            return (null, null);
        }
    }

    // Raw LRC (or plain text) stored in the track's own tags, or null.
    public async Task<string?> FetchLyricsAsync(CancellationToken ct = default)
    {
        if (_trackId is not { } id || !HasLyrics) return null;
        try
        {
            var payload = await _http.GetFromJsonAsync<TauonLyrics>($"api1/lyrics/{id}", ct);
            return string.IsNullOrWhiteSpace(payload?.LyricsText) ? null : payload.LyricsText;
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or System.Text.Json.JsonException)
        {
            return null;
        }
    }

    private void SetUnavailable()
    {
        if (!Available) return;
        Available = false;
        CurrentTrack = null;
        CurrentState = null;
        _trackId = null;
        _logger.LogInformation("API de Tauon no disponible; se usa la información de Windows (SMTC).");
        Changed?.Invoke();
    }

    // ------------------------------------------------------------ control

    public Task TryPlayPauseAsync() => SendAsync(Playing ? "api1/pause" : "api1/play");
    public Task TryNextAsync() => SendAsync("api1/next");
    public Task TryPreviousAsync() => SendAsync("api1/back");
    public Task TrySeekAsync(long positionMs) => SendAsync($"api1/seek/{Math.Max(0, positionMs)}");
    public Task TryToggleShuffleAsync() => SendAsync("api1/shuffle");
    // Tauon's repeat is on/off only, so "cycle" just toggles it.
    public Task TryCycleRepeatAsync() => SendAsync("api1/repeat");

    private async Task SendAsync(string path)
    {
        try
        {
            using var _ = await _http.GetAsync(path);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
        {
            _logger.LogWarning("Tauon no respondió a '{Path}'.", path);
        }
        // Reflect the result right away instead of waiting for the next tick.
        if (_pollNow.CurrentCount == 0) _pollNow.Release();
    }

    private static string Clean(string? text) => (text ?? string.Empty).Replace("﻿", string.Empty).Trim();

    private static long NowMs() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    private sealed record TauonStatus(
        [property: JsonPropertyName("status")] string? Status,
        [property: JsonPropertyName("shuffle")] bool Shuffle,
        [property: JsonPropertyName("repeat")] bool Repeat,
        [property: JsonPropertyName("progress")] long Progress,
        [property: JsonPropertyName("id")] int? Id,
        [property: JsonPropertyName("track")] TauonTrack? Track);

    private sealed record TauonTrack(
        [property: JsonPropertyName("title")] string? Title,
        [property: JsonPropertyName("artist")] string? Artist,
        [property: JsonPropertyName("album")] string? Album,
        [property: JsonPropertyName("duration")] long Duration,
        [property: JsonPropertyName("has_lyrics")] bool HasLyrics);

    private sealed record TauonLyrics([property: JsonPropertyName("lyrics_text")] string? LyricsText);
}
