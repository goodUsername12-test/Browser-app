namespace FableBrowser.Core;

public static class AppPaths
{
    public static string Root { get; } = ResolveRoot();
    public static bool IsPortable { get; private set; }
    public static string Profile => Path.Combine(Root, "Profile");
    public static string Data => Ensure(Path.Combine(Root, "Data"));
    public static string Web => Ensure(Path.Combine(Root, "Web"));
    public static string Filters => Ensure(Path.Combine(Root, "Filters"));
    public static string Logs => Ensure(Path.Combine(Root, "Logs"));
    public static string Temp => Ensure(Path.Combine(Path.GetTempPath(), "FableBrowser"));
    public static string InstanceKey { get; } = Hash(Root);

    static string ResolveRoot()
    {
        var baseDir = AppContext.BaseDirectory;
        string root;
        if (File.Exists(Path.Combine(baseDir, "portable.txt")))
        {
            IsPortable = true;
            root = Path.Combine(baseDir, "FableData");
        }
        else
        {
            root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "FableBrowser");
        }
        Directory.CreateDirectory(root);
        return root;
    }

    static string Ensure(string path)
    {
        Directory.CreateDirectory(path);
        return path;
    }

    static string Hash(string value)
    {
        var bytes = System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(value.ToLowerInvariant()));
        return Convert.ToHexString(bytes, 0, 8);
    }
}
