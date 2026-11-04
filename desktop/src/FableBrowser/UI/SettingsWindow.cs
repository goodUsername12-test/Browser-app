using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using FableBrowser.Browser;
using FableBrowser.Core;
using Microsoft.Web.WebView2.Core;

namespace FableBrowser.UI;

public sealed class SettingsWindow : Window
{
    readonly MainWindow main;
    readonly ListBox nav = new() { BorderThickness = new Thickness(0), Margin = new Thickness(10, 14, 0, 14) };
    readonly ScrollViewer scroller = new() { VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
    readonly Border restartBanner = new() { Padding = new Thickness(18, 10, 18, 10), Visibility = Visibility.Collapsed };
    readonly List<(string Title, Action Build)> sections;
    StackPanel page = new();

    static Settings S => SettingsStore.Current;

    public SettingsWindow(MainWindow main)
    {
        this.main = main;
        Owner = main;
        Title = "Settings - Fable Browser";
        Width = 940;
        Height = 700;
        MinWidth = 640;
        MinHeight = 420;
        WindowStartupLocation = WindowStartupLocation.CenterOwner;
        Dialogs.Decorate(this);
        sections = new List<(string, Action)>
        {
            ("General", BuildGeneral),
            ("Privacy and shield", BuildPrivacy),
            ("Incognito", BuildIncognito),
            ("Super incognito", BuildSuper),
            ("Performance", BuildPerformance),
            ("Gaming", BuildGaming),
            ("Appearance", BuildAppearance),
            ("Downloads", BuildDownloads),
            ("Developer", BuildDeveloper),
            ("About", BuildAbout)
        };
        foreach (var section in sections) nav.Items.Add(new ListBoxItem { Content = section.Title, Padding = new Thickness(12, 8, 12, 8) });
        nav.SetResourceReference(BackgroundProperty, "FbWindow");
        nav.SelectionChanged += (_, _) => ShowSection(nav.SelectedIndex);
        var bannerText = new TextBlock { Text = "Some changes take effect after Fable restarts.", VerticalAlignment = VerticalAlignment.Center };
        bannerText.SetResourceReference(TextBlock.ForegroundProperty, "FbText");
        var restartButton = new Button { Content = "Restart now", Style = (Style)FindResource("AccentButton"), Margin = new Thickness(16, 0, 0, 0) };
        restartButton.Click += (_, _) =>
        {
            Close();
            main.Restart();
        };
        var bannerRow = new DockPanel();
        DockPanel.SetDock(restartButton, Dock.Right);
        bannerRow.Children.Add(restartButton);
        bannerRow.Children.Add(bannerText);
        restartBanner.Child = bannerRow;
        restartBanner.SetResourceReference(Border.BackgroundProperty, "FbInfo");
        var right = new DockPanel();
        DockPanel.SetDock(restartBanner, Dock.Top);
        right.Children.Add(restartBanner);
        right.Children.Add(scroller);
        var layout = new Grid();
        layout.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(220) });
        layout.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var divider = new Border { Width = 1, HorizontalAlignment = HorizontalAlignment.Right };
        divider.SetResourceReference(Border.BackgroundProperty, "FbLine");
        Grid.SetColumn(nav, 0);
        Grid.SetColumn(divider, 0);
        Grid.SetColumn(right, 1);
        layout.Children.Add(nav);
        layout.Children.Add(divider);
        layout.Children.Add(right);
        Content = layout;
        nav.SelectedIndex = 0;
        UpdateRestartBanner();
    }

    public void SelectSection(int index) => nav.SelectedIndex = Math.Clamp(index, 0, sections.Count - 1);

    void ShowSection(int index)
    {
        if (index < 0 || index >= sections.Count) return;
        page = new StackPanel { Margin = new Thickness(30, 22, 30, 30), MaxWidth = 760, HorizontalAlignment = HorizontalAlignment.Left };
        Heading(sections[index].Title);
        sections[index].Build();
        scroller.Content = page;
        scroller.ScrollToTop();
    }

    void Save(bool restart)
    {
        SettingsStore.Save();
        if (restart) UpdateRestartBanner();
    }

    void UpdateRestartBanner() => restartBanner.Visibility = Engine.NeedsRestart() ? Visibility.Visible : Visibility.Collapsed;

    void Heading(string text)
    {
        var block = new TextBlock { Text = text, FontSize = 24, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 0, 0, 12) };
        block.SetResourceReference(TextBlock.ForegroundProperty, "FbText");
        page.Children.Add(block);
    }

    void Group(string text)
    {
        var block = new TextBlock { Text = text, FontSize = 14, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 22, 0, 6) };
        block.SetResourceReference(TextBlock.ForegroundProperty, "FbAccent");
        page.Children.Add(block);
    }

    void Note(string text)
    {
        var block = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 4, 0, 8), FontSize = 12.5 };
        block.SetResourceReference(TextBlock.ForegroundProperty, "FbSubtle");
        page.Children.Add(block);
    }

    void AddRow(string label, string? help, FrameworkElement control, bool restart)
    {
        var grid = new Grid { Margin = new Thickness(0, 6, 0, 6) };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var texts = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 18, 0) };
        var title = new TextBlock { Text = label + (restart ? "  (restart)" : ""), FontSize = 14, TextWrapping = TextWrapping.Wrap };
        title.SetResourceReference(TextBlock.ForegroundProperty, "FbText");
        texts.Children.Add(title);
        if (!string.IsNullOrEmpty(help))
        {
            var sub = new TextBlock { Text = help, FontSize = 12, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 2, 0, 0) };
            sub.SetResourceReference(TextBlock.ForegroundProperty, "FbSubtle");
            texts.Children.Add(sub);
        }
        control.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(texts, 0);
        Grid.SetColumn(control, 1);
        grid.Children.Add(texts);
        grid.Children.Add(control);
        page.Children.Add(grid);
    }

    void Toggle(string label, string? help, Func<Settings, bool> get, Action<Settings, bool> set, bool restart = false)
    {
        var box = new CheckBox { IsChecked = get(S), MinWidth = 0 };
        box.Click += (_, _) =>
        {
            set(S, box.IsChecked == true);
            Save(restart);
        };
        AddRow(label, help, box, restart);
    }

    void Choice<T>(string label, string? help, (T Value, string Text)[] options, Func<Settings, T> get, Action<Settings, T> set, bool restart = false)
    {
        var combo = new ComboBox { MinWidth = 200 };
        foreach (var option in options) combo.Items.Add(new ComboBoxItem { Content = option.Text, Tag = option.Value });
        var current = get(S);
        combo.SelectedIndex = Array.FindIndex(options, o => EqualityComparer<T>.Default.Equals(o.Value, current));
        combo.SelectionChanged += (_, _) =>
        {
            if (combo.SelectedItem is ComboBoxItem { Tag: T value })
            {
                set(S, value);
                Save(restart);
            }
        };
        AddRow(label, help, combo, restart);
    }

    void Number(string label, string? help, int min, int max, Func<Settings, int> get, Action<Settings, int> set, bool restart = false)
    {
        var box = new TextBox { Width = 90, Text = get(S).ToString(CultureInfo.InvariantCulture), Padding = new Thickness(6, 3, 6, 3) };
        void Commit()
        {
            if (!int.TryParse(box.Text.Trim(), NumberStyles.Integer, CultureInfo.InvariantCulture, out var value)) value = get(S);
            value = Math.Clamp(value, min, max);
            box.Text = value.ToString(CultureInfo.InvariantCulture);
            if (value == get(S)) return;
            set(S, value);
            Save(restart);
        }
        box.LostKeyboardFocus += (_, _) => Commit();
        box.KeyDown += (_, e) =>
        {
            if (e.Key == Key.Enter) Commit();
        };
        AddRow(label, help, box, restart);
    }

    void Text(string label, string? help, Func<Settings, string> get, Action<Settings, string> set, bool restart = false, double width = 300)
    {
        var box = new TextBox { Width = width, Text = get(S), Padding = new Thickness(6, 3, 6, 3) };
        void Commit()
        {
            var value = box.Text.Trim();
            if (value == get(S)) return;
            set(S, value);
            Save(restart);
        }
        box.LostKeyboardFocus += (_, _) => Commit();
        box.KeyDown += (_, e) =>
        {
            if (e.Key == Key.Enter) Commit();
        };
        AddRow(label, help, box, restart);
    }

    void SiteList(string label, string? help, Func<Settings, List<string>> get)
    {
        Text(label, help, s => string.Join(", ", get(s)), (s, value) =>
        {
            var list = get(s);
            list.Clear();
            list.AddRange(value.Split(new[] { ',', ';', ' ', '\n' }, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
                .Select(x => x.ToLowerInvariant().Replace("https://", "").Replace("http://", "").TrimEnd('/'))
                .Where(x => x.Length > 0)
                .Distinct());
        }, false, 340);
    }

    void ActionRow(string label, string? help, string buttonText, Action action)
    {
        var button = new Button { Content = buttonText, Padding = new Thickness(14, 5, 14, 5), MinWidth = 120 };
        button.Click += (_, _) => action();
        AddRow(label, help, button, false);
    }

    static (T, string)[] Options<T>(params (T, string)[] items) => items;

    void BuildGeneral()
    {
        Choice("Search engine", "Used for searches typed in the address bar.", Options(
            (SearchEngineKind.Google, "Google"),
            (SearchEngineKind.DuckDuckGo, "DuckDuckGo"),
            (SearchEngineKind.Bing, "Bing"),
            (SearchEngineKind.Brave, "Brave Search"),
            (SearchEngineKind.Startpage, "Startpage"),
            (SearchEngineKind.Ecosia, "Ecosia")), s => s.SearchEngine, (s, v) => s.SearchEngine = v);
        Text("Home page", "Leave empty to use the Fable new tab page.", s => s.Homepage, (s, v) => s.Homepage = v);
        Toggle("Restore tabs from last time", "Normal tabs only. Private tabs are never saved.", s => s.RestoreTabs, (s, v) => s.RestoreTabs = v);
        Number("Default zoom (%)", null, 25, 500, s => s.DefaultZoom, (s, v) => s.DefaultZoom = v);
    }

    void BuildPrivacy()
    {
        Group("Blocking");
        Toggle("Block ads and trackers", "Uses EasyList and EasyPrivacy rules, checked inside Fable.", s => s.AdBlock, (s, v) => s.AdBlock = v);
        Toggle("Update filter lists every week", "Downloads the lists from easylist.to.", s => s.AutoUpdateFilters, (s, v) => s.AutoUpdateFilters = v);
        ActionRow("Filter rules loaded: " + Engine.Blocker.RuleCount.ToString(CultureInfo.InvariantCulture), null, "Update now", async () =>
        {
            try
            {
                using var cts = new CancellationTokenSource(TimeSpan.FromMinutes(2));
                var count = await Engine.UpdateFiltersAsync(cts.Token);
                MessageBox.Show(this, count > 0 ? $"Updated. {Engine.Blocker.RuleCount} rules are active." : "The lists could not be updated right now.", "Filter lists", MessageBoxButton.OK, MessageBoxImage.Information);
                ShowSection(nav.SelectedIndex);
            }
            catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or IOException)
            {
                MessageBox.Show(this, "Could not download the lists: " + ex.Message, "Filter lists", MessageBoxButton.OK, MessageBoxImage.Warning);
            }
        });
        Choice("Tracking prevention", "Built-in engine protection. Strict can break some logins and embedded content.", Options(
            (TrackingLevel.None, "Off"),
            (TrackingLevel.Basic, "Basic"),
            (TrackingLevel.Balanced, "Balanced"),
            (TrackingLevel.Strict, "Strict")), s => s.Tracking, (s, v) => s.Tracking = v);
        SiteList("Sites where blocking is off", "Comma separated, for example example.com", s => s.BlockerAllowedSites);
        Group("Connections");
        Toggle("HTTPS-only mode", "Upgrades every page to a secure connection and asks before using an insecure one.", s => s.HttpsOnly, (s, v) => s.HttpsOnly = v);
        Toggle("Remove tracking parameters from links", "Strips utm_, fbclid, gclid and similar tags.", s => s.StripTrackingParams, (s, v) => s.StripTrackingParams = v);
        Toggle("Send Global Privacy Control", "Tells sites you do not want your data sold or shared.", s => s.SendGpc, (s, v) => s.SendGpc = v);
        Toggle("Block pop-ups", "Pop-ups that you did not click to open are blocked.", s => s.BlockPopups, (s, v) => s.BlockPopups = v);
        Toggle("Hide local IP address from WebRTC", null, s => s.HideLocalIp, (s, v) => s.HideLocalIp = v, true);
        Choice("Secure DNS", "Encrypts DNS lookups. Falls back to normal DNS if the provider cannot be reached.", Options(
            (DohProvider.Off, "Off"),
            (DohProvider.Cloudflare, "Cloudflare"),
            (DohProvider.Quad9, "Quad9"),
            (DohProvider.Google, "Google"),
            (DohProvider.Custom, "Custom")), s => s.Doh, (s, v) => s.Doh = v, true);
        Text("Custom secure DNS address", "Must start with https://", s => s.DohCustomUrl, (s, v) => s.DohCustomUrl = v, true);
        Toggle("Disable prefetching", "Stops the engine from looking up or preloading links you have not opened.", s => s.BlockPrefetch, (s, v) => s.BlockPrefetch = v, true);
        Toggle("Reduce background connections", "Turns off background network services the engine does not need.", s => s.QuietNetwork, (s, v) => s.QuietNetwork = v, true);
        Toggle("Microsoft SmartScreen", "Checks pages against Microsoft's list of dangerous sites. This sends visited addresses to Microsoft.", s => s.SmartScreen, (s, v) => s.SmartScreen = v);
        Group("Fingerprint shield");
        Toggle("Use the fingerprint shield in normal tabs", "Adds noise to canvas and audio readouts. It is always available in private tabs.", s => s.ShieldNormalTabs, (s, v) => s.ShieldNormalTabs = v);
        Toggle("Hide graphics card model", "Reports a common graphics card instead of yours.", s => s.HideGpuModel, (s, v) => s.HideGpuModel = v);
        SiteList("Sites without the fingerprint shield", "Useful for sites that break with it, comma separated.", s => s.ShieldTrustedSites);
        Group("Saved data");
        Toggle("Offer to save passwords", "Passwords are stored by the engine in your Windows profile.", s => s.SavePasswords, (s, v) => s.SavePasswords = v);
        Toggle("Autofill forms", null, s => s.Autofill, (s, v) => s.Autofill = v);
        Toggle("Clear history when Fable closes", null, s => s.ClearHistoryOnExit, (s, v) => s.ClearHistoryOnExit = v);
        Toggle("Clear cookies and site data when Fable closes", "You will be signed out of sites.", s => s.ClearCookiesOnExit, (s, v) => s.ClearCookiesOnExit = v);
        Toggle("Clear cache when Fable closes", null, s => s.ClearCacheOnExit, (s, v) => s.ClearCacheOnExit = v);
        ActionRow("Site permissions", "Camera, microphone, location and other choices you saved.", "Reset all", () =>
        {
            Library.Permissions.Clear();
            MessageBox.Show(this, "All saved site permissions were removed.", "Site permissions", MessageBoxButton.OK, MessageBoxImage.Information);
        });
        ActionRow("Browsing data", "Cookies, cache, history and site storage of normal tabs.", "Clear now", () => _ = ClearDataAsync());
    }

    async Task ClearDataAsync()
    {
        if (MessageBox.Show(this, "Delete cookies, site data, cache and history now? You will be signed out of sites.", "Clear browsing data", MessageBoxButton.YesNo, MessageBoxImage.Warning) != MessageBoxResult.Yes) return;
        Library.History.Clear();
        var core = main.Tabs.FirstOrDefault(t => t.Kind == TabKind.Normal && t.Core != null)?.Core;
        if (core == null)
        {
            S.PendingExitClear = true;
            var cookies = S.ClearCookiesOnExit;
            var cache = S.ClearCacheOnExit;
            S.ClearCookiesOnExit = true;
            S.ClearCacheOnExit = true;
            SettingsStore.Save();
            MessageBox.Show(this, "History was cleared. The rest will be cleared next time Fable starts.", "Clear browsing data", MessageBoxButton.OK, MessageBoxImage.Information);
            S.ClearCookiesOnExit = cookies;
            S.ClearCacheOnExit = cache;
            SettingsStore.Save();
            return;
        }
        try
        {
            await core.Profile.ClearBrowsingDataAsync(CoreWebView2BrowsingDataKinds.AllProfile);
            MessageBox.Show(this, "Browsing data was cleared.", "Clear browsing data", MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex)
        {
            CrashLog.Write("Clear data failed", ex);
            MessageBox.Show(this, "Could not clear everything: " + ex.Message, "Clear browsing data", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    void BuildIncognito()
    {
        Note("Incognito tabs run in memory only. Nothing is written to disk and everything is wiped when they close.");
        Toggle("Block ads and trackers", null, s => s.IncognitoAdBlock, (s, v) => s.IncognitoAdBlock = v);
        Toggle("Separate cookies for every incognito tab", "Sites cannot link what you do in different incognito tabs.", s => s.IncognitoIsolateTabs, (s, v) => s.IncognitoIsolateTabs = v);
        Toggle("Fingerprint shield", "Adds noise to canvas and audio readouts and hides the graphics card model.", s => s.IncognitoShield, (s, v) => s.IncognitoShield = v);
        Toggle("Use UTC time zone", "Hides your real time zone from sites.", s => s.IncognitoForceUtc, (s, v) => s.IncognitoForceUtc = v);
        Toggle("Allow JavaScript", "Turning this off breaks most modern sites but removes most tracking.", s => s.IncognitoJavascript, (s, v) => s.IncognitoJavascript = v);
        Group("Lock");
        Toggle("Lock private tabs with a PIN", "Asks for the PIN after Fable has been in the background.", s => s.IncognitoLock, (s, v) =>
        {
            s.IncognitoLock = v && PinLock.HasPin(s);
            if (v && !PinLock.HasPin(s)) Dispatcher.InvokeAsync(SetPin);
        });
        ActionRow(PinLock.HasPin(S) ? "A PIN is set" : "No PIN set", null, PinLock.HasPin(S) ? "Change PIN" : "Set PIN", SetPin);
        if (PinLock.HasPin(S))
        {
            ActionRow("Remove the PIN", null, "Remove", () =>
            {
                PinLock.ClearPin(S);
                S.IncognitoLock = false;
                SettingsStore.Save();
                ShowSection(nav.SelectedIndex);
            });
        }
        Choice("Close private tabs automatically", "When Fable stays in the background this long.", Options(
            (AutoWipe.Off, "Never"),
            (AutoWipe.OneMinute, "After 1 minute"),
            (AutoWipe.FiveMinutes, "After 5 minutes"),
            (AutoWipe.FifteenMinutes, "After 15 minutes"),
            (AutoWipe.SixtyMinutes, "After 1 hour")), s => s.IncognitoAutoWipe, (s, v) => s.IncognitoAutoWipe = v);
        Group("Proxy");
        Note("When the proxy is on, incognito traffic and DNS lookups go through it with no direct fallback. Applies to new incognito tabs.");
        Toggle("Route incognito tabs through a proxy", null, s => s.IncognitoProxy, (s, v) => s.IncognitoProxy = v);
        ProxyRows(s => s.IncognitoProxyKind, (s, v) => s.IncognitoProxyKind = v, s => s.IncognitoProxyHost, (s, v) => s.IncognitoProxyHost = v, s => s.IncognitoProxyPort, (s, v) => s.IncognitoProxyPort = v);
    }

    void ProxyRows(Func<Settings, ProxyKind> getKind, Action<Settings, ProxyKind> setKind, Func<Settings, string> getHost, Action<Settings, string> setHost, Func<Settings, int> getPort, Action<Settings, int> setPort)
    {
        Choice("Proxy type", "Tor uses SOCKS5 on port 9050.", Options(
            (ProxyKind.Socks5, "SOCKS5"),
            (ProxyKind.Socks4, "SOCKS4"),
            (ProxyKind.Http, "HTTP"),
            (ProxyKind.Https, "HTTPS")), getKind, setKind);
        Text("Proxy host", null, getHost, setHost, false, 220);
        Number("Proxy port", null, 1, 65535, getPort, setPort);
    }

    void SetPin()
    {
        var pin = PinDialog.AskNewPin(this);
        if (pin == null)
        {
            ShowSection(nav.SelectedIndex);
            return;
        }
        PinLock.SetPin(S, pin);
        S.IncognitoLock = true;
        SettingsStore.Save();
        ShowSection(nav.SelectedIndex);
    }

    void BuildSuper()
    {
        Note("Every super incognito tab gets its own made-up computer: browser, screen, CPU, languages, time zone and graphics card, plus its own cookies and storage. The identity stays the same for the life of the tab.");
        Choice("Device type", "Fable runs on a Chromium engine, so identities are desktop Chrome on these systems.", Options(
            (DeviceRange.Computers, "Any computer"),
            (DeviceRange.Windows, "Windows"),
            (DeviceRange.Mac, "Mac"),
            (DeviceRange.Linux, "Linux")), s => s.SuperRange, (s, v) => s.SuperRange = v);
        Toggle("Random time zone", "When off, your real time zone is shown.", s => s.SuperRandomTimezone, (s, v) => s.SuperRandomTimezone = v);
        Toggle("Game mode", "Keeps canvas, audio and screen real so WebGL games and drawing sites work normally.", s => s.SuperGameMode, (s, v) => s.SuperGameMode = v);
        Group("What to hide");
        foreach (var key in SuperIdentity.HideKeys)
        {
            var name = key;
            Toggle(SuperIdentity.HideLabels[name], null, s => !s.SuperReveal.Contains(name), (s, v) =>
            {
                s.SuperReveal.RemoveAll(x => x == name);
                if (!v) s.SuperReveal.Add(name);
            });
        }
        Note("Changes apply to new super incognito tabs.");
        Group("Proxy");
        Toggle("Route super incognito tabs through a proxy", null, s => s.SuperProxy, (s, v) => s.SuperProxy = v);
        ProxyRows(s => s.SuperProxyKind, (s, v) => s.SuperProxyKind = v, s => s.SuperProxyHost, (s, v) => s.SuperProxyHost = v, s => s.SuperProxyPort, (s, v) => s.SuperProxyPort = v);
    }

    void BuildPerformance()
    {
        var (total, _, _) = NativeMethods.Memory();
        Note("This PC has " + (total / (1024.0 * 1024 * 1024)).ToString("0.0", CultureInfo.CurrentCulture) + " GB of memory. Sleeping tabs keep their place and reload when you open them.");
        Choice("Tab sleeping", null, Options(
            (TabUnloadMode.Smart, "Smart"),
            (TabUnloadMode.KeepAll, "Never unload tabs"),
            (TabUnloadMode.Aggressive, "Aggressive (lowest memory)")), s => s.UnloadMode, (s, v) => s.UnloadMode = v);
        Choice("Memory profile", "Auto picks a profile from the memory in this PC.", Options(
            (MemoryMode.Auto, "Auto"),
            (MemoryMode.Low, "Low memory"),
            (MemoryMode.Balanced, "Balanced"),
            (MemoryMode.High, "Plenty of memory")), s => s.Memory, (s, v) => s.Memory = v);
        Number("Background tabs kept loaded", null, 0, 50, s => s.KeepLoadedTabs, (s, v) => s.KeepLoadedTabs = v);
        Number("Unload background tabs after (minutes)", null, 1, 1440, s => s.IdleTabMinutes, (s, v) => s.IdleTabMinutes = v);
        Toggle("Freeze background tabs", "Background tabs stop using CPU after 30 seconds and resume instantly.", s => s.SuspendBackgroundTabs, (s, v) => s.SuspendBackgroundTabs = v);
        Toggle("Never sleep tabs that play audio", null, s => s.KeepAudioTabsAwake, (s, v) => s.KeepAudioTabsAwake = v);
        Toggle("Clear the cache automatically", null, s => s.AutoClearCache, (s, v) => s.AutoClearCache = v);
        Number("Cache size limit (MB)", null, 50, 10000, s => s.CacheLimitMb, (s, v) => s.CacheLimitMb = v);
        ActionRow("Cache in use: " + (Engine.CacheSize() / (1024.0 * 1024)).ToString("0", CultureInfo.CurrentCulture) + " MB", null, "Clear cache", async () =>
        {
            var core = main.Tabs.FirstOrDefault(t => t.Kind == TabKind.Normal && t.Core != null)?.Core;
            if (core == null) return;
            try
            {
                await core.Profile.ClearBrowsingDataAsync(CoreWebView2BrowsingDataKinds.DiskCache | CoreWebView2BrowsingDataKinds.CacheStorage);
            }
            catch (Exception ex)
            {
                CrashLog.Write("Cache clear failed", ex);
            }
            ShowSection(nav.SelectedIndex);
        });
    }

    void BuildGaming()
    {
        Note("Games run on the real GPU with no scripts added to normal tabs. Fable does not change how pages run their code.");
        Toggle("GPU rasterization and zero-copy", "Draws pages on the GPU for smoother frames.", s => s.GameMode, (s, v) => s.GameMode = v, true);
        Toggle("Uncapped frame rate", "Turns off VSync. Lower input delay, but more power use and possible tearing.", s => s.UncappedFps, (s, v) => s.UncappedFps = v, true);
        Toggle("Force GPU acceleration", "Uses the GPU even on drivers the engine does not trust. Turn off if pages flicker or crash.", s => s.ForceGpu, (s, v) => s.ForceGpu = v, true);
        Toggle("Freeze other tabs while a page is full screen", "Gives the game all the CPU and GPU time.", s => s.FocusGameTab, (s, v) => s.FocusGameTab = v);
        Note("Tip: press F11 for full screen. Pointer lock and gamepads work like in Edge or Chrome.");
    }

    void BuildAppearance()
    {
        Choice("Theme", null, Options(
            (UiTheme.System, "Use Windows setting"),
            (UiTheme.Light, "Light"),
            (UiTheme.Dark, "Dark")), s => s.Theme, (s, v) => s.Theme = v);
        Toggle("Force dark mode on websites", "Darkens sites that have no dark theme.", s => s.ForceDarkWeb, (s, v) => s.ForceDarkWeb = v, true);
    }

    void BuildDownloads()
    {
        Text("Download folder", "Leave empty for Downloads\\Fable.", s => s.DownloadFolder, (s, v) => s.DownloadFolder = v, false, 340);
        ActionRow("Choose a folder", null, "Browse", () =>
        {
            var dialog = new Microsoft.Win32.OpenFolderDialog { Title = "Download folder" };
            if (dialog.ShowDialog(this) != true) return;
            S.DownloadFolder = dialog.FolderName;
            SettingsStore.Save();
            ShowSection(nav.SelectedIndex);
        });
        Toggle("Ask where to save each file", null, s => s.AskWhereToSave, (s, v) => s.AskWhereToSave = v);
    }

    void BuildDeveloper()
    {
        Toggle("Developer tools", "F12 opens the full engine developer tools for the current page.", s => s.DevTools, (s, v) => s.DevTools = v);
        Note("The Fable Console (Ctrl+Shift+K) runs code in the current page the same way the developer tools console does, and lets you save snippets.");
        ActionRow("Logs", "Error logs stay on this PC and are never sent anywhere.", "Open folder", () => MainWindow.OpenExternal(AppPaths.Logs));
        ActionRow("Data folder", AppPaths.Root, "Open folder", () => MainWindow.OpenExternal(AppPaths.Root));
    }

    void BuildAbout()
    {
        var version = typeof(SettingsWindow).Assembly.GetName().Version?.ToString(3) ?? "1.0.0";
        Note("Fable Browser " + version + " for Windows");
        Note("Engine: Microsoft Edge WebView2 " + (Engine.BrowserVersion.Length > 0 ? Engine.BrowserVersion : "not found"));
        Note("Filter rules loaded: " + Engine.Blocker.RuleCount.ToString(CultureInfo.InvariantCulture));
        Note("Fable collects no telemetry. The WebView2 engine is part of Windows and follows the Windows diagnostic data setting.");
        Note(AppPaths.IsPortable ? "Portable mode: data is stored next to the program." : "Data is stored in " + AppPaths.Root);
    }
}
