using System.Collections.Concurrent;

namespace Shade.Agent.Streaming;

public sealed record CachedArt(byte[] Bytes, string ContentType);

// Caches cover art in memory by hash, to serve it over HTTP at /art/{hash}
// without touching the SMTC session or the socket again.
public sealed class ArtCache
{
    private readonly ConcurrentDictionary<string, CachedArt> _cache = new();

    public void Store(string hash, byte[] bytes, string contentType) =>
        _cache.TryAdd(hash, new CachedArt(bytes, contentType));

    public bool TryGet(string hash, out CachedArt art) => _cache.TryGetValue(hash, out art!);
}
