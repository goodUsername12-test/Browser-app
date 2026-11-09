using System.Text;

namespace FableBrowser.Core;

public static class CrashLog
{
    static readonly object Gate = new();

    public static string? LastFile { get; private set; }

    public static void Write(string context, Exception? ex)
    {
        try
        {
            var builder = new StringBuilder();
            builder.Append(DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss.fff")).Append("  ").AppendLine(context);
            builder.Append("Version ").Append(typeof(CrashLog).Assembly.GetName().Version).Append("  OS ").AppendLine(Environment.OSVersion.VersionString);
            if (ex != null) builder.AppendLine(ex.ToString());
            builder.AppendLine();
            var file = Path.Combine(AppPaths.Logs, "fable-" + DateTime.Now.ToString("yyyy-MM-dd") + ".log");
            lock (Gate)
            {
                File.AppendAllText(file, builder.ToString());
            }
            LastFile = file;
            Trim();
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }

    static void Trim()
    {
        var files = new DirectoryInfo(AppPaths.Logs).GetFiles("fable-*.log").OrderByDescending(f => f.Name).Skip(14);
        foreach (var file in files)
        {
            try
            {
                file.Delete();
            }
            catch (IOException)
            {
            }
        }
    }
}
