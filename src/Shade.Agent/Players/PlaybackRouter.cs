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

// Picks the source of truth between Windows' SMTC and the Nation API, and
// re-exposes a single TrackChanged/StateChanged stream to the rest of the
// agent. Nation wins whenever its API is up and it's the thing in use —
// playing, or paused while nothing else is playing — because it carries
// art, position and lyrics SMTC doesn't. When the winner flips (Nation
// paused and Spotify started, Nation closed, ...), the new source's current
// track and state are re-emitted so clients never keep stale data.
public sealed class PlaybackRouter : IPlaybackControl
{
    private readonly SmtcSessionWatcher _smtc;
    private readonly NationBridge _nation;
    private readonly object _lock = new();
    private bool? _lastWasNation;
    // Nation raises Changed for state-only updates too; only re-emit its
    // track when it's actually a new object (new song), so art isn't
    // re-pushed to Bluetooth clients on every play/pause.
    private TrackInfo? _lastNationTrack;

    public event Action<TrackInfo>? TrackChanged;
    public event Action<PlaybackState>? StateChanged;

    public PlaybackRouter(SmtcSessionWatcher smtc, NationBridge nation)
    {
        _smtc = smtc;
        _nation = nation;

        _smtc.TrackChanged += track => Forward(fromNation: false, track, null);
        _smtc.StateChanged += state => Forward(fromNation: false, null, state);
        _nation.Changed += () => Forward(fromNation: true, _nation.CurrentTrack, _nation.CurrentState);
    }

    public bool NationIsSource
    {
        get
        {
            if (!_nation.Available || _nation.CurrentTrack is null) return false;
            if (_nation.Playing) return true;
            var smtcTrack = _smtc.CurrentTrack;
            var smtcPlaying = _smtc.CurrentState?.Playing == true;
            var smtcIsNation = smtcTrack?.AppName == NationBridge.AppName;
            return smtcTrack is null || smtcIsNation || !smtcPlaying;
        }
    }

    private IPlaybackControl Active => NationIsSource ? _nation : _smtc;

    public TrackInfo? CurrentTrack => Active.CurrentTrack;
    public PlaybackState? CurrentState => Active.CurrentState;

    public Task TryPlayPauseAsync() => Active.TryPlayPauseAsync();
    public Task TryNextAsync() => Active.TryNextAsync();
    public Task TryPreviousAsync() => Active.TryPreviousAsync();
    public Task TrySeekAsync(long positionMs) => Active.TrySeekAsync(positionMs);
    public Task TryToggleShuffleAsync() => Active.TryToggleShuffleAsync();
    public Task TryCycleRepeatAsync() => Active.TryCycleRepeatAsync();

    private void Forward(bool fromNation, TrackInfo? track, PlaybackState? state)
    {
        TrackInfo? emitTrack = null;
        PlaybackState? emitState = null;
        lock (_lock)
        {
            var nationNow = NationIsSource;
            if (_lastWasNation != nationNow)
            {
                // Source flipped: replay the new source's snapshot.
                _lastWasNation = nationNow;
                var active = Active;
                emitTrack = active.CurrentTrack;
                emitState = active.CurrentState ?? new PlaybackState(false, 0, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
            }
            else if (fromNation == nationNow)
            {
                emitTrack = fromNation && ReferenceEquals(track, _lastNationTrack) ? null : track;
                emitState = state;
            }
            if (nationNow) _lastNationTrack = _nation.CurrentTrack;
        }

        if (emitTrack is not null) TrackChanged?.Invoke(emitTrack);
        if (emitState is not null) StateChanged?.Invoke(emitState);
    }
}
