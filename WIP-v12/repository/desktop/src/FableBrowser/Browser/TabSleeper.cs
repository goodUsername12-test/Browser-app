using FableBrowser.Core;

namespace FableBrowser.Browser;

public static class TabSleeper
{
    public static void Run(MainWindow window)
    {
        var settings = SettingsStore.Current;
        var active = window.ActiveTab;
        var now = DateTime.UtcNow;
        var (total, available, load) = NativeMethods.Memory();
        var mode = settings.Memory;
        if (mode == MemoryMode.Auto)
        {
            var gigabytes = total / (1024.0 * 1024 * 1024);
            mode = gigabytes <= 0 ? MemoryMode.Balanced : gigabytes < 6 ? MemoryMode.Low : gigabytes < 12 ? MemoryMode.Balanced : MemoryMode.High;
        }
        var pressure = load >= 88 || (available > 0 && available < 1024UL * 1024 * 1024);
        int keep;
        TimeSpan idle;
        switch (mode)
        {
            case MemoryMode.Low:
                keep = Math.Min(settings.KeepLoadedTabs, 1);
                idle = TimeSpan.FromMinutes(Math.Min(settings.IdleTabMinutes, 3));
                break;
            case MemoryMode.High:
                keep = Math.Max(settings.KeepLoadedTabs, 8);
                idle = TimeSpan.FromMinutes(settings.IdleTabMinutes * 3);
                break;
            default:
                keep = settings.KeepLoadedTabs;
                idle = TimeSpan.FromMinutes(settings.IdleTabMinutes);
                break;
        }
        if (settings.UnloadMode == TabUnloadMode.Aggressive)
        {
            keep = 0;
            idle = TimeSpan.FromMinutes(1);
        }
        if (pressure) keep = Math.Min(keep, 1);
        var background = window.Tabs.Where(t => t != active && t.View != null).OrderByDescending(t => t.LastActive).ToList();
        var kept = 0;
        foreach (var tab in background)
        {
            var hidden = now - tab.LastActive;
            var busy = settings.KeepAudioTabsAwake && tab.IsAudible;
            if (settings.UnloadMode != TabUnloadMode.KeepAll && tab.CanDiscard && !busy && !tab.IsPinned)
            {
                var overLimit = kept >= keep && hidden > TimeSpan.FromSeconds(45);
                if (overLimit || hidden > idle)
                {
                    tab.Discard();
                    continue;
                }
            }
            kept++;
            if (settings.SuspendBackgroundTabs && !busy && hidden > TimeSpan.FromSeconds(30) && !tab.IsSuspended) _ = tab.SuspendAsync();
        }
    }

    public static void QuietBackground(MainWindow window)
    {
        var settings = SettingsStore.Current;
        foreach (var tab in window.Tabs.Where(t => t != window.ActiveTab && t.View != null).ToList())
        {
            if (settings.KeepAudioTabsAwake && tab.IsAudible) continue;
            _ = tab.SuspendAsync();
        }
    }
}
