using System.Collections.Concurrent;
using System.Text.Json;
using Shade.Agent.Protocol;

namespace Shade.Agent.Streaming;

// Tracks connected app clients (WebSocket or Bluetooth) and acts as the single
// broadcast point. One agent can have several clients at once (e.g. the phone
// and the test page open at the same time), so everything here is 1-to-N.
public sealed class ClientHub
{
    private readonly ConcurrentDictionary<Guid, IClientConnection> _clients = new();
    private readonly ILogger<ClientHub> _logger;

    public ClientHub(ILogger<ClientHub> logger)
    {
        _logger = logger;
    }

    public void Register(IClientConnection connection)
    {
        _clients[connection.Id] = connection;
        _logger.LogInformation("Cliente conectado ({Id}, {Transport}). Total: {Count}", connection.Id, connection.Transport, _clients.Count);
    }

    // Reads messages off the connection until it closes, dispatching each one to
    // onCommand. Same loop for every transport — this is what generalizing
    // IClientConnection buys us over the old WebSocket-only HandleClientAsync.
    public async Task PumpAsync(IClientConnection connection, Func<string, Task> onCommand, CancellationToken ct)
    {
        try
        {
            while (connection.IsOpen)
            {
                var json = await connection.ReceiveNextMessageAsync(ct);
                if (json is null) break;
                await onCommand(json);
            }
        }
        catch (OperationCanceledException)
        {
            // The server is shutting down; nothing to do.
        }
        catch (Exception ex)
        {
            _logger.LogInformation(ex, "Cliente {Id} desconectado.", connection.Id);
        }
        finally
        {
            _clients.TryRemove(connection.Id, out _);
            await connection.DisposeAsync();
            _logger.LogInformation("Cliente desconectado ({Id}). Total: {Count}", connection.Id, _clients.Count);
        }
    }

    public Task BroadcastAsync(object message, CancellationToken ct = default)
    {
        var bytes = Serialize(message);

        var sends = _clients.Values
            .Where(c => c.IsOpen)
            .Select(c => c.SendJsonAsync(bytes, ct));

        return Task.WhenAll(sends);
    }

    // Catches a newly (re)connected client up with the last known track/state,
    // without waiting for the next SMTC change.
    public Task SendToAsync(IClientConnection connection, object message, CancellationToken ct = default) =>
        connection.SendJsonAsync(Serialize(message), ct);

    // Pushes cover art to Bluetooth clients only — WebSocket clients fetch it
    // over HTTP GET /art/{hash} instead, so SendArtAsync is a no-op for them.
    public Task BroadcastArtAsync(string hash, byte[] bytes, string contentType, CancellationToken ct = default)
    {
        var sends = _clients.Values
            .Where(c => c.IsOpen && c.Transport == ClientTransport.Bluetooth)
            .Select(c => c.SendArtAsync(hash, contentType, bytes, ct));

        return Task.WhenAll(sends);
    }

    private static byte[] Serialize(object message) =>
        JsonSerializer.SerializeToUtf8Bytes(message, message.GetType(), ShadeJson.Options);
}
