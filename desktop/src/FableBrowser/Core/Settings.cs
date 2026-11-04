namespace FableBrowser.Core;

public enum SearchEngineKind { Google, DuckDuckGo, Bing, Brave, Startpage, Ecosia }
public enum UiTheme { System, Light, Dark }
public enum DohProvider { Off, Cloudflare, Quad9, Google, Custom }
public enum TabUnloadMode { Smart, KeepAll, Aggressive }
public enum MemoryMode { Auto, Low, Balanced, High }
public enum AutoWipe { Off = 0, OneMinute = 1, FiveMinutes = 5, FifteenMinutes = 15, SixtyMinutes = 60 }
public enum ProxyKind { Socks5, Socks4, Http, Https }
public enum TrackingLevel { None, Basic, Balanced, Strict }
public enum DeviceRange { Computers, Windows, Mac, Linux }

public sealed class Snippet
{
    public string Name { get; set; } = "";
    public string Code { get; set; } = "";
}

public sealed class Settings
{
    public SearchEngineKind SearchEngine { get; set; } = SearchEngineKind.Google;
    public string Homepage { get; set; } = "";
    public bool RestoreTabs { get; set; } = true;
    public bool AdBlock { get; set; } = true;
    public TrackingLevel Tracking { get; set; } = TrackingLevel.Balanced;
    public bool HttpsOnly { get; set; } = true;
    public bool SendGpc { get; set; } = true;
    public bool StripTrackingParams { get; set; } = true;
    public bool HideLocalIp { get; set; } = true;
    public bool BlockPopups { get; set; } = true;
    public bool BlockPrefetch { get; set; } = true;
    public bool QuietNetwork { get; set; } = true;
    public bool SmartScreen { get; set; } = true;
    public bool SavePasswords { get; set; }
    public bool Autofill { get; set; }
    public bool HideGpuModel { get; set; } = true;
    public DohProvider Doh { get; set; } = DohProvider.Cloudflare;
    public string DohCustomUrl { get; set; } = "";
    public bool ClearHistoryOnExit { get; set; }
    public bool ClearCookiesOnExit { get; set; }
    public bool ClearCacheOnExit { get; set; }
    public bool PendingExitClear { get; set; }
    public bool ShieldNormalTabs { get; set; }
    public List<string> ShieldTrustedSites { get; set; } = new();
    public List<string> BlockerAllowedSites { get; set; } = new();
    public bool IncognitoAdBlock { get; set; } = true;
    public bool IncognitoIsolateTabs { get; set; } = true;
    public bool IncognitoShield { get; set; } = true;
    public bool IncognitoForceUtc { get; set; } = true;
    public bool IncognitoJavascript { get; set; } = true;
    public bool IncognitoLock { get; set; }
    public string IncognitoPinHash { get; set; } = "";
    public string IncognitoPinSalt { get; set; } = "";
    public AutoWipe IncognitoAutoWipe { get; set; } = AutoWipe.Off;
    public bool IncognitoProxy { get; set; }
    public ProxyKind IncognitoProxyKind { get; set; } = ProxyKind.Socks5;
    public string IncognitoProxyHost { get; set; } = "127.0.0.1";
    public int IncognitoProxyPort { get; set; } = 9050;
    public DeviceRange SuperRange { get; set; } = DeviceRange.Computers;
    public bool SuperRandomTimezone { get; set; } = true;
    public bool SuperGameMode { get; set; }
    public List<string> SuperReveal { get; set; } = new();
    public bool SuperProxy { get; set; }
    public ProxyKind SuperProxyKind { get; set; } = ProxyKind.Socks5;
    public string SuperProxyHost { get; set; } = "127.0.0.1";
    public int SuperProxyPort { get; set; } = 1080;
    public TabUnloadMode UnloadMode { get; set; } = TabUnloadMode.Smart;
    public int KeepLoadedTabs { get; set; } = 3;
    public int IdleTabMinutes { get; set; } = 10;
    public MemoryMode Memory { get; set; } = MemoryMode.Auto;
    public bool SuspendBackgroundTabs { get; set; } = true;
    public bool KeepAudioTabsAwake { get; set; } = true;
    public bool AutoClearCache { get; set; } = true;
    public int CacheLimitMb { get; set; } = 300;
    public DateTime LastCacheCheck { get; set; }
    public bool GameMode { get; set; } = true;
    public bool UncappedFps { get; set; }
    public bool ForceGpu { get; set; }
    public bool FocusGameTab { get; set; } = true;
    public UiTheme Theme { get; set; } = UiTheme.Dark;
    public bool ForceDarkWeb { get; set; }
    public int DefaultZoom { get; set; } = 100;
    public string DownloadFolder { get; set; } = "";
    public bool AskWhereToSave { get; set; }
    public bool DevTools { get; set; } = true;
    public List<Snippet> Snippets { get; set; } = new();
    public bool AutoUpdateFilters { get; set; } = true;
    public DateTime LastFilterUpdate { get; set; }

    public void Normalize()
    {
        KeepLoadedTabs = Math.Clamp(KeepLoadedTabs, 0, 50);
        IdleTabMinutes = Math.Clamp(IdleTabMinutes, 1, 1440);
        CacheLimitMb = Math.Clamp(CacheLimitMb, 50, 10000);
        DefaultZoom = Math.Clamp(DefaultZoom, 25, 500);
        IncognitoProxyPort = Math.Clamp(IncognitoProxyPort, 1, 65535);
        SuperProxyPort = Math.Clamp(SuperProxyPort, 1, 65535);
        ShieldTrustedSites ??= new List<string>();
        BlockerAllowedSites ??= new List<string>();
        SuperReveal ??= new List<string>();
        Snippets ??= new List<Snippet>();
        Homepage ??= "";
        DohCustomUrl ??= "";
        DownloadFolder ??= "";
        IncognitoProxyHost ??= "";
        SuperProxyHost ??= "";
        IncognitoPinHash ??= "";
        IncognitoPinSalt ??= "";
    }
}

public static class SearchEngines
{
    public static string QueryUrl(SearchEngineKind kind) => kind switch
    {
        SearchEngineKind.DuckDuckGo => "https://duckduckgo.com/?q=%s",
        SearchEngineKind.Bing => "https://www.bing.com/search?q=%s",
        SearchEngineKind.Brave => "https://search.brave.com/search?q=%s",
        SearchEngineKind.Startpage => "https://www.startpage.com/sp/search?query=%s",
        SearchEngineKind.Ecosia => "https://www.ecosia.org/search?q=%s",
        _ => "https://www.google.com/search?q=%s"
    };

    public static string Label(SearchEngineKind kind) => kind switch
    {
        SearchEngineKind.DuckDuckGo => "DuckDuckGo",
        SearchEngineKind.Bing => "Bing",
        SearchEngineKind.Brave => "Brave Search",
        SearchEngineKind.Startpage => "Startpage",
        SearchEngineKind.Ecosia => "Ecosia",
        _ => "Google"
    };

    public static string? DohTemplate(Settings settings) => settings.Doh switch
    {
        DohProvider.Cloudflare => "https://cloudflare-dns.com/dns-query",
        DohProvider.Quad9 => "https://dns.quad9.net/dns-query",
        DohProvider.Google => "https://dns.google/dns-query",
        DohProvider.Custom when settings.DohCustomUrl.StartsWith("https://", StringComparison.OrdinalIgnoreCase) => settings.DohCustomUrl.Trim(),
        _ => null
    };
}

public static class SettingsStore
{
    static string FilePath => Path.Combine(AppPaths.Data, "settings.json");

    public static Settings Current { get; private set; } = new();

    public static event Action? Changed;

    public static void Load()
    {
        Current = JsonStore.Read<Settings>(FilePath) ?? new Settings();
        Current.Normalize();
    }

    public static void Save()
    {
        Current.Normalize();
        try
        {
            JsonStore.Write(FilePath, Current);
        }
        catch (Exception ex)
        {
            CrashLog.Write("Could not save settings", ex);
        }
        Changed?.Invoke();
    }

    public static void Update(Action<Settings> change)
    {
        change(Current);
        Save();
    }
}
