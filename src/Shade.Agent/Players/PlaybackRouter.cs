using Shade.Agent.Smtc;

namespace Shade.Agent.Players;

// What the command handler / dashboard need from "whatever is playing".
public interface IPlaybackControl
{
    TrackInfo? CurrentTrack { get; }
    PlaybackState? CurrentState { get; }
    Task TryPlayPauseAsync();
    Task TryNextAsync();
    Task TryPreviousAsync();
    Task TrySeekAsync(long positionMs);
    Task TryToggleShuffleAsync();
    Task TryCycleRepeatAsync();
}

// Picks the source of truth between Windows' SMTC and the Tauon API, and
// re-exposes a single TrackChanged/StateChanged stream to the rest of the
// agent. Tauon wins whenever its API is up and it's the thing in use —
// playing, or paused while nothing else is playing — because it carries
// art, position and lyrics SMTC doesn't. When the winner flips (Tauon
// paused and Spotify started, Tauon closed, ...), the new source's current
// track and state are re-emitted so clients never keep stale data.
public sealed class PlaybackRouter : IPlaybackControl
{
    private readonly SmtcSessionWatcher _smtc;
    private readonly TauonBridge _tauon;
    private readonly object _lock = new();
    private bool? _lastWasTauon;
    // Tauon raises Changed for state-only updates too; only re-emit its
    // track when it's actually a new object (new song), so art isn't
    // re-pushed to Bluetooth clients on every play/pause.
    private TrackInfo? _lastTauonTrack;

    public event Action<TrackInfo>? TrackChanged;
    public event Action<PlaybackState>? StateChanged;

    public PlaybackRouter(SmtcSessionWatcher smtc, TauonBridge tauon)
    {
        _smtc = smtc;
        _tauon = tauon;

        _smtc.TrackChanged += track => Forward(fromTauon: false, track, null);
        _smtc.StateChanged += state => Forward(fromTauon: false, null, state);
        _tauon.Changed += () => Forward(fromTauon: true, _tauon.CurrentTrack, _tauon.CurrentState);
    }

    public bool TauonIsSource
    {
        get
        {
            if (!_tauon.Available || _tauon.CurrentTrack is null) return false;
            if (_tauon.Playing) return true;
            var smtcTrack = _smtc.CurrentTrack;
            var smtcPlaying = _smtc.CurrentState?.Playing == true;
            var smtcIsTauon = smtcTrack?.AppName == TauonBridge.AppName;
            return smtcTrack is null || smtcIsTauon || !smtcPlaying;
        }
    }

    private IPlaybackControl Active => TauonIsSource ? _tauon : _smtc;

    public TrackInfo? CurrentTrack => Active.CurrentTrack;
    public PlaybackState? CurrentState => Active.CurrentState;

    public Task TryPlayPauseAsync() => Active.TryPlayPauseAsync();
    public Task TryNextAsync() => Active.TryNextAsync();
    public Task TryPreviousAsync() => Active.TryPreviousAsync();
    public Task TrySeekAsync(long positionMs) => Active.TrySeekAsync(positionMs);
    public Task TryToggleShuffleAsync() => Active.TryToggleShuffleAsync();
    public Task TryCycleRepeatAsync() => Active.TryCycleRepeatAsync();

    private void Forward(bool fromTauon, TrackInfo? track, PlaybackState? state)
    {
        TrackInfo? emitTrack = null;
        PlaybackState? emitState = null;
        lock (_lock)
        {
            var tauonNow = TauonIsSource;
            if (_lastWasTauon != tauonNow)
            {
                // Source flipped: replay the new source's snapshot.
                _lastWasTauon = tauonNow;
                var active = Active;
                emitTrack = active.CurrentTrack;
                emitState = active.CurrentState ?? new PlaybackState(false, 0, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
            }
            else if (fromTauon == tauonNow)
            {
                emitTrack = fromTauon && ReferenceEquals(track, _lastTauonTrack) ? null : track;
                emitState = state;
            }
            if (tauonNow) _lastTauonTrack = _tauon.CurrentTrack;
        }

        if (emitTrack is not null) TrackChanged?.Invoke(emitTrack);
        if (emitState is not null) StateChanged?.Invoke(emitState);
    }
}
