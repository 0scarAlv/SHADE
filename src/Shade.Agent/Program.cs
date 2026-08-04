using System.Net.WebSockets;
using System.Text.Json;
using Shade.Agent.Adb;
using Shade.Agent.Protocol;
using Shade.Agent.Smtc;
using Shade.Agent.Streaming;

var builder = WebApplication.CreateBuilder(args);
builder.WebHost.UseUrls("http://127.0.0.1:8080");

builder.Services.AddSingleton<SmtcSessionWatcher>();
builder.Services.AddSingleton<ArtCache>();
builder.Services.AddSingleton<ClientHub>();
builder.Services.AddHostedService<AdbReverseWatchdog>();

var app = builder.Build();

var smtc = app.Services.GetRequiredService<SmtcSessionWatcher>();
var artCache = app.Services.GetRequiredService<ArtCache>();
var clientHub = app.Services.GetRequiredService<ClientHub>();
var logger = app.Services.GetRequiredService<ILogger<Program>>();

smtc.TrackChanged += track =>
{
    if (track.ArtBytes is not null && track.ArtHash is not null && track.ArtContentType is not null)
        artCache.Store(track.ArtHash, track.ArtBytes, track.ArtContentType);

    _ = clientHub.BroadcastAsync(new TrackMessage(
        track.Title, track.Artist, track.Album, track.DurationMs, track.ArtHash));
};

smtc.StateChanged += state =>
{
    _ = clientHub.BroadcastAsync(new StateMessage(state.Playing, state.PositionMs, state.TimestampMs));
};

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
        await clientHub.SendToAsync(socket, new StateMessage(state.Playing, state.PositionMs, state.TimestampMs),
            context.RequestAborted);
    }

    await clientHub.HandleClientAsync(
        socket,
        json => HandleCommandAsync(json, smtc, logger),
        context.RequestAborted);
});

app.MapGet("/art/{hash}", (string hash) =>
{
    if (!artCache.TryGet(hash, out var art))
        return Results.NotFound();

    return Results.File(art.Bytes, art.ContentType);
});

app.Run();

static async Task HandleCommandAsync(string json, SmtcSessionWatcher smtc, ILogger logger)
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
        default:
            logger.LogInformation("Acción '{Action}' aún no implementada (fase posterior).", command.Action);
            break;
    }
}
