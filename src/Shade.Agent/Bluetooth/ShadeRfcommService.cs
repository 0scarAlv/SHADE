namespace Shade.Agent.Bluetooth;

public static class ShadeRfcommService
{
    // Fixed constant — must match android/.../data/ShadeBluetoothTransport.kt exactly.
    public static readonly Guid Uuid = Guid.Parse("ae019494-b8df-4acc-9b10-a6ecf17410b2");
}
