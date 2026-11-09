using FableBrowser.Core;

namespace FableBrowser.Browser;

public static class WebAssets
{
    static readonly string[] Files = { "newtab.html", "newtab.css", "newtab.js", "fable.png" };

    public static void Extract()
    {
        foreach (var name in Files)
        {
            try
            {
                using var stream = Open(name);
                if (stream == null) continue;
                using var memory = new MemoryStream();
                stream.CopyTo(memory);
                var bytes = memory.ToArray();
                var target = Path.Combine(AppPaths.Web, name);
                if (File.Exists(target) && new FileInfo(target).Length == bytes.Length && File.ReadAllBytes(target).AsSpan().SequenceEqual(bytes)) continue;
                File.WriteAllBytes(target, bytes);
            }
            catch (IOException ex)
            {
                CrashLog.Write("Could not extract " + name, ex);
            }
        }
    }

    public static Stream? Open(string name) => typeof(WebAssets).Assembly.GetManifestResourceStream("Fable." + name);

    public static string? ReadText(string name)
    {
        using var stream = Open(name);
        if (stream == null) return null;
        using var reader = new StreamReader(stream);
        return reader.ReadToEnd();
    }
}
