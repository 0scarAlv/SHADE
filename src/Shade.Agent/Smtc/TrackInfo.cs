namespace Shade.Agent.Smtc;

public sealed record TrackInfo(
    string Title,
    string Artist,
    string Album,
    long DurationMs,
    byte[]? ArtBytes,
    string? ArtHash,
    string? ArtContentType);

public sealed record PlaybackState(
    bool Playing,
    long PositionMs,
    long TimestampMs);
