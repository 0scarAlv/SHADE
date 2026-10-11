namespace Shade.Agent.Smtc;

public sealed record TrackInfo(
    string Title,
    string Artist,
    string Album,
    long DurationMs,
    byte[]? ArtBytes,
    string? ArtHash,
    string? ArtContentType,
    string? AppName = null);

public sealed record PlaybackState(
    bool Playing,
    long PositionMs,
    long TimestampMs,
    // Null when the source app doesn't expose shuffle/repeat through SMTC at
    // all (most browsers) — the panel hides those toggles in that case.
    bool? Shuffle = null,
    string? Repeat = null);
