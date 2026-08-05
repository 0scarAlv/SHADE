using Shade.Agent.Audio;
using Shade.Agent.Protocol;
using Shade.Agent.Smtc;
using Shade.Agent.Streaming;
using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Networking.Sockets;

namespace Shade.Agent.Bluetooth;

// Advertises the Shade RFCOMM service so the phone can reach the agent without
// adb/USB. Sibling of AdbReverseWatchdog: both are just "keep a transport
// alive" hosted services, running side by side.
public sealed class RfcommServer : BackgroundService
{
    private readonly ClientHub _clientHub;
    private readonly SmtcSessionWatcher _smtc;
    private readonly SystemVolumeController _volumeController;
    private readonly ILogger<RfcommServer> _logger;
    private RfcommServiceProvider? _provider;
    private StreamSocketListener? _listener;

    // Fired right after a Bluetooth client is registered with ClientHub, so
    // Program.cs can send it the same catch-up snapshot a fresh WebSocket
    // client gets.
    public event Func<IClientConnection, Task>? ClientConnected;

    public RfcommServer(ClientHub clientHub, SmtcSessionWatcher smtc, SystemVolumeController volumeController, ILogger<RfcommServer> logger)
    {
        _clientHub = clientHub;
        _smtc = smtc;
        _volumeController = volumeController;
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

            _provider.StartAdvertising(_listener, true);
            _logger.LogInformation("Servicio RFCOMM anunciándose ({Uuid}).", ShadeRfcommService.Uuid);
        }
        catch (Exception ex)
        {
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
        IClientConnection connection = new BluetoothClientConnection(args.Socket, _logger);
        try
        {
            _clientHub.Register(connection);
            if (ClientConnected is { } handler)
                await handler(connection);

            await _clientHub.PumpAsync(
                connection,
                json => CommandHandler.HandleAsync(json, _smtc, _volumeController, _logger),
                stoppingToken);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Fallo manejando una conexión Bluetooth entrante.");
        }
    }
}
