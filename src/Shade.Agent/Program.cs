using Shade.Agent.Adb;
using Shade.Agent.Audio;
using Shade.Agent.Bluetooth;
using Shade.Agent.Dashboard;
using Shade.Agent.Lyrics;
using Shade.Agent.Metadata;
using Shade.Agent.Protocol;
using Shade.Agent.Smtc;
using Shade.Agent.Streaming;
using Shade.Agent.SystemMonitor;

var builder = WebApplication.CreateBuilder(args);
builder.WebHost.UseUrls("http://127.0.0.1:8080");

// Interactive console -> lazygit-style dashboard (see ConsoleDashboard), with
// logs captured into a buffer it renders as a panel. `--plain` or a
// redirected console keeps the classic scrolling log instead.
var dashboardEnabled = ConsoleDashboard.ShouldRun(args);
var snapshotPath = args.SkipWhile(a => a != "--dashboard-snapshot").Skip(1).FirstOrDefault();
var logBuffer = new LogBuffer();
builder.Services.AddSingleton(logBuffer);
if (dashboardEnabled) builder.Logging.ClearProviders();
builder.Logging.AddProvider(new BufferLoggerProvider(logBuffer));
builder.Services.AddSingleton<ConsoleDashboard>();
if (dashboardEnabled) builder.Services.AddHostedService(sp => sp.GetRequiredService<ConsoleDashboard>());

builder.Services.AddSingleton<SmtcSessionWatcher>();
builder.Services.AddSingleton<ArtCache>();
builder.Services.AddSingleton<ClientHub>();
builder.Services.AddSingleton<SystemVolumeController>();
builder.Services.AddSingleton<SpectrumAnalyzer>();
builder.Services.AddSingleton<LyricsProvider>();
builder.Services.AddSingleton<TrackMetadataProvider>();
builder.Services.AddSingleton<AdbReverseWatchdog>();
builder.Services.AddHostedService(sp => sp.GetRequiredService<AdbReverseWatchdog>());
builder.Services.AddSingleton<RfcommServer>();
builder.Services.AddHostedService(sp => sp.GetRequiredService<RfcommServer>());
builder.Services.AddSingleton<ResourceMonitorService>();
builder.Services.AddHostedService(sp => sp.GetRequiredService<ResourceMonitorService>());

var app = builder.Build();

var smtc = app.Services.GetRequiredService<SmtcSessionWatcher>();
var artCache = app.Services.GetRequiredService<ArtCache>();
var clientHub = app.Services.GetRequiredService<ClientHub>();
var volumeController = app.Services.GetRequiredService<SystemVolumeController>();
var spectrumAnalyzer = app.Services.GetRequiredService<SpectrumAnalyzer>();
var lyricsProvider = app.Services.GetRequiredService<LyricsProvider>();
var metadataProvider = app.Services.GetRequiredService<TrackMetadataProvider>();
var rfcommServer = app.Services.GetRequiredService<RfcommServer>();
var resourceMonitor = app.Services.GetRequiredService<ResourceMonitorService>();
var logger = app.Services.GetRequiredService<ILogger<Program>>();

string? lastLyricsKey = null;
LyricsMessage? currentLyrics = null;
ResourceMessage? currentResource = null;
// The SMTC track merged with any metadata looked up for it (see
// TrackMetadataProvider) — what clients are actually shown.
TrackInfo? currentTrack = null;

smtc.TrackChanged += track =>
{
    // SMTC re-fires for the same song with the same sparse data; reapply
    // what an earlier lookup found so the art/album don't flicker away.
    if (metadataProvider.TryGetCached(track, out var known))
        track = TrackMetadataProvider.Apply(track, known);
    PublishTrack(track);

    var key = TrackMetadataProvider.KeyOf(track);
    if (key != lastLyricsKey)
    {
        lastLyricsKey = key;
        currentLyrics = null; // clear until the new fetch resolves, so a client
                               // connecting mid-fetch doesn't get the old song's lyrics
        _ = EnrichThenFetchLyricsAsync(track, key);
    }
};

void PublishTrack(TrackInfo track)
{
    currentTrack = track;
    if (track.ArtBytes is not null && track.ArtHash is not null && track.ArtContentType is not null)
    {
        artCache.Store(track.ArtHash, track.ArtBytes, track.ArtContentType);
        // WebSocket clients fetch the full-size art over HTTP GET /art/{hash}
        // instead; this only reaches Bluetooth-connected clients (no-op
        // otherwise), resized down first — see PushResizedArtAsync.
        _ = PushResizedArtAsync(track.ArtHash, track.ArtBytes);
    }
    _ = clientHub.BroadcastAsync(ToTrackMessage(track));
}

// Lookup first, lyrics second: LRCLIB matches far better with the album and
// duration the lookup may have filled in.
async Task EnrichThenFetchLyricsAsync(TrackInfo track, string key)
{
    if (TrackMetadataProvider.NeedsEnrichment(track) && !metadataProvider.TryGetCached(track, out _))
    {
        var enrichment = await metadataProvider.LookupAsync(track);
        // Drop the result if the user already skipped to another song.
        if (enrichment is not null && lastLyricsKey == key && currentTrack is { } latest && TrackMetadataProvider.KeyOf(latest) == key)
        {
            track = TrackMetadataProvider.Apply(latest, enrichment);
            PublishTrack(track);
        }
    }
    await FetchAndBroadcastLyricsAsync(track, key);
}

void BroadcastState(PlaybackState state)
{
    // The visualizer only makes sense while something's actually playing —
    // start/stop the WASAPI capture alongside it instead of running it
    // (and spamming the socket) against a silent desktop. Both calls are
    // idempotent, so re-broadcasting the same Playing value (e.g. after a
    // volume change) is harmless.
    if (state.Playing)
    {
        spectrumAnalyzer.Start();
    }
    else
    {
        spectrumAnalyzer.Stop();
        _ = clientHub.BroadcastAsync(new SpectrumMessage(new float[SpectrumAnalyzer.BandCount]));
    }

    _ = clientHub.BroadcastAsync(ToStateMessage(state));
}

TrackMessage ToTrackMessage(TrackInfo track) =>
    new(track.Title, track.Artist, track.Album, track.DurationMs, track.ArtHash, track.AppName);

StateMessage ToStateMessage(PlaybackState state) =>
    new(state.Playing, state.PositionMs, state.TimestampMs,
        volumeController.GetVolume(), volumeController.GetMuted(),
        state.Shuffle, state.Repeat);

smtc.StateChanged += BroadcastState;

volumeController.VolumeChanged += () =>
{
    if (smtc.CurrentState is { } state) BroadcastState(state);
};

spectrumAnalyzer.SpectrumAvailable += bands => _ = clientHub.BroadcastAsync(new SpectrumMessage(bands));

resourceMonitor.SampleAvailable += sample =>
{
    currentResource = new ResourceMessage(
        sample.RamUsedBytes, sample.RamTotalBytes,
        sample.NetDownBytesPerSec, sample.NetUpBytesPerSec,
        sample.HasBattery, sample.BatteryPercent, sample.BatteryCharging,
        sample.CpuUsagePercent,
        sample.DiskReadBytesPerSec, sample.DiskWriteBytesPerSec);
    _ = clientHub.BroadcastAsync(currentResource);
};

async Task FetchAndBroadcastLyricsAsync(TrackInfo track, string key)
{
    var result = await lyricsProvider.TryFetchAsync(track.Title, track.Artist, track.Album, track.DurationMs);
    if (result is null || lastLyricsKey != key) return;

    var message = new LyricsMessage(result.Lines, result.Plain);
    currentLyrics = message;
    await clientHub.BroadcastAsync(message);
}

// SMTC can hand back oversized thumbnails (seen up to 1500x1500) — sending
// that raw over Bluetooth's much lower throughput blocks every other
// broadcast queued behind it on the same connection. Resize once here rather
// than per-connection.
async Task<(byte[] Bytes, string ContentType)?> TryResizeArtForBluetoothAsync(byte[] original)
{
    try
    {
        return await ArtResizer.ResizeForBluetoothAsync(original);
    }
    catch (Exception ex)
    {
        logger.LogWarning(ex, "No se pudo redimensionar la carátula para Bluetooth.");
        return null;
    }
}

async Task PushResizedArtAsync(string hash, byte[] originalBytes)
{
    if (await TryResizeArtForBluetoothAsync(originalBytes) is not { } resized) return;
    await clientHub.BroadcastArtAsync(hash, resized.Bytes, resized.ContentType);
}

// A client that just (re)connected needs to be caught up immediately —
// otherwise it's blind until the next real SMTC change, which can take
// minutes. Shared by both transports.
async Task SendCatchUpAsync(IClientConnection connection, CancellationToken ct)
{
    if (smtc.CurrentTrack is not null && currentTrack is { } track)
    {
        await clientHub.SendToAsync(connection, ToTrackMessage(track), ct);
        if (track.ArtBytes is not null && track.ArtHash is not null
            && await TryResizeArtForBluetoothAsync(track.ArtBytes) is { } resized)
        {
            await connection.SendArtAsync(track.ArtHash, resized.ContentType, resized.Bytes, ct);
        }
    }
    if (smtc.CurrentState is { } state)
    {
        await clientHub.SendToAsync(connection, ToStateMessage(state), ct);
    }
    if (currentLyrics is { } lyrics)
    {
        await clientHub.SendToAsync(connection, lyrics, ct);
    }
    if (currentResource is { } resource)
    {
        await clientHub.SendToAsync(connection, resource, ct);
    }
}

rfcommServer.ClientConnected += connection => SendCatchUpAsync(connection, CancellationToken.None);

await smtc.StartAsync();

app.UseWebSockets();

app.MapGet("/", async (HttpContext context) =>
{
    if (!context.WebSockets.IsWebSocketRequest)
    {
        context.Response.StatusCode = StatusCodes.Status400BadRequest;
        return;
    }

    var socket = await context.WebSockets.AcceptWebSocketAsync();
    IClientConnection connection = new WebSocketClientConnection(socket, logger);
    clientHub.Register(connection);

    await SendCatchUpAsync(connection, context.RequestAborted);

    await clientHub.PumpAsync(
        connection,
        json => CommandHandler.HandleAsync(json, smtc, volumeController, resourceMonitor, clientHub, connection, logger),
        context.RequestAborted);
});

app.MapGet("/art/{hash}", (string hash) =>
{
    if (!artCache.TryGet(hash, out var art))
        return Results.NotFound();

    return Results.File(art.Bytes, art.ContentType);
});

// Dev aid: render one dashboard frame to a text file after a few seconds
// of live data, then exit — checks the layout without a real terminal.
if (snapshotPath is not null)
{
    app.Lifetime.ApplicationStarted.Register(() => _ = Task.Run(async () =>
    {
        await Task.Delay(4_000);
        var dashboard = app.Services.GetRequiredService<ConsoleDashboard>();
        await File.WriteAllTextAsync(snapshotPath, dashboard.RenderSnapshot(120, 34) + "\n---\n" + dashboard.RenderSnapshot(80, 24));
        app.Lifetime.StopApplication();
    }));
}

app.Run();
