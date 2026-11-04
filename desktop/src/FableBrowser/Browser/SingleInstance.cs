using System.Diagnostics;
using System.IO.Pipes;
using System.Text.Json;
using FableBrowser.Core;

namespace FableBrowser.Browser;

public static class SingleInstance
{
    static Mutex? mutex;
    static bool owned;

    static string PipeName => "FableBrowser-" + AppPaths.InstanceKey + "-" + Process.GetCurrentProcess().SessionId.ToString(System.Globalization.CultureInfo.InvariantCulture);

    public static bool TryClaim(string[] args)
    {
        mutex = new Mutex(true, @"Local\FableBrowser-" + AppPaths.InstanceKey, out var created);
        if (!created && args.Contains("--restarted", StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                created = mutex.WaitOne(TimeSpan.FromSeconds(10));
            }
            catch (AbandonedMutexException)
            {
                created = true;
            }
        }
        owned = created;
        if (created) return true;
        try
        {
            using var client = new NamedPipeClientStream(".", PipeName, PipeDirection.Out);
            client.Connect(2000);
            using var writer = new StreamWriter(client);
            writer.Write(JsonSerializer.Serialize(args));
            writer.Flush();
        }
        catch (Exception ex) when (ex is IOException or TimeoutException or UnauthorizedAccessException)
        {
            CrashLog.Write("Could not reach the running window", ex);
        }
        return false;
    }

    public static void Listen(Action<string[]> onArguments)
    {
        var thread = new Thread(() =>
        {
            while (true)
            {
                try
                {
                    using var server = new NamedPipeServerStream(PipeName, PipeDirection.In, 1, PipeTransmissionMode.Byte, PipeOptions.None);
                    server.WaitForConnection();
                    using var reader = new StreamReader(server);
                    var text = reader.ReadToEnd();
                    var args = JsonSerializer.Deserialize<string[]>(text) ?? Array.Empty<string>();
                    onArguments(args);
                }
                catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
                {
                    CrashLog.Write("Instance pipe error", ex);
                    Thread.Sleep(500);
                }
            }
        })
        {
            IsBackground = true,
            Name = "Fable instance pipe"
        };
        thread.Start();
    }

    public static void Release()
    {
        if (mutex == null) return;
        if (owned)
        {
            try
            {
                mutex.ReleaseMutex();
            }
            catch (ApplicationException)
            {
            }
        }
        mutex.Dispose();
        mutex = null;
    }
}
