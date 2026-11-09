using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using FableBrowser.Browser;
using FableBrowser.Core;
using FableBrowser.UI;

namespace FableBrowser;

public partial class MainWindow
{
    Popup suggestionPopup = null!;
    ListBox suggestionList = null!;
    bool omniboxEditing;
    bool settingOmnibox;

    void UpdateChrome()
    {
        var tab = ActiveTab;
        var core = tab?.Core;
        BackButton.IsEnabled = core?.CanGoBack ?? false;
        ForwardButton.IsEnabled = core?.CanGoForward ?? false;
        var loading = tab?.IsLoading ?? false;
        ReloadButton.Content = loading ? "\uE711" : "\uE72C";
        ReloadButton.ToolTip = loading ? "Stop (Esc)" : "Reload (F5)";
        LoadingBar.Visibility = loading ? Visibility.Visible : Visibility.Collapsed;
        LoadingBar.IsIndeterminate = loading;
        if (!omniboxEditing) SetOmniboxText(UrlUtils.DisplayUrl(tab?.Url));
        var url = tab?.Url ?? "";
        if (url.StartsWith("https://", StringComparison.OrdinalIgnoreCase) && !UrlUtils.IsInternal(url))
        {
            SecurityIcon.Text = "\uE72E";
            SecurityIcon.ToolTip = "Secure connection";
            SecurityIcon.SetResourceReference(TextBlock.ForegroundProperty, "FbSubtle");
        }
        else if (url.StartsWith("http://", StringComparison.OrdinalIgnoreCase))
        {
            SecurityIcon.Text = "\uE7BA";
            SecurityIcon.ToolTip = "Not secure";
            SecurityIcon.SetResourceReference(TextBlock.ForegroundProperty, "FbDanger");
        }
        else
        {
            SecurityIcon.Text = "\uE721";
            SecurityIcon.ToolTip = null;
            SecurityIcon.SetResourceReference(TextBlock.ForegroundProperty, "FbSubtle");
        }
        if (tab == null || tab.Kind == TabKind.Normal)
        {
            ModeBadge.Visibility = Visibility.Collapsed;
        }
        else
        {
            ModeBadge.Visibility = Visibility.Visible;
            ModeBadgeText.Text = tab.KindLabel;
            ModeBadge.SetResourceReference(Border.BackgroundProperty, tab.Kind == TabKind.Super ? "FbSuper" : "FbIncognito");
        }
        UpdateStar();
        UpdateShield();
        UpdateWindowTitle();
    }

    public void UpdateWindowTitle()
    {
        var tab = ActiveTab;
        if (tab == null)
        {
            Title = "Fable Browser";
            return;
        }
        var suffix = tab.Kind switch
        {
            TabKind.Incognito => " (Incognito)",
            TabKind.Super => " (Super incognito)",
            _ => ""
        };
        Title = tab.Title + suffix + " - Fable Browser";
    }

    void UpdateStar()
    {
        var url = ActiveTab?.Url;
        var canBookmark = !string.IsNullOrEmpty(url) && !UrlUtils.IsInternal(url);
        StarButton.IsEnabled = canBookmark;
        var marked = canBookmark && Library.Bookmarks.Contains(url);
        StarButton.Content = marked ? "\uE735" : "\uE734";
        if (marked) StarButton.SetResourceReference(ForegroundProperty, "FbAccent");
        else StarButton.SetResourceReference(ForegroundProperty, "FbText");
    }

    void UpdateShield()
    {
        var tab = ActiveTab;
        var count = tab?.BlockedCount ?? 0;
        ShieldBadge.Visibility = count > 0 ? Visibility.Visible : Visibility.Collapsed;
        ShieldCount.Text = count > 99 ? "99+" : count.ToString(System.Globalization.CultureInfo.InvariantCulture);
        var settings = SettingsStore.Current;
        var host = tab?.DocumentHost;
        var enabled = tab != null && (tab.Kind == TabKind.Normal ? settings.AdBlock : settings.IncognitoAdBlock) && !UrlUtils.HostMatchesList(host, settings.BlockerAllowedSites);
        ShieldGlyph.Text = enabled ? "\uEA18" : "\uE83D";
        ShieldGlyph.SetResourceReference(TextBlock.ForegroundProperty, enabled ? "FbAccent" : "FbSubtle");
        ShieldButton.ToolTip = enabled ? $"Shield is on. {count} blocked on this page." : "Shield is off for this site";
    }

    void SetOmniboxText(string text)
    {
        settingOmnibox = true;
        Omnibox.Text = text;
        settingOmnibox = false;
        OmniboxHint.Visibility = text.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    public void FocusOmnibox()
    {
        Omnibox.Focus();
        Keyboard.Focus(Omnibox);
        Omnibox.SelectAll();
    }

    void Omnibox_GotKeyboardFocus(object sender, KeyboardFocusChangedEventArgs e)
    {
        Omnibox.SelectAll();
        OmniboxBorder.SetResourceReference(Border.BorderBrushProperty, "FbAccent");
    }

    void Omnibox_LostKeyboardFocus(object sender, KeyboardFocusChangedEventArgs e)
    {
        OmniboxBorder.SetResourceReference(Border.BorderBrushProperty, "FbOmniboxBorder");
        CloseSuggestions();
        omniboxEditing = false;
        if (ActiveTab != null) SetOmniboxText(UrlUtils.DisplayUrl(ActiveTab.Url));
    }

    void Omnibox_PreviewMouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (Omnibox.IsKeyboardFocusWithin) return;
        e.Handled = true;
        FocusOmnibox();
    }

    void Omnibox_TextChanged(object sender, TextChangedEventArgs e)
    {
        OmniboxHint.Visibility = Omnibox.Text.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
        if (settingOmnibox || !Omnibox.IsKeyboardFocusWithin) return;
        omniboxEditing = true;
        UpdateSuggestions(Omnibox.Text);
    }

    void Omnibox_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        var key = e.Key == Key.System ? e.SystemKey : e.Key;
        switch (key)
        {
            case Key.Enter:
                var newTab = (Keyboard.Modifiers & ModifierKeys.Alt) != 0;
                if (suggestionPopup.IsOpen && suggestionList.SelectedItem is ListBoxItem { Tag: string picked }) Commit(picked, newTab);
                else Commit(Omnibox.Text, newTab);
                e.Handled = true;
                break;
            case Key.Escape:
                CloseSuggestions();
                omniboxEditing = false;
                SetOmniboxText(UrlUtils.DisplayUrl(ActiveTab?.Url));
                ActiveTab?.View?.Focus();
                e.Handled = true;
                break;
            case Key.Down:
                MoveSuggestion(1);
                e.Handled = suggestionPopup.IsOpen;
                break;
            case Key.Up:
                MoveSuggestion(-1);
                e.Handled = suggestionPopup.IsOpen;
                break;
        }
    }

    void Commit(string input, bool newTab)
    {
        var text = input.Trim();
        CloseSuggestions();
        omniboxEditing = false;
        if (text.Length == 0) return;
        var target = UrlUtils.Normalize(text, SearchEngines.QueryUrl(SettingsStore.Current.SearchEngine));
        if (newTab || ActiveTab == null)
        {
            OpenTab(target, ActiveTab?.Kind ?? TabKind.Normal, true, ActiveTab);
            return;
        }
        var tab = ActiveTab;
        SetOmniboxText(target);
        _ = tab.NavigateAsync(target);
        tab.View?.Focus();
    }

    void BuildSuggestionPopup()
    {
        suggestionList = new ListBox
        {
            BorderThickness = new Thickness(0),
            MaxHeight = 380,
            Focusable = false,
            Padding = new Thickness(4)
        };
        suggestionList.SetResourceReference(BackgroundProperty, "FbPopup");
        ScrollViewer.SetHorizontalScrollBarVisibility(suggestionList, ScrollBarVisibility.Disabled);
        var border = new Border
        {
            CornerRadius = new CornerRadius(12),
            BorderThickness = new Thickness(1),
            Padding = new Thickness(2),
            Child = suggestionList,
            Effect = new System.Windows.Media.Effects.DropShadowEffect { BlurRadius = 18, ShadowDepth = 4, Opacity = 0.35 }
        };
        border.SetResourceReference(Border.BackgroundProperty, "FbPopup");
        border.SetResourceReference(Border.BorderBrushProperty, "FbLine");
        suggestionPopup = new Popup
        {
            PlacementTarget = OmniboxBorder,
            Placement = PlacementMode.Bottom,
            VerticalOffset = 6,
            AllowsTransparency = true,
            StaysOpen = true,
            Child = border
        };
    }

    void UpdateSuggestions(string text)
    {
        var query = text.Trim();
        if (query.Length == 0)
        {
            CloseSuggestions();
            return;
        }
        suggestionList.Items.Clear();
        var engine = SettingsStore.Current.SearchEngine;
        var likelyUrl = UrlUtils.IsLikelyUrl(query);
        var primary = UrlUtils.Normalize(query, SearchEngines.QueryUrl(engine));
        suggestionList.Items.Add(SuggestionItem(likelyUrl ? "\uE774" : "\uE721", likelyUrl ? query : query, likelyUrl ? "Go to address" : "Search with " + SearchEngines.Label(engine), primary));
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase) { primary };
        foreach (var bookmark in Library.Bookmarks.Snapshot().Where(b => b.Url.Contains(query, StringComparison.OrdinalIgnoreCase) || b.Title.Contains(query, StringComparison.OrdinalIgnoreCase)).Take(3))
        {
            if (seen.Add(bookmark.Url)) suggestionList.Items.Add(SuggestionItem("\uE735", bookmark.Title, bookmark.Url, bookmark.Url));
        }
        if (ActiveTab?.Kind == TabKind.Normal || ActiveTab == null)
        {
            foreach (var entry in Library.History.Suggest(query, 6))
            {
                if (seen.Add(entry.Url)) suggestionList.Items.Add(SuggestionItem("\uE81C", string.IsNullOrWhiteSpace(entry.Title) ? entry.Url : entry.Title, entry.Url, entry.Url));
            }
        }
        suggestionList.SelectedIndex = 0;
        suggestionPopup.Width = OmniboxBorder.ActualWidth;
        suggestionPopup.IsOpen = true;
    }

    ListBoxItem SuggestionItem(string glyph, string title, string detail, string target)
    {
        var icon = new TextBlock { Text = glyph, FontFamily = (FontFamily)FindResource("IconFont"), FontSize = 13, Width = 22, VerticalAlignment = VerticalAlignment.Center };
        icon.SetResourceReference(TextBlock.ForegroundProperty, "FbSubtle");
        var main = new TextBlock { Text = title, TextTrimming = TextTrimming.CharacterEllipsis, FontSize = 13.5 };
        main.SetResourceReference(TextBlock.ForegroundProperty, "FbText");
        var sub = new TextBlock { Text = detail, TextTrimming = TextTrimming.CharacterEllipsis, FontSize = 11.5 };
        sub.SetResourceReference(TextBlock.ForegroundProperty, "FbSubtle");
        var texts = new StackPanel();
        texts.Children.Add(main);
        texts.Children.Add(sub);
        var row = new DockPanel { Margin = new Thickness(8, 5, 8, 5) };
        DockPanel.SetDock(icon, Dock.Left);
        row.Children.Add(icon);
        row.Children.Add(texts);
        var item = new ListBoxItem { Content = row, Tag = target, Focusable = false, Cursor = Cursors.Hand };
        item.PreviewMouseLeftButtonDown += (_, args) =>
        {
            args.Handled = true;
            Commit(target, (Keyboard.Modifiers & ModifierKeys.Alt) != 0);
        };
        return item;
    }

    void MoveSuggestion(int delta)
    {
        if (!suggestionPopup.IsOpen || suggestionList.Items.Count == 0) return;
        var index = Math.Clamp(suggestionList.SelectedIndex + delta, 0, suggestionList.Items.Count - 1);
        suggestionList.SelectedIndex = index;
        suggestionList.ScrollIntoView(suggestionList.SelectedItem);
        if (suggestionList.SelectedItem is ListBoxItem { Tag: string target })
        {
            settingOmnibox = true;
            Omnibox.Text = target;
            Omnibox.CaretIndex = Omnibox.Text.Length;
            settingOmnibox = false;
        }
    }

    void CloseSuggestions()
    {
        if (suggestionPopup != null) suggestionPopup.IsOpen = false;
    }

    void NewTab_Click(object sender, RoutedEventArgs e) => OpenTab(null, TabKind.Normal, true, null);

    void NewIncognito_Click(object sender, RoutedEventArgs e) => OpenTab(null, TabKind.Incognito, true, null);

    void NewSuper_Click(object sender, RoutedEventArgs e) => OpenTab(null, TabKind.Super, true, null);

    void Back_Click(object sender, RoutedEventArgs e)
    {
        if (ActiveTab?.Core is { CanGoBack: true } core) core.GoBack();
    }

    void Forward_Click(object sender, RoutedEventArgs e)
    {
        if (ActiveTab?.Core is { CanGoForward: true } core) core.GoForward();
    }

    void Reload_Click(object sender, RoutedEventArgs e)
    {
        var tab = ActiveTab;
        if (tab == null) return;
        if (tab.IsLoading) tab.Core?.Stop();
        else tab.Reload();
    }

    void Home_Click(object sender, RoutedEventArgs e) => GoHome();

    void GoHome()
    {
        var tab = ActiveTab;
        if (tab == null) return;
        var home = SettingsStore.Current.Homepage.Trim();
        _ = tab.NavigateAsync(home.Length == 0 ? tab.NewTabPageUrl : UrlUtils.Normalize(home, SearchEngines.QueryUrl(SettingsStore.Current.SearchEngine)));
    }

    void Star_Click(object sender, RoutedEventArgs e) => ToggleBookmark();

    void ToggleBookmark()
    {
        var tab = ActiveTab;
        if (tab == null || string.IsNullOrEmpty(tab.Url) || UrlUtils.IsInternal(tab.Url)) return;
        Library.Bookmarks.Toggle(tab.Url, tab.Title);
        UpdateStar();
    }

    void Downloads_Click(object sender, RoutedEventArgs e) => OpenLibrary(LibraryPage.Downloads);

    void Console_Click(object sender, RoutedEventArgs e) => ToggleConsole();

    public void OpenLibrary(LibraryPage page)
    {
        var existing = OwnedWindows.OfType<LibraryWindow>().FirstOrDefault();
        if (existing != null)
        {
            existing.Show(page);
            existing.Activate();
            return;
        }
        var window = new LibraryWindow(this);
        window.Show(page);
    }

    public void OpenSettings(int section = 0)
    {
        var existing = OwnedWindows.OfType<SettingsWindow>().FirstOrDefault();
        if (existing != null)
        {
            existing.SelectSection(section);
            existing.Activate();
            return;
        }
        var window = new SettingsWindow(this);
        window.SelectSection(section);
        window.Show();
    }

    void Menu_Click(object sender, RoutedEventArgs e)
    {
        var tab = ActiveTab;
        var menu = new ContextMenu();
        menu.Items.Add(MenuEntry("New tab", "Ctrl+T", () => OpenTab(null, TabKind.Normal, true, null)));
        menu.Items.Add(MenuEntry("New incognito tab", "Ctrl+Shift+N", () => OpenTab(null, TabKind.Incognito, true, null)));
        menu.Items.Add(MenuEntry("New super incognito tab", "Ctrl+Alt+N", () => OpenTab(null, TabKind.Super, true, null)));
        menu.Items.Add(new Separator());
        menu.Items.Add(MenuEntry("History", "Ctrl+H", () => OpenLibrary(LibraryPage.History)));
        menu.Items.Add(MenuEntry("Bookmarks", "Ctrl+Shift+O", () => OpenLibrary(LibraryPage.Bookmarks)));
        menu.Items.Add(MenuEntry("Downloads", "Ctrl+J", () => OpenLibrary(LibraryPage.Downloads)));
        menu.Items.Add(new Separator());
        var zoom = new MenuItem { Header = "Zoom (" + Math.Round((tab?.Zoom ?? 1) * 100) + "%)" };
        zoom.Items.Add(MenuEntry("Zoom in", "Ctrl+Plus", () => ChangeZoom(0.1)));
        zoom.Items.Add(MenuEntry("Zoom out", "Ctrl+Minus", () => ChangeZoom(-0.1)));
        zoom.Items.Add(MenuEntry("Reset", "Ctrl+0", () => ResetZoom()));
        menu.Items.Add(zoom);
        menu.Items.Add(MenuEntry("Find on page", "Ctrl+F", ShowFind, tab?.Core != null));
        menu.Items.Add(MenuEntry("Print", "Ctrl+P", () => tab?.Core?.ShowPrintUI(), tab?.Core != null));
        menu.Items.Add(MenuEntry(userFullscreen ? "Exit full screen" : "Full screen", "F11", ToggleUserFullscreen));
        menu.Items.Add(new Separator());
        menu.Items.Add(MenuEntry("Fable Console", "Ctrl+Shift+K", ToggleConsole));
        menu.Items.Add(MenuEntry("Developer tools", "F12", OpenDevTools, tab?.Core != null && SettingsStore.Current.DevTools));
        menu.Items.Add(MenuEntry("Browser task manager", "Shift+Esc", () => tab?.Core?.OpenTaskManagerWindow(), tab?.Core != null));
        menu.Items.Add(new Separator());
        if (tab != null)
        {
            menu.Items.Add(MenuEntry(tab.IsPinned ? "Unpin tab" : "Pin tab", null, () =>
            {
                tab.IsPinned = !tab.IsPinned;
                ScheduleSessionSave();
            }));
        }
        if (Tabs.Any(t => t.IsPrivate)) menu.Items.Add(MenuEntry("Close all private tabs", null, ClosePrivateTabs));
        menu.Items.Add(MenuEntry("Settings", "Ctrl+,", () => OpenSettings()));
        menu.Items.Add(MenuEntry("About Fable Browser", null, ShowAbout));
        menu.Items.Add(new Separator());
        menu.Items.Add(MenuEntry("Exit", "Alt+F4", Close));
        menu.PlacementTarget = MenuButton;
        menu.Placement = PlacementMode.Bottom;
        menu.IsOpen = true;
    }

    void ShowAbout()
    {
        var engine = Engine.BrowserVersion.Length > 0 ? Engine.BrowserVersion : "not installed";
        var version = typeof(MainWindow).Assembly.GetName().Version?.ToString(3) ?? "1.0.0";
        MessageBox.Show(this,
            $"Fable Browser {version} for Windows\n\nEngine: Microsoft Edge WebView2 {engine}\nFilter rules loaded: {Engine.Blocker.RuleCount}\nData folder: {AppPaths.Root}\n\nA private, lightweight browser. No telemetry is collected by Fable.",
            "About Fable Browser", MessageBoxButton.OK, MessageBoxImage.Information);
    }

    void ChangeZoom(double delta)
    {
        if (ActiveTab != null) ActiveTab.Zoom = Math.Round(ActiveTab.Zoom + delta, 2);
    }

    void ResetZoom()
    {
        if (ActiveTab != null) ActiveTab.Zoom = SettingsStore.Current.DefaultZoom / 100.0;
    }

    void OpenDevTools()
    {
        if (!SettingsStore.Current.DevTools) return;
        ActiveTab?.Core?.OpenDevToolsWindow();
    }

    void DevTools_Click(object sender, RoutedEventArgs e) => OpenDevTools();

    void Shield_Click(object sender, RoutedEventArgs e)
    {
        var tab = ActiveTab;
        if (tab == null) return;
        var settings = SettingsStore.Current;
        var host = tab.DocumentHost;
        var site = string.IsNullOrEmpty(host) ? null : UrlUtils.Site(host);
        var menu = new ContextMenu();
        var header = new MenuItem { Header = $"{tab.BlockedCount} ads and trackers blocked on this page", IsEnabled = false };
        menu.Items.Add(header);
        menu.Items.Add(new Separator());
        if (site != null && !UrlUtils.IsInternal(tab.Url))
        {
            var blockerOn = !UrlUtils.HostMatchesList(host, settings.BlockerAllowedSites);
            var blockItem = new MenuItem { Header = "Block ads and trackers on " + site, IsCheckable = true, IsChecked = blockerOn };
            blockItem.Click += (_, _) =>
            {
                SettingsStore.Update(s =>
                {
                    s.BlockerAllowedSites.RemoveAll(x => string.Equals(x, site, StringComparison.OrdinalIgnoreCase));
                    if (blockerOn) s.BlockerAllowedSites.Add(site);
                });
                tab.Reload();
            };
            menu.Items.Add(blockItem);
            var shieldActive = tab.Kind == TabKind.Super || (tab.Kind == TabKind.Incognito ? settings.IncognitoShield : settings.ShieldNormalTabs);
            if (shieldActive)
            {
                var trusted = UrlUtils.HostMatchesList(host, settings.ShieldTrustedSites);
                var shieldItem = new MenuItem { Header = "Fingerprint shield on " + site, IsCheckable = true, IsChecked = !trusted };
                shieldItem.Click += (_, _) =>
                {
                    SettingsStore.Update(s =>
                    {
                        s.ShieldTrustedSites.RemoveAll(x => string.Equals(x, site, StringComparison.OrdinalIgnoreCase));
                        if (!trusted) s.ShieldTrustedSites.Add(site);
                    });
                    tab.Reload();
                };
                menu.Items.Add(shieldItem);
            }
        }
        var top = tab.BlockedHosts.OrderByDescending(p => p.Value).Take(8).ToList();
        if (top.Count > 0)
        {
            menu.Items.Add(new Separator());
            foreach (var pair in top) menu.Items.Add(new MenuItem { Header = $"{pair.Key}  x{pair.Value}", IsEnabled = false });
        }
        menu.Items.Add(new Separator());
        if (tab.Kind == TabKind.Super && tab.Identity != null)
        {
            var identity = tab.Identity;
            menu.Items.Add(MenuEntry("Show this tab's identity", null, () =>
            {
                var lines = string.Join("\n", identity.Describe().Select(d => d.Label + ": " + d.Value));
                MessageBox.Show(this, lines, "Super incognito identity", MessageBoxButton.OK, MessageBoxImage.Information);
            }));
            menu.Items.Add(MenuEntry("New identity for this page", null, () =>
            {
                var url = tab.Url;
                OpenTab(UrlUtils.IsInternal(url) ? null : url, TabKind.Super, true, tab);
                CloseTab(tab);
            }));
        }
        menu.Items.Add(MenuEntry("Update filter lists now", null, () => _ = UpdateFiltersAsync()));
        menu.Items.Add(MenuEntry("Shield settings", null, () => OpenSettings(1)));
        menu.PlacementTarget = ShieldButton;
        menu.Placement = PlacementMode.Bottom;
        menu.IsOpen = true;
    }

    async Task UpdateFiltersAsync()
    {
        var tab = ActiveTab;
        try
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromMinutes(2));
            var count = await Engine.UpdateFiltersAsync(cts.Token);
            if (tab != null) ShowInfo(tab, count > 0 ? $"Filter lists updated. {Engine.Blocker.RuleCount} rules active." : "Filter lists could not be updated right now.");
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or IOException)
        {
            CrashLog.Write("Filter update failed", ex);
            if (tab != null) ShowInfo(tab, "Filter lists could not be downloaded: " + ex.Message);
        }
    }
}
