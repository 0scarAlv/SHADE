using System.Reflection;
using System.Text;
using Shade.Agent.Adb;
using Shade.Agent.Audio;
using Shade.Agent.Bluetooth;
using Shade.Agent.Metadata;
using Shade.Agent.Players;
using Shade.Agent.Smtc;
using Shade.Agent.Streaming;
using Shade.Agent.SystemMonitor;
using Spectre.Console;
using Spectre.Console.Rendering;

namespace Shade.Agent.Dashboard;

// lazygit-style terminal UI for the agent: numbered panels (now playing,
// connections, PC resources, log), keyboard shortcuts that drive the same
// SMTC/volume actions the phone does, and a live spectrum line. Replaces
// the raw scrolling log the console window used to show; `--plain` (or a
// redirected console, e.g. running as a service) falls back to that.
public sealed class ConsoleDashboard : BackgroundService
{
    private const int RefreshMs = 120;
    // Frame time while the cyber effects animate (playing, decoding, glitching).
    private const int AnimatedRefreshMs = 40;
    private const int SeekStepMs = 10_000;
    private const double DecodeTime = 0.7;
    private static readonly Rgb BorderGrey = Rgb.Hex(0x4E4E4E);
    private static readonly Rgb TrackGrey = Rgb.Hex(0x3A3A3A);

    private static readonly string[] PanelTitles = { "Sonando", "Conexión", "PC", "Registro" };
    private const int PanelNow = 0, PanelConnection = 1, PanelPc = 2, PanelLog = 3;

    private readonly PlaybackRouter _smtc;
    private readonly SystemVolumeController _volume;
    private readonly SpectrumAnalyzer _spectrum;
    private readonly ResourceMonitorService _resources;
    private readonly ClientHub _clients;
    private readonly TrackMetadataProvider _metadata;
    private readonly AdbReverseWatchdog _adb;
    private readonly RfcommServer _rfcomm;
    private readonly LogBuffer _log;
    private readonly IHostApplicationLifetime _lifetime;
    private readonly string _version;
    private readonly DateTimeOffset _startedAt = DateTimeOffset.Now;

    private volatile float[] _bands = new float[SpectrumAnalyzer.BandCount];
    private readonly float[] _shownBands = new float[SpectrumAnalyzer.BandCount];
    private readonly Queue<float> _cpuHistory = new();

    private int _focus;
    private int _logScroll;
    private LogLevel _logFilter = LogLevel.Information;
    private bool _topByRam;
    private string? _flash;
    private DateTimeOffset _flashUntil;

    // Cover colors + cyberpunk mode, mirroring the phone app (1.4.x) and Nation.
    private readonly System.Diagnostics.Stopwatch _clock = System.Diagnostics.Stopwatch.StartNew();
    private readonly BeatDetector _beat = new();
    private DashboardSettings _settings = DashboardSettings.Load();
    private ArtPalette _palette = ArtPalette.Default;
    private ArtPalette _targetPalette = ArtPalette.Default;
    private readonly object _paletteGate = new();
    private string? _paletteHash;
    private string? _trackKey;
    private double _trackChangedAt = double.NegativeInfinity;
    private double _lastFrameAt;
    private double _glitchAt, _glitchUntil, _nextGlitch = 8;
    private int _glitchSeed;
    private int _seenHardHits;
    private double _beatLevel;
    private bool _animating;

    public ConsoleDashboard(
        PlaybackRouter smtc,
        SystemVolumeController volume,
        SpectrumAnalyzer spectrum,
        ResourceMonitorService resources,
        ClientHub clients,
        TrackMetadataProvider metadata,
        AdbReverseWatchdog adb,
        RfcommServer rfcomm,
        LogBuffer log,
        IHostApplicationLifetime lifetime)
    {
        _smtc = smtc;
        _volume = volume;
        _spectrum = spectrum;
        _resources = resources;
        _clients = clients;
        _metadata = metadata;
        _adb = adb;
        _rfcomm = rfcomm;
        _log = log;
        _lifetime = lifetime;
        _version = Assembly.GetExecutingAssembly().GetName().Version is { } v ? $"{v.Major}.{v.Minor}.{v.Build}" : "";

        _spectrum.SpectrumAvailable += bands =>
        {
            _bands = bands;
            _beat.Feed(bands, Now);
        };
        _resources.SampleAvailable += sample =>
        {
            lock (_cpuHistory)
            {
                _cpuHistory.Enqueue((float)(sample.CpuUsagePercent / 100));
                while (_cpuHistory.Count > 120) _cpuHistory.Dequeue();
            }
        };
    }

    public static bool ShouldRun(string[] args) =>
        !args.Contains("--plain") && !Console.IsOutputRedirected && !Console.IsInputRedirected;

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        // Let the rest of the host (Kestrel, SMTC) finish starting first so
        // a startup failure surfaces as a crash, not a frozen dashboard.
        await Task.Yield();

        Console.OutputEncoding = Encoding.UTF8;
        Console.Title = "Shade Server";
        EnableVirtualTerminal();
        var keyLoop = Task.Run(() => KeyLoopAsync(stoppingToken), stoppingToken);

        // Hand-rolled render loop instead of Spectre's Live display: Live
        // moves the cursor up by the previous frame's height, which breaks
        // as soon as the window is resized (the terminal reflows the old
        // frame and every later frame lands offset). Here each frame is
        // drawn absolutely from the top-left of the alternate screen buffer
        // (no scrollback to reflow), and any size change wipes the screen.
        var output = Console.Out;
        output.Write(AltScreenOn + HideCursor + ClearScreen);
        output.Flush();
        var lastWidth = -1;
        var lastHeight = -1;
        var shown = Array.Empty<string>();
        var frame = new StringBuilder();
        try
        {
            while (!stoppingToken.IsCancellationRequested)
            {
                var (width, height) = WindowSize();
                if (width != lastWidth || height != lastHeight)
                {
                    lastWidth = width;
                    lastHeight = height;
                    shown = new string[height];
                    output.Write(ClearScreen);
                }

                // One column short so a full-width row never triggers the
                // terminal's auto-wrap. Only rows that differ from what is
                // already on screen are rewritten, each positioned absolutely:
                // the console write is the expensive part of a frame, and
                // with the cyber effects most frames only touch a few rows.
                var lines = RenderLines(Math.Max(1, width - 1), height);
                frame.Clear();
                for (var row = 0; row < height; row++)
                {
                    var line = row < lines.Length ? lines[row] : "";
                    if (line == shown[row]) continue;
                    shown[row] = line;
                    frame.Append("\u001b[").Append(row + 1).Append(";1H").Append(line).Append(ClearToLineEnd);
                }
                if (frame.Length > 0)
                {
                    output.Write(frame.ToString());
                    output.Flush();
                }

                try { await Task.Delay(_animating ? AnimatedRefreshMs : RefreshMs, stoppingToken); }
                catch (OperationCanceledException) { break; }
            }
        }
        finally
        {
            output.Write(ShowCursor + AltScreenOff);
            output.Flush();
            // Whatever made the agent stop is usually in the last few lines —
            // leave them on screen instead of a blank window.
            foreach (var entry in _log.Snapshot().TakeLast(12))
                Console.WriteLine($"{entry.Time:HH:mm:ss} {LevelTag(entry.Level)} {entry.Category}: {entry.Message}");
            Console.WriteLine("Shade Server detenido.");
        }

        await keyLoop.ContinueWith(_ => { }, TaskScheduler.Default);
    }

    private const string AltScreenOn = "\u001b[?1049h";
    private const string AltScreenOff = "\u001b[?1049l";
    private const string HideCursor = "\u001b[?25l";
    private const string ShowCursor = "\u001b[?25h";
    private const string ClearScreen = "\u001b[2J";
    private const string ClearToLineEnd = "\u001b[K";

    private static (int Width, int Height) WindowSize()
    {
        try
        {
            return (Console.WindowWidth, Console.WindowHeight);
        }
        catch (IOException)
        {
            // Can throw mid-resize on some hosts; next tick will catch up.
            return (100, 30);
        }
    }

    // Renders one frame to ANSI rows, at most `height` of them, each
    // self-contained (Spectre resets styles at the end of every segment).
    private string[] RenderLines(int width, int height)
    {
        var writer = new StringWriter();
        var console = AnsiConsole.Create(new AnsiConsoleSettings
        {
            Ansi = AnsiSupport.Yes,
            ColorSystem = ColorSystemSupport.TrueColor,
            Interactive = InteractionSupport.No,
            Out = new AnsiConsoleOutput(writer),
        });
        console.Profile.Width = width;
        console.Profile.Height = height;
        console.Write(Render(width, height));

        return writer.ToString().Replace("\r\n", "\n").TrimEnd('\n').Split('\n').Take(height).ToArray();
    }

    // conhost only interprets ANSI escapes with this mode flag set; Windows
    // Terminal already has it on, so this is a harmless no-op there.
    private static void EnableVirtualTerminal()
    {
        const int stdOutputHandle = -11;
        const uint enableVirtualTerminalProcessing = 0x0004;
        var handle = GetStdHandle(stdOutputHandle);
        if (GetConsoleMode(handle, out var mode))
            SetConsoleMode(handle, mode | enableVirtualTerminalProcessing);
    }

    [System.Runtime.InteropServices.DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr GetStdHandle(int nStdHandle);

    [System.Runtime.InteropServices.DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool GetConsoleMode(IntPtr hConsoleHandle, out uint lpMode);

    [System.Runtime.InteropServices.DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool SetConsoleMode(IntPtr hConsoleHandle, uint dwMode);

    // Renders a single frame to a plain-text console of the given size —
    // used by `--dashboard-snapshot` to check the layout without a terminal.
    public string RenderSnapshot(int width, int height)
    {
        var writer = new StringWriter();
        var console = AnsiConsole.Create(new AnsiConsoleSettings
        {
            Ansi = AnsiSupport.No,
            ColorSystem = ColorSystemSupport.NoColors,
            Out = new AnsiConsoleOutput(writer),
        });
        console.Profile.Width = width;
        console.Profile.Height = height;
        console.Write(Render(width, height));
        return writer.ToString();
    }

    public string RenderColorSnapshot(int width, int height) => string.Join("\n", RenderLines(width, height));

    // ---------------------------------------------------------------- input

    private async Task KeyLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                if (!Console.KeyAvailable)
                {
                    await Task.Delay(30, ct);
                    continue;
                }
                await HandleKeyAsync(Console.ReadKey(intercept: true));
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                Flash($"Error: {ex.Message}");
            }
        }
    }

    private async Task HandleKeyAsync(ConsoleKeyInfo key)
    {
        switch (key.Key)
        {
            case ConsoleKey.Tab:
                _focus = (_focus + ((key.Modifiers & ConsoleModifiers.Shift) != 0 ? 3 : 1)) % 4;
                return;
            case ConsoleKey.D1 or ConsoleKey.NumPad1: _focus = PanelNow; return;
            case ConsoleKey.D2 or ConsoleKey.NumPad2: _focus = PanelConnection; return;
            case ConsoleKey.D3 or ConsoleKey.NumPad3: _focus = PanelPc; return;
            case ConsoleKey.D4 or ConsoleKey.NumPad4: _focus = PanelLog; return;
            case ConsoleKey.Q or ConsoleKey.Escape:
                _lifetime.StopApplication();
                return;
            case ConsoleKey.Spacebar:
                await _smtc.TryPlayPauseAsync();
                return;
            case ConsoleKey.N:
                await _smtc.TryNextAsync();
                Flash("Siguiente ⏭");
                return;
            case ConsoleKey.P or ConsoleKey.B:
                await _smtc.TryPreviousAsync();
                Flash("Anterior ⏮");
                return;
            case ConsoleKey.LeftArrow or ConsoleKey.RightArrow:
                await SeekRelativeAsync(key.Key == ConsoleKey.RightArrow ? SeekStepMs : -SeekStepMs);
                return;
            case ConsoleKey.OemPlus or ConsoleKey.Add:
                _volume.VolumeUp();
                return;
            case ConsoleKey.OemMinus or ConsoleKey.Subtract:
                _volume.VolumeDown();
                return;
            case ConsoleKey.M:
                _volume.ToggleMute();
                Flash(_volume.GetMuted() ? "Silenciado" : "Sonido activado");
                return;
            case ConsoleKey.S:
                await _smtc.TryToggleShuffleAsync();
                return;
            case ConsoleKey.R:
                await _smtc.TryCycleRepeatAsync();
                return;
            case ConsoleKey.F:
                _settings = _settings with { Cyber = !_settings.Cyber };
                _settings.Save();
                if (_settings.Cyber) TriggerGlitch(Now, 0.22);
                Flash(_settings.Cyber ? "Modo ciberpunk activado" : "Modo ciberpunk desactivado");
                return;
            case ConsoleKey.V:
                _settings = _settings with { Spectrum = (SpectrumStyle)(((int)_settings.Spectrum + 1) % 3) };
                _settings.Save();
                Flash($"Visualizador: {SpectrumName(_settings.Spectrum)}");
                return;
        }

        switch (_focus)
        {
            case PanelConnection when key.Key == ConsoleKey.A:
                _adb.RequestRecheck();
                Flash("Revisando adb reverse…");
                break;
            case PanelPc when key.Key == ConsoleKey.O:
                _topByRam = !_topByRam;
                break;
            case PanelLog:
                HandleLogKey(key.Key);
                break;
        }
    }

    private void HandleLogKey(ConsoleKey key)
    {
        switch (key)
        {
            case ConsoleKey.UpArrow: _logScroll++; break;
            case ConsoleKey.DownArrow: _logScroll = Math.Max(0, _logScroll - 1); break;
            case ConsoleKey.PageUp: _logScroll += 10; break;
            case ConsoleKey.PageDown: _logScroll = Math.Max(0, _logScroll - 10); break;
            case ConsoleKey.End: _logScroll = 0; break;
            case ConsoleKey.Home: _logScroll = int.MaxValue / 2; break;
            case ConsoleKey.C:
                _log.Clear();
                _logScroll = 0;
                break;
            case ConsoleKey.L:
                _logFilter = _logFilter switch
                {
                    LogLevel.Information => LogLevel.Warning,
                    LogLevel.Warning => LogLevel.Error,
                    _ => LogLevel.Information,
                };
                _logScroll = 0;
                break;
        }
    }

    private async Task SeekRelativeAsync(long deltaMs)
    {
        if (_smtc.CurrentState is not { } state || CurrentTrack() is not { } track || track.DurationMs <= 0) return;
        var target = Math.Clamp(CurrentPositionMs(state) + deltaMs, 0, track.DurationMs);
        await _smtc.TrySeekAsync(target);
        Flash($"{(deltaMs > 0 ? "+" : "−")}{Math.Abs(deltaMs) / 1000}s → {FormatMs(target)}");
    }

    private void Flash(string message)
    {
        _flash = message;
        _flashUntil = DateTimeOffset.Now.AddSeconds(2);
    }

    // ---------------------------------------------------------------- render

    private double Now => _clock.Elapsed.TotalSeconds;

    private string AccentHex => _palette.Accent.Markup;

    private bool Cyber => _settings.Cyber;

    private bool Playing => _smtc.CurrentState?.Playing == true;

    // Advances palette fades, beat, glitch timers and decides the next frame
    // time: 25 fps only while something is actually moving, else the old 8 fps.
    private void UpdateFx()
    {
        var now = Now;
        var dt = Math.Clamp(now - _lastFrameAt, 0, 0.5);
        _lastFrameAt = now;
        var animating = false;

        var track = CurrentTrack();
        var key = track is null ? null : $"{track.Title}\u0001{track.Artist}";
        if (key != _trackKey)
        {
            _trackKey = key;
            _trackChangedAt = now;
            _beat.Reset();
            if (key is not null) TriggerGlitch(now, 0.22);
        }

        // Palette follows the cover the phone is shown; decoding runs off the render loop.
        var hash = track?.ArtBytes is null ? null : track.ArtHash;
        if (hash != _paletteHash)
        {
            _paletteHash = hash;
            if (hash is null || track?.ArtBytes is null)
            {
                SetTargetPalette(ArtPalette.Default);
            }
            else
            {
                var bytes = track.ArtBytes;
                _ = Task.Run(async () =>
                {
                    var palette = await ArtPalette.FromCoverAsync(hash, bytes);
                    if (_paletteHash == hash) SetTargetPalette(palette);
                });
            }
        }
        ArtPalette target;
        lock (_paletteGate) target = _targetPalette;
        if (_palette != target)
        {
            var next = ArtPalette.Lerp(_palette, target, 1 - Math.Exp(-dt / 0.25));
            // Byte steps round to zero near the target; snap once nothing visibly moves.
            _palette = next == _palette ? target : next;
            animating = true;
        }

        _beatLevel = Cyber && Playing ? _beat.Level(now) : 0;
        if (Cyber)
        {
            var hits = _beat.HardHits;
            if (hits != _seenHardHits)
            {
                _seenHardHits = hits;
                if (Playing) TriggerGlitch(now, 0.1);
            }
            // Ambient bursts only while playing; paused stays still.
            if (Playing && now >= _nextGlitch)
            {
                TriggerGlitch(now, 0.08 + Fx.Noise(_glitchSeed, 7) * 0.14);
                _nextGlitch = now + 10 + Fx.Noise(_glitchSeed, 11) * 10;
            }
            animating |= Playing || now < _glitchUntil || now - _trackChangedAt < DecodeTime;
        }
        _animating = animating;
    }

    private void SetTargetPalette(ArtPalette palette)
    {
        lock (_paletteGate) _targetPalette = palette;
    }

    private void TriggerGlitch(double now, double length)
    {
        _glitchAt = now;
        _glitchUntil = now + length;
        _glitchSeed++;
    }

    private bool Glitching => Cyber && Now < _glitchUntil;

    // Glitch sub-frame: changes ~25 times a second so a burst stutters instead of sliding.
    private int GlitchTick => (int)((Now - _glitchAt) * 25) + _glitchSeed * 1000;

    private IRenderable Render(int width, int height)
    {
        UpdateFx();
        if (width < 70 || height < 20)
        {
            return new Panel(new Markup("[grey]Agranda la ventana para ver el panel de Shade.[/]"))
                .Border(BoxBorder.Rounded).BorderColor(Color.Grey35);
        }

        var leftWidth = width * 5 / 12;
        var rightWidth = width - leftWidth;
        var bodyHeight = height - 2;
        // Short windows drop the spacer rows so all three left panels fit.
        var compact = bodyHeight < 30;
        var nowHeight = compact ? 8 : 11;
        var connectionHeight = (compact ? 6 : 7) + Math.Min(compact ? 1 : 3, _clients.Snapshot().Count)
            + (_adb.Status == AdbTunnelStatus.Error ? 1 : 0);
        var pcHeight = Math.Max(4, bodyHeight - nowHeight - connectionHeight);

        var layout = new Layout("root").SplitRows(
            new Layout("header").Size(1),
            new Layout("body").Size(bodyHeight).SplitColumns(
                new Layout("left").Size(leftWidth).SplitRows(
                    new Layout("now").Size(nowHeight),
                    new Layout("connection").Size(connectionHeight),
                    new Layout("pc").Size(pcHeight)),
                new Layout("log").Size(rightWidth)),
            new Layout("footer").Size(1));

        var leftInner = leftWidth - 4;
        layout["header"].Update(Header(width));
        layout["now"].Update(Frame(PanelNow, NowPlaying(leftInner, compact)));
        layout["connection"].Update(Frame(PanelConnection, Connection(leftInner, compact)));
        layout["pc"].Update(Frame(PanelPc, Pc(leftInner, pcHeight - 2)));
        layout["log"].Update(Frame(PanelLog, Log(rightWidth - 4, bodyHeight - 2)));
        layout["footer"].Update(Footer(width));
        return layout;
    }

    private Panel Frame(int index, IRenderable content)
    {
        var focused = _focus == index;
        var title = focused
            ? $"[{AccentHex} bold] {index + 1} {PanelTitles[index]} [/]"
            : $"[grey] {index + 1} {PanelTitles[index]} [/]";
        if (index == PanelLog && _logFilter != LogLevel.Information)
            title += _logFilter == LogLevel.Warning ? "[yellow] avisos+ [/]" : "[red] errores [/]";
        if (index == PanelLog && _logScroll > 0)
            title += $"[grey] ↑{_logScroll} [/]";

        // Cyber mode tints each panel with one cover color and pulses it on the beat.
        var hue = index switch
        {
            PanelNow => _palette.Accent,
            PanelConnection => _palette.Second,
            PanelPc => _palette.Third,
            _ => _palette.Fourth,
        };
        var border = focused ? _palette.Accent : Cyber ? Rgb.Lerp(BorderGrey, hue, 0.4) : BorderGrey;
        if (Cyber && _beatLevel > 0)
            border = Rgb.Lerp(border, focused ? Fx.White : hue, Math.Round(_beatLevel * 4) / 4 * (focused ? 0.5 : 0.8));

        return new Panel(content)
            .Header(title)
            .Border(BoxBorder.Rounded)
            .BorderColor(new Color(border.R, border.G, border.B))
            .Padding(1, 0, 1, 0)
            .Expand();
    }

    private IRenderable Header(int width)
    {
        var uptime = DateTimeOffset.Now - _startedAt;
        var clientCount = _clients.Snapshot().Count;
        // The logo flashes toward white on each beat in cyber mode.
        var logo = Rgb.Lerp(_palette.Accent, Fx.White, _beatLevel * 0.8).Markup;
        var left = $"[{logo} bold]{(_beatLevel > 0.5 ? "◈" : "◆")} SHADE[/] [bold]SERVER[/] [grey]{_version}[/]";
        var right = $"[grey]ws://127.0.0.1:8080 · activo {(int)uptime.TotalHours}:{uptime.Minutes:00}:{uptime.Seconds:00} · " +
                    $"{clientCount} {(clientCount == 1 ? "cliente" : "clientes")}[/]";
        var grid = new Grid().AddColumn(new GridColumn().NoWrap()).AddColumn(new GridColumn().RightAligned().NoWrap());
        grid.AddRow(new Markup(left), new Markup(right));
        grid.Width(width);
        return grid;
    }

    private IRenderable Footer(int width)
    {
        if (_flash is not null && DateTimeOffset.Now < _flashUntil)
            return new Markup($"[{AccentHex}]● {Esc(_flash)}[/]");

        var context = _focus switch
        {
            PanelConnection => "[white]a[/] revisar adb",
            PanelPc => $"[white]o[/] top por {(_topByRam ? "CPU" : "RAM")}",
            PanelLog => "[white]↑↓ RePág AvPág[/] desplazar  [white]l[/] filtrar  [white]c[/] limpiar",
            _ => "",
        };
        var hints = "[white]espacio[/] play/pausa  [white]n/p[/] sig/ant  [white]←/→[/] ±10s  [white]+/-[/] vol  " +
                    "[white]m[/] mute  [white]s[/] aleatorio  [white]r[/] repetir  [white]v[/] visualizador  " +
                    $"[white]f[/] ciberpunk{(_settings.Cyber ? "" : " (off)")}  [white]tab/1-4[/] panel  " +
                    (context.Length > 0 ? context + "  " : "") + "[white]q[/] salir";
        return new Markup($"[grey]{hints}[/]").Overflow(Overflow.Ellipsis);
    }

    // SMTC's track plus whatever the metadata lookup filled in (album, art,
    // duration), same as what the phone is shown.
    private TrackInfo? CurrentTrack() =>
        _smtc.CurrentTrack is { } track && _metadata.TryGetCached(track, out var extra)
            ? TrackMetadataProvider.Apply(track, extra)
            : _smtc.CurrentTrack;

    private IRenderable NowPlaying(int inner, bool compact)
    {
        var track = CurrentTrack();
        var state = _smtc.CurrentState;
        var rows = new List<IRenderable>();

        if (track is null || string.IsNullOrWhiteSpace(track.Title))
        {
            rows.Add(new Markup("[bold]Nada sonando[/]"));
            rows.Add(new Markup($"[grey]{Esc(Truncate("Reproduce algo en la PC (Spotify, navegador…)", inner))}[/]"));
            rows.Add(Text.Empty);
        }
        else
        {
            var subtitle = string.Join(" · ", new[] { track.Artist, track.Album }.Where(s => !string.IsNullOrWhiteSpace(s)));
            rows.Add(new Markup(FxText(Truncate(track.Title, inner), inner, Fx.White, bold: true, row: 0)));
            rows.Add(new Markup(FxText(Truncate(subtitle, inner), inner, Rgb.Hex(0x9E9E9E), bold: false, row: 1)));
            var playing = state?.Playing == true;
            var status = playing ? $"[{AccentHex}]▶ Reproduciendo[/]" : "[yellow]‖ En pausa[/]";
            if (track.AppName is { } app) status += $"[grey] en {Esc(app)}[/]";
            rows.Add(new Markup(status));
        }

        if (!compact) rows.Add(Text.Empty);
        rows.Add(ProgressLine(track, state, inner));
        rows.AddRange(Spectrum(inner, compact ? 1 : 3, state?.Playing == true));
        rows.Add(VolumeLine(state, inner));
        return new Rows(rows);
    }

    private IRenderable ProgressLine(TrackInfo? track, PlaybackState? state, int inner)
    {
        var duration = track?.DurationMs ?? 0;
        var position = state is null ? 0 : Math.Min(CurrentPositionMs(state), duration);
        var times = $" {FormatMs(position)} / {FormatMs(duration)}";
        var barWidth = Math.Max(10, inner - times.Length);
        var filled = duration > 0 ? (int)Math.Round(barWidth * (double)position / duration) : 0;
        filled = Math.Clamp(filled, 0, barWidth);
        if (!Cyber || filled == 0)
            return new Markup($"[{AccentHex}]{new string('━', filled)}[/][grey23]{new string('─', barWidth - filled)}[/][grey]{times}[/]");

        // Cyber: the played part runs through the cover colors and the head flashes on the beat.
        var sb = new StringBuilder();
        for (var i = 0; i < filled; i++)
        {
            var color = i == filled - 1
                ? Rgb.Lerp(Wheel(i / (double)barWidth), Fx.White, 0.35 + _beatLevel * 0.65)
                : Wheel(i / (double)barWidth);
            Append(sb, i == filled - 1 ? (_beatLevel > 0.5 ? '◆' : '◈') : '━', color, false);
        }
        return new Markup($"{Flush(sb)}[grey23]{new string('─', barWidth - filled)}[/][grey]{times}[/]");
    }

    private static string SpectrumName(SpectrumStyle style) => style switch
    {
        SpectrumStyle.Mirror => "espejo",
        SpectrumStyle.Wave => "onda",
        _ => "barras",
    };

    // The live FFT bands as bars, a mirrored column or a braille wave, with
    // the same fast-attack / slow-release smoothing as the phone's visualizer.
    // Colored across the cover palette like the phone's hue-wheel spectrum.
    private IEnumerable<IRenderable> Spectrum(int inner, int height, bool playing)
    {
        var source = playing ? _bands : new float[SpectrumAnalyzer.BandCount];
        for (var i = 0; i < _shownBands.Length; i++)
        {
            var target = i < source.Length ? source[i] : 0f;
            _shownBands[i] += (target - _shownBands[i]) * (target > _shownBands[i] ? 0.8f : 0.35f);
        }

        var style = height == 1 && _settings.Spectrum == SpectrumStyle.Mirror ? SpectrumStyle.Bars : _settings.Spectrum;
        var lines = style switch
        {
            SpectrumStyle.Mirror => MirrorRows(inner, height, playing),
            SpectrumStyle.Wave => WaveRows(inner, height, playing),
            _ => BarRows(inner, height, playing),
        };
        return lines.Select(line => (IRenderable)new Markup(line));
    }

    // Band level under a column, linearly interpolated so any width looks smooth.
    private float LevelAt(double t)
    {
        var x = Math.Clamp(t, 0, 1) * (_shownBands.Length - 1);
        var a = (int)x;
        var b = Math.Min(a + 1, _shownBands.Length - 1);
        return (float)(_shownBands[a] + (_shownBands[b] - _shownBands[a]) * (x - a));
    }

    private Rgb SpectrumColor(int col, int width, bool playing, double brightness = 1)
    {
        if (!playing) return TrackGrey;
        var color = Cyber ? Wheel(col / (double)Math.Max(1, width - 1)) : _palette.Accent;
        if (brightness < 1) color = Rgb.Lerp(_palette.Backdrop, color, brightness);
        return Cyber && _beatLevel > 0 ? Rgb.Lerp(color, Fx.White, _beatLevel * 0.25) : color;
    }

    private List<string> BarRows(int width, int height, bool playing)
    {
        const string glyphs = " ▁▂▃▄▅▆▇█";
        var rows = new List<string>();
        for (var r = height - 1; r >= 0; r--)
        {
            var sb = new StringBuilder();
            for (var col = 0; col < width; col++)
            {
                var fill = Math.Clamp(LevelAt(col / (double)Math.Max(1, width - 1)) * height - r, 0, 1);
                var glyph = glyphs[(int)Math.Round(fill * (glyphs.Length - 1))];
                // Silence still shows a floor line on the bottom row.
                if (r == 0 && glyph == ' ') glyph = '▁';
                Append(sb, glyph, SpectrumColor(col, width, playing), false);
            }
            rows.Add(Flush(sb));
        }
        return rows;
    }

    // Grows up and down from the middle row; brighter toward the center.
    private List<string> MirrorRows(int width, int height, bool playing)
    {
        var center = (height - 1) / 2.0;
        var rows = new List<string>();
        for (var r = 0; r < height; r++)
        {
            var distance = Math.Abs(r - center);
            var sb = new StringBuilder();
            for (var col = 0; col < width; col++)
            {
                var reach = LevelAt(col / (double)Math.Max(1, width - 1)) * (center + 0.5);
                var fill = Math.Clamp(reach - distance, 0, 1);
                var glyph = fill > 0.66 ? '█' : fill > 0.33 ? (r < center ? '▄' : r > center ? '▀' : '■') : distance == 0 ? '─' : ' ';
                var brightness = 1 - distance / (center + 1) * 0.6;
                Append(sb, glyph, SpectrumColor(col, width, playing, brightness), false);
            }
            rows.Add(Flush(sb));
        }
        return rows;
    }

    // Braille line: each cell is 2x4 dots, so the wave has 4x the vertical resolution.
    private List<string> WaveRows(int width, int height, bool playing)
    {
        int[,] dotBits = { { 0x01, 0x08 }, { 0x02, 0x10 }, { 0x04, 0x20 }, { 0x40, 0x80 } };
        var dotsHigh = height * 4;
        var dotsWide = width * 2;
        var cells = new int[height, width];
        var prevY = -1;
        for (var x = 0; x < dotsWide; x++)
        {
            var level = LevelAt(x / (double)Math.Max(1, dotsWide - 1));
            var y = dotsHigh - 1 - (int)Math.Round(level * (dotsHigh - 1));
            // Join steep steps so the line never breaks into loose dots.
            var from = prevY < 0 ? y : Math.Min(prevY, y);
            var to = prevY < 0 ? y : Math.Max(prevY, y);
            for (var yy = from; yy <= to; yy++)
                cells[yy / 4, x / 2] |= dotBits[yy % 4, x % 2];
            prevY = y;
        }

        var rows = new List<string>();
        for (var r = 0; r < height; r++)
        {
            var sb = new StringBuilder();
            for (var col = 0; col < width; col++)
                Append(sb, cells[r, col] == 0 ? ' ' : (char)(0x2800 + cells[r, col]), SpectrumColor(col, width, playing), false);
            rows.Add(Flush(sb));
        }
        return rows;
    }

    // Accent → Second → Third → Fourth across t in [0, 1].
    private Rgb Wheel(double t)
    {
        Rgb[] stops = { _palette.Accent, _palette.Second, _palette.Third, _palette.Fourth };
        var x = Math.Clamp(t, 0, 1) * (stops.Length - 1);
        var a = Math.Min((int)x, stops.Length - 2);
        return Rgb.Lerp(stops[a], stops[a + 1], x - a);
    }

    // Text that lights up left to right after a track change (dim ghost, then
    // a white flash as each character locks in), and tears sideways during a
    // glitch burst. Plain colored text otherwise.
    private string FxText(string text, int width, Rgb color, bool bold, int row)
    {
        var age = Now - _trackChangedAt;
        if (!Cyber || (age >= DecodeTime && !Glitching))
            return $"[{(bold ? "bold " : "")}{color.Markup}]{Esc(text)}[/]";

        var chars = text.ToCharArray();
        var colors = new Rgb?[chars.Length];
        var ghost = Rgb.Lerp(_palette.Backdrop, _palette.Second, 0.55);
        var n = Math.Max(1, chars.Length - 1);
        var rowDelay = row * 0.05;
        for (var i = 0; i < chars.Length; i++)
        {
            if (age >= DecodeTime) { colors[i] = color; continue; }
            var appearAt = rowDelay + i / (double)n * 0.2;
            var lockAt = Math.Min(DecodeTime - 0.05, rowDelay + 0.12 + i / (double)n * 0.35 + Fx.Noise(i, row + _glitchSeed * 7) * 0.15);
            colors[i] = age >= lockAt ? (age - lockAt < 0.07 ? Fx.White : color) : age >= appearAt ? ghost : null;
        }

        var shift = 0;
        if (Glitching)
        {
            var tick = GlitchTick;
            if (Fx.Noise(tick, row) < 0.6) shift = (int)(Fx.Noise(tick, row + 13) * 5) - 2;
            for (var i = 0; i < chars.Length; i++)
            {
                var r = Fx.Noise(tick, i + row * 131);
                if (colors[i] is null) continue;
                if (r < 0.06) colors[i] = _palette.Second;
                else if (r < 0.10) colors[i] = _palette.Fourth;
            }
        }

        var sb = new StringBuilder();
        var cells = Math.Min(width, chars.Length + Math.Max(0, shift));
        for (var col = 0; col < cells; col++)
        {
            var i = col - shift;
            if (i < 0 || i >= chars.Length || colors[i] is not { } c) Append(sb, ' ', color, false);
            else Append(sb, chars[i], c, bold);
        }
        return Flush(sb);
    }

    // Run-length markup builder: consecutive cells with the same style share one tag.
    private string? _runStyle;
    private readonly StringBuilder _run = new();

    private void Append(StringBuilder sb, char ch, Rgb color, bool bold)
    {
        var style = bold ? "bold " + color.Markup : color.Markup;
        if (style != _runStyle) FlushRun(sb);
        _runStyle = style;
        _run.Append(ch);
    }

    private void FlushRun(StringBuilder sb)
    {
        if (_run.Length > 0 && _runStyle is not null)
            sb.Append('[').Append(_runStyle).Append(']').Append(Esc(_run.ToString())).Append("[/]");
        _run.Clear();
        _runStyle = null;
    }

    private string Flush(StringBuilder sb)
    {
        FlushRun(sb);
        return sb.ToString();
    }

    private IRenderable VolumeLine(PlaybackState? state, int inner)
    {
        var volume = _volume.GetVolume();
        var muted = _volume.GetMuted();
        var extras = new StringBuilder();
        if (state?.Shuffle is { } shuffle)
            extras.Append(shuffle ? $"  [{AccentHex}]ALEATORIO[/]" : "  [grey30]ALEATORIO[/]");
        if (state?.Repeat is { } repeat)
        {
            var label = repeat switch { "track" => "REPETIR 1", "list" => "REPETIR", _ => "REPETIR" };
            extras.Append(repeat == "none" ? $"  [grey30]{label}[/]" : $"  [{AccentHex}]{label}[/]");
        }

        var percent = $" {(int)Math.Round(volume * 100),3}%";
        var barWidth = Math.Clamp(inner - 6 - percent.Length - 22, 8, 24);
        var filled = (int)Math.Round(barWidth * volume);
        var barColor = muted ? "grey35" : AccentHex;
        var label2 = muted ? "[yellow]MUTE[/]" : "[grey]VOL [/]";
        return new Markup($"{label2}[{barColor}]{new string('█', filled)}[/][grey23]{new string('░', barWidth - filled)}[/][grey]{percent}[/]{extras}");
    }

    private IRenderable Connection(int inner, bool compact)
    {
        var rows = new List<IRenderable>();
        var (adbDot, adbText) = _adb.Status switch
        {
            AdbTunnelStatus.Active => ($"[{AccentHex}]●[/]", "túnel activo"),
            AdbTunnelStatus.NoDevice => ("[grey]○[/]", "sin teléfono"),
            AdbTunnelStatus.Error => ("[red]×[/]", "error"),
            _ => ("[yellow]◌[/]", "comprobando…"),
        };
        rows.Add(new Markup($"{adbDot} [bold]USB[/] [grey]{Esc(Truncate($"adb reverse · {adbText}", inner - 6))}[/]"));
        if (_adb.Status == AdbTunnelStatus.Error && _adb.LastError is { } error)
            rows.Add(new Markup($"  [red]{Esc(Truncate(error, inner - 2))}[/]"));

        var (btDot, btText) = _rfcomm.Status switch
        {
            BluetoothStatus.Advertising => ($"[{AccentHex}]●[/]", "anunciando"),
            BluetoothStatus.Unavailable => ("[grey]×[/]", "sin adaptador"),
            _ => ("[yellow]◌[/]", "iniciando…"),
        };
        rows.Add(new Markup($"{btDot} [bold]Bluetooth[/] [grey]{Esc(Truncate($"RFCOMM · {btText}", inner - 12))}[/]"));
        if (!compact) rows.Add(Text.Empty);

        var clients = _clients.Snapshot();
        if (clients.Count == 0)
        {
            rows.Add(new Markup($"[grey]{Esc(Truncate("Ningún teléfono conectado todavía", inner))}[/]"));
        }
        else
        {
            rows.Add(new Markup($"[bold]{clients.Count}[/] [grey]{(clients.Count == 1 ? "cliente conectado" : "clientes conectados")}[/]"));
            foreach (var client in clients.Take(compact ? 1 : 3))
            {
                var tag = client.Transport == ClientTransport.Bluetooth ? "[blue]BT[/]" : "[cyan]WS[/]";
                rows.Add(new Markup($"  {tag} [grey]{Ago(client.ConnectedAt)}[/]"));
            }
        }
        return new Rows(rows);
    }

    private IRenderable Pc(int inner, int innerHeight)
    {
        if (_resources.LastSample is not { } sample)
            return new Markup("[grey]Midiendo…[/]");

        var rows = new List<IRenderable>();
        // Leaves room for "100%" plus the RAM line's "12.0/15.8 GB" detail.
        var barWidth = Math.Clamp(inner - 24, 6, 40);
        var cpu = sample.CpuUsagePercent / 100;
        var ram = sample.RamTotalBytes > 0 ? (double)sample.RamUsedBytes / sample.RamTotalBytes : 0;

        rows.Add(new Markup($"[grey]CPU [/]{Bar(cpu, barWidth)} [bold]{sample.CpuUsagePercent,3:0}%[/]"));
        rows.Add(new Markup($"[grey]RAM [/]{Bar(ram, barWidth)} [bold]{ram * 100,3:0}%[/] [grey]{sample.RamUsedBytes / 1073741824d:0.0}/{sample.RamTotalBytes / 1073741824d:0.0} GB[/]"));
        if (innerHeight >= 7) rows.Add(new Markup($"[grey]    {Esc(Sparkline(inner - 4))}[/]"));
        rows.Add(new Markup($"[grey]RED [/][{AccentHex}]↓ {FormatRate(sample.NetDownBytesPerSec)}[/]  [mediumpurple1]↑ {FormatRate(sample.NetUpBytesPerSec)}[/]"));
        rows.Add(new Markup($"[grey]DISCO [/][cyan]L {FormatRate(sample.DiskReadBytesPerSec)}[/]  [yellow]E {FormatRate(sample.DiskWriteBytesPerSec)}[/]"));
        if (sample.HasBattery)
        {
            var charging = sample.BatteryCharging == true ? " [cyan]cargando[/]" : "";
            rows.Add(new Markup($"[grey]BAT [/][bold]{sample.BatteryPercent ?? 0}%[/]{charging}"));
        }

        var room = innerHeight - rows.Count - 2;
        if (room >= 2)
        {
            rows.Add(Text.Empty);
            var top = _topByRam ? _resources.TopProcessesByRam : _resources.TopProcessesByCpu;
            rows.Add(new Markup($"[grey]Top por {(_topByRam ? "RAM" : "CPU")}[/]"));
            foreach (var process in top.Take(room - 1))
            {
                var value = _topByRam ? FormatBytes(process.RamBytes) : $"{process.CpuPercent:0.0}%";
                var name = Truncate(process.Name, inner - value.Length - 3);
                rows.Add(new Markup($"  {Esc(name.PadRight(inner - value.Length - 3))}[{AccentHex}]{value}[/]"));
            }
        }
        return new Rows(rows);
    }

    private IRenderable Log(int inner, int innerHeight)
    {
        var entries = _log.Snapshot().Where(e => e.Level >= _logFilter).ToList();
        if (entries.Count == 0)
            return new Markup("[grey]Sin mensajes todavía[/]");

        var visible = Math.Max(1, innerHeight);
        _logScroll = Math.Clamp(_logScroll, 0, Math.Max(0, entries.Count - visible));
        var start = Math.Max(0, entries.Count - visible - _logScroll);
        var rows = new List<IRenderable>();
        foreach (var entry in entries.Skip(start).Take(visible))
        {
            var prefix = $"{entry.Time:HH:mm:ss} {LevelTag(entry.Level)} {entry.Category} ";
            var message = Truncate(entry.Message.ReplaceLineEndings(" "), Math.Max(10, inner - prefix.Length));
            var levelColor = entry.Level switch
            {
                >= LogLevel.Error => "red",
                LogLevel.Warning => "yellow",
                _ => AccentHex,
            };
            var messageColor = entry.Level >= LogLevel.Warning ? levelColor : "white";
            rows.Add(new Markup(
                $"[grey35]{entry.Time:HH:mm:ss}[/] [{levelColor}]{LevelTag(entry.Level)}[/] [grey]{Esc(entry.Category)}[/] [{messageColor}]{Esc(message)}[/]"));
        }
        return new Rows(rows);
    }

    // ---------------------------------------------------------------- helpers

    private string Bar(double fraction, int width)
    {
        fraction = Math.Clamp(fraction, 0, 1);
        var filled = (int)Math.Round(width * fraction);
        var color = fraction < 0.7 ? AccentHex : fraction < 0.9 ? "yellow" : "red";
        return $"[{color}]{new string('█', filled)}[/][grey23]{new string('░', width - filled)}[/]";
    }

    private string Sparkline(int width)
    {
        const string glyphs = "▁▂▃▄▅▆▇█";
        float[] history;
        lock (_cpuHistory) history = _cpuHistory.ToArray();
        var slice = history.Skip(Math.Max(0, history.Length - width)).ToArray();
        var sb = new StringBuilder();
        foreach (var value in slice)
            sb.Append(glyphs[Math.Clamp((int)Math.Round(value * (glyphs.Length - 1)), 0, glyphs.Length - 1)]);
        return sb.ToString().PadLeft(width);
    }

    private static long CurrentPositionMs(PlaybackState state) =>
        state.Playing ? state.PositionMs + (DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - state.TimestampMs) : state.PositionMs;

    private static string LevelTag(LogLevel level) => level switch
    {
        LogLevel.Critical => "CRT",
        LogLevel.Error => "ERR",
        LogLevel.Warning => "AVS",
        LogLevel.Information => "INF",
        _ => "DBG",
    };

    private static string Ago(DateTimeOffset at)
    {
        var span = DateTimeOffset.Now - at;
        if (span.TotalSeconds < 60) return $"hace {(int)span.TotalSeconds} s";
        if (span.TotalMinutes < 60) return $"hace {(int)span.TotalMinutes} min";
        return $"hace {(int)span.TotalHours} h {span.Minutes} min";
    }

    private static string FormatMs(long ms)
    {
        var t = TimeSpan.FromMilliseconds(Math.Max(0, ms));
        return t.TotalHours >= 1 ? $"{(int)t.TotalHours}:{t.Minutes:00}:{t.Seconds:00}" : $"{t.Minutes}:{t.Seconds:00}";
    }

    private static string FormatBytes(long bytes)
    {
        var gb = bytes / 1024d / 1024d / 1024d;
        return gb >= 1 ? $"{gb:0.0} GB" : $"{bytes / 1024d / 1024d:0} MB";
    }

    private static string FormatRate(double bytesPerSec)
    {
        var mb = bytesPerSec / 1024d / 1024d;
        return mb >= 1 ? $"{mb:0.0} MB/s" : $"{bytesPerSec / 1024d:0} KB/s";
    }

    private static string Truncate(string text, int max) =>
        max <= 1 ? "" : text.Length <= max ? text : text[..(max - 1)] + "…";

    private static string Esc(string text) => Markup.Escape(text);
}
