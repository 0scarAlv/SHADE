namespace Shade.Agent.Streaming;

public enum ClientTransport { WebSocket, Bluetooth }

// One connected app (phone, test-client, ...), regardless of transport. ClientHub
// only ever talks to this interface, so WebSocket and Bluetooth clients can be
// broadcast to / read from uniformly.
public interface IClientConnection : IAsyncDisposable
{
    Guid Id { get; }
    ClientTransport Transport { get; }
    bool IsOpen { get; }

    Task SendJsonAsync(byte[] utf8Json, CancellationToken ct);

    // No-op on transports that serve art over HTTP instead (WebSocket).
    Task SendArtAsync(string hash, string contentType, byte[] bytes, CancellationToken ct);

    // Returns null when the connection has closed.
    Task<string?> ReceiveNextMessageAsync(CancellationToken ct);
}
