using Shade.Agent.Adb;
using Shade.Agent.Audio;
using Shade.Agent.Bluetooth;
using Shade.Agent.Lyrics;
using Shade.Agent.Protocol;
using Shade.Agent.Smtc;
using Shade.Agent.Streaming;

var builder = WebApplication.CreateBuilder(args);
builder.WebHost.UseUrls("http://127.0.0.1:8080");

builder.Services.AddSingleton<SmtcSessionWatcher>();
builder.Services.AddSingleton<ArtCache>();
builder.Services.AddSingleton<ClientHub>();
builder.Services.AddSingleton<SystemVolumeController>();
builder.Services.AddSingleton<SpectrumAnalyzer>();
builder.Services.AddSingleton<LyricsProvider>();
builder.Services.AddHostedService<AdbReverseWatchdog>();
builder.Services.AddSingleton<RfcommServer>();
builder.Services.AddHostedService(sp => sp.GetRequiredService<RfcommServer>());

var app = builder.Build();

var smtc = app.Services.GetRequiredService<SmtcSessionWatcher>();
var artCache = app.Services.GetRequiredService<ArtCache>();
var clientHub = app.Services.GetRequiredService<ClientHub>();
var volumeController = app.Services.GetRequiredService<SystemVolumeController>();
var spectrumAnalyzer = app.Services.GetRequiredService<SpectrumAnalyzer>();
var lyricsProvider = app.Services.GetRequiredService<LyricsProvider>();
var rfcommServer = app.Services.GetRequiredService<RfcommServer>();
var logger = app.Services.GetRequiredService<ILogger<Program>>();

string? lastLyricsKey = null;
LyricsMessage? currentLyrics = null;

smtc.TrackChanged += track =>
{
    if (track.ArtBytes is not null && track.ArtHash is not null && track.ArtContentType is not null)
    {
        artCache.Store(track.ArtHash, track.ArtBytes, track.ArtContentType);
        // WebSocket clients fetch art over HTTP GET /art/{hash} instead; this
        // only reaches Bluetooth-connected clients (no-op otherwise).
        _ = clientHub.BroadcastArtAsync(track.ArtHash, track.ArtBytes, track.ArtContentType);
    }

    _ = clientHub.BroadcastAsync(new TrackMessage(
        track.Title, track.Artist, track.Album, track.DurationMs, track.ArtHash));

    var lyricsKey = $"{track.Title}|{track.Artist}";
    if (lyricsKey != lastLyricsKey)
    {
        lastLyricsKey = lyricsKey;
        currentLyrics = null; // clear until the new fetch resolves, so a client
                               // connecting mid-fetch doesn't get the old song's lyrics
        _ = FetchAndBroadcastLyricsAsync(track);
    }
};

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
        _ = clientHub.BroadcastAsync(new SpectrumMessage(new float[32]));
    }

    _ = clientHub.BroadcastAsync(new StateMessage(state.Playing, state.PositionMs, state.TimestampMs, volumeController.GetVolume()));
}

smtc.StateChanged += BroadcastState;

volumeController.VolumeChanged += () =>
{
    if (smtc.CurrentState is { } state) BroadcastState(state);
};

spectrumAnalyzer.SpectrumAvailable += bands => _ = clientHub.BroadcastAsync(new SpectrumMessage(bands));

async Task FetchAndBroadcastLyricsAsync(TrackInfo track)
{
    var result = await lyricsProvider.TryFetchAsync(track.Title, track.Artist, track.Album, track.DurationMs);
    if (result is null) return;

    var message = new LyricsMessage(result.Lines, result.Plain);
    currentLyrics = message;
    await clientHub.BroadcastAsync(message);
}

// A client that just (re)connected needs to be caught up immediately —
// otherwise it's blind until the next real SMTC change, which can take
// minutes. Shared by both transports.
async Task SendCatchUpAsync(IClientConnection connection, CancellationToken ct)
{
    if (smtc.CurrentTrack is { } track)
    {
        await clientHub.SendToAsync(connection, new TrackMessage(
            track.Title, track.Artist, track.Album, track.DurationMs, track.ArtHash), ct);
        if (track.ArtBytes is not null && track.ArtHash is not null && track.ArtContentType is not null)
            await connection.SendArtAsync(track.ArtHash, track.ArtContentType, track.ArtBytes, ct);
    }
    if (smtc.CurrentState is { } state)
    {
        await clientHub.SendToAsync(connection, new StateMessage(state.Playing, state.PositionMs, state.TimestampMs, volumeController.GetVolume()), ct);
    }
    if (currentLyrics is { } lyrics)
    {
        await clientHub.SendToAsync(connection, lyrics, ct);
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
        json => CommandHandler.HandleAsync(json, smtc, volumeController, logger),
        context.RequestAborted);
});

app.MapGet("/art/{hash}", (string hash) =>
{
    if (!artCache.TryGet(hash, out var art))
        return Results.NotFound();

    return Results.File(art.Bytes, art.ContentType);
});

app.Run();
