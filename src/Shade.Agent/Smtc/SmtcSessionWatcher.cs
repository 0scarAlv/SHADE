using System.Runtime.InteropServices.WindowsRuntime;
using System.Security.Cryptography;
using Windows.Media.Control;
using Windows.Storage.Streams;

namespace Shade.Agent.Smtc;

// Wraps GlobalSystemMediaTransportControlsSessionManager (WinRT) and translates
// it into the TrackChanged/StateChanged events the rest of the agent consumes.
// WinRT events arrive on their own COM thread, not on the ASP.NET Core
// threadpool; that's why the handlers are "async void" (the only signature
// WinRT accepts for an event) but delegate the real work to methods that
// catch their own exceptions, so a WinRT callback can never crash the process.
public sealed class SmtcSessionWatcher : IAsyncDisposable
{
    private readonly ILogger<SmtcSessionWatcher> _logger;
    private GlobalSystemMediaTransportControlsSessionManager? _manager;
    private GlobalSystemMediaTransportControlsSession? _session;

    public event Action<TrackInfo>? TrackChanged;
    public event Action<PlaybackState>? StateChanged;

    // Last known value, so a client that connects (or reconnects) between two
    // SMTC changes can be caught up instead of left blind until the next event.
    public TrackInfo? CurrentTrack { get; private set; }
    public PlaybackState? CurrentState { get; private set; }

    public SmtcSessionWatcher(ILogger<SmtcSessionWatcher> logger)
    {
        _logger = logger;
    }

    public async Task StartAsync()
    {
        _manager = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
        _manager.CurrentSessionChanged += OnCurrentSessionChanged;
        AttachToCurrentSession();
    }

    private void OnCurrentSessionChanged(
        GlobalSystemMediaTransportControlsSessionManager sender,
        CurrentSessionChangedEventArgs args)
        => AttachToCurrentSession();

    private void AttachToCurrentSession()
    {
        DetachFromCurrentSession();

        _session = _manager?.GetCurrentSession();
        if (_session is null)
        {
            _logger.LogInformation("No hay ninguna sesión de medios activa.");
            CurrentTrack = null;
            CurrentState = new PlaybackState(false, 0, NowMs());
            StateChanged?.Invoke(CurrentState);
            return;
        }

        _logger.LogInformation("Sesión activa: {AppId}", _session.SourceAppUserModelId);

        _session.MediaPropertiesChanged += OnMediaPropertiesChanged;
        _session.PlaybackInfoChanged += OnPlaybackInfoChanged;
        _session.TimelinePropertiesChanged += OnTimelinePropertiesChanged;

        _ = RefreshTrackAsync();
        RefreshPlaybackState();
    }

    private void DetachFromCurrentSession()
    {
        if (_session is null) return;

        _session.MediaPropertiesChanged -= OnMediaPropertiesChanged;
        _session.PlaybackInfoChanged -= OnPlaybackInfoChanged;
        _session.TimelinePropertiesChanged -= OnTimelinePropertiesChanged;
        _session = null;
    }

    private async void OnMediaPropertiesChanged(
        GlobalSystemMediaTransportControlsSession sender,
        MediaPropertiesChangedEventArgs args)
        => await RefreshTrackAsync();

    private void OnPlaybackInfoChanged(
        GlobalSystemMediaTransportControlsSession sender,
        PlaybackInfoChangedEventArgs args)
        => RefreshPlaybackState();

    private void OnTimelinePropertiesChanged(
        GlobalSystemMediaTransportControlsSession sender,
        TimelinePropertiesChangedEventArgs args)
        => RefreshPlaybackState();

    private async Task RefreshTrackAsync()
    {
        var session = _session;
        if (session is null) return;

        try
        {
            var props = await session.TryGetMediaPropertiesAsync();
            if (props is null) return;

            var art = await ReadArtAsync(props.Thumbnail);
            var duration = session.GetTimelineProperties().EndTime;

            var track = new TrackInfo(
                Title: props.Title ?? string.Empty,
                Artist: props.Artist ?? string.Empty,
                Album: props.AlbumTitle ?? string.Empty,
                DurationMs: (long)duration.TotalMilliseconds,
                ArtBytes: art.Bytes,
                ArtHash: art.Hash,
                ArtContentType: art.ContentType);

            CurrentTrack = track;
            TrackChanged?.Invoke(track);
        }
        catch (Exception ex)
        {
            // Some apps (or a session that just closed) fail here; not fatal.
            _logger.LogWarning(ex, "No se pudieron leer los metadatos de la sesión SMTC.");
        }
    }

    private void RefreshPlaybackState()
    {
        var session = _session;
        if (session is null) return;

        try
        {
            var playback = session.GetPlaybackInfo();
            var timeline = session.GetTimelineProperties();
            var playing = playback.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;

            var state = new PlaybackState(
                Playing: playing,
                PositionMs: (long)timeline.Position.TotalMilliseconds,
                TimestampMs: NowMs());

            CurrentState = state;
            StateChanged?.Invoke(state);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "No se pudo leer el estado de reproducción SMTC.");
        }
    }

    private static async Task<(byte[]? Bytes, string? Hash, string? ContentType)> ReadArtAsync(IRandomAccessStreamReference? thumbnail)
    {
        if (thumbnail is null) return (null, null, null);

        using var stream = await thumbnail.OpenReadAsync();
        using var netStream = stream.AsStreamForRead();
        using var buffer = new MemoryStream();
        await netStream.CopyToAsync(buffer);

        var bytes = buffer.ToArray();
        var hash = Convert.ToHexString(SHA1.HashData(bytes)).ToLowerInvariant();
        return (bytes, hash, stream.ContentType);
    }

    public async Task TryPlayPauseAsync()
    {
        if (_session is not { } session) return;
        await session.TryTogglePlayPauseAsync();
    }

    public async Task TryNextAsync()
    {
        if (_session is not { } session) return;
        await session.TrySkipNextAsync();
    }

    public async Task TryPreviousAsync()
    {
        if (_session is not { } session) return;
        await session.TrySkipPreviousAsync();
    }

    private static long NowMs() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    public ValueTask DisposeAsync()
    {
        DetachFromCurrentSession();
        if (_manager is not null)
            _manager.CurrentSessionChanged -= OnCurrentSessionChanged;
        return ValueTask.CompletedTask;
    }
}
