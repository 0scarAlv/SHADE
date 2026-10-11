namespace Shade.Agent.Dashboard;

public sealed record LogEntry(DateTimeOffset Time, LogLevel Level, string Category, string Message);

// In-memory ring of the most recent log lines. While the console dashboard
// owns the terminal, the default console logger would scribble over it, so
// logging is routed here instead and the dashboard renders it as a panel.
public sealed class LogBuffer
{
    private const int Capacity = 500;

    private readonly LinkedList<LogEntry> _entries = new();
    private readonly object _lock = new();

    public void Add(LogEntry entry)
    {
        lock (_lock)
        {
            _entries.AddLast(entry);
            if (_entries.Count > Capacity) _entries.RemoveFirst();
        }
    }

    public IReadOnlyList<LogEntry> Snapshot()
    {
        lock (_lock) return _entries.ToList();
    }

    public void Clear()
    {
        lock (_lock) _entries.Clear();
    }
}

public sealed class BufferLoggerProvider(LogBuffer buffer) : ILoggerProvider
{
    public ILogger CreateLogger(string categoryName) => new BufferLogger(buffer, ShortCategory(categoryName));

    public void Dispose() { }

    // "Shade.Agent.Smtc.SmtcSessionWatcher" -> "SmtcSessionWatcher".
    private static string ShortCategory(string category)
    {
        var dot = category.LastIndexOf('.');
        return dot >= 0 ? category[(dot + 1)..] : category;
    }

    private sealed class BufferLogger(LogBuffer buffer, string category) : ILogger
    {
        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;

        public bool IsEnabled(LogLevel logLevel) => logLevel >= LogLevel.Information;

        public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception, Func<TState, Exception?, string> formatter)
        {
            if (!IsEnabled(logLevel)) return;
            // ASP.NET Core's own chatter is filtered out by appsettings'
            // "Microsoft.AspNetCore": "Warning" before it ever reaches here.
            var message = formatter(state, exception);
            if (exception is not null) message += $" — {exception.GetType().Name}: {exception.Message}";
            buffer.Add(new LogEntry(DateTimeOffset.Now, logLevel, category, message));
        }
    }
}
