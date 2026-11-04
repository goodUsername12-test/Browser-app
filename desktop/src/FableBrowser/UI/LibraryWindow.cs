using System.Diagnostics;
using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using FableBrowser.Browser;
using FableBrowser.Core;

namespace FableBrowser.UI;

public enum LibraryPage { History, Bookmarks, Downloads }

public sealed class LibraryWindow : Window
{
    readonly MainWindow main;
    readonly TabControl pages = new() { Margin = new Thickness(12) };
    readonly TextBox historySearch = new() { Padding = new Thickness(8, 5, 8, 5), Margin = new Thickness(0, 0, 0, 8) };
    readonly ListBox historyList = new();
    readonly ListBox bookmarkList = new();
    readonly ListBox downloadList = new();
    readonly Action historyChanged;
    readonly Action bookmarksChanged;
    readonly Action downloadsChanged;

    public LibraryWindow(MainWindow main)
    {
        this.main = main;
        Owner = main;
        Title = "Library - Fable Browser";
        Width = 820;
        Height = 600;
        MinWidth = 480;
        MinHeight = 320;
        WindowStartupLocation = WindowStartupLocation.CenterOwner;
        Dialogs.Decorate(this);
        pages.Items.Add(new TabItem { Header = "History", Content = BuildHistory() });
        pages.Items.Add(new TabItem { Header = "Bookmarks", Content = BuildBookmarks() });
        pages.Items.Add(new TabItem { Header = "Downloads", Content = BuildDownloads() });
        Content = pages;
        historyChanged = () => Dispatcher.InvokeAsync(RefreshHistory);
        bookmarksChanged = () => Dispatcher.InvokeAsync(RefreshBookmarks);
        downloadsChanged = () => Dispatcher.InvokeAsync(RefreshDownloads);
        Library.History.Changed += historyChanged;
        Library.Bookmarks.Changed += bookmarksChanged;
        Library.Downloads.Changed += downloadsChanged;
        Library.PrivateDownloads.Changed += downloadsChanged;
        Closed += (_, _) =>
        {
            Library.History.Changed -= historyChanged;
            Library.Bookmarks.Changed -= bookmarksChanged;
            Library.Downloads.Changed -= downloadsChanged;
            Library.PrivateDownloads.Changed -= downloadsChanged;
        };
        RefreshHistory();
        RefreshBookmarks();
        RefreshDownloads();
    }

    public void Show(LibraryPage page)
    {
        pages.SelectedIndex = (int)page;
        if (!IsVisible) base.Show();
        if (page == LibraryPage.History) historySearch.Focus();
    }

    static ListBoxItem Row(string title, string detail, string right, object tag)
    {
        var head = new TextBlock { Text = title, TextTrimming = TextTrimming.CharacterEllipsis, FontSize = 13.5 };
        head.SetResourceReference(TextBlock.ForegroundProperty, "FbText");
        var sub = new TextBlock { Text = detail, TextTrimming = TextTrimming.CharacterEllipsis, FontSize = 11.5 };
        sub.SetResourceReference(TextBlock.ForegroundProperty, "FbSubtle");
        var side = new TextBlock { Text = right, FontSize = 11.5, Margin = new Thickness(12, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
        side.SetResourceReference(TextBlock.ForegroundProperty, "FbSubtle");
        var stack = new StackPanel();
        stack.Children.Add(head);
        stack.Children.Add(sub);
        var dock = new DockPanel { Margin = new Thickness(6, 4, 6, 4), LastChildFill = true };
        DockPanel.SetDock(side, Dock.Right);
        dock.Children.Add(side);
        dock.Children.Add(stack);
        return new ListBoxItem { Content = dock, Tag = tag };
    }

    static Button MakeButton(string text, RoutedEventHandler handler)
    {
        var button = new Button { Content = text, Margin = new Thickness(0, 0, 8, 0), Padding = new Thickness(12, 5, 12, 5) };
        button.Click += handler;
        return button;
    }

    static DockPanel MakePage(UIElement top, UIElement list, params Button[] buttons)
    {
        var bar = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 10, 0, 0) };
        foreach (var b in buttons) bar.Children.Add(b);
        var dock = new DockPanel { Margin = new Thickness(4, 10, 4, 4) };
        DockPanel.SetDock(top, Dock.Top);
        DockPanel.SetDock(bar, Dock.Bottom);
        dock.Children.Add(top);
        dock.Children.Add(bar);
        dock.Children.Add(list);
        return dock;
    }

    UIElement BuildHistory()
    {
        historySearch.TextChanged += (_, _) => RefreshHistory();
        historyList.MouseDoubleClick += (_, _) => OpenSelectedHistory(false);
        historyList.SelectionMode = SelectionMode.Extended;
        historyList.KeyDown += (_, e) =>
        {
            if (e.Key == Key.Delete) DeleteSelectedHistory();
            if (e.Key == Key.Enter) OpenSelectedHistory(false);
        };
        var label = new TextBlock { Text = "Search history", Margin = new Thickness(0, 0, 0, 4) };
        label.SetResourceReference(TextBlock.ForegroundProperty, "FbSubtle");
        var top = new StackPanel();
        top.Children.Add(label);
        top.Children.Add(historySearch);
        return MakePage(top, historyList,
            MakeButton("Open", (_, _) => OpenSelectedHistory(false)),
            MakeButton("Open in new tab", (_, _) => OpenSelectedHistory(true)),
            MakeButton("Delete", (_, _) => DeleteSelectedHistory()),
            MakeButton("Clear all history", (_, _) =>
            {
                if (MessageBox.Show(this, "Delete all browsing history from Fable?", "Clear history", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
                Library.History.Clear();
                foreach (var tab in main.Tabs.Where(t => t.Core != null && t.Kind == TabKind.Normal).Take(1))
                {
                    _ = tab.Core!.Profile.ClearBrowsingDataAsync(Microsoft.Web.WebView2.Core.CoreWebView2BrowsingDataKinds.BrowsingHistory);
                }
            }));
    }

    void RefreshHistory()
    {
        historyList.Items.Clear();
        foreach (var entry in Library.History.Search(historySearch.Text, 600))
        {
            var title = string.IsNullOrWhiteSpace(entry.Title) ? entry.Url : entry.Title;
            historyList.Items.Add(Row(title, entry.Url, entry.LastVisit.ToString("g", CultureInfo.CurrentCulture), entry));
        }
    }

    void OpenSelectedHistory(bool newTab)
    {
        var selected = historyList.SelectedItems.OfType<ListBoxItem>().Select(i => i.Tag).OfType<HistoryEntry>().ToList();
        OpenUrls(selected.Select(e => e.Url), newTab);
    }

    void DeleteSelectedHistory()
    {
        foreach (var entry in historyList.SelectedItems.OfType<ListBoxItem>().Select(i => i.Tag).OfType<HistoryEntry>().ToList())
        {
            Library.History.Remove(entry.Url);
        }
    }

    void OpenUrls(IEnumerable<string> urls, bool newTab)
    {
        var list = urls.ToList();
        if (list.Count == 0) return;
        if (!newTab && list.Count == 1 && main.ActiveTab != null && main.ActiveTab.Kind == TabKind.Normal)
        {
            _ = main.ActiveTab.NavigateAsync(list[0]);
        }
        else
        {
            foreach (var url in list) main.OpenTab(url, TabKind.Normal, true, null);
        }
        main.Activate();
    }

    UIElement BuildBookmarks()
    {
        bookmarkList.SelectionMode = SelectionMode.Extended;
        bookmarkList.MouseDoubleClick += (_, _) => OpenSelectedBookmarks(false);
        bookmarkList.KeyDown += (_, e) =>
        {
            if (e.Key == Key.Delete) DeleteSelectedBookmarks();
            if (e.Key == Key.Enter) OpenSelectedBookmarks(false);
        };
        var hint = new TextBlock { Text = "Press Ctrl+D on any page to bookmark it.", Margin = new Thickness(0, 0, 0, 8) };
        hint.SetResourceReference(TextBlock.ForegroundProperty, "FbSubtle");
        return MakePage(hint, bookmarkList,
            MakeButton("Open", (_, _) => OpenSelectedBookmarks(false)),
            MakeButton("Open in new tab", (_, _) => OpenSelectedBookmarks(true)),
            MakeButton("Rename", (_, _) => RenameBookmark()),
            MakeButton("Delete", (_, _) => DeleteSelectedBookmarks()),
            MakeButton("Import HTML", (_, _) => ImportBookmarks()),
            MakeButton("Export HTML", (_, _) => ExportBookmarks()));
    }

    void RefreshBookmarks()
    {
        bookmarkList.Items.Clear();
        foreach (var bookmark in Library.Bookmarks.Snapshot())
        {
            bookmarkList.Items.Add(Row(bookmark.Title, bookmark.Url, bookmark.Added.ToString("d", CultureInfo.CurrentCulture), bookmark));
        }
    }

    List<Bookmark> SelectedBookmarks() => bookmarkList.SelectedItems.OfType<ListBoxItem>().Select(i => i.Tag).OfType<Bookmark>().ToList();

    void OpenSelectedBookmarks(bool newTab) => OpenUrls(SelectedBookmarks().Select(b => b.Url), newTab);

    void DeleteSelectedBookmarks()
    {
        foreach (var bookmark in SelectedBookmarks()) Library.Bookmarks.Remove(bookmark.Url);
    }

    void RenameBookmark()
    {
        var bookmark = SelectedBookmarks().FirstOrDefault();
        if (bookmark == null) return;
        var name = TextPrompt.Ask(this, "Rename bookmark", "Name:", bookmark.Title);
        if (string.IsNullOrWhiteSpace(name)) return;
        Library.Bookmarks.Remove(bookmark.Url);
        Library.Bookmarks.Add(bookmark.Url, name.Trim());
    }

    void ImportBookmarks()
    {
        var dialog = new Microsoft.Win32.OpenFileDialog { Filter = "Bookmark files (*.html;*.htm)|*.html;*.htm|All files (*.*)|*.*" };
        if (dialog.ShowDialog(this) != true) return;
        try
        {
            var count = Library.Bookmarks.ImportHtml(File.ReadAllText(dialog.FileName));
            MessageBox.Show(this, $"Imported {count} bookmarks.", "Import", MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            MessageBox.Show(this, "Could not read the file: " + ex.Message, "Import", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    void ExportBookmarks()
    {
        var dialog = new Microsoft.Win32.SaveFileDialog { Filter = "Bookmark file (*.html)|*.html", FileName = "fable-bookmarks.html" };
        if (dialog.ShowDialog(this) != true) return;
        try
        {
            File.WriteAllText(dialog.FileName, Library.Bookmarks.ExportHtml());
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            MessageBox.Show(this, "Could not save the file: " + ex.Message, "Export", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    UIElement BuildDownloads()
    {
        downloadList.MouseDoubleClick += (_, _) => OpenDownload();
        var hint = new TextBlock { Text = "Downloads from private tabs are listed only until Fable closes.", Margin = new Thickness(0, 0, 0, 8) };
        hint.SetResourceReference(TextBlock.ForegroundProperty, "FbSubtle");
        return MakePage(hint, downloadList,
            MakeButton("Open file", (_, _) => OpenDownload()),
            MakeButton("Show in folder", (_, _) => ShowDownloadInFolder()),
            MakeButton("Remove from list", (_, _) =>
            {
                if (SelectedDownload() is { } entry)
                {
                    Library.Downloads.Remove(entry.Id);
                    Library.PrivateDownloads.Remove(entry.Id);
                }
            }),
            MakeButton("Clear finished", (_, _) =>
            {
                Library.Downloads.Clear();
                Library.PrivateDownloads.Clear();
            }),
            MakeButton("Open downloads folder", (_, _) =>
            {
                var folder = SettingsStore.Current.DownloadFolder.Length > 0 ? SettingsStore.Current.DownloadFolder : MainWindow.DefaultDownloadFolder();
                Directory.CreateDirectory(folder);
                MainWindow.OpenExternal(folder);
            }));
    }

    static string Size(long bytes)
    {
        if (bytes <= 0) return "";
        string[] units = { "B", "KB", "MB", "GB", "TB" };
        double value = bytes;
        var unit = 0;
        while (value >= 1024 && unit < units.Length - 1)
        {
            value /= 1024;
            unit++;
        }
        return value.ToString(unit == 0 ? "0" : "0.0", CultureInfo.CurrentCulture) + " " + units[unit];
    }

    void RefreshDownloads()
    {
        var selectedId = SelectedDownload()?.Id;
        downloadList.Items.Clear();
        foreach (var entry in Library.PrivateDownloads.Snapshot().Concat(Library.Downloads.Snapshot()).OrderByDescending(d => d.Started))
        {
            var name = Path.GetFileName(entry.FilePath);
            if (string.IsNullOrEmpty(name)) name = entry.Url;
            var progress = entry.State == "In progress" && entry.Total > 0 ? $"{entry.Received * 100 / entry.Total}%  " : "";
            var size = Size(entry.Total > 0 ? entry.Total : entry.Received);
            var item = Row(name, entry.Url, progress + entry.State + (size.Length > 0 ? "  " + size : ""), entry);
            downloadList.Items.Add(item);
            if (entry.Id == selectedId) item.IsSelected = true;
        }
    }

    DownloadEntry? SelectedDownload() => (downloadList.SelectedItem as ListBoxItem)?.Tag as DownloadEntry;

    void OpenDownload()
    {
        var entry = SelectedDownload();
        if (entry == null || entry.State != "Completed" || !File.Exists(entry.FilePath)) return;
        MainWindow.OpenExternal(entry.FilePath);
    }

    void ShowDownloadInFolder()
    {
        var entry = SelectedDownload();
        if (entry == null) return;
        try
        {
            if (File.Exists(entry.FilePath)) Process.Start(new ProcessStartInfo("explorer.exe", "/select,\"" + entry.FilePath + "\"") { UseShellExecute = false });
            else if (Path.GetDirectoryName(entry.FilePath) is { } folder && Directory.Exists(folder)) MainWindow.OpenExternal(folder);
        }
        catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            CrashLog.Write("Could not show download", ex);
        }
    }
}
