using System.Diagnostics;

namespace Shade.Agent.Adb;

// BackgroundService = long-running task managed by the host (starts with the
// app, stops with the app). This one polls `adb reverse` every few seconds
// and re-establishes it if it drops from a cable disconnect or the adb
// server restarting.
public enum AdbTunnelStatus { Checking, Active, NoDevice, Error }

public sealed class AdbReverseWatchdog : BackgroundService
{
    private const string ReversePortSpec = "tcp:8080 tcp:8080";

    private readonly string _adbPath;
    private readonly TimeSpan _checkInterval;
    private readonly ILogger<AdbReverseWatchdog> _logger;

    // Lets the console dashboard force an immediate re-check instead of
    // waiting out the rest of the poll interval.
    private readonly SemaphoreSlim _recheckSignal = new(0, 1);

    public AdbTunnelStatus Status { get; private set; } = AdbTunnelStatus.Checking;
    public string? LastError { get; private set; }

    public AdbReverseWatchdog(IConfiguration configuration, ILogger<AdbReverseWatchdog> logger)
    {
        _logger = logger;
        var configuredPath = configuration["Adb:Path"]
            ?? throw new InvalidOperationException("Falta 'Adb:Path' en la configuración.");
        // Relative paths resolve against the agent's own install dir, so a
        // bundled adb (tools/adb/adb.exe) works regardless of where it's installed.
        _adbPath = Path.IsPathRooted(configuredPath)
            ? configuredPath
            : Path.Combine(AppContext.BaseDirectory, configuredPath);
        _checkInterval = TimeSpan.FromSeconds(configuration.GetValue("Adb:CheckIntervalSeconds", 5));
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                if (await IsReverseActiveAsync(stoppingToken))
                {
                    Status = AdbTunnelStatus.Active;
                    LastError = null;
                }
                else
                {
                    await EstablishReverseAsync(stoppingToken);
                }
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                Status = AdbTunnelStatus.Error;
                LastError = ex.Message;
                _logger.LogWarning(ex, "Fallo comprobando/estableciendo 'adb reverse'.");
            }

            try
            {
                await _recheckSignal.WaitAsync(_checkInterval, stoppingToken);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }

    public void RequestRecheck()
    {
        if (_recheckSignal.CurrentCount == 0) _recheckSignal.Release();
    }

    private async Task<bool> IsReverseActiveAsync(CancellationToken ct)
    {
        var (exitCode, stdout, _) = await RunAdbAsync("reverse --list", ct);
        return exitCode == 0 && stdout.Contains(ReversePortSpec, StringComparison.Ordinal);
    }

    private async Task EstablishReverseAsync(CancellationToken ct)
    {
        var (exitCode, _, stderr) = await RunAdbAsync($"reverse {ReversePortSpec}", ct);
        if (exitCode == 0)
        {
            Status = AdbTunnelStatus.Active;
            LastError = null;
            _logger.LogInformation("'adb reverse {Spec}' establecido.", ReversePortSpec);
        }
        else
        {
            var error = stderr.Trim();
            var noDevice = error.Contains("no devices", StringComparison.OrdinalIgnoreCase)
                || error.Contains("device not found", StringComparison.OrdinalIgnoreCase);
            // A phone simply not being plugged in is the normal idle state,
            // not a warning — only log when it changes into that state.
            if (noDevice)
            {
                if (Status != AdbTunnelStatus.NoDevice)
                    _logger.LogInformation("Sin dispositivo USB para 'adb reverse'; esperando…");
                Status = AdbTunnelStatus.NoDevice;
                LastError = null;
            }
            else
            {
                Status = AdbTunnelStatus.Error;
                LastError = error;
                _logger.LogWarning("No se pudo establecer 'adb reverse': {Error}", error);
            }
        }
    }

    private async Task<(int ExitCode, string StdOut, string StdErr)> RunAdbAsync(string arguments, CancellationToken ct)
    {
        var startInfo = new ProcessStartInfo(_adbPath, arguments)
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        };

        using var process = Process.Start(startInfo)
            ?? throw new InvalidOperationException("No se pudo iniciar el proceso adb.");

        var stdoutTask = process.StandardOutput.ReadToEndAsync(ct);
        var stderrTask = process.StandardError.ReadToEndAsync(ct);
        await process.WaitForExitAsync(ct);

        return (process.ExitCode, await stdoutTask, await stderrTask);
    }
}
