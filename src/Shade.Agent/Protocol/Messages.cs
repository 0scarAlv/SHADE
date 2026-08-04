namespace Shade.Agent.Protocol;

// Agent -> App

public sealed record TrackMessage(
    string Title,
    string Artist,
    string Album,
    long DurationMs,
    string? ArtHash)
{
    public string Type => "track";
}

public sealed record StateMessage(
    bool Playing,
    long PositionMs,
    long TimestampMs,
    double? Volume = null) // CoreAudio lands in a later phase; omitted from the JSON while null.
{
    public string Type => "state";
}

// App -> Agent

public sealed record IncomingCommand(string Type, string Action, double? Value);
