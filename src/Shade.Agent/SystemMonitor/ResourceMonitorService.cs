using System.Diagnostics;
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
    bool? BatteryCharging,
    double CpuUsagePercent);

public readonly record struct ProcessSample(string Name, int Pid, long RamBytes, double CpuPercent);

// BackgroundService because this owns a recurring 1s tick (PeriodicTimer)
// rather than reacting to a Windows-pushed event like SpectrumAnalyzer/
// SystemVolumeController do — same role as AdbReverseWatchdog.
public sealed class ResourceMonitorService : BackgroundService
{
    private static readonly TimeSpan SampleInterval = TimeSpan.FromSeconds(1);

    private const int TopProcessCount = 8;

    private long _lastBytesReceived;
    private long _lastBytesSent;
    private DateTime _lastSampleTimeUtc;

    private ulong _lastIdleTime, _lastKernelTime, _lastUserTime;
    private bool _hasCpuBaseline;
    private Dictionary<int, TimeSpan> _lastProcessCpuTimes = new();

    public event Action<ResourceSample>? SampleAvailable;

    // Reference-swapped wholesale each tick (see SampleProcesses) — reads
    // from a different async context than the tick loop are safe without a
    // lock since a reference assignment is atomic.
    public IReadOnlyList<ProcessSample> TopProcessesByRam { get; private set; } = Array.Empty<ProcessSample>();
    public IReadOnlyList<ProcessSample> TopProcessesByCpu { get; private set; } = Array.Empty<ProcessSample>();

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
        var cpu = SampleCpu();
        SampleProcesses();
        return new ResourceSample(ramUsed, ramTotal, down, up, hasBattery, percent, charging, cpu);
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

    private double SampleCpu()
    {
        if (!GetSystemTimes(out var idle, out var kernel, out var user)) return 0;

        var idleTime = ToUInt64(idle);
        var kernelTime = ToUInt64(kernel);
        var userTime = ToUInt64(user);

        double percent = 0;
        if (_hasCpuBaseline)
        {
            var idleDelta = idleTime - _lastIdleTime;
            // Kernel time already includes idle time, so kernel+user minus
            // idle is the busy fraction — already normalized 0-100 regardless
            // of core count (unlike per-process TotalProcessorTime below).
            var totalDelta = (kernelTime - _lastKernelTime) + (userTime - _lastUserTime);
            if (totalDelta > 0)
                percent = Math.Max(0, 100.0 * (totalDelta - idleDelta) / totalDelta);
        }

        _lastIdleTime = idleTime;
        _lastKernelTime = kernelTime;
        _lastUserTime = userTime;
        _hasCpuBaseline = true;
        return percent;
    }

    private void SampleProcesses()
    {
        var samples = new List<ProcessSample>();
        var nextCpuTimes = new Dictionary<int, TimeSpan>();
        var processorCount = Environment.ProcessorCount;

        foreach (var process in Process.GetProcesses())
        {
            try
            {
                var pid = process.Id;
                var name = process.ProcessName;
                var ram = process.WorkingSet64;
                var cpuTime = process.TotalProcessorTime;

                nextCpuTimes[pid] = cpuTime;

                double cpuPercent = 0;
                if (_lastProcessCpuTimes.TryGetValue(pid, out var previous))
                {
                    // Unlike SampleCpu's system-wide figure, TotalProcessorTime
                    // sums time across every core the process ran on, so this
                    // does need dividing by core count to land on 0-100.
                    var deltaMs = (cpuTime - previous).TotalMilliseconds;
                    cpuPercent = Math.Max(0, deltaMs / SampleInterval.TotalMilliseconds / processorCount * 100);
                }

                samples.Add(new ProcessSample(name, pid, ram, cpuPercent));
            }
            catch
            {
                // Protected/elevated process (WorkingSet64/TotalProcessorTime
                // need OpenProcess, denied without admin — installer runs
                // unprivileged), or it exited mid-enumeration (Id/ProcessName
                // then throw InvalidOperationException). Either way: skip it,
                // not fatal to the whole tick.
            }
            finally
            {
                process.Dispose();
            }
        }

        // Wholesale replace rather than mutate in place, so PIDs that exited
        // since the last tick don't leak in this dictionary forever.
        _lastProcessCpuTimes = nextCpuTimes;

        TopProcessesByRam = samples.OrderByDescending(s => s.RamBytes).Take(TopProcessCount).ToList();
        TopProcessesByCpu = samples.OrderByDescending(s => s.CpuPercent).Take(TopProcessCount).ToList();
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

    [StructLayout(LayoutKind.Sequential)]
    private struct FILETIME
    {
        public uint dwLowDateTime;
        public uint dwHighDateTime;
    }

    private static ulong ToUInt64(FILETIME ft) => ((ulong)ft.dwHighDateTime << 32) | ft.dwLowDateTime;

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GlobalMemoryStatusEx(ref MEMORYSTATUSEX lpBuffer);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetSystemPowerStatus(out SYSTEM_POWER_STATUS lpSystemPowerStatus);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetSystemTimes(out FILETIME lpIdleTime, out FILETIME lpKernelTime, out FILETIME lpUserTime);
}
