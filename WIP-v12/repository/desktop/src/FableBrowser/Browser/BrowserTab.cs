using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using FableBrowser.Core;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.Wpf;

namespace FableBrowser.Browser;

public enum TabKind { Normal, Incognito, Super }

public sealed record InfoAction(string Label, Action? Run);

public sealed class BrowserTab : INotifyPropertyChanged
{
    static int profileCounter;
    static readonly uint SessionSeed = (uint)Random.Shared.Next(1, int.MaxValue);
    static readonly HashSet<string> HttpAllowedHosts = new(StringComparer.OrdinalIgnoreCase);
    static readonly System.Drawing.Color DarkBackground = System.Drawing.Color.FromArgb(255, 20, 18, 24);
    static readonly System.Drawing.Color LightBackground = System.Drawing.Color.FromArgb(255, 253, 248, 253);

    static readonly HashSet<CoreWebView2PermissionKind> PromptedPermissions = new()
    {
        CoreWebView2PermissionKind.Camera,
        CoreWebView2PermissionKind.Microphone,
        CoreWebView2PermissionKind.Geolocation,
        CoreWebView2PermissionKind.Notifications,
        CoreWebView2PermissionKind.OtherSensors,
        CoreWebView2PermissionKind.ClipboardRead,
        CoreWebView2PermissionKind.LocalFonts,
        CoreWebView2PermissionKind.MidiSystemExclusiveMessages,
        CoreWebView2PermissionKind.WindowManagement,
        CoreWebView2PermissionKind.MultipleAutomaticDownloads
    };

    readonly MainWindow window;
    readonly Dictionary<string, bool> sessionPermissions = new();
    readonly Dictionary<string, int> blockedHosts = new(StringComparer.OrdinalIgnoreCase);
    Task? creation;
    string? documentHost;
    bool requestFilterOn;
    bool blocking;
    bool sendGpc;
    string? upgradedHost;
    string? shieldScriptId;
    double zoom = SettingsStore.Current.DefaultZoom / 100.0;
    DateTime lastDownloadUpdate;

    string title = "New tab";
    string url = "";
    ImageSource? favicon;
    bool isActive;
    bool isLoading;
    bool isAudible;
    bool isMuted;
    bool isDiscarded = true;
    bool isPinned;
    int blockedCount;

    public BrowserTab(MainWindow window, TabKind kind, string? profileName = null, SuperIdentity? identity = null)
    {
        this.window = window;
        Kind = kind;
        ProfileName = profileName ?? NewProfileName(kind);
        Identity = identity ?? (kind == TabKind.Super ? SuperIdentity.Create(SettingsStore.Current, Engine.BrowserVersion, Random.Shared) : null);
        ShieldSeed = kind == TabKind.Normal ? SessionSeed : (uint)Random.Shared.Next(1, int.MaxValue);
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    public TabKind Kind { get; }
    public string? ProfileName { get; }
    public SuperIdentity? Identity { get; }
    public uint ShieldSeed { get; }
    public WebView2? View { get; private set; }
    public CoreWebView2? Core => View?.CoreWebView2;
    public DateTime LastActive { get; set; } = DateTime.UtcNow;
    public bool IsPrivate => Kind != TabKind.Normal;
    public bool IsCreated => Core != null;
    public bool WasFullscreen { get; set; }

    public string Title { get => title; set => Set(ref title, value); }
    public string Url { get => url; set => Set(ref url, value); }
    public ImageSource? Favicon { get => favicon; set => Set(ref favicon, value); }
    public bool IsActive { get => isActive; set => Set(ref isActive, value); }
    public bool IsLoading { get => isLoading; set => Set(ref isLoading, value); }
    public bool IsPinned { get => isPinned; set => Set(ref isPinned, value); }
    public int BlockedCount { get => blockedCount; private set => Set(ref blockedCount, value); }

    public bool IsAudible
    {
        get => isAudible;
        set
        {
            if (Set(ref isAudible, value)) RaiseAudio();
        }
    }

    public bool IsMuted
    {
        get => isMuted;
        set
        {
            if (Set(ref isMuted, value)) RaiseAudio();
        }
    }

    public bool IsDiscarded
    {
        get => isDiscarded;
        private set
        {
            if (Set(ref isDiscarded, value)) Raise(nameof(TitleOpacity));
        }
    }

    public string Glyph => Kind switch
    {
        TabKind.Incognito => "\uE8D7",
        TabKind.Super => "\uEA18",
        _ => "\uE774"
    };

    public string KindLabel => Kind switch
    {
        TabKind.Incognito => "Incognito",
        TabKind.Super => "Super incognito",
        _ => ""
    };

    public string AudioGlyph => IsMuted ? "\uE74F" : "\uE767";
    public Visibility AudioVisibility => IsAudible || IsMuted ? Visibility.Visible : Visibility.Collapsed;
    public double TitleOpacity => IsDiscarded && !IsActive ? 0.65 : 1.0;
    public IReadOnlyDictionary<string, int> BlockedHosts => blockedHosts;
    public string? DocumentHost => documentHost;

    static string? NewProfileName(TabKind kind)
    {
        if (kind == TabKind.Normal) return null;
        if (kind == TabKind.Incognito && !SettingsStore.Current.IncognitoIsolateTabs) return "Incognito";
        var n = Interlocked.Increment(ref profileCounter);
        return (kind == TabKind.Super ? "Super" : "Incognito") + "-" + n.ToString(System.Globalization.CultureInfo.InvariantCulture);
    }

    void RaiseAudio()
    {
        Raise(nameof(AudioGlyph));
        Raise(nameof(AudioVisibility));
    }

    bool Set<T>(ref T storage, T value, [CallerMemberName] string? name = null)
    {
        if (EqualityComparer<T>.Default.Equals(storage, value)) return false;
        storage = value;
        Raise(name);
        return true;
    }

    void Raise(string? name) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));

    public Task EnsureCreatedAsync()
    {
        if (creation == null || creation.IsFaulted || creation.IsCanceled) creation = CreateAsync();
        return creation;
    }

    async Task CreateAsync()
    {
        var view = new WebView2
        {
            DefaultBackgroundColor = App.IsDarkTheme ? DarkBackground : LightBackground,
            Visibility = IsActive ? Visibility.Visible : Visibility.Hidden
        };
        View = view;
        window.ViewHost.Children.Add(view);
        try
        {
            var environment = await Engine.GetAsync(Kind);
            var options = environment.CreateCoreWebView2ControllerOptions();
            if (ProfileName != null) options.ProfileName = ProfileName;
            options.IsInPrivateModeEnabled = IsPrivate;
            await view.EnsureCoreWebView2Async(environment, options);
            if (!ReferenceEquals(View, view)) return;
            var core = view.CoreWebView2;
            Configure(core);
            await InstallShieldAsync(core);
            await ApplyEmulationAsync(core);
            if (Math.Abs(view.ZoomFactor - zoom) > 0.001) view.ZoomFactor = zoom;
            IsDiscarded = false;
        }
        catch
        {
            if (ReferenceEquals(View, view)) DisposeView();
            throw;
        }
    }

    void Configure(CoreWebView2 core)
    {
        ApplySettings(core);
        core.SetVirtualHostNameToFolderMapping(UrlUtils.InternalHost, AppPaths.Web, CoreWebView2HostResourceAccessKind.Deny);
        var folder = SettingsStore.Current.DownloadFolder.Length > 0 ? SettingsStore.Current.DownloadFolder : MainWindow.DefaultDownloadFolder();
        try
        {
            Directory.CreateDirectory(folder);
            core.Profile.DefaultDownloadFolderPath = folder;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException or COMException)
        {
            CrashLog.Write("Download folder not available", ex);
        }
        core.NavigationStarting += OnNavigationStarting;
        core.NavigationCompleted += OnNavigationCompleted;
        core.SourceChanged += (_, _) =>
        {
            Url = core.Source;
            window.OnTabStateChanged(this);
        };
        core.HistoryChanged += (_, _) => window.OnTabStateChanged(this);
        core.DocumentTitleChanged += (_, _) => UpdateTitle(core);
        core.FaviconChanged += OnFaviconChanged;
        core.IsDocumentPlayingAudioChanged += (_, _) => IsAudible = core.IsDocumentPlayingAudio;
        core.IsMutedChanged += (_, _) => IsMuted = core.IsMuted;
        core.ContainsFullScreenElementChanged += (_, _) => window.SetPageFullscreen(this, core.ContainsFullScreenElement);
        core.NewWindowRequested += OnNewWindowRequested;
        core.WindowCloseRequested += (_, _) => window.Dispatcher.InvokeAsync(() => window.CloseTab(this));
        core.PermissionRequested += OnPermissionRequested;
        core.DownloadStarting += OnDownloadStarting;
        core.ProcessFailed += OnProcessFailed;
        core.ContextMenuRequested += OnContextMenuRequested;
        core.WebMessageReceived += OnWebMessageReceived;
        core.WebResourceRequested += OnWebResourceRequested;
        core.Find.MatchCountChanged += (_, _) => window.OnFindChanged(this);
        core.Find.ActiveMatchIndexChanged += (_, _) => window.OnFindChanged(this);
    }

    public void ApplySettings()
    {
        var core = Core;
        if (core == null) return;
        ApplySettings(core);
        _ = ReinstallShieldAsync(core);
    }

    void ApplySettings(CoreWebView2 core)
    {
        var settings = SettingsStore.Current;
        var s = core.Settings;
        s.AreDevToolsEnabled = settings.DevTools;
        s.AreHostObjectsAllowed = false;
        s.IsWebMessageEnabled = UrlUtils.IsInternal(core.Source);
        s.IsStatusBarEnabled = true;
        s.IsZoomControlEnabled = true;
        s.AreDefaultContextMenusEnabled = true;
        s.AreBrowserAcceleratorKeysEnabled = true;
        s.IsBuiltInErrorPageEnabled = true;
        s.IsPinchZoomEnabled = true;
        s.IsSwipeNavigationEnabled = true;
        s.IsPasswordAutosaveEnabled = !IsPrivate && settings.SavePasswords;
        s.IsGeneralAutofillEnabled = !IsPrivate && settings.Autofill;
        s.IsReputationCheckingRequired = settings.SmartScreen;
        s.IsScriptEnabled = Kind == TabKind.Normal || settings.IncognitoJavascript;
        if (Kind == TabKind.Super && Identity != null && Identity.Hides("browser")) s.UserAgent = Identity.UserAgent;
        core.Profile.PreferredColorScheme = settings.Theme switch
        {
            UiTheme.Dark => CoreWebView2PreferredColorScheme.Dark,
            UiTheme.Light => CoreWebView2PreferredColorScheme.Light,
            _ => CoreWebView2PreferredColorScheme.Auto
        };
        core.Profile.PreferredTrackingPreventionLevel = settings.Tracking switch
        {
            TrackingLevel.None => CoreWebView2TrackingPreventionLevel.None,
            TrackingLevel.Basic => CoreWebView2TrackingPreventionLevel.Basic,
            TrackingLevel.Strict => CoreWebView2TrackingPreventionLevel.Strict,
            _ => IsPrivate ? CoreWebView2TrackingPreventionLevel.Strict : CoreWebView2TrackingPreventionLevel.Balanced
        };
        UpdateRequestFilter(core);
    }

    async Task ReinstallShieldAsync(CoreWebView2 core)
    {
        try
        {
            if (shieldScriptId != null)
            {
                core.RemoveScriptToExecuteOnDocumentCreated(shieldScriptId);
                shieldScriptId = null;
            }
            await InstallShieldAsync(core);
        }
        catch (Exception ex) when (ex is COMException or InvalidOperationException)
        {
            CrashLog.Write("Shield update failed", ex);
        }
    }

    async Task InstallShieldAsync(CoreWebView2 core)
    {
        var config = BuildShieldConfig();
        if (config == null || Engine.ShieldTemplate == null) return;
        var script = Engine.ShieldTemplate.Replace("__FABLE_CONFIG__", config);
        shieldScriptId = await core.AddScriptToExecuteOnDocumentCreatedAsync(script);
    }

    string? BuildShieldConfig()
    {
        var settings = SettingsStore.Current;
        var trusted = settings.ShieldTrustedSites.Select(x => x.Trim().ToLowerInvariant()).Where(x => x.Length > 0).ToArray();
        if (Kind == TabKind.Super && Identity != null)
        {
            var id = Identity;
            return JsonSerializer.Serialize(new
            {
                seed = id.Seed,
                trusted,
                canvas = id.Hides("canvas") ? id.CanvasNoise : 0,
                audio = id.Hides("audio") ? id.AudioNoise : 0,
                glVendor = id.Hides("graphics") ? id.Device.GlVendor : null,
                glRenderer = id.Hides("graphics") ? id.Device.GlRenderer : null,
                screen = id.Hides("screen") ? new { width = id.Device.Width, height = id.Device.Height, availHeight = id.Device.Height - id.Device.TaskbarHeight } : null,
                cores = id.Hides("cpu") ? id.Cores : 0,
                memory = id.Hides("cpu") ? id.Memory : 0,
                webrtc = id.Hides("webrtc")
            });
        }
        var enabled = Kind == TabKind.Incognito ? settings.IncognitoShield : settings.ShieldNormalTabs;
        if (!enabled) return null;
        return JsonSerializer.Serialize(new
        {
            seed = ShieldSeed,
            trusted,
            canvas = 2,
            audio = 2,
            glVendor = settings.HideGpuModel ? "Google Inc. (Intel)" : null,
            glRenderer = settings.HideGpuModel ? "ANGLE (Intel, Intel(R) UHD Graphics Direct3D11 vs_5_0 ps_5_0, D3D11)" : null,
            screen = (object?)null,
            cores = 0,
            memory = 0,
            webrtc = false
        });
    }

    async Task ApplyEmulationAsync(CoreWebView2 core)
    {
        if (Kind == TabKind.Super && Identity != null)
        {
            var id = Identity;
            if (id.Hides("browser"))
            {
                await Cdp(core, "Emulation.setUserAgentOverride", id.UserAgentOverrideJson());
            }
            else if (id.Hides("languages"))
            {
                await Cdp(core, "Emulation.setUserAgentOverride", JsonSerializer.Serialize(new { userAgent = core.Settings.UserAgent, acceptLanguage = id.AcceptLanguage }));
            }
            if (id.Hides("languages")) await Cdp(core, "Emulation.setLocaleOverride", JsonSerializer.Serialize(new { locale = id.Languages[0] }));
            if (id.Hides("timezone") && id.TimeZone.Length > 0) await Cdp(core, "Emulation.setTimezoneOverride", JsonSerializer.Serialize(new { timezoneId = id.TimeZone }));
            if (id.Hides("cpu")) await Cdp(core, "Emulation.setHardwareConcurrencyOverride", JsonSerializer.Serialize(new { hardwareConcurrency = id.Cores }));
        }
        else if (Kind == TabKind.Incognito && SettingsStore.Current.IncognitoForceUtc)
        {
            await Cdp(core, "Emulation.setTimezoneOverride", "{\"timezoneId\":\"UTC\"}");
        }
    }

    static async Task Cdp(CoreWebView2 core, string method, string json)
    {
        try
        {
            await core.CallDevToolsProtocolMethodAsync(method, json);
        }
        catch (Exception ex) when (ex is COMException or ArgumentException or InvalidOperationException)
        {
            CrashLog.Write("DevTools protocol call failed: " + method, ex);
        }
    }

    void UpdateRequestFilter(CoreWebView2 core) => UpdateRequestFilterFor(core, core.Source);

    void OnWebResourceRequested(object? sender, CoreWebView2WebResourceRequestedEventArgs e)
    {
        var request = e.Request;
        var uri = request.Uri;
        if (!uri.StartsWith("http", StringComparison.OrdinalIgnoreCase) || UrlUtils.IsInternal(uri)) return;
        if (blocking && Engine.Blocker.ShouldBlock(uri, documentHost))
        {
            var core = Core;
            if (core != null)
            {
                e.Response = core.Environment.CreateWebResourceResponse(null, 403, "Blocked", "Content-Type: text/plain");
                RecordBlocked(uri);
                return;
            }
        }
        if (sendGpc) request.Headers.SetHeader("Sec-GPC", "1");
    }

    void RecordBlocked(string uri)
    {
        var host = UrlUtils.FastHost(uri);
        blockedHosts.TryGetValue(host, out var count);
        blockedHosts[host] = count + 1;
        BlockedCount++;
        if (IsActive) window.OnBlockedChanged(this);
    }

    void OnNavigationStarting(object? sender, CoreWebView2NavigationStartingEventArgs e)
    {
        var core = Core;
        if (core == null) return;
        var uri = e.Uri;
        var internalPage = UrlUtils.IsInternal(uri);
        core.Settings.IsWebMessageEnabled = internalPage;
        if (!internalPage && uri.StartsWith("http", StringComparison.OrdinalIgnoreCase))
        {
            var settings = SettingsStore.Current;
            var target = settings.StripTrackingParams ? UrlUtils.StripTrackingParams(uri) : uri;
            var host = UrlUtils.Host(target) ?? "";
            if (settings.HttpsOnly && UrlUtils.IsHttp(target) && !UrlUtils.IsLocalHost(host) && !HttpAllowedHosts.Contains(host))
            {
                if (e.IsRedirected && string.Equals(upgradedHost, host, StringComparison.OrdinalIgnoreCase))
                {
                    e.Cancel = true;
                    upgradedHost = null;
                    var insecure = target;
                    window.ShowInfo(this, host + " only works over an insecure connection.", new InfoAction("Continue on HTTP", () =>
                    {
                        HttpAllowedHosts.Add(host);
                        Navigate(insecure);
                    }), new InfoAction("Cancel", null));
                    return;
                }
                target = UrlUtils.UpgradeToHttps(target);
                upgradedHost = host;
            }
            if (!string.Equals(target, uri, StringComparison.Ordinal))
            {
                e.Cancel = true;
                var redirected = target;
                window.Dispatcher.InvokeAsync(() => Navigate(redirected));
                return;
            }
        }
        var newHost = UrlUtils.Host(uri);
        if (!string.Equals(newHost, documentHost, StringComparison.OrdinalIgnoreCase) || !e.IsRedirected)
        {
            blockedHosts.Clear();
            BlockedCount = 0;
        }
        documentHost = newHost;
        IsLoading = true;
        UpdateRequestFilterFor(core, uri);
        window.HideInfo(this);
        window.OnTabStateChanged(this);
    }

    void UpdateRequestFilterFor(CoreWebView2 core, string uri)
    {
        var settings = SettingsStore.Current;
        var adBlock = Kind == TabKind.Normal ? settings.AdBlock : settings.IncognitoAdBlock;
        blocking = adBlock && !UrlUtils.IsInternal(uri) && !UrlUtils.HostMatchesList(documentHost, settings.BlockerAllowedSites);
        sendGpc = settings.SendGpc;
        var wanted = blocking || sendGpc;
        if (wanted == requestFilterOn) return;
        const CoreWebView2WebResourceRequestSourceKinds sources = CoreWebView2WebResourceRequestSourceKinds.Document | CoreWebView2WebResourceRequestSourceKinds.SharedWorker | CoreWebView2WebResourceRequestSourceKinds.ServiceWorker;
        if (wanted) core.AddWebResourceRequestedFilter("*", CoreWebView2WebResourceContext.All, sources);
        else core.RemoveWebResourceRequestedFilter("*", CoreWebView2WebResourceContext.All, sources);
        requestFilterOn = wanted;
    }

    static bool IsSecureConnectionFailure(CoreWebView2WebErrorStatus status) => status is
        CoreWebView2WebErrorStatus.CertificateCommonNameIsIncorrect or
        CoreWebView2WebErrorStatus.CertificateExpired or
        CoreWebView2WebErrorStatus.ClientCertificateContainsErrors or
        CoreWebView2WebErrorStatus.CertificateRevoked or
        CoreWebView2WebErrorStatus.CertificateIsInvalid or
        CoreWebView2WebErrorStatus.ServerUnreachable or
        CoreWebView2WebErrorStatus.Timeout or
        CoreWebView2WebErrorStatus.ErrorHttpInvalidServerResponse or
        CoreWebView2WebErrorStatus.ConnectionAborted or
        CoreWebView2WebErrorStatus.ConnectionReset or
        CoreWebView2WebErrorStatus.Disconnected or
        CoreWebView2WebErrorStatus.CannotConnect or
        CoreWebView2WebErrorStatus.UnexpectedError;

    void OnNavigationCompleted(object? sender, CoreWebView2NavigationCompletedEventArgs e)
    {
        var core = Core;
        if (core == null) return;
        IsLoading = false;
        var source = core.Source;
        if (e.IsSuccess)
        {
            upgradedHost = null;
            if (Kind == TabKind.Normal && !UrlUtils.IsInternal(source)) Library.History.Record(source, core.DocumentTitle);
        }
        else if (upgradedHost != null && IsSecureConnectionFailure(e.WebErrorStatus))
        {
            var host = upgradedHost;
            upgradedHost = null;
            var insecure = UrlUtils.DowngradeToHttp(source);
            window.ShowInfo(this, host + " does not support a secure connection.", new InfoAction("Continue on HTTP", () =>
            {
                HttpAllowedHosts.Add(host);
                Navigate(insecure);
            }), new InfoAction("Cancel", null));
        }
        UpdateTitle(core);
        window.OnTabStateChanged(this);
    }

    void UpdateTitle(CoreWebView2 core)
    {
        var source = core.Source;
        string next;
        if (UrlUtils.IsNewTab(source)) next = Kind == TabKind.Normal ? "New tab" : KindLabel;
        else if (!string.IsNullOrWhiteSpace(core.DocumentTitle)) next = core.DocumentTitle;
        else next = UrlUtils.DisplayHost(source) is { Length: > 0 } host ? host : source;
        Title = next;
        if (Kind == TabKind.Normal && !UrlUtils.IsInternal(source)) Library.History.UpdateTitle(source, core.DocumentTitle);
        if (IsActive) window.UpdateWindowTitle();
    }

    async void OnFaviconChanged(object? sender, object e)
    {
        var core = Core;
        if (core == null) return;
        try
        {
            if (string.IsNullOrEmpty(core.FaviconUri))
            {
                Favicon = null;
                return;
            }
            using var stream = await core.GetFaviconAsync(CoreWebView2FaviconImageFormat.Png);
            if (stream == null)
            {
                Favicon = null;
                return;
            }
            var memory = new MemoryStream();
            await stream.CopyToAsync(memory);
            if (memory.Length == 0)
            {
                Favicon = null;
                return;
            }
            memory.Position = 0;
            var image = new BitmapImage();
            image.BeginInit();
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.DecodePixelWidth = 32;
            image.StreamSource = memory;
            image.EndInit();
            image.Freeze();
            Favicon = image;
        }
        catch (Exception)
        {
            Favicon = null;
        }
    }

    async void OnNewWindowRequested(object? sender, CoreWebView2NewWindowRequestedEventArgs e)
    {
        if (SettingsStore.Current.BlockPopups && !e.IsUserInitiated)
        {
            e.Handled = true;
            var target = e.Uri;
            window.ShowInfo(this, "Blocked a pop-up from " + (UrlUtils.DisplayHost(Url) is { Length: > 0 } h ? h : "this page") + ".", new InfoAction("Open it", () => window.OpenTab(target, Kind, true, this)), new InfoAction("Dismiss", null));
            return;
        }
        var deferral = e.GetDeferral();
        try
        {
            var tab = window.CreateTabFor(this);
            await tab.EnsureCreatedAsync();
            if (tab.Core != null)
            {
                e.NewWindow = tab.Core;
                e.Handled = true;
            }
        }
        catch (Exception ex)
        {
            CrashLog.Write("Could not open new window", ex);
        }
        finally
        {
            deferral.Complete();
        }
    }

    void OnPermissionRequested(object? sender, CoreWebView2PermissionRequestedEventArgs e)
    {
        var kind = e.PermissionKind;
        if (!PromptedPermissions.Contains(kind)) return;
        var origin = UrlUtils.Origin(e.Uri);
        var key = kind.ToString();
        e.SavesInProfile = false;
        bool? saved;
        if (IsPrivate) saved = sessionPermissions.TryGetValue(origin + "|" + key, out var remembered) ? remembered : (bool?)null;
        else saved = Library.Permissions.Get(origin, key);
        if (saved.HasValue)
        {
            e.State = saved.Value ? CoreWebView2PermissionState.Allow : CoreWebView2PermissionState.Deny;
            return;
        }
        if (IsPrivate && kind is CoreWebView2PermissionKind.Notifications or CoreWebView2PermissionKind.LocalFonts)
        {
            e.State = CoreWebView2PermissionState.Deny;
            return;
        }
        var deferral = e.GetDeferral();
        window.Dispatcher.InvokeAsync(() =>
        {
            try
            {
                var answer = UI.PermissionDialog.Ask(window, origin, kind, IsPrivate);
                e.State = answer.Allow ? CoreWebView2PermissionState.Allow : CoreWebView2PermissionState.Deny;
                if (answer.Remember)
                {
                    if (IsPrivate) sessionPermissions[origin + "|" + key] = answer.Allow;
                    else Library.Permissions.Set(origin, key, answer.Allow);
                }
            }
            finally
            {
                deferral.Complete();
            }
        });
    }

    void OnDownloadStarting(object? sender, CoreWebView2DownloadStartingEventArgs e)
    {
        var operation = e.DownloadOperation;
        var entry = new DownloadEntry
        {
            Url = operation.Uri,
            FilePath = e.ResultFilePath,
            Started = DateTime.Now,
            Total = (long)(operation.TotalBytesToReceive ?? 0)
        };
        if (SettingsStore.Current.AskWhereToSave)
        {
            var deferral = e.GetDeferral();
            window.Dispatcher.InvokeAsync(() =>
            {
                try
                {
                    var dialog = new Microsoft.Win32.SaveFileDialog
                    {
                        FileName = Path.GetFileName(e.ResultFilePath),
                        InitialDirectory = Path.GetDirectoryName(e.ResultFilePath) ?? ""
                    };
                    if (dialog.ShowDialog(window) == true)
                    {
                        e.ResultFilePath = dialog.FileName;
                        entry.FilePath = dialog.FileName;
                        Track(operation, entry);
                    }
                    else
                    {
                        e.Cancel = true;
                    }
                }
                finally
                {
                    deferral.Complete();
                }
            });
            return;
        }
        Track(operation, entry);
    }

    void Track(CoreWebView2DownloadOperation operation, DownloadEntry entry)
    {
        var store = IsPrivate ? Library.PrivateDownloads : Library.Downloads;
        store.Add(entry);
        operation.BytesReceivedChanged += (_, _) =>
        {
            entry.Received = operation.BytesReceived;
            entry.Total = (long)(operation.TotalBytesToReceive ?? 0);
            if (DateTime.UtcNow - lastDownloadUpdate < TimeSpan.FromMilliseconds(700)) return;
            lastDownloadUpdate = DateTime.UtcNow;
            store.Touch();
        };
        operation.StateChanged += (_, _) =>
        {
            entry.FilePath = operation.ResultFilePath;
            entry.Received = operation.BytesReceived;
            entry.State = operation.State switch
            {
                CoreWebView2DownloadState.Completed => "Completed",
                CoreWebView2DownloadState.Interrupted => "Interrupted",
                _ => "In progress"
            };
            store.Touch();
        };
    }

    void OnProcessFailed(object? sender, CoreWebView2ProcessFailedEventArgs e)
    {
        CrashLog.Write("Process failed: " + e.ProcessFailedKind + " reason " + e.Reason + " exit " + e.ExitCode, null);
        switch (e.ProcessFailedKind)
        {
            case CoreWebView2ProcessFailedKind.RenderProcessExited:
                window.ShowInfo(this, "This page stopped working.", new InfoAction("Reload", Reload));
                break;
            case CoreWebView2ProcessFailedKind.RenderProcessUnresponsive:
                window.ShowInfo(this, "This page is not responding.", new InfoAction("Reload", Reload), new InfoAction("Wait", null));
                break;
            case CoreWebView2ProcessFailedKind.BrowserProcessExited:
                window.Dispatcher.InvokeAsync(() =>
                {
                    Discard(true);
                    window.ShowInfo(this, "The browser engine stopped.", new InfoAction("Reload", () => _ = window.ActivateAsync(this)));
                });
                break;
        }
    }

    void OnContextMenuRequested(object? sender, CoreWebView2ContextMenuRequestedEventArgs e)
    {
        var core = Core;
        if (core == null) return;
        var target = e.ContextMenuTarget;
        var items = e.MenuItems;
        var environment = core.Environment;
        var index = 0;
        if (target.HasLinkUri)
        {
            var link = target.LinkUri;
            Insert(items, ref index, environment, "Open link in new tab", () => window.OpenTab(link, Kind, false, this));
            if (Kind != TabKind.Incognito) Insert(items, ref index, environment, "Open link in incognito tab", () => window.OpenTab(link, TabKind.Incognito, true, null));
            if (Kind != TabKind.Super) Insert(items, ref index, environment, "Open link in super incognito tab", () => window.OpenTab(link, TabKind.Super, true, null));
            items.Insert(index++, environment.CreateContextMenuItem("", null, CoreWebView2ContextMenuItemKind.Separator));
        }
        if (target.HasSelection && !target.IsEditable)
        {
            var text = target.SelectionText.Trim();
            if (text.Length > 0)
            {
                var label = text.Length > 32 ? text[..32] + "..." : text;
                Insert(items, ref index, environment, "Search for \"" + label.ReplaceLineEndings(" ") + "\"", () => window.OpenTab(UrlUtils.SearchUrl(text, SearchEngines.QueryUrl(SettingsStore.Current.SearchEngine)), Kind, true, this));
                items.Insert(index++, environment.CreateContextMenuItem("", null, CoreWebView2ContextMenuItemKind.Separator));
            }
        }
    }

    static void Insert(IList<CoreWebView2ContextMenuItem> items, ref int index, CoreWebView2Environment environment, string label, Action action)
    {
        var item = environment.CreateContextMenuItem(label, null, CoreWebView2ContextMenuItemKind.Command);
        item.CustomItemSelected += (_, _) => action();
        items.Insert(index++, item);
    }

    void OnWebMessageReceived(object? sender, CoreWebView2WebMessageReceivedEventArgs e)
    {
        if (!UrlUtils.IsInternal(e.Source)) return;
        try
        {
            using var document = JsonDocument.Parse(e.WebMessageAsJson);
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object || !root.TryGetProperty("type", out var type)) return;
            switch (type.GetString())
            {
                case "ready":
                    SendTiles();
                    break;
                case "open":
                    var text = root.TryGetProperty("text", out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() : null;
                    if (!string.IsNullOrWhiteSpace(text)) Navigate(UrlUtils.Normalize(text, SearchEngines.QueryUrl(SettingsStore.Current.SearchEngine)));
                    break;
            }
        }
        catch (JsonException)
        {
        }
    }

    void SendTiles()
    {
        var core = Core;
        if (core == null) return;
        var items = new List<object>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var b in Library.Bookmarks.Snapshot().Take(8))
        {
            if (seen.Add(UrlUtils.DisplayHost(b.Url))) items.Add(new { url = b.Url, title = b.Title, label = b.Title });
        }
        if (Kind == TabKind.Normal)
        {
            foreach (var h in Library.History.TopSites(16))
            {
                if (items.Count >= 12) break;
                var host = UrlUtils.DisplayHost(h.Url);
                if (seen.Add(host)) items.Add(new { url = h.Url, title = h.Title, label = host });
            }
        }
        core.PostWebMessageAsJson(JsonSerializer.Serialize(new { type = "tiles", items }));
    }

    public void Navigate(string target)
    {
        _ = NavigateAsync(target);
    }

    public async Task NavigateAsync(string target)
    {
        Url = target;
        try
        {
            await EnsureCreatedAsync();
            var core = Core;
            if (core == null) return;
            try
            {
                core.Navigate(target);
            }
            catch (ArgumentException)
            {
                core.Navigate(UrlUtils.SearchUrl(target, SearchEngines.QueryUrl(SettingsStore.Current.SearchEngine)));
            }
        }
        catch (Exception ex)
        {
            window.ReportEngineFailure(ex);
        }
    }

    public string NewTabPageUrl => UrlUtils.NewTabUrl + (Kind switch
    {
        TabKind.Incognito => "?mode=incognito",
        TabKind.Super => "?mode=super",
        _ => ""
    });

    public void Reload()
    {
        var core = Core;
        if (core == null)
        {
            _ = window.ActivateAsync(this);
            return;
        }
        core.Reload();
    }

    public void Show()
    {
        IsActive = true;
        LastActive = DateTime.UtcNow;
        Raise(nameof(TitleOpacity));
        var view = View;
        if (view == null) return;
        view.Visibility = Visibility.Visible;
        var core = view.CoreWebView2;
        if (core == null) return;
        try
        {
            if (core.IsSuspended) core.Resume();
            core.MemoryUsageTargetLevel = CoreWebView2MemoryUsageTargetLevel.Normal;
        }
        catch (Exception ex) when (ex is COMException or InvalidOperationException)
        {
            CrashLog.Write("Resume failed", ex);
        }
    }

    public void Hide()
    {
        IsActive = false;
        LastActive = DateTime.UtcNow;
        Raise(nameof(TitleOpacity));
        var view = View;
        if (view == null) return;
        view.Visibility = Visibility.Hidden;
        var core = view.CoreWebView2;
        if (core == null || !SettingsStore.Current.SuspendBackgroundTabs) return;
        try
        {
            core.MemoryUsageTargetLevel = CoreWebView2MemoryUsageTargetLevel.Low;
        }
        catch (Exception ex) when (ex is COMException or InvalidOperationException)
        {
            CrashLog.Write("Memory target failed", ex);
        }
    }

    public bool IsSuspended => Core?.IsSuspended ?? false;

    public async Task SuspendAsync()
    {
        var core = Core;
        if (core == null || IsActive || core.IsSuspended) return;
        try
        {
            await core.TrySuspendAsync();
        }
        catch (Exception ex) when (ex is COMException or InvalidOperationException)
        {
            CrashLog.Write("Suspend failed", ex);
        }
    }

    public bool CanDiscard => Kind == TabKind.Normal && View != null && !IsActive;

    public void Discard(bool force = false)
    {
        if (View == null) return;
        if (!force && !CanDiscard) return;
        var core = Core;
        if (core != null && !string.IsNullOrEmpty(core.Source)) Url = core.Source;
        zoom = View.ZoomFactor;
        DisposeView();
        IsDiscarded = true;
        IsLoading = false;
        IsAudible = false;
    }

    void DisposeView()
    {
        var view = View;
        if (view == null) return;
        View = null;
        creation = null;
        requestFilterOn = false;
        shieldScriptId = null;
        window.ViewHost.Children.Remove(view);
        try
        {
            view.Dispose();
        }
        catch (Exception ex) when (ex is COMException or InvalidOperationException)
        {
            CrashLog.Write("Dispose failed", ex);
        }
    }

    public void Close()
    {
        DisposeView();
    }

    public void ToggleMute()
    {
        var core = Core;
        if (core != null) core.IsMuted = !core.IsMuted;
    }

    public double Zoom
    {
        get => View?.ZoomFactor ?? zoom;
        set
        {
            zoom = Math.Clamp(value, 0.25, 5.0);
            if (View != null) View.ZoomFactor = zoom;
        }
    }

    public async Task<string> EvaluateAsync(string code)
    {
        var core = Core ?? throw new InvalidOperationException("This tab is not loaded yet.");
        string json;
        try
        {
            json = await core.CallDevToolsProtocolMethodAsync("Runtime.evaluate", JsonSerializer.Serialize(new { expression = code, awaitPromise = true, replMode = true, returnByValue = true, userGesture = true, includeCommandLineAPI = true }));
        }
        catch (COMException)
        {
            json = await core.CallDevToolsProtocolMethodAsync("Runtime.evaluate", JsonSerializer.Serialize(new { expression = code, awaitPromise = true, replMode = true, returnByValue = false, userGesture = true, includeCommandLineAPI = true }));
        }
        return FormatEvaluation(json);
    }

    static string FormatEvaluation(string json)
    {
        using var document = JsonDocument.Parse(json);
        var root = document.RootElement;
        if (root.TryGetProperty("exceptionDetails", out var details))
        {
            if (details.TryGetProperty("exception", out var exception) && exception.TryGetProperty("description", out var description)) return "Uncaught " + description.GetString();
            return "Uncaught " + (details.TryGetProperty("text", out var text) ? text.GetString() : "error");
        }
        if (!root.TryGetProperty("result", out var result)) return json;
        var type = result.TryGetProperty("type", out var t) ? t.GetString() : "";
        if (type == "undefined") return "undefined";
        if (result.TryGetProperty("value", out var value))
        {
            return value.ValueKind == JsonValueKind.String ? JsonSerializer.Serialize(value.GetString()) : JsonSerializer.Serialize(value, new JsonSerializerOptions { WriteIndented = true });
        }
        if (result.TryGetProperty("unserializableValue", out var special)) return special.GetString() ?? "";
        if (result.TryGetProperty("description", out var desc)) return desc.GetString() ?? "";
        return type ?? "";
    }

    public async Task FindAsync(string term)
    {
        var core = Core;
        if (core == null) return;
        try
        {
            if (term.Length == 0)
            {
                core.Find.Stop();
                window.OnFindChanged(this);
                return;
            }
            var options = core.Environment.CreateFindOptions();
            options.FindTerm = term;
            options.SuppressDefaultFindDialog = true;
            options.ShouldHighlightAllMatches = true;
            options.IsCaseSensitive = false;
            options.ShouldMatchWord = false;
            await core.Find.StartAsync(options);
            window.OnFindChanged(this);
        }
        catch (Exception ex) when (ex is COMException or InvalidOperationException or ArgumentException)
        {
            CrashLog.Write("Find failed", ex);
        }
    }

    public void FindNext(bool backwards)
    {
        var core = Core;
        if (core == null) return;
        try
        {
            if (backwards) core.Find.FindPrevious();
            else core.Find.FindNext();
        }
        catch (Exception ex) when (ex is COMException or InvalidOperationException)
        {
            CrashLog.Write("Find next failed", ex);
        }
    }

    public void StopFind()
    {
        try
        {
            Core?.Find.Stop();
        }
        catch (Exception ex) when (ex is COMException or InvalidOperationException)
        {
            CrashLog.Write("Find stop failed", ex);
        }
    }

    public (int Active, int Count) FindStatus
    {
        get
        {
            var core = Core;
            if (core == null) return (0, 0);
            try
            {
                return (core.Find.ActiveMatchIndex, core.Find.MatchCount);
            }
            catch (Exception ex) when (ex is COMException or InvalidOperationException)
            {
                return (0, 0);
            }
        }
    }

    public SavedTab ToSaved(bool active) => new() { Url = Url, Title = Title, Active = active, Pinned = IsPinned };
}
