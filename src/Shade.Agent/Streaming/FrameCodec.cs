using System.Buffers.Binary;

namespace Shade.Agent.Streaming;

// RFCOMM is a raw byte stream with no message boundaries (unlike WebSocket), so
// every message is wrapped in a 5-byte header: [1-byte type][4-byte BE length].
// Must match android/.../data/FrameCodec.kt exactly.
public static class FrameCodec
{
    public const byte TypeJson = 0x01;
    public const byte TypeArt = 0x02;

    // Guards against a corrupted/malicious length field causing a runaway allocation.
    public const int MaxFrameBytes = 8 * 1024 * 1024;

    public static async Task WriteFrameAsync(Stream stream, byte type, ReadOnlyMemory<byte> payload, CancellationToken ct)
    {
        var header = new byte[5];
        header[0] = type;
        BinaryPrimitives.WriteUInt32BigEndian(header.AsSpan(1), (uint)payload.Length);

        await stream.WriteAsync(header, ct);
        await stream.WriteAsync(payload, ct);
        await stream.FlushAsync(ct);
    }

    // Returns null when the stream ended cleanly right at a frame boundary
    // (i.e. the connection closed).
    public static async Task<(byte Type, byte[] Payload)?> ReadFrameAsync(Stream stream, CancellationToken ct)
    {
        var header = new byte[5];
        if (!await TryReadExactlyAsync(stream, header, ct))
            return null;

        var type = header[0];
        var length = BinaryPrimitives.ReadUInt32BigEndian(header.AsSpan(1));
        if (length > MaxFrameBytes)
            throw new InvalidDataException($"Frame Bluetooth de {length} bytes excede el máximo permitido ({MaxFrameBytes}).");

        var payload = new byte[length];
        if (length > 0 && !await TryReadExactlyAsync(stream, payload, ct))
            throw new EndOfStreamException("Conexión Bluetooth cerrada a mitad de un frame.");

        return (type, payload);
    }

    // Like Stream.ReadExactlyAsync, but returns false instead of throwing when
    // the stream ends before any bytes were read at all (a clean close).
    private static async Task<bool> TryReadExactlyAsync(Stream stream, byte[] buffer, CancellationToken ct)
    {
        var read = 0;
        while (read < buffer.Length)
        {
            var n = await stream.ReadAsync(buffer.AsMemory(read), ct);
            if (n == 0)
                return read == 0 ? false : throw new EndOfStreamException("Conexión Bluetooth cerrada a mitad de un frame.");
            read += n;
        }
        return true;
    }
}
