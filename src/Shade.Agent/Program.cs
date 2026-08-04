using System.Net.WebSockets;
using System.Text.Json;
using Shade.Agent.Adb;
using Shade.Agent.Audio;
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

var app = builder.Build();

var smtc = app.Services.GetRequiredService<SmtcSessionWatcher>();
var artCache = app.Services.GetRequiredService<ArtCache>();
var clientHub = app.Services.GetRequiredService<ClientHub>();
var volumeController = app.Services.GetRequiredService<SystemVolumeController>();
var spectrumAnalyzer = app.Services.GetRequiredService<SpectrumAnalyzer>();
var lyricsProvider = app.Services.GetRequiredService<LyricsProvider>();
var logger = app.Services.GetRequiredService<ILogger<Program>>();

string? lastLyricsKey = null;
LyricsMessage? currentLyrics = null;

smtc.TrackChanged += track =>
{
    if (track.ArtBytes is not null && track.ArtHash is not null && track.ArtContentType is not null)
        artCache.Store(track.ArtHash, track.ArtBytes, track.ArtContentType);

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

await smtc.StartAsync();

app.UseWebSockets();

app.MapGet("/", async (HttpContext context) =>
{
    if (!context.WebSockets.IsWebSocketRequest)
    {
        context.Response.StatusCode = StatusCodes.Status400BadRequest;
        return;
    }

    using var socket = await context.WebSockets.AcceptWebSocketAsync();

    // A client that just (re)connected needs to be caught up immediately —
    // otherwise it's blind until the next real SMTC change, which can take
    // minutes.
    if (smtc.CurrentTrack is { } track)
    {
        await clientHub.SendToAsync(socket, new TrackMessage(
            track.Title, track.Artist, track.Album, track.DurationMs, track.ArtHash),
            context.RequestAborted);
    }
    if (smtc.CurrentState is { } state)
    {
        await clientHub.SendToAsync(socket, new StateMessage(state.Playing, state.PositionMs, state.TimestampMs, volumeController.GetVolume()),
            context.RequestAborted);
    }
    if (currentLyrics is { } lyrics)
    {
        await clientHub.SendToAsync(socket, lyrics, context.RequestAborted);
    }

    await clientHub.HandleClientAsync(
        socket,
        json => HandleCommandAsync(json, smtc, volumeController, logger),
        context.RequestAborted);
});

app.MapGet("/art/{hash}", (string hash) =>
{
    if (!artCache.TryGet(hash, out var art))
        return Results.NotFound();

    return Results.File(art.Bytes, art.ContentType);
});

app.Run();

static async Task HandleCommandAsync(string json, SmtcSessionWatcher smtc, SystemVolumeController volumeController, ILogger logger)
{
    IncomingCommand? command;
    try
    {
        command = JsonSerializer.Deserialize<IncomingCommand>(json, ShadeJson.Options);
    }
    catch (JsonException ex)
    {
        logger.LogWarning(ex, "Comando con JSON inválido: {Json}", json);
        return;
    }

    if (command is not { Type: "cmd" })
    {
        logger.LogWarning("Mensaje ignorado (tipo inesperado): {Json}", json);
        return;
    }

    switch (command.Action)
    {
        case "playPause":
            await smtc.TryPlayPauseAsync();
            break;
        case "next":
            await smtc.TryNextAsync();
            break;
        case "prev":
            await smtc.TryPreviousAsync();
            break;
        case "volumeUp":
            volumeController.VolumeUp();
            break;
        case "volumeDown":
            volumeController.VolumeDown();
            break;
        case "seek":
            if (command.Value is { } positionMs)
                await smtc.TrySeekAsync((long)positionMs);
            break;
        default:
            logger.LogInformation("Acción '{Action}' desconocida.", command.Action);
            break;
    }
}
