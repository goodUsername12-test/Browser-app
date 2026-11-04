using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using FableBrowser.Browser;
using FableBrowser.Core;
using FableBrowser.UI;

namespace FableBrowser;

public partial class MainWindow
{
    bool pageFullscreen;
    bool userFullscreen;
    bool fullscreenApplied;
    WindowState savedState;
    WindowStyle savedStyle;
    ResizeMode savedResize;
    bool privateLocked;
    double consoleHeight = 260;

    public void ShowInfo(BrowserTab tab, string message, params InfoAction[] actions)
    {
        infos[tab] = (message, actions);
        if (tab == ActiveTab) ShowInfoFor(tab);
    }

    public void HideInfo(BrowserTab tab)
    {
        if (!infos.Remove(tab)) return;
        if (tab == ActiveTab) InfoBar.Visibility = Visibility.Collapsed;
    }

    void ShowInfoFor(BrowserTab tab)
    {
        if (!infos.TryGetValue(tab, out var info) || fullscreenApplied)
        {
            InfoBar.Visibility = Visibility.Collapsed;
            return;
        }
        InfoText.Text = info.Message;
        InfoText.ToolTip = info.Message;
        InfoActions.Children.Clear();
        foreach (var action in info.Actions)
        {
            var button = new Button { Content = action.Label, Style = (Style)FindResource("TextButton") };
            var run = action.Run;
            button.Click += (_, _) =>
            {
                HideInfo(tab);
                run?.Invoke();
            };
            InfoActions.Children.Add(button);
        }
        InfoBar.Visibility = Visibility.Visible;
    }

    void InfoClose_Click(object sender, RoutedEventArgs e)
    {
        if (ActiveTab != null) HideInfo(ActiveTab);
        InfoBar.Visibility = Visibility.Collapsed;
    }

    void ShowFind()
    {
        if (ActiveTab?.Core == null) return;
        FindBar.Visibility = Visibility.Visible;
        FindBox.Focus();
        Keyboard.Focus(FindBox);
        FindBox.SelectAll();
        if (FindBox.Text.Length > 0) _ = ActiveTab.FindAsync(FindBox.Text);
    }

    void CloseFind()
    {
        if (FindBar.Visibility != Visibility.Visible) return;
        FindBar.Visibility = Visibility.Collapsed;
        FindStatus.Text = "";
        ActiveTab?.StopFind();
    }

    public void OnFindChanged(BrowserTab tab)
    {
        if (tab != ActiveTab || FindBar.Visibility != Visibility.Visible) return;
        var (active, count) = tab.FindStatus;
        FindStatus.Text = FindBox.Text.Length == 0 ? "" : count == 0 ? "No matches" : $"{Math.Max(1, active)} of {count}";
    }

    void FindBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (ActiveTab != null) _ = ActiveTab.FindAsync(FindBox.Text);
    }

    void FindBox_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            ActiveTab?.FindNext((Keyboard.Modifiers & ModifierKeys.Shift) != 0);
            e.Handled = true;
        }
        else if (e.Key == Key.Escape)
        {
            CloseFind();
            ActiveTab?.View?.Focus();
            e.Handled = true;
        }
    }

    void FindNext_Click(object sender, RoutedEventArgs e) => ActiveTab?.FindNext(false);

    void FindPrevious_Click(object sender, RoutedEventArgs e) => ActiveTab?.FindNext(true);

    void FindClose_Click(object sender, RoutedEventArgs e)
    {
        CloseFind();
        ActiveTab?.View?.Focus();
    }

    void ToggleConsole()
    {
        var open = ConsolePanel.Visibility != Visibility.Visible;
        if (!open && ConsoleRow.ActualHeight > 60) consoleHeight = ConsoleRow.ActualHeight;
        ConsolePanel.Visibility = open ? Visibility.Visible : Visibility.Collapsed;
        ConsoleSplitter.Visibility = open ? Visibility.Visible : Visibility.Collapsed;
        ConsoleRow.Height = open ? new GridLength(consoleHeight) : new GridLength(0);
        if (open)
        {
            ConsoleInput.Focus();
            Keyboard.Focus(ConsoleInput);
        }
        else
        {
            ActiveTab?.View?.Focus();
        }
    }

    void ConsoleClose_Click(object sender, RoutedEventArgs e)
    {
        if (ConsolePanel.Visibility == Visibility.Visible) ToggleConsole();
    }

    void ConsoleClear_Click(object sender, RoutedEventArgs e) => ConsoleOutput.Clear();

    void ConsoleRun_Click(object sender, RoutedEventArgs e) => _ = RunConsoleAsync();

    void ConsoleInput_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter && (Keyboard.Modifiers & ModifierKeys.Control) != 0)
        {
            e.Handled = true;
            _ = RunConsoleAsync();
        }
    }

    async Task RunConsoleAsync()
    {
        var code = ConsoleInput.SelectionLength > 0 ? ConsoleInput.SelectedText : ConsoleInput.Text;
        if (string.IsNullOrWhiteSpace(code)) return;
        var tab = ActiveTab;
        var firstLine = code.Trim().Split('\n')[0].TrimEnd('\r');
        if (firstLine.Length > 80) firstLine = firstLine[..80] + "...";
        AppendConsole("> " + firstLine);
        if (tab?.Core == null)
        {
            AppendConsole("The current tab is not loaded.");
            return;
        }
        if (UrlUtils.IsInternal(tab.Url))
        {
            AppendConsole("Open a website first. Scripts do not run on Fable's own pages.");
            return;
        }
        try
        {
            var result = await tab.EvaluateAsync(code);
            AppendConsole("< " + result);
        }
        catch (Exception ex)
        {
            AppendConsole("Error: " + ex.Message);
        }
    }

    void AppendConsole(string line)
    {
        if (ConsoleOutput.Text.Length > 200000) ConsoleOutput.Text = ConsoleOutput.Text[^100000..];
        ConsoleOutput.AppendText(line + Environment.NewLine);
        ConsoleOutput.ScrollToEnd();
    }

    void LoadSnippets()
    {
        var selected = (SnippetBox.SelectedItem as Snippet)?.Name;
        SnippetBox.ItemsSource = SettingsStore.Current.Snippets.ToList();
        if (selected != null) SnippetBox.SelectedItem = SettingsStore.Current.Snippets.FirstOrDefault(s => s.Name == selected);
    }

    void SnippetBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (SnippetBox.SelectedItem is Snippet snippet) ConsoleInput.Text = snippet.Code;
    }

    void SnippetSave_Click(object sender, RoutedEventArgs e)
    {
        var code = ConsoleInput.Text;
        if (string.IsNullOrWhiteSpace(code)) return;
        var current = (SnippetBox.SelectedItem as Snippet)?.Name ?? "";
        var name = TextPrompt.Ask(this, "Save snippet", "Name for this snippet:", current);
        if (string.IsNullOrWhiteSpace(name)) return;
        name = name.Trim();
        SettingsStore.Update(s =>
        {
            s.Snippets.RemoveAll(x => x.Name == name);
            s.Snippets.Add(new Snippet { Name = name, Code = code });
            s.Snippets.Sort((a, b) => string.Compare(a.Name, b.Name, StringComparison.OrdinalIgnoreCase));
        });
        LoadSnippets();
        SnippetBox.SelectedItem = SettingsStore.Current.Snippets.FirstOrDefault(s => s.Name == name);
    }

    void SnippetDelete_Click(object sender, RoutedEventArgs e)
    {
        if (SnippetBox.SelectedItem is not Snippet snippet) return;
        SettingsStore.Update(s => s.Snippets.RemoveAll(x => x.Name == snippet.Name));
        SnippetBox.SelectedItem = null;
        LoadSnippets();
    }

    public void SetPageFullscreen(BrowserTab tab, bool on)
    {
        if (tab != ActiveTab && on) return;
        tab.WasFullscreen = on;
        pageFullscreen = on;
        ApplyFullscreen();
        if (on && SettingsStore.Current.FocusGameTab) TabSleeper.QuietBackground(this);
    }

    void ToggleUserFullscreen()
    {
        userFullscreen = !userFullscreen;
        ApplyFullscreen();
    }

    void ApplyFullscreen()
    {
        var want = pageFullscreen || userFullscreen;
        if (want == fullscreenApplied) return;
        fullscreenApplied = want;
        var chrome = want ? Visibility.Collapsed : Visibility.Visible;
        TabStrip.Visibility = chrome;
        Toolbar.Visibility = chrome;
        if (want)
        {
            InfoBar.Visibility = Visibility.Collapsed;
            CloseFind();
            CloseSuggestions();
            savedState = WindowState;
            savedStyle = WindowStyle;
            savedResize = ResizeMode;
            WindowStyle = WindowStyle.None;
            ResizeMode = ResizeMode.NoResize;
            if (WindowState == WindowState.Maximized) WindowState = WindowState.Normal;
            WindowState = WindowState.Maximized;
        }
        else
        {
            WindowStyle = savedStyle;
            ResizeMode = savedResize;
            WindowState = savedState == WindowState.Minimized ? WindowState.Normal : savedState;
            if (ActiveTab != null) ShowInfoFor(ActiveTab);
        }
        ActiveTab?.View?.Focus();
    }

    void HandleStateChanged()
    {
        CloseSuggestions();
        if (WindowState == WindowState.Minimized) LockIfNeeded();
    }

    bool LockConfigured => SettingsStore.Current.IncognitoLock && PinLock.HasPin(SettingsStore.Current);

    void LockIfNeeded()
    {
        if (!LockConfigured || !Tabs.Any(t => t.IsPrivate)) return;
        privateLocked = true;
        if (ActiveTab?.IsPrivate == true) ShowLock();
    }

    void OnWindowDeactivated()
    {
        CloseSuggestions();
        if (LockConfigured && Tabs.Any(t => t.IsPrivate)) lockTimer.Start();
        var wipe = SettingsStore.Current.IncognitoAutoWipe;
        if (wipe != AutoWipe.Off && Tabs.Any(t => t.IsPrivate))
        {
            wipeTimer.Interval = TimeSpan.FromMinutes((int)wipe);
            wipeTimer.Start();
        }
    }

    void OnWindowActivated()
    {
        lockTimer.Stop();
        wipeTimer.Stop();
        if (privateLocked && ActiveTab?.IsPrivate == true) ShowLock();
    }

    static bool AnyAppWindowActive() => Application.Current.Windows.OfType<Window>().Any(w => w.IsActive);

    void OnLockTimer()
    {
        lockTimer.Stop();
        if (AnyAppWindowActive()) return;
        LockIfNeeded();
    }

    void OnWipeTimer()
    {
        wipeTimer.Stop();
        if (AnyAppWindowActive()) return;
        ClosePrivateTabs();
    }

    void ShowLock()
    {
        LockError.Text = "";
        LockPin.Clear();
        ViewHost.Visibility = Visibility.Hidden;
        LockOverlay.Visibility = Visibility.Visible;
        LockPin.Focus();
        Keyboard.Focus(LockPin);
    }

    void HideLock()
    {
        if (LockOverlay.Visibility != Visibility.Visible) return;
        LockOverlay.Visibility = Visibility.Collapsed;
        if (!engineMissing) ViewHost.Visibility = Visibility.Visible;
    }

    void TryUnlock()
    {
        if (PinLock.Verify(SettingsStore.Current, LockPin.Password))
        {
            privateLocked = false;
            HideLock();
            var tab = ActiveTab;
            if (tab != null && tab.View == null) _ = ActivateAsync(tab);
            else tab?.View?.Focus();
            return;
        }
        LockError.Text = "Wrong PIN";
        LockPin.Clear();
    }

    void LockPin_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter) return;
        e.Handled = true;
        TryUnlock();
    }

    void Unlock_Click(object sender, RoutedEventArgs e) => TryUnlock();

    void LockWipe_Click(object sender, RoutedEventArgs e)
    {
        privateLocked = false;
        HideLock();
        ClosePrivateTabs();
    }

    void OnWindowKeyDown(object sender, KeyEventArgs e)
    {
        var key = e.Key == Key.System ? e.SystemKey : e.Key;
        var modifiers = Keyboard.Modifiers;
        var ctrl = (modifiers & ModifierKeys.Control) != 0;
        var shift = (modifiers & ModifierKeys.Shift) != 0;
        var alt = (modifiers & ModifierKeys.Alt) != 0;
        var handled = true;
        if (ctrl && !alt && !shift && key == Key.T) OpenTab(null, TabKind.Normal, true, null);
        else if (ctrl && shift && key == Key.T) ReopenClosedTab();
        else if (ctrl && shift && !alt && key == Key.N) OpenTab(null, TabKind.Incognito, true, null);
        else if (ctrl && alt && key == Key.N) OpenTab(null, TabKind.Super, true, null);
        else if (ctrl && !alt && (key == Key.W || key == Key.F4)) CloseActiveTab();
        else if (ctrl && key == Key.Tab) CycleTabs(shift ? -1 : 1);
        else if (ctrl && key == Key.PageDown) CycleTabs(1);
        else if (ctrl && key == Key.PageUp) CycleTabs(-1);
        else if (ctrl && !shift && !alt && key >= Key.D1 && key <= Key.D8) SelectTabNumber(key - Key.D1);
        else if (ctrl && !shift && !alt && key == Key.D9) SelectTabNumber(Tabs.Count - 1);
        else if ((ctrl && key == Key.L) || (alt && !ctrl && key == Key.D) || key == Key.F6) FocusOmnibox();
        else if (ctrl && !shift && key == Key.F) ShowFind();
        else if (key == Key.F3) ActiveTab?.FindNext(shift);
        else if (ctrl && !shift && key == Key.D) ToggleBookmark();
        else if (ctrl && !shift && key == Key.H) OpenLibrary(LibraryPage.History);
        else if (ctrl && !shift && key == Key.J) OpenLibrary(LibraryPage.Downloads);
        else if (ctrl && shift && key == Key.O) OpenLibrary(LibraryPage.Bookmarks);
        else if (key == Key.F11) ToggleUserFullscreen();
        else if (alt && !ctrl && key == Key.Home) GoHome();
        else if (alt && !ctrl && key == Key.Left) Back_Click(this, new RoutedEventArgs());
        else if (alt && !ctrl && key == Key.Right) Forward_Click(this, new RoutedEventArgs());
        else if (key == Key.F5 || (ctrl && !shift && key == Key.R)) ActiveTab?.Reload();
        else if (ctrl && shift && key == Key.K) ToggleConsole();
        else if (key == Key.F12 || (ctrl && shift && key == Key.I)) OpenDevTools();
        else if (ctrl && key == Key.OemComma) OpenSettings();
        else if (ctrl && !shift && key == Key.P) ActiveTab?.Core?.ShowPrintUI();
        else if (ctrl && (key == Key.OemPlus || key == Key.Add)) ChangeZoom(0.1);
        else if (ctrl && (key == Key.OemMinus || key == Key.Subtract)) ChangeZoom(-0.1);
        else if (ctrl && (key == Key.D0 || key == Key.NumPad0)) ResetZoom();
        else if (shift && !ctrl && key == Key.Escape) ActiveTab?.Core?.OpenTaskManagerWindow();
        else handled = false;
        if (handled) e.Handled = true;
    }
}
