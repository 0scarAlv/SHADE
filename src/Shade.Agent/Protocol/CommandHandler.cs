using System.Text.Json;
using Shade.Agent.Audio;
using Shade.Agent.Players;
using Shade.Agent.Streaming;
using Shade.Agent.SystemMonitor;

namespace Shade.Agent.Protocol;

// Shared by every transport (WebSocket, Bluetooth): parses one incoming JSON
// command and applies it to the SMTC session / system volume.
public static class CommandHandler
{
    public static async Task HandleAsync(
        string json,
        IPlaybackControl smtc,
        SystemVolumeController volumeController,
        ResourceMonitorService resourceMonitor,
        ClientHub clientHub,
        IClientConnection connection,
        ILogger logger)
    {
        // The journal sync is a whole document, not a cmd; it's relayed to Nation.
        if (json.Contains("\"sync\"") &&
            ParseObject(json) is { } doc &&
            doc["type"]?.GetValue<string>() == "sync")
        {
            await ListenSyncRelay.HandleAsync(doc, clientHub, connection, logger);
            return;
        }

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
            case "setVolume":
                if (command.Value is { } level)
                    volumeController.SetVolume(level);
                break;
            case "toggleMute":
                volumeController.ToggleMute();
                break;
            case "toggleShuffle":
                await smtc.TryToggleShuffleAsync();
                break;
            case "cycleRepeat":
                await smtc.TryCycleRepeatAsync();
                break;
            case "seek":
                if (command.Value is { } positionMs)
                    await smtc.TrySeekAsync((long)positionMs);
                break;
            case "topProcessesByRam":
                await clientHub.SendToAsync(connection, ToProcessListMessage("ram", resourceMonitor.TopProcessesByRam));
                break;
            case "topProcessesByCpu":
                await clientHub.SendToAsync(connection, ToProcessListMessage("cpu", resourceMonitor.TopProcessesByCpu));
                break;
            default:
                logger.LogInformation("Acción '{Action}' desconocida.", command.Action);
                break;
        }
    }

    private static System.Text.Json.Nodes.JsonObject? ParseObject(string json)
    {
        try { return System.Text.Json.Nodes.JsonNode.Parse(json) as System.Text.Json.Nodes.JsonObject; }
        catch (JsonException) { return null; }
    }

    private static ProcessListMessage ToProcessListMessage(string metric, IReadOnlyList<ProcessSample> samples) =>
        new(metric, samples.Select(s => new ProcessEntry(s.Name, s.Pid, s.RamBytes, s.CpuPercent)).ToList());
}
