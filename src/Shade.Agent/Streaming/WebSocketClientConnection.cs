using System.Net.WebSockets;
using System.Text;

namespace Shade.Agent.Streaming;

public sealed class WebSocketClientConnection : IClientConnection
{
    private readonly WebSocket _socket;
    private readonly ILogger _logger;
    private readonly byte[] _buffer = new byte[4096];

    public Guid Id { get; } = Guid.NewGuid();
    public ClientTransport Transport => ClientTransport.WebSocket;
    public bool IsOpen => _socket.State == WebSocketState.Open;

    public WebSocketClientConnection(WebSocket socket, ILogger logger)
    {
        _socket = socket;
        _logger = logger;
    }

    public async Task SendJsonAsync(byte[] utf8Json, CancellationToken ct)
    {
        try
        {
            await _socket.SendAsync(utf8Json, WebSocketMessageType.Text, true, ct);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Fallo enviando a un cliente WebSocket, se ignora.");
        }
    }

    // WebSocket clients fetch cover art over HTTP GET /art/{hash} instead.
    public Task SendArtAsync(string hash, string contentType, byte[] bytes, CancellationToken ct) =>
        Task.CompletedTask;

    public async Task<string?> ReceiveNextMessageAsync(CancellationToken ct)
    {
        if (_socket.State != WebSocketState.Open) return null;

        using var message = new MemoryStream();
        WebSocketReceiveResult result;
        do
        {
            result = await _socket.ReceiveAsync(_buffer, ct);
            if (result.MessageType == WebSocketMessageType.Close) return null;
            message.Write(_buffer, 0, result.Count);
        } while (!result.EndOfMessage);

        return Encoding.UTF8.GetString(message.ToArray());
    }

    public async ValueTask DisposeAsync()
    {
        if (_socket.State == WebSocketState.Open)
        {
            try
            {
                await _socket.CloseAsync(WebSocketCloseStatus.NormalClosure, null, CancellationToken.None);
            }
            catch (WebSocketException)
            {
                // Already gone; nothing to do.
            }
        }
        _socket.Dispose();
    }
}
