using System.Text.Json;
using Shade.Agent.Audio;
using Shade.Agent.Smtc;

namespace Shade.Agent.Protocol;

// Shared by every transport (WebSocket, Bluetooth): parses one incoming JSON
// command and applies it to the SMTC session / system volume.
public static class CommandHandler
{
    public static async Task HandleAsync(string json, SmtcSessionWatcher smtc, SystemVolumeController volumeController, ILogger logger)
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
}
