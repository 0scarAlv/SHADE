using Shade.Agent.Audio;
using Shade.Agent.Protocol;
using Shade.Agent.Smtc;
using Shade.Agent.Streaming;
using Shade.Agent.SystemMonitor;
using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Networking.Sockets;
using Windows.Storage.Streams;

namespace Shade.Agent.Bluetooth;

// Advertises the Shade RFCOMM service so the phone can reach the agent without
// adb/USB. Sibling of AdbReverseWatchdog: both are just "keep a transport
// alive" hosted services, running side by side.
public enum BluetoothStatus { Starting, Advertising, Unavailable }

public sealed class RfcommServer : BackgroundService
{
    public BluetoothStatus Status { get; private set; } = BluetoothStatus.Starting;

    // SDP attribute id 0x0100 = ServiceName; 0x25 = "UTF-8 text string, length
    // in the next byte" per the Bluetooth SDP attribute type encoding.
    private const uint SdpServiceNameAttributeId = 0x100;
    private const byte SdpServiceNameAttributeType = 0x25;
    private const string SdpServiceName = "Shade";

    private readonly ClientHub _clientHub;
    private readonly SmtcSessionWatcher _smtc;
    private readonly SystemVolumeController _volumeController;
    private readonly ResourceMonitorService _resourceMonitor;
    private readonly ILogger<RfcommServer> _logger;
    private RfcommServiceProvider? _provider;
    private StreamSocketListener? _listener;

    // Fired right after a Bluetooth client is registered with ClientHub, so
    // Program.cs can send it the same catch-up snapshot a fresh WebSocket
    // client gets.
    public event Func<IClientConnection, Task>? ClientConnected;

    public RfcommServer(
        ClientHub clientHub,
        SmtcSessionWatcher smtc,
        SystemVolumeController volumeController,
        ResourceMonitorService resourceMonitor,
        ILogger<RfcommServer> logger)
    {
        _clientHub = clientHub;
        _smtc = smtc;
        _volumeController = volumeController;
        _resourceMonitor = resourceMonitor;
        _logger = logger;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        try
        {
            _provider = await RfcommServiceProvider.CreateAsync(RfcommServiceId.FromUuid(ShadeRfcommService.Uuid));

            _listener = new StreamSocketListener();
            _listener.ConnectionReceived += (sender, args) => OnConnectionReceived(args, stoppingToken);
            await _listener.BindServiceNameAsync(
                _provider.ServiceId.AsString(),
                SocketProtectionLevel.BluetoothEncryptionAllowNullAuthentication);

            // Windows' auto-generated SDP record (ServiceClassId + protocol
            // descriptor list) is enough for another Windows/UWP client, but
            // Android's SDP client does a direct UUID lookup that comes back
            // SDP_SUCCESS-but-scn:0 (record found the transport, but nothing
            // usable in it) without an explicit ServiceName attribute (SDP
            // attribute id 0x0100) — confirmed via adb logcat's bt_btif_sock_rfcomm
            // trace during a real connection attempt. This mirrors Microsoft's
            // own RfcommChat sample, which sets this same attribute for the
            // same cross-platform-interop reason.
            var sdpWriter = new DataWriter();
            sdpWriter.WriteByte(SdpServiceNameAttributeType);
            var serviceNameBytes = System.Text.Encoding.UTF8.GetBytes(SdpServiceName);
            sdpWriter.WriteByte((byte)serviceNameBytes.Length);
            sdpWriter.WriteBytes(serviceNameBytes);
            _provider.SdpRawAttributes.Add(SdpServiceNameAttributeId, sdpWriter.DetachBuffer());

            _provider.StartAdvertising(_listener, true);
            Status = BluetoothStatus.Advertising;
            _logger.LogInformation("Servicio RFCOMM anunciándose ({Uuid}).", ShadeRfcommService.Uuid);
        }
        catch (Exception ex)
        {
            Status = BluetoothStatus.Unavailable;
            _logger.LogWarning(ex, "No se pudo iniciar el servidor RFCOMM (¿sin adaptador Bluetooth?). El agente sigue funcionando sin Bluetooth.");
            return;
        }

        try
        {
            await Task.Delay(Timeout.Infinite, stoppingToken);
        }
        catch (OperationCanceledException)
        {
            // Shutting down.
        }
        finally
        {
            _provider?.StopAdvertising();
            _listener?.Dispose();
        }
    }

    private async void OnConnectionReceived(StreamSocketListenerConnectionReceivedEventArgs args, CancellationToken stoppingToken)
    {
        try
        {
            // args.Socket can throw (e.g. COMException 0x800703E3 if the
            // connection was aborted mid-handshake) — this whole method is
            // `async void`, so anything thrown outside this try crashes the
            // entire host process instead of just failing this connection.
            IClientConnection connection = new BluetoothClientConnection(args.Socket, _logger);
            _clientHub.Register(connection);
            if (ClientConnected is { } handler)
                await handler(connection);

            await _clientHub.PumpAsync(
                connection,
                json => CommandHandler.HandleAsync(json, _smtc, _volumeController, _resourceMonitor, _clientHub, connection, _logger),
                stoppingToken);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Fallo manejando una conexión Bluetooth entrante.");
        }
    }
}
