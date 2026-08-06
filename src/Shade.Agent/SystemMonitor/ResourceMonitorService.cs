using System.Net.NetworkInformation;
using System.Runtime.InteropServices;

namespace Shade.Agent.SystemMonitor;

public readonly record struct ResourceSample(
    long RamUsedBytes,
    long RamTotalBytes,
    double NetDownBytesPerSec,
    double NetUpBytesPerSec,
    bool HasBattery,
    int? BatteryPercent,
    bool? BatteryCharging);

// BackgroundService because this owns a recurring 1s tick (PeriodicTimer)
// rather than reacting to a Windows-pushed event like SpectrumAnalyzer/
// SystemVolumeController do — same role as AdbReverseWatchdog.
public sealed class ResourceMonitorService : BackgroundService
{
    private static readonly TimeSpan SampleInterval = TimeSpan.FromSeconds(1);

    private long _lastBytesReceived;
    private long _lastBytesSent;
    private DateTime _lastSampleTimeUtc;

    public event Action<ResourceSample>? SampleAvailable;

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(SampleInterval);
        while (await timer.WaitForNextTickAsync(stoppingToken))
        {
            SampleAvailable?.Invoke(Sample());
        }
    }

    private ResourceSample Sample()
    {
        var (ramUsed, ramTotal) = SampleRam();
        var (down, up) = SampleNetworkRate();
        var (hasBattery, percent, charging) = SampleBattery();
        return new ResourceSample(ramUsed, ramTotal, down, up, hasBattery, percent, charging);
    }

    private static (long Used, long Total) SampleRam()
    {
        var status = new MEMORYSTATUSEX { dwLength = (uint)Marshal.SizeOf<MEMORYSTATUSEX>() };
        if (!GlobalMemoryStatusEx(ref status)) return (0, 0);

        var total = (long)status.ullTotalPhys;
        var used = total - (long)status.ullAvailPhys;
        return (used, total);
    }

    private (double Down, double Up) SampleNetworkRate()
    {
        long received = 0, sent = 0;
        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up) continue;
            if (nic.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;

            var stats = nic.GetIPv4Statistics();
            received += stats.BytesReceived;
            sent += stats.BytesSent;
        }

        var nowUtc = DateTime.UtcNow;
        double down = 0, up = 0;

        if (_lastSampleTimeUtc != default)
        {
            var elapsedSeconds = (nowUtc - _lastSampleTimeUtc).TotalSeconds;
            if (elapsedSeconds > 0)
            {
                // Max(0, ...) guards against a counter reset (e.g. an adapter
                // reconnecting) momentarily producing a negative delta.
                down = Math.Max(0, received - _lastBytesReceived) / elapsedSeconds;
                up = Math.Max(0, sent - _lastBytesSent) / elapsedSeconds;
            }
        }

        _lastBytesReceived = received;
        _lastBytesSent = sent;
        _lastSampleTimeUtc = nowUtc;
        return (down, up);
    }

    private static (bool HasBattery, int? Percent, bool? Charging) SampleBattery()
    {
        if (!GetSystemPowerStatus(out var status)) return (false, null, null);

        // 0x80 = no system battery (desktop); 0xFF on either field = "unknown",
        // which isn't a confirmed real battery worth showing either.
        if (status.BatteryFlag == 0x80 || status.BatteryFlag == 0xFF || status.BatteryLifePercent == 0xFF)
        {
            return (false, null, null);
        }

        var charging = (status.BatteryFlag & 0x08) != 0;
        return (true, status.BatteryLifePercent, charging);
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MEMORYSTATUSEX
    {
        public uint dwLength;
        public uint dwMemoryLoad;
        public ulong ullTotalPhys;
        public ulong ullAvailPhys;
        public ulong ullTotalPageFile;
        public ulong ullAvailPageFile;
        public ulong ullTotalVirtual;
        public ulong ullAvailVirtual;
        public ulong ullAvailExtendedVirtual;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SYSTEM_POWER_STATUS
    {
        public byte ACLineStatus;
        public byte BatteryFlag;
        public byte BatteryLifePercent;
        public byte SystemStatusFlag;
        public int BatteryLifeTime;
        public int BatteryFullLifeTime;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GlobalMemoryStatusEx(ref MEMORYSTATUSEX lpBuffer);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetSystemPowerStatus(out SYSTEM_POWER_STATUS lpSystemPowerStatus);
}
