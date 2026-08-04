using System.Diagnostics;

namespace Shade.Agent.Adb;

// BackgroundService = long-running task managed by the host (starts with the
// app, stops with the app). This one polls `adb reverse` every few seconds
// and re-establishes it if it drops from a cable disconnect or the adb
// server restarting.
public sealed class AdbReverseWatchdog : BackgroundService
{
    private const string ReversePortSpec = "tcp:8080 tcp:8080";

    private readonly string _adbPath;
    private readonly TimeSpan _checkInterval;
    private readonly ILogger<AdbReverseWatchdog> _logger;

    public AdbReverseWatchdog(IConfiguration configuration, ILogger<AdbReverseWatchdog> logger)
    {
        _logger = logger;
        _adbPath = configuration["Adb:Path"]
            ?? throw new InvalidOperationException("Falta 'Adb:Path' en la configuración.");
        _checkInterval = TimeSpan.FromSeconds(configuration.GetValue("Adb:CheckIntervalSeconds", 5));
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                if (!await IsReverseActiveAsync(stoppingToken))
                {
                    await EstablishReverseAsync(stoppingToken);
                }
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Fallo comprobando/estableciendo 'adb reverse'.");
            }

            await Task.Delay(_checkInterval, stoppingToken);
        }
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
            _logger.LogInformation("'adb reverse {Spec}' establecido.", ReversePortSpec);
        }
        else
        {
            _logger.LogWarning("No se pudo establecer 'adb reverse': {Error}", stderr.Trim());
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
