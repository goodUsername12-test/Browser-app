using System.Text.Json;
using System.Text.Json.Serialization;

namespace FableBrowser.Core;

public static class JsonStore
{
    static readonly object WriteGate = new();

    public static readonly JsonSerializerOptions Options = new()
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true,
        ReadCommentHandling = JsonCommentHandling.Skip,
        AllowTrailingCommas = true,
        Converters = { new JsonStringEnumConverter() }
    };

    public static T? Read<T>(string path) where T : class
    {
        try
        {
            if (!File.Exists(path)) return null;
            using var stream = File.OpenRead(path);
            return JsonSerializer.Deserialize<T>(stream, Options);
        }
        catch (Exception ex)
        {
            CrashLog.Write("Could not read " + Path.GetFileName(path), ex);
            try
            {
                File.Copy(path, path + ".bad", true);
            }
            catch (Exception)
            {
            }
            return null;
        }
    }

    public static void Write<T>(string path, T value)
    {
        lock (WriteGate)
        {
            var temp = path + ".tmp";
            using (var stream = File.Create(temp))
            {
                JsonSerializer.Serialize(stream, value, Options);
            }
            File.Move(temp, path, true);
        }
    }

    public static T Clone<T>(T value) where T : class
    {
        var json = JsonSerializer.Serialize(value, Options);
        return JsonSerializer.Deserialize<T>(json, Options)!;
    }
}
