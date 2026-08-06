using System.Text;
using Windows.Networking.Sockets;

namespace Shade.Agent.Streaming;

public sealed class BluetoothClientConnection : IClientConnection
{
    private readonly StreamSocket _socket;
    private readonly Stream _input;
    private readonly Stream _output;
    private readonly ILogger _logger;

    // RFCOMM is a single raw stream: two broadcasts racing each other (e.g. a
    // TrackMessage and the StateMessage that follows it) must not interleave
    // their bytes on the wire, or every frame after that is corrupted.
    private readonly SemaphoreSlim _writeLock = new(1, 1);

    private volatile bool _isOpen = true;

    public Guid Id { get; } = Guid.NewGuid();
    public ClientTransport Transport => ClientTransport.Bluetooth;
    public bool IsOpen => _isOpen;

    public BluetoothClientConnection(StreamSocket socket, ILogger logger)
    {
        _socket = socket;
        _input = socket.InputStream.AsStreamForRead();
        _output = socket.OutputStream.AsStreamForWrite();
        _logger = logger;
    }

    public Task SendJsonAsync(byte[] utf8Json, CancellationToken ct) =>
        WriteFrameSafeAsync(FrameCodec.TypeJson, utf8Json, ct);

    public Task SendArtAsync(string hash, string contentType, byte[] bytes, CancellationToken ct)
    {
        var hashBytes = Encoding.UTF8.GetBytes(hash);
        var contentTypeBytes = Encoding.UTF8.GetBytes(contentType);
        if (hashBytes.Length > 255 || contentTypeBytes.Length > 255)
        {
            _logger.LogWarning("Hash o content-type de carátula demasiado largo para el frame Bluetooth; se omite.");
            return Task.CompletedTask;
        }

        var payload = new byte[2 + hashBytes.Length + contentTypeBytes.Length + bytes.Length];
        var offset = 0;
        payload[offset++] = (byte)hashBytes.Length;
        hashBytes.CopyTo(payload, offset); offset += hashBytes.Length;
        payload[offset++] = (byte)contentTypeBytes.Length;
        contentTypeBytes.CopyTo(payload, offset); offset += contentTypeBytes.Length;
        bytes.CopyTo(payload, offset);

        return WriteFrameSafeAsync(FrameCodec.TypeArt, payload, ct);
    }

    private async Task WriteFrameSafeAsync(byte type, byte[] payload, CancellationToken ct)
    {
        await _writeLock.WaitAsync(ct);
        try
        {
            await FrameCodec.WriteFrameAsync(_output, type, payload, ct);
        }
        catch (Exception ex)
        {
            _isOpen = false;
            _logger.LogWarning(ex, "Fallo enviando a un cliente Bluetooth, se ignora.");
        }
        finally
        {
            _writeLock.Release();
        }
    }

    // Only JSON frames are ever expected from the phone — the app never pushes
    // art. A stray Art frame here would mean a protocol mismatch; drop the
    // connection rather than silently misinterpreting it.
    public async Task<string?> ReceiveNextMessageAsync(CancellationToken ct)
    {
        try
        {
            var frame = await FrameCodec.ReadFrameAsync(_input, ct);
            if (frame is not { } f)
            {
                _isOpen = false;
                return null;
            }

            if (f.Type != FrameCodec.TypeJson)
            {
                _logger.LogWarning("Frame Bluetooth de tipo inesperado ({Type}) recibido del cliente; se cierra la conexión.", f.Type);
                _isOpen = false;
                return null;
            }

            return Encoding.UTF8.GetString(f.Payload);
        }
        catch (Exception ex) when (ex is IOException or ObjectDisposedException)
        {
            _isOpen = false;
            return null;
        }
    }

    public ValueTask DisposeAsync()
    {
        _isOpen = false;
        _input.Dispose();
        _output.Dispose();
        _socket.Dispose();
        _writeLock.Dispose();
        return ValueTask.CompletedTask;
    }
}
