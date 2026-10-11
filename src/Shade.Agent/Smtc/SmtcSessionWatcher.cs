using System.Runtime.InteropServices.WindowsRuntime;
using System.Security.Cryptography;
using System.Linq;
using Windows.Media;
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

    // Lightweight PlaybackInfoChanged subscription kept on every known session
    // (not just the attached one), purely to notice when a background session
    // starts/stops playing — Windows only raises SessionsChanged when sessions
    // are added/removed, not when an existing one's playback state flips.
    private readonly List<GlobalSystemMediaTransportControlsSession> _watchedSessions = new();

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
        _manager.SessionsChanged += OnSessionsChanged;
        RefreshWatchedSessions();
        AttachToBestSession();
    }

    private void OnCurrentSessionChanged(
        GlobalSystemMediaTransportControlsSessionManager sender,
        CurrentSessionChangedEventArgs args)
        => AttachToBestSession();

    private void OnSessionsChanged(
        GlobalSystemMediaTransportControlsSessionManager sender,
        SessionsChangedEventArgs args)
    {
        RefreshWatchedSessions();
        AttachToBestSession();
    }

    private void OnAnySessionPlaybackInfoChanged(
        GlobalSystemMediaTransportControlsSession sender,
        PlaybackInfoChangedEventArgs args)
        => AttachToBestSession();

    private void RefreshWatchedSessions()
    {
        foreach (var watched in _watchedSessions)
            watched.PlaybackInfoChanged -= OnAnySessionPlaybackInfoChanged;
        _watchedSessions.Clear();

        if (_manager is null) return;

        foreach (var session in _manager.GetSessions())
        {
            session.PlaybackInfoChanged += OnAnySessionPlaybackInfoChanged;
            _watchedSessions.Add(session);
        }
    }

    // A browser tab autoplaying a video counts as a Playing SMTC session just
    // like a dedicated music app does, so when both are playing at once we'd
    // otherwise pick whichever GetSessions() happens to list first. Bias away
    // from browsers in that tie so the actual music app wins.
    private static readonly string[] BrowserAppIdHints =
        { "brave", "chrome", "msedge", "firefox", "opera" };

    // Windows' own "current session" is whichever app the user last
    // interacted with (media keys, volume mixer flyout), which can keep
    // pointing at a paused/silent session while a different app is actually
    // playing music. Prefer any session that's actually Playing, favoring
    // non-browser apps when several are; only fall back to Windows' pick
    // when nothing is playing at all.
    private GlobalSystemMediaTransportControlsSession? SelectBestSession()
    {
        if (_manager is null) return null;

        // Sticky: keep the session we're already attached to as long as it's
        // still playing, instead of jumping to some other session that also
        // started playing (e.g. a background tab autoplaying next to the
        // music app the user is actually listening to).
        if (_session is not null && IsPlaying(_session)) return _session;

        var current = _manager.GetCurrentSession();
        var playingSessions = _manager.GetSessions().Where(IsPlaying).ToList();

        var nonBrowser = playingSessions.FirstOrDefault(s => !LooksLikeBrowser(s));
        if (nonBrowser is not null) return nonBrowser;

        if (current is not null && IsPlaying(current)) return current;
        return playingSessions.FirstOrDefault() ?? current;
    }

    private static bool LooksLikeBrowser(GlobalSystemMediaTransportControlsSession session)
    {
        var appId = session.SourceAppUserModelId ?? string.Empty;
        return BrowserAppIdHints.Any(hint => appId.Contains(hint, StringComparison.OrdinalIgnoreCase));
    }

    private static bool IsPlaying(GlobalSystemMediaTransportControlsSession session)
    {
        try
        {
            return session.GetPlaybackInfo().PlaybackStatus
                == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;
        }
        catch
        {
            // A session that just closed can throw here; treat as not playing.
            return false;
        }
    }

    private void AttachToBestSession()
    {
        var best = SelectBestSession();
        if (best?.SourceAppUserModelId == _session?.SourceAppUserModelId && best is not null)
            return;

        DetachFromCurrentSession();

        _session = best;
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
                ArtContentType: art.ContentType,
                AppName: FriendlyAppName(session.SourceAppUserModelId));

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
                TimestampMs: NowMs(),
                Shuffle: playback.IsShuffleActive,
                Repeat: playback.AutoRepeatMode switch
                {
                    MediaPlaybackAutoRepeatMode.None => "none",
                    MediaPlaybackAutoRepeatMode.Track => "track",
                    MediaPlaybackAutoRepeatMode.List => "list",
                    _ => null,
                });

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

    public async Task TryToggleShuffleAsync()
    {
        if (_session is not { } session) return;
        var current = session.GetPlaybackInfo().IsShuffleActive;
        if (current is null) return; // source app doesn't support shuffle
        await session.TryChangeShuffleActiveAsync(!current.Value);
    }

    // none -> list -> track -> none, same order Spotify's own button cycles in.
    public async Task TryCycleRepeatAsync()
    {
        if (_session is not { } session) return;
        var current = session.GetPlaybackInfo().AutoRepeatMode;
        if (current is null) return; // source app doesn't support repeat
        var next = current.Value switch
        {
            MediaPlaybackAutoRepeatMode.None => MediaPlaybackAutoRepeatMode.List,
            MediaPlaybackAutoRepeatMode.List => MediaPlaybackAutoRepeatMode.Track,
            _ => MediaPlaybackAutoRepeatMode.None,
        };
        await session.TryChangeAutoRepeatModeAsync(next);
    }

    // SourceAppUserModelId is either an exe name ("Spotify.exe"), a packaged
    // AUMID ("SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify") or, for Firefox,
    // an opaque hash — map the common ones to a display name and otherwise
    // fall back to the most readable fragment of the id.
    private static readonly (string Hint, string Name)[] KnownApps =
    {
        ("spotify", "Spotify"),
        ("msedge", "Edge"),
        ("chrome", "Chrome"),
        ("firefox", "Firefox"),
        ("308046B0AF4A39CB", "Firefox"),
        ("brave", "Brave"),
        ("opera", "Opera"),
        ("zunemusic", "Reproductor multimedia"),
        ("applemusic", "Apple Music"),
        ("tidal", "TIDAL"),
        ("deezer", "Deezer"),
        ("vlc", "VLC"),
        ("foobar", "foobar2000"),
        ("musicbee", "MusicBee"),
        ("aimp", "AIMP"),
    };

    internal static string? FriendlyAppName(string? appId)
    {
        if (string.IsNullOrWhiteSpace(appId)) return null;

        foreach (var (hint, name) in KnownApps)
        {
            if (appId.Contains(hint, StringComparison.OrdinalIgnoreCase)) return name;
        }

        var fragment = appId.Contains('!') ? appId[(appId.LastIndexOf('!') + 1)..] : appId;
        if (fragment.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)) fragment = fragment[..^4];
        fragment = fragment.Split('.').Last();
        return fragment.Length == 0 ? null : char.ToUpperInvariant(fragment[0]) + fragment[1..];
    }

    public async Task TrySeekAsync(long positionMs)
    {
        if (_session is not { } session) return;

        var accepted = await session.TryChangePlaybackPositionAsync(TimeSpan.FromMilliseconds(positionMs).Ticks);
        if (!accepted)
        {
            _logger.LogWarning(
                "La app de la sesión activa rechazó el seek (no implementa TryChangePlaybackPositionAsync en su SMTC).");
        }
    }

    private static long NowMs() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    public ValueTask DisposeAsync()
    {
        DetachFromCurrentSession();
        foreach (var watched in _watchedSessions)
            watched.PlaybackInfoChanged -= OnAnySessionPlaybackInfoChanged;
        _watchedSessions.Clear();

        if (_manager is not null)
        {
            _manager.CurrentSessionChanged -= OnCurrentSessionChanged;
            _manager.SessionsChanged -= OnSessionsChanged;
        }
        return ValueTask.CompletedTask;
    }
}
