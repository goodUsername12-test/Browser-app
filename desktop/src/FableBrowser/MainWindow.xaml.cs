using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Threading;
using FableBrowser.Browser;
using FableBrowser.Core;
using Microsoft.Web.WebView2.Core;

namespace FableBrowser;

public partial class MainWindow : Window
{
    public static readonly DependencyProperty TabWidthProperty = DependencyProperty.Register(nameof(TabWidth), typeof(double), typeof(MainWindow), new PropertyMetadata(220.0));

    readonly Stack<(string Url, TabKind Kind)> closedTabs = new();
    readonly Dictionary<BrowserTab, (string Message, InfoAction[] Actions)> infos = new();
    readonly DispatcherTimer maintenanceTimer;
    readonly DispatcherTimer sessionTimer;
    readonly DispatcherTimer lockTimer;
    readonly DispatcherTimer wipeTimer;
    readonly string[] startupArguments;
    bool closingReady;
    bool engineMissing;

    public MainWindow(string[] arguments)
    {
        InitializeComponent();
        startupArguments = arguments;
        TabList.ItemsSource = Tabs;
        Tabs.CollectionChanged += (_, _) =>
        {
            UpdateTabWidth();
            ScheduleSessionSave();
        };
        maintenanceTimer = new DispatcherTimer(DispatcherPriority.Background) { Interval = TimeSpan.FromSeconds(15) };
        maintenanceTimer.Tick += (_, _) => TabSleeper.Run(this);
        sessionTimer = new DispatcherTimer(DispatcherPriority.Background) { Interval = TimeSpan.FromSeconds(3) };
        sessionTimer.Tick += (_, _) =>
        {
            sessionTimer.Stop();
            SaveSession();
        };
        lockTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(15) };
        lockTimer.Tick += (_, _) => OnLockTimer();
        wipeTimer = new DispatcherTimer();
        wipeTimer.Tick += (_, _) => OnWipeTimer();
        Loaded += OnLoaded;
        SourceInitialized += (_, _) => NativeMethods.UseDarkTitleBar(this, App.IsDarkTheme);
        Activated += (_, _) => OnWindowActivated();
        Deactivated += (_, _) => OnWindowDeactivated();
        Closing += OnClosing;
        PreviewKeyDown += OnWindowKeyDown;
        LocationChanged += (_, _) => CloseSuggestions();
        StateChanged += (_, _) => HandleStateChanged();
        TabArea.SizeChanged += (_, _) => UpdateTabWidth();
        SettingsStore.Changed += OnSettingsChanged;
        Library.Bookmarks.Changed += () => Dispatcher.InvokeAsync(UpdateStar);
        BuildSuggestionPopup();
        LoadSnippets();
    }

    public ObservableCollection<BrowserTab> Tabs { get; } = new();

    public BrowserTab? ActiveTab { get; private set; }

    public double TabWidth
    {
        get => (double)GetValue(TabWidthProperty);
        set => SetValue(TabWidthProperty, value);
    }

    public static bool RestartRequested { get; private set; }

    public static string DefaultDownloadFolder() => Path.Combine(NativeMethods.DownloadsFolder(), "Fable");

    public void Restart()
    {
        RestartRequested = true;
        Close();
    }

    async void OnLoaded(object sender, RoutedEventArgs e)
    {
        if (Engine.BrowserVersion.Length == 0)
        {
            ShowRuntimeMissing();
            return;
        }
        if (startupArguments.Contains("--restarted", StringComparer.OrdinalIgnoreCase)) await Task.Delay(1500);
        await RunPendingClearAsync();
        var restored = SettingsStore.Current.RestoreTabs && RestoreSession();
        var opened = OpenFromArguments(startupArguments);
        if (!restored && !opened) OpenTab(null, TabKind.Normal, true, null);
        maintenanceTimer.Start();
        _ = Engine.AutoUpdateFiltersAsync();
        _ = CheckCacheAsync();
    }

    async Task RunPendingClearAsync()
    {
        var settings = SettingsStore.Current;
        if (!settings.PendingExitClear) return;
        var helper = new BrowserTab(this, TabKind.Normal);
        try
        {
            await helper.EnsureCreatedAsync();
            if (helper.Core != null)
            {
                var kinds = ExitClearKinds(settings);
                if (kinds != 0) await helper.Core.Profile.ClearBrowsingDataAsync(kinds);
            }
            settings.PendingExitClear = false;
            SettingsStore.Save();
        }
        catch (Exception ex)
        {
            CrashLog.Write("Startup clear failed", ex);
        }
        finally
        {
            helper.Close();
        }
    }

    async Task CheckCacheAsync()
    {
        var settings = SettingsStore.Current;
        if (!settings.AutoClearCache || DateTime.UtcNow - settings.LastCacheCheck < TimeSpan.FromHours(12)) return;
        try
        {
            var size = await Task.Run(Engine.CacheSize);
            if (size > settings.CacheLimitMb * 1024L * 1024L)
            {
                var core = Tabs.FirstOrDefault(t => t.Kind == TabKind.Normal && t.Core != null)?.Core;
                if (core == null)
                {
                    await Task.Delay(TimeSpan.FromSeconds(20));
                    core = Tabs.FirstOrDefault(t => t.Kind == TabKind.Normal && t.Core != null)?.Core;
                }
                if (core != null) await core.Profile.ClearBrowsingDataAsync(CoreWebView2BrowsingDataKinds.DiskCache);
            }
            settings.LastCacheCheck = DateTime.UtcNow;
            SettingsStore.Save();
        }
        catch (Exception ex)
        {
            CrashLog.Write("Cache check failed", ex);
        }
    }

    static CoreWebView2BrowsingDataKinds ExitClearKinds(Settings settings)
    {
        CoreWebView2BrowsingDataKinds kinds = 0;
        if (settings.ClearCookiesOnExit) kinds |= CoreWebView2BrowsingDataKinds.Cookies | CoreWebView2BrowsingDataKinds.AllDomStorage;
        if (settings.ClearCacheOnExit) kinds |= CoreWebView2BrowsingDataKinds.DiskCache | CoreWebView2BrowsingDataKinds.CacheStorage;
        if (settings.ClearHistoryOnExit) kinds |= CoreWebView2BrowsingDataKinds.BrowsingHistory | CoreWebView2BrowsingDataKinds.DownloadHistory;
        return kinds;
    }

    bool RestoreSession()
    {
        var saved = Library.Session.Load().Where(t => !string.IsNullOrWhiteSpace(t.Url)).ToList();
        if (saved.Count == 0) return false;
        BrowserTab? active = null;
        foreach (var item in saved)
        {
            var tab = new BrowserTab(this, TabKind.Normal)
            {
                Url = item.Url,
                Title = string.IsNullOrWhiteSpace(item.Title) ? UrlUtils.DisplayHost(item.Url) : item.Title,
                IsPinned = item.Pinned
            };
            Tabs.Add(tab);
            if (item.Active) active = tab;
        }
        _ = ActivateAsync(active ?? Tabs[Tabs.Count - 1]);
        return true;
    }

    public bool OpenFromArguments(IEnumerable<string> arguments)
    {
        var kind = TabKind.Normal;
        var opened = false;
        foreach (var raw in arguments)
        {
            var arg = raw.Trim();
            if (arg.Length == 0) continue;
            if (arg.Equals("--incognito", StringComparison.OrdinalIgnoreCase) || arg.Equals("-private", StringComparison.OrdinalIgnoreCase))
            {
                kind = TabKind.Incognito;
                continue;
            }
            if (arg.Equals("--super-incognito", StringComparison.OrdinalIgnoreCase))
            {
                kind = TabKind.Super;
                continue;
            }
            if (arg.StartsWith("-", StringComparison.Ordinal)) continue;
            string target;
            if (File.Exists(arg) || Directory.Exists(arg)) target = new Uri(Path.GetFullPath(arg)).AbsoluteUri;
            else target = UrlUtils.Normalize(arg, SearchEngines.QueryUrl(SettingsStore.Current.SearchEngine));
            OpenTab(target, kind, true, null);
            opened = true;
        }
        if (!opened && kind != TabKind.Normal)
        {
            OpenTab(null, kind, true, null);
            opened = true;
        }
        return opened;
    }

    public void HandleExternalArguments(string[] arguments)
    {
        if (!OpenFromArguments(arguments)) OpenTab(null, TabKind.Normal, true, null);
        if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
        Activate();
        NativeMethods.BringToFront(this);
    }

    public BrowserTab OpenTab(string? url, TabKind kind, bool activate, BrowserTab? opener)
    {
        var tab = new BrowserTab(this, kind) { Url = url ?? "" };
        InsertTab(tab, opener);
        if (activate) _ = ActivateAsync(tab);
        else if (url != null) _ = tab.NavigateAsync(url);
        return tab;
    }

    public BrowserTab CreateTabFor(BrowserTab opener)
    {
        var tab = new BrowserTab(this, opener.Kind, opener.ProfileName, opener.Identity);
        InsertTab(tab, opener);
        _ = ActivateAsync(tab, false);
        return tab;
    }

    void InsertTab(BrowserTab tab, BrowserTab? opener)
    {
        var index = opener == null ? -1 : Tabs.IndexOf(opener);
        if (index >= 0 && index < Tabs.Count - 1) Tabs.Insert(index + 1, tab);
        else Tabs.Add(tab);
    }

    public Task ActivateAsync(BrowserTab tab) => ActivateAsync(tab, true);

    async Task ActivateAsync(BrowserTab tab, bool load)
    {
        if (!Tabs.Contains(tab)) return;
        var previous = ActiveTab;
        if (previous != null && previous != tab)
        {
            previous.StopFind();
            previous.Hide();
        }
        ActiveTab = tab;
        tab.Show();
        CloseFind();
        UpdateChrome();
        ShowInfoFor(tab);
        ScrollTabIntoView(tab);
        if (tab.IsPrivate && privateLocked)
        {
            ShowLock();
            return;
        }
        HideLock();
        if (load && tab.View == null)
        {
            var target = string.IsNullOrEmpty(tab.Url) ? tab.NewTabPageUrl : tab.Url;
            if (UrlUtils.IsNewTab(target)) target = tab.NewTabPageUrl;
            await tab.NavigateAsync(target);
        }
        if (ActiveTab != tab) return;
        if (UrlUtils.IsNewTab(tab.Url) || string.IsNullOrEmpty(tab.Url)) FocusOmnibox();
        else tab.View?.Focus();
    }

    public void CloseTab(BrowserTab tab)
    {
        var index = Tabs.IndexOf(tab);
        if (index < 0) return;
        if (tab.Kind == TabKind.Normal && !string.IsNullOrEmpty(tab.Url) && !UrlUtils.IsInternal(tab.Url)) closedTabs.Push((tab.Url, tab.Kind));
        var wasActive = tab == ActiveTab;
        infos.Remove(tab);
        Tabs.RemoveAt(index);
        tab.Close();
        if (Tabs.Count == 0)
        {
            ActiveTab = null;
            Close();
            return;
        }
        if (wasActive)
        {
            ActiveTab = null;
            _ = ActivateAsync(Tabs[Math.Min(index, Tabs.Count - 1)]);
        }
        if (!Tabs.Any(t => t.IsPrivate))
        {
            privateLocked = false;
            wipeTimer.Stop();
        }
    }

    public void CloseActiveTab()
    {
        if (ActiveTab != null && !ActiveTab.IsPinned) CloseTab(ActiveTab);
    }

    public void ClosePrivateTabs()
    {
        foreach (var tab in Tabs.Where(t => t.IsPrivate).ToList())
        {
            CloseTab(tab);
        }
    }

    void ReopenClosedTab()
    {
        if (closedTabs.Count == 0) return;
        var (url, kind) = closedTabs.Pop();
        OpenTab(url, kind, true, null);
    }

    void CycleTabs(int direction)
    {
        if (Tabs.Count < 2 || ActiveTab == null) return;
        var index = (Tabs.IndexOf(ActiveTab) + direction + Tabs.Count) % Tabs.Count;
        _ = ActivateAsync(Tabs[index]);
    }

    void SelectTabNumber(int index)
    {
        if (Tabs.Count == 0) return;
        _ = ActivateAsync(Tabs[Math.Clamp(index, 0, Tabs.Count - 1)]);
    }

    public void OnTabStateChanged(BrowserTab tab)
    {
        if (tab == ActiveTab) UpdateChrome();
        ScheduleSessionSave();
    }

    public void OnBlockedChanged(BrowserTab tab)
    {
        if (tab == ActiveTab) UpdateShield();
    }

    public void ReportEngineFailure(Exception ex)
    {
        CrashLog.Write("Browser engine failure", ex);
        if (ex is WebView2RuntimeNotFoundException)
        {
            ShowRuntimeMissing();
            return;
        }
        if (ActiveTab != null) ShowInfo(ActiveTab, "The page could not be opened: " + ex.Message, new InfoAction("Retry", () => ActiveTab?.Reload()));
    }

    void ShowRuntimeMissing()
    {
        engineMissing = true;
        ErrorTitle.Text = "Microsoft Edge WebView2 Runtime is needed";
        ErrorText.Text = "Fable Browser uses the WebView2 engine that ships with Windows 10 and 11. It looks like it is missing or damaged on this PC. Install the Evergreen Runtime from Microsoft, then press Try again.";
        ErrorAction.Content = "Download WebView2 Runtime";
        ErrorOverlay.Visibility = Visibility.Visible;
        ViewHost.Visibility = Visibility.Hidden;
    }

    void ErrorAction_Click(object sender, RoutedEventArgs e)
    {
        if (engineMissing) OpenExternal("https://go.microsoft.com/fwlink/p/?LinkId=2124703");
    }

    void ErrorRetry_Click(object sender, RoutedEventArgs e)
    {
        var version = Engine.InstalledRuntimeVersion();
        if (version == null) return;
        engineMissing = false;
        ErrorOverlay.Visibility = Visibility.Collapsed;
        ViewHost.Visibility = Visibility.Visible;
        Engine.Initialize();
        if (Tabs.Count == 0) OpenTab(null, TabKind.Normal, true, null);
        maintenanceTimer.Start();
    }

    public static void OpenExternal(string target)
    {
        try
        {
            Process.Start(new ProcessStartInfo(target) { UseShellExecute = true });
        }
        catch (Exception ex) when (ex is Win32Exception or InvalidOperationException or FileNotFoundException)
        {
            CrashLog.Write("Could not open " + target, ex);
        }
    }

    void ScheduleSessionSave()
    {
        sessionTimer.Stop();
        sessionTimer.Start();
    }

    void SaveSession()
    {
        if (!SettingsStore.Current.RestoreTabs)
        {
            Library.Session.Replace(Array.Empty<SavedTab>());
            return;
        }
        Library.Session.Replace(Tabs.Where(t => t.Kind == TabKind.Normal && !string.IsNullOrWhiteSpace(t.Url)).Select(t => t.ToSaved(t == ActiveTab)));
    }

    void OnSettingsChanged()
    {
        Dispatcher.InvokeAsync(() =>
        {
            App.ApplyTheme();
            NativeMethods.UseDarkTitleBar(this, App.IsDarkTheme);
            foreach (var tab in Tabs) tab.ApplySettings();
            UpdateChrome();
            LoadSnippets();
        });
    }

    void OnClosing(object? sender, CancelEventArgs e)
    {
        if (closingReady) return;
        sessionTimer.Stop();
        SaveSession();
        var settings = SettingsStore.Current;
        if (settings.ClearHistoryOnExit) Library.History.Clear();
        var kinds = ExitClearKinds(settings);
        if (kinds == 0)
        {
            closingReady = true;
            return;
        }
        var core = Tabs.FirstOrDefault(t => t.Kind == TabKind.Normal && t.Core != null)?.Core;
        if (core == null)
        {
            settings.PendingExitClear = true;
            SettingsStore.Save();
            closingReady = true;
            return;
        }
        e.Cancel = true;
        closingReady = true;
        _ = ClearThenCloseAsync(core, kinds);
    }

    async Task ClearThenCloseAsync(CoreWebView2 core, CoreWebView2BrowsingDataKinds kinds)
    {
        try
        {
            await core.Profile.ClearBrowsingDataAsync(kinds).WaitAsync(TimeSpan.FromSeconds(5));
        }
        catch (Exception ex)
        {
            CrashLog.Write("Exit clear failed, will finish on next start", ex);
            SettingsStore.Current.PendingExitClear = true;
            SettingsStore.Save();
        }
        Close();
    }

    protected override void OnClosed(EventArgs e)
    {
        maintenanceTimer.Stop();
        lockTimer.Stop();
        wipeTimer.Stop();
        SettingsStore.Changed -= OnSettingsChanged;
        foreach (var tab in Tabs.ToList()) tab.Close();
        Library.FlushAll();
        base.OnClosed(e);
    }

    void Tab_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (sender is FrameworkElement { Tag: BrowserTab tab } && tab != ActiveTab) _ = ActivateAsync(tab);
    }

    void Tab_MouseUp(object sender, MouseButtonEventArgs e)
    {
        if (e.ChangedButton == MouseButton.Middle && sender is FrameworkElement { Tag: BrowserTab tab })
        {
            CloseTab(tab);
            e.Handled = true;
        }
    }

    void Tab_MouseRightButtonUp(object sender, MouseButtonEventArgs e)
    {
        if (sender is not FrameworkElement element || element.Tag is not BrowserTab tab) return;
        var menu = new ContextMenu();
        menu.Items.Add(MenuEntry("Reload", "F5", () => tab.Reload()));
        menu.Items.Add(MenuEntry("Duplicate", null, () => OpenTab(string.IsNullOrEmpty(tab.Url) ? null : tab.Url, tab.Kind, true, tab)));
        menu.Items.Add(MenuEntry(tab.IsPinned ? "Unpin" : "Pin", null, () =>
        {
            tab.IsPinned = !tab.IsPinned;
            ScheduleSessionSave();
        }));
        menu.Items.Add(MenuEntry(tab.IsMuted ? "Unmute" : "Mute", null, tab.ToggleMute, tab.Core != null));
        menu.Items.Add(MenuEntry("Put tab to sleep", null, () =>
        {
            if (tab.CanDiscard) tab.Discard();
            else _ = tab.SuspendAsync();
        }, tab != ActiveTab && tab.View != null));
        menu.Items.Add(new Separator());
        menu.Items.Add(MenuEntry("Close", "Ctrl+W", () => CloseTab(tab)));
        menu.Items.Add(MenuEntry("Close other tabs", null, () =>
        {
            foreach (var other in Tabs.Where(t => t != tab && !t.IsPinned).ToList()) CloseTab(other);
        }, Tabs.Count > 1));
        menu.Items.Add(MenuEntry("Close tabs to the right", null, () =>
        {
            var index = Tabs.IndexOf(tab);
            foreach (var other in Tabs.Skip(index + 1).Where(t => !t.IsPinned).ToList()) CloseTab(other);
        }, Tabs.IndexOf(tab) < Tabs.Count - 1));
        menu.Items.Add(MenuEntry("Reopen closed tab", "Ctrl+Shift+T", ReopenClosedTab, closedTabs.Count > 0));
        menu.PlacementTarget = element;
        menu.IsOpen = true;
        e.Handled = true;
    }

    void TabClose_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { Tag: BrowserTab tab }) CloseTab(tab);
        e.Handled = true;
    }

    void TabMute_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { Tag: BrowserTab tab }) tab.ToggleMute();
        e.Handled = true;
    }

    void TabScroller_PreviewMouseWheel(object sender, MouseWheelEventArgs e)
    {
        TabScroller.ScrollToHorizontalOffset(TabScroller.HorizontalOffset - e.Delta / 2.0);
        e.Handled = true;
    }

    void UpdateTabWidth()
    {
        var available = Math.Max(0, TabArea.ActualWidth - 40);
        TabScroller.MaxWidth = available;
        if (Tabs.Count == 0) return;
        TabWidth = Math.Clamp(Math.Floor(available / Tabs.Count) - 3, 64, 230);
    }

    void ScrollTabIntoView(BrowserTab tab)
    {
        Dispatcher.InvokeAsync(() =>
        {
            if (TabList.ItemContainerGenerator.ContainerFromItem(tab) is FrameworkElement container) container.BringIntoView();
        }, DispatcherPriority.Loaded);
    }

    public static MenuItem MenuEntry(string header, string? gesture, Action action, bool enabled = true)
    {
        var item = new MenuItem { Header = header, InputGestureText = gesture ?? "", IsEnabled = enabled };
        item.Click += (_, _) => action();
        return item;
    }
}
