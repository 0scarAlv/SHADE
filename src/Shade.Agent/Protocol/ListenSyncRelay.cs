using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Shade.Agent.Streaming;

namespace Shade.Agent.Protocol;

// Carries the phone player's listening journal to and from Nation, the source
// of truth for stats. The agent only relays: Nation decides who owns the
// journal and what to accept; the phone decides whether to pair with that owner.
//
// App -> Agent: { type: "sync", listener, since, sinceId, device: {id,name,kind}, listens: [...] }
// Agent -> App: { type: "syncResult", nation, owner: {id,name}, paired, accepted, listens: [...], more, error }
public static class ListenSyncRelay
{
    private static HttpClient _http = NewClient(7814);

    // Same port the Tauon bridge polls: Nation serves both APIs there.
    public static void Configure(int port) => _http = NewClient(port);

    private static HttpClient NewClient(int port) =>
        new() { BaseAddress = new Uri($"http://127.0.0.1:{port}/"), Timeout = TimeSpan.FromSeconds(15) };

    public static async Task HandleAsync(JsonObject request, ClientHub hub, IClientConnection connection, ILogger logger)
    {
        var result = new JsonObject { ["type"] = "syncResult", ["nation"] = false };
        try
        {
            using var profileResponse = await _http.GetAsync("nation/profile");
            if (!profileResponse.IsSuccessStatusCode)
            {
                // Tauon (or a Nation with stats off) on that port: nothing to sync with.
                result["error"] = "Nation no tiene estadísticas en este PC";
                await hub.SendToAsync(connection, result);
                return;
            }
            var profile = JsonNode.Parse(await profileResponse.Content.ReadAsStringAsync())!.AsObject();
            var owner = profile["listener"]?.GetValue<string>();
            result["nation"] = true;
            result["owner"] = new JsonObject { ["id"] = owner, ["name"] = profile["name"]?.GetValue<string>() };

            var listener = request["listener"]?.GetValue<string>();
            bool paired = listener is not null && listener == owner;
            result["paired"] = paired;
            if (!paired)
            {
                // The phone asks its user before taking this owner's identity.
                await hub.SendToAsync(connection, result);
                return;
            }

            int accepted = 0;
            if (request["listens"] is JsonArray { Count: > 0 } listens)
            {
                var push = new JsonObject { ["device"] = request["device"]?.DeepClone(), ["listens"] = listens.DeepClone() };
                using var body = new StringContent(push.ToJsonString(), Encoding.UTF8);
                body.Headers.ContentType = new MediaTypeHeaderValue("application/json");
                using var pushResponse = await _http.PostAsync("nation/listens", body);
                pushResponse.EnsureSuccessStatusCode();
                accepted = JsonNode.Parse(await pushResponse.Content.ReadAsStringAsync())?["accepted"]?.GetValue<int>() ?? 0;
            }
            result["accepted"] = accepted;

            long since = request["since"]?.GetValue<long>() ?? 0;
            var sinceId = Uri.EscapeDataString(request["sinceId"]?.GetValue<string>() ?? "");
            var page = JsonNode.Parse(await _http.GetStringAsync($"nation/listens?since={since}&sinceId={sinceId}&limit=1000"))!.AsObject();
            result["listens"] = page["listens"]?.DeepClone() ?? new JsonArray();
            result["more"] = page["more"]?.GetValue<bool>() ?? false;
            logger.LogInformation("Sync con Nation: {Up} subidas, {Down} bajadas", accepted, (result["listens"] as JsonArray)?.Count ?? 0);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or JsonException or InvalidOperationException)
        {
            result["error"] = ex is HttpRequestException ? "Nation no está abierto" : ex.Message;
            logger.LogInformation("Sync con Nation falló: {Message}", ex.Message);
        }
        await hub.SendToAsync(connection, result);
    }
}
