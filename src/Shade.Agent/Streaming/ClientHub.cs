using System.Collections.Concurrent;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using Shade.Agent.Protocol;

namespace Shade.Agent.Streaming;

// Tracks connected app sockets and acts as the single broadcast point. One
// agent can have several clients at once (e.g. the phone and the test page
// open at the same time), so everything here is 1-to-N.
public sealed class ClientHub
{
    private readonly ConcurrentDictionary<Guid, WebSocket> _clients = new();
    private readonly ILogger<ClientHub> _logger;

    public ClientHub(ILogger<ClientHub> logger)
    {
        _logger = logger;
    }

    public async Task HandleClientAsync(WebSocket socket, Func<string, Task> onCommand, CancellationToken cancellationToken)
    {
        var id = Guid.NewGuid();
        _clients[id] = socket;
        _logger.LogInformation("Cliente conectado ({Id}). Total: {Count}", id, _clients.Count);

        var buffer = new byte[4096];
        try
        {
            while (socket.State == WebSocketState.Open)
            {
                using var message = new MemoryStream();
                WebSocketReceiveResult result;
                do
                {
                    result = await socket.ReceiveAsync(buffer, cancellationToken);
                    if (result.MessageType == WebSocketMessageType.Close) break;
                    message.Write(buffer, 0, result.Count);
                } while (!result.EndOfMessage);

                if (result.MessageType == WebSocketMessageType.Close) break;

                var text = Encoding.UTF8.GetString(message.ToArray());
                await onCommand(text);
            }

            await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, null, cancellationToken);
        }
        catch (OperationCanceledException)
        {
            // The server is shutting down; nothing to do.
        }
        catch (WebSocketException ex)
        {
            _logger.LogInformation("Cliente {Id} desconectado: {Message}", id, ex.Message);
        }
        finally
        {
            _clients.TryRemove(id, out _);
            _logger.LogInformation("Cliente desconectado ({Id}). Total: {Count}", id, _clients.Count);
        }
    }

    public Task BroadcastAsync(object message, CancellationToken cancellationToken = default)
    {
        var bytes = Serialize(message);

        var sends = _clients.Values
            .Where(socket => socket.State == WebSocketState.Open)
            .Select(socket => SendSafeAsync(socket, bytes, cancellationToken));

        return Task.WhenAll(sends);
    }

    // Catches a newly (re)connected client up with the last known track/state,
    // without waiting for the next SMTC change.
    public Task SendToAsync(WebSocket socket, object message, CancellationToken cancellationToken = default) =>
        SendSafeAsync(socket, Serialize(message), cancellationToken);

    private static byte[] Serialize(object message) =>
        Encoding.UTF8.GetBytes(JsonSerializer.Serialize(message, message.GetType(), ShadeJson.Options));

    private async Task SendSafeAsync(WebSocket socket, byte[] bytes, CancellationToken cancellationToken)
    {
        try
        {
            await socket.SendAsync(bytes, WebSocketMessageType.Text, true, cancellationToken);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Fallo enviando a un cliente, se ignora.");
        }
    }
}
