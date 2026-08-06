using System.Runtime.InteropServices.WindowsRuntime;
using Windows.Graphics.Imaging;
using Windows.Storage.Streams;

namespace Shade.Agent.Bluetooth;

// Bluetooth Classic RFCOMM has far less throughput than the HTTP path used by
// WebSocket clients, and SMTC can hand back oversized thumbnails (seen up to
// 1500x1500 from some apps) — sending that raw over Bluetooth takes long
// enough to block every other broadcast queued behind it on the same
// connection's write lock (state, spectrum, the next track...). Re-encode
// down to something a phone screen actually needs before pushing it.
public static class ArtResizer
{
    private const uint MaxDimension = 320;
    private const float JpegQuality = 0.75f;

    public static async Task<(byte[] Bytes, string ContentType)> ResizeForBluetoothAsync(byte[] original)
    {
        using var inputStream = new InMemoryRandomAccessStream();
        await inputStream.WriteAsync(original.AsBuffer());
        inputStream.Seek(0);

        var decoder = await BitmapDecoder.CreateAsync(inputStream);
        var bitmap = await decoder.GetSoftwareBitmapAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied);
        var (width, height) = ScaledSize(decoder.PixelWidth, decoder.PixelHeight);

        using var outputStream = new InMemoryRandomAccessStream();
        var encoder = await BitmapEncoder.CreateAsync(BitmapEncoder.JpegEncoderId, outputStream);
        encoder.SetSoftwareBitmap(bitmap);
        encoder.BitmapTransform.ScaledWidth = width;
        encoder.BitmapTransform.ScaledHeight = height;
        encoder.BitmapTransform.InterpolationMode = BitmapInterpolationMode.Fant;

        var properties = new BitmapPropertySet
        {
            { "ImageQuality", new BitmapTypedValue(JpegQuality, Windows.Foundation.PropertyType.Single) },
        };
        await encoder.BitmapProperties.SetPropertiesAsync(properties);
        await encoder.FlushAsync();

        var bytes = new byte[outputStream.Size];
        await outputStream.ReadAsync(bytes.AsBuffer(), (uint)outputStream.Size, InputStreamOptions.None);
        return (bytes, "image/jpeg");
    }

    private static (uint Width, uint Height) ScaledSize(uint width, uint height)
    {
        if (width <= MaxDimension && height <= MaxDimension) return (width, height);

        var scale = Math.Min((double)MaxDimension / width, (double)MaxDimension / height);
        return ((uint)Math.Max(1, width * scale), (uint)Math.Max(1, height * scale));
    }
}
