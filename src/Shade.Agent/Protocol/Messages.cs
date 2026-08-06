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
    double? Volume = null)
{
    public string Type => "state";
}

public sealed record LyricsLine(long TimeMs, string Text);

public sealed record LyricsMessage(
    List<LyricsLine>? Lines,
    string? Plain)
{
    public string Type => "lyrics";
}

public sealed record SpectrumMessage(float[] Bands)
{
    public string Type => "spectrum";
}

public sealed record ResourceMessage(
    long RamUsedBytes,
    long RamTotalBytes,
    double NetDownBytesPerSec,
    double NetUpBytesPerSec,
    bool HasBattery,
    int? BatteryPercent,
    bool? BatteryCharging)
{
    public string Type => "resource";
}

// App -> Agent

public sealed record IncomingCommand(string Type, string Action, double? Value);
