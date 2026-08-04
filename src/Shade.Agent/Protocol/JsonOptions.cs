using System.Text.Json;
using System.Text.Json.Serialization;

namespace Shade.Agent.Protocol;

public static class ShadeJson
{
    public static readonly JsonSerializerOptions Options = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };
}
