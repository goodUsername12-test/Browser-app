using System.Diagnostics;
using System.Globalization;
using FableBrowser.Core;
using Microsoft.Web.WebView2.Core;

namespace FableBrowser.Browser;

public static class Engine
{
    static Task<CoreWebView2Environment>? mainEnvironment;
    static readonly Dictionary<string, Task<CoreWebView2Environment>> ProxyEnvironments = new();
    static readonly List<string> TempFolders = new();
    static readonly HttpClient Http = CreateHttp();

    public static ContentBlocker Blocker { get; } = new();

    public static string StartupArguments { get; private set; } = "";

    public static string BrowserVersion { get; private set; } = "";

    public static string? ShieldTemplate { get; private set; }

    public static event Action<CoreWebView2Environment>? EnvironmentLost;

    static HttpClient CreateHttp()
    {
        var client = new HttpClient { Timeout = TimeSpan.FromSeconds(60) };
        client.DefaultRequestHeaders.UserAgent.ParseAdd("FableBrowser/1.0");
        return client;
    }

    public static void Initialize()
    {
        ShieldTemplate = WebAssets.ReadText("shield.js");
        BrowserVersion = InstalledRuntimeVersion() ?? "";
        _ = Blocker.LoadAsync();
        CleanStaleTempFolders();
    }

    public static string? FixedRuntimeFolder()
    {
        var folder = Path.Combine(AppContext.BaseDirectory, "WebView2Runtime");
        return File.Exists(Path.Combine(folder, "msedgewebview2.exe")) ? folder : null;
    }

    public static string? InstalledRuntimeVersion()
    {
        try
        {
            return CoreWebView2Environment.GetAvailableBrowserVersionString(FixedRuntimeFolder());
        }
        catch (WebView2RuntimeNotFoundException)
        {
            return null;
        }
    }

    public static Task<CoreWebView2Environment> GetAsync(TabKind kind)
    {
        var settings = SettingsStore.Current;
        if (kind == TabKind.Incognito && settings.IncognitoProxy && settings.IncognitoProxyHost.Trim().Length > 0)
        {
            return GetProxy(settings.IncognitoProxyKind, settings.IncognitoProxyHost.Trim(), settings.IncognitoProxyPort);
        }
        if (kind == TabKind.Super && settings.SuperProxy && settings.SuperProxyHost.Trim().Length > 0)
        {
            return GetProxy(settings.SuperProxyKind, settings.SuperProxyHost.Trim(), settings.SuperProxyPort);
        }
        if (mainEnvironment == null || mainEnvironment.IsFaulted || mainEnvironment.IsCanceled)
        {
            StartupArguments = BuildArguments(null, null);
            mainEnvironment = CreateAsync(AppPaths.Profile, StartupArguments);
        }
        return mainEnvironment;
    }

    static Task<CoreWebView2Environment> GetProxy(ProxyKind kind, string host, int port)
    {
        var scheme = kind switch
        {
            ProxyKind.Socks4 => "socks4",
            ProxyKind.Http => "http",
            ProxyKind.Https => "https",
            _ => "socks5"
        };
        var proxy = $"{scheme}://{host}:{port.ToString(CultureInfo.InvariantCulture)}";
        if (ProxyEnvironments.TryGetValue(proxy, out var existing) && !existing.IsFaulted && !existing.IsCanceled) return existing;
        var folder = Path.Combine(AppPaths.Temp, "proxy-" + Environment.ProcessId.ToString(CultureInfo.InvariantCulture) + "-" + ProxyEnvironments.Count.ToString(CultureInfo.InvariantCulture));
        TempFolders.Add(folder);
        var task = CreateAsync(folder, BuildArguments(proxy, kind is ProxyKind.Socks5 or ProxyKind.Socks4 ? host : null));
        ProxyEnvironments[proxy] = task;
        return task;
    }

    public static string BuildArguments(string? proxy, string? socksHost)
    {
        var settings = SettingsStore.Current;
        var args = new List<string>();
        var enable = new List<string>();
        var disable = new List<string>();
        if (settings.GameMode)
        {
            args.Add("--enable-gpu-rasterization");
            args.Add("--enable-zero-copy");
        }
        if (settings.ForceGpu) args.Add("--ignore-gpu-blocklist");
        if (settings.UncappedFps)
        {
            args.Add("--disable-frame-rate-limit");
            args.Add("--disable-gpu-vsync");
        }
        if (settings.HideLocalIp || proxy != null)
        {
            args.Add("--force-webrtc-ip-handling-policy=" + (proxy != null ? "disable_non_proxied_udp" : "default_public_interface_only"));
        }
        if (settings.BlockPrefetch || proxy != null)
        {
            args.Add("--dns-prefetch-disable");
            disable.Add("Prerender2");
        }
        if (settings.QuietNetwork) args.Add("--disable-background-networking");
        if (settings.ForceDarkWeb) enable.Add("WebContentsForceDark");
        var doh = SearchEngines.DohTemplate(settings);
        if (doh != null && proxy == null)
        {
            enable.Add("DnsOverHttps<DoHTrial");
            args.Add("--force-fieldtrials=DoHTrial/Group1");
            args.Add("--force-fieldtrial-params=DoHTrial.Group1:Fallback/true/Templates/" + Uri.EscapeDataString(doh));
        }
        if (proxy != null)
        {
            args.Add("--proxy-server=" + proxy);
            if (socksHost != null) args.Add("--host-resolver-rules=\"MAP * ~NOTFOUND , EXCLUDE " + socksHost + "\"");
        }
        if (enable.Count > 0) args.Add("--enable-features=" + string.Join(",", enable));
        if (disable.Count > 0) args.Add("--disable-features=" + string.Join(",", disable));
        return string.Join(" ", args);
    }

    public static bool NeedsRestart() => mainEnvironment != null && BuildArguments(null, null) != StartupArguments;

    static async Task<CoreWebView2Environment> CreateAsync(string userDataFolder, string arguments)
    {
        Directory.CreateDirectory(userDataFolder);
        var options = new CoreWebView2EnvironmentOptions
        {
            AdditionalBrowserArguments = arguments,
            EnableTrackingPrevention = true,
            AllowSingleSignOnUsingOSPrimaryAccount = false,
            ScrollBarStyle = CoreWebView2ScrollbarStyle.FluentOverlay
        };
        var environment = await CoreWebView2Environment.CreateAsync(FixedRuntimeFolder(), userDataFolder, options);
        if (BrowserVersion.Length == 0) BrowserVersion = environment.BrowserVersionString;
        environment.BrowserProcessExited += OnBrowserProcessExited;
        return environment;
    }

    static void OnBrowserProcessExited(object? sender, CoreWebView2BrowserProcessExitedEventArgs e)
    {
        if (sender is not CoreWebView2Environment environment) return;
        if (e.BrowserProcessExitKind == CoreWebView2BrowserProcessExitKind.Normal) return;
        if (mainEnvironment is { IsCompletedSuccessfully: true } && ReferenceEquals(mainEnvironment.Result, environment)) mainEnvironment = null;
        foreach (var pair in ProxyEnvironments.ToList())
        {
            if (pair.Value.IsCompletedSuccessfully && ReferenceEquals(pair.Value.Result, environment)) ProxyEnvironments.Remove(pair.Key);
        }
        EnvironmentLost?.Invoke(environment);
    }

    public static async Task<int> UpdateFiltersAsync(CancellationToken token)
    {
        var count = await Blocker.UpdateListsAsync(Http, token);
        SettingsStore.Current.LastFilterUpdate = DateTime.UtcNow;
        SettingsStore.Save();
        return count;
    }

    public static async Task AutoUpdateFiltersAsync()
    {
        var settings = SettingsStore.Current;
        if (!settings.AdBlock || !settings.AutoUpdateFilters) return;
        if (DateTime.UtcNow - settings.LastFilterUpdate < TimeSpan.FromDays(7)) return;
        try
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromMinutes(2));
            await UpdateFiltersAsync(cts.Token);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or IOException)
        {
            CrashLog.Write("Filter list update skipped", ex);
        }
    }

    public static void Shutdown()
    {
        foreach (var folder in TempFolders)
        {
            TryDelete(folder);
        }
    }

    static void CleanStaleTempFolders()
    {
        try
        {
            foreach (var dir in Directory.GetDirectories(AppPaths.Temp, "proxy-*"))
            {
                var name = Path.GetFileName(dir);
                var parts = name.Split('-');
                if (parts.Length >= 2 && int.TryParse(parts[1], out var pid) && pid != Environment.ProcessId && !ProcessAlive(pid)) TryDelete(dir);
            }
        }
        catch (IOException)
        {
        }
    }

    static bool ProcessAlive(int pid)
    {
        try
        {
            using var process = Process.GetProcessById(pid);
            return !process.HasExited;
        }
        catch (ArgumentException)
        {
            return false;
        }
        catch (InvalidOperationException)
        {
            return false;
        }
    }

    static void TryDelete(string folder)
    {
        try
        {
            if (Directory.Exists(folder)) Directory.Delete(folder, true);
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }

    public static long DirectorySize(string path)
    {
        if (!Directory.Exists(path)) return 0;
        long total = 0;
        try
        {
            foreach (var file in new DirectoryInfo(path).EnumerateFiles("*", new EnumerationOptions { RecurseSubdirectories = true, IgnoreInaccessible = true }))
            {
                total += file.Length;
            }
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
        return total;
    }

    public static long CacheSize()
    {
        var root = Path.Combine(AppPaths.Profile, "EBWebView", "Default");
        return DirectorySize(Path.Combine(root, "Cache")) + DirectorySize(Path.Combine(root, "Code Cache")) + DirectorySize(Path.Combine(root, "GPUCache"));
    }
}
