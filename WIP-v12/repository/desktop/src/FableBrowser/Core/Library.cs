using System.Net;
using System.Text;
using System.Text.RegularExpressions;

namespace FableBrowser.Core;

public sealed class HistoryEntry
{
    public string Url { get; set; } = "";
    public string Title { get; set; } = "";
    public DateTime LastVisit { get; set; }
    public int Visits { get; set; }
}

public sealed class Bookmark
{
    public string Url { get; set; } = "";
    public string Title { get; set; } = "";
    public DateTime Added { get; set; }
}

public sealed class DownloadEntry
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Url { get; set; } = "";
    public string FilePath { get; set; } = "";
    public DateTime Started { get; set; }
    public long Received { get; set; }
    public long Total { get; set; }
    public string State { get; set; } = "In progress";
}

public sealed class SitePermission
{
    public string Origin { get; set; } = "";
    public string Kind { get; set; } = "";
    public bool Allowed { get; set; }
}

public sealed class SavedTab
{
    public string Url { get; set; } = "";
    public string Title { get; set; } = "";
    public bool Active { get; set; }
    public bool Pinned { get; set; }
}

public class JsonListStore<T> where T : class
{
    readonly string path;
    readonly object gate = new();
    readonly bool persist;
    Timer? timer;
    bool dirty;

    protected JsonListStore(string fileName, bool persist = true)
    {
        path = Path.Combine(AppPaths.Data, fileName);
        this.persist = persist;
        Items = persist ? JsonStore.Read<List<T>>(path) ?? new List<T>() : new List<T>();
    }

    protected List<T> Items { get; }

    public event Action? Changed;

    public List<T> Snapshot()
    {
        lock (gate) return Items.ToList();
    }

    protected TResult Read<TResult>(Func<List<T>, TResult> reader)
    {
        lock (gate) return reader(Items);
    }

    protected void Mutate(Action<List<T>> change)
    {
        lock (gate)
        {
            change(Items);
            dirty = true;
        }
        ScheduleSave();
        Changed?.Invoke();
    }

    void ScheduleSave()
    {
        if (!persist) return;
        lock (gate)
        {
            timer ??= new Timer(_ => Flush(), null, Timeout.Infinite, Timeout.Infinite);
            timer.Change(1200, Timeout.Infinite);
        }
    }

    public void Flush()
    {
        if (!persist) return;
        List<T> snapshot;
        lock (gate)
        {
            if (!dirty) return;
            dirty = false;
            snapshot = Items.ToList();
        }
        try
        {
            JsonStore.Write(path, snapshot);
        }
        catch (Exception ex)
        {
            lock (gate) dirty = true;
            CrashLog.Write("Could not save " + Path.GetFileName(path), ex);
        }
    }
}

public sealed class HistoryStore : JsonListStore<HistoryEntry>
{
    const int MaxEntries = 20000;

    public HistoryStore() : base("history.json")
    {
    }

    public void Record(string url, string title)
    {
        if (string.IsNullOrWhiteSpace(url) || UrlUtils.IsInternal(url) || !(url.StartsWith("http", StringComparison.OrdinalIgnoreCase) || url.StartsWith("file:", StringComparison.OrdinalIgnoreCase))) return;
        Mutate(items =>
        {
            var existing = items.FindIndex(e => e.Url == url);
            HistoryEntry entry;
            if (existing >= 0)
            {
                entry = items[existing];
                items.RemoveAt(existing);
            }
            else
            {
                entry = new HistoryEntry { Url = url };
            }
            entry.Visits++;
            entry.LastVisit = DateTime.Now;
            if (!string.IsNullOrWhiteSpace(title)) entry.Title = title;
            items.Insert(0, entry);
            if (items.Count > MaxEntries) items.RemoveRange(MaxEntries, items.Count - MaxEntries);
        });
    }

    public void UpdateTitle(string url, string title)
    {
        if (string.IsNullOrWhiteSpace(title)) return;
        var needs = Read(items => items.Count > 0 && items[0].Url == url && items[0].Title != title);
        if (needs) Mutate(items => items[0].Title = title);
    }

    public List<HistoryEntry> Search(string query, int limit)
    {
        var q = query.Trim();
        return Read(items =>
        {
            IEnumerable<HistoryEntry> source = items;
            if (q.Length > 0) source = items.Where(e => e.Url.Contains(q, StringComparison.OrdinalIgnoreCase) || e.Title.Contains(q, StringComparison.OrdinalIgnoreCase));
            return source.Take(limit).ToList();
        });
    }

    public List<HistoryEntry> Suggest(string query, int limit)
    {
        var q = query.Trim();
        if (q.Length == 0) return new List<HistoryEntry>();
        return Read(items => items
            .Select(e => (Entry: e, Score: Score(e, q)))
            .Where(x => x.Score > 0)
            .OrderByDescending(x => x.Score)
            .ThenByDescending(x => x.Entry.LastVisit)
            .Take(limit)
            .Select(x => x.Entry)
            .ToList());
    }

    static int Score(HistoryEntry entry, string q)
    {
        var host = UrlUtils.DisplayHost(entry.Url);
        var score = 0;
        if (host.StartsWith(q, StringComparison.OrdinalIgnoreCase)) score += 100;
        else if (entry.Url.Contains(q, StringComparison.OrdinalIgnoreCase)) score += 40;
        else if (entry.Title.Contains(q, StringComparison.OrdinalIgnoreCase)) score += 20;
        else return 0;
        return score + Math.Min(entry.Visits, 50);
    }

    public List<HistoryEntry> TopSites(int limit) => Read(items => items
        .GroupBy(e => UrlUtils.DisplayHost(e.Url))
        .Where(g => g.Key.Length > 0)
        .Select(g => g.OrderByDescending(e => e.Visits).First())
        .OrderByDescending(e => e.Visits)
        .Take(limit)
        .ToList());

    public void Remove(string url) => Mutate(items => items.RemoveAll(e => e.Url == url));

    public void Clear() => Mutate(items => items.Clear());
}

public sealed partial class BookmarkStore : JsonListStore<Bookmark>
{
    public BookmarkStore() : base("bookmarks.json")
    {
    }

    public bool Contains(string? url) => url != null && Read(items => items.Any(b => b.Url == url));

    public bool Toggle(string url, string title)
    {
        var added = false;
        Mutate(items =>
        {
            var removed = items.RemoveAll(b => b.Url == url);
            if (removed == 0)
            {
                items.Insert(0, new Bookmark { Url = url, Title = string.IsNullOrWhiteSpace(title) ? url : title, Added = DateTime.Now });
                added = true;
            }
        });
        return added;
    }

    public void Add(string url, string title)
    {
        if (Contains(url)) return;
        Mutate(items => items.Add(new Bookmark { Url = url, Title = string.IsNullOrWhiteSpace(title) ? url : title, Added = DateTime.Now }));
    }

    public void Remove(string url) => Mutate(items => items.RemoveAll(b => b.Url == url));

    public string ExportHtml()
    {
        var builder = new StringBuilder();
        builder.AppendLine("<!DOCTYPE NETSCAPE-Bookmark-file-1>");
        builder.AppendLine("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">");
        builder.AppendLine("<TITLE>Bookmarks</TITLE>");
        builder.AppendLine("<H1>Bookmarks</H1>");
        builder.AppendLine("<DL><p>");
        foreach (var b in Snapshot())
        {
            var added = b.Added.Year < 1971 ? 0 : new DateTimeOffset(b.Added.ToUniversalTime(), TimeSpan.Zero).ToUnixTimeSeconds();
            builder.Append("    <DT><A HREF=\"").Append(WebUtility.HtmlEncode(b.Url)).Append("\" ADD_DATE=\"").Append(added).Append("\">")
                .Append(WebUtility.HtmlEncode(b.Title)).AppendLine("</A>");
        }
        builder.AppendLine("</DL><p>");
        return builder.ToString();
    }

    [GeneratedRegex("<A\\s[^>]*HREF=\"([^\"]+)\"[^>]*>(.*?)</A>", RegexOptions.IgnoreCase | RegexOptions.Singleline)]
    private static partial Regex AnchorRegex();

    public int ImportHtml(string html)
    {
        var count = 0;
        var found = AnchorRegex().Matches(html)
            .Select(m => (Url: WebUtility.HtmlDecode(m.Groups[1].Value), Title: WebUtility.HtmlDecode(Regex.Replace(m.Groups[2].Value, "<[^>]+>", "")).Trim()))
            .Where(x => x.Url.StartsWith("http", StringComparison.OrdinalIgnoreCase))
            .ToList();
        Mutate(items =>
        {
            foreach (var (url, title) in found)
            {
                if (items.Any(b => b.Url == url)) continue;
                items.Add(new Bookmark { Url = url, Title = title.Length == 0 ? url : title, Added = DateTime.Now });
                count++;
            }
        });
        return count;
    }
}

public sealed class DownloadStore : JsonListStore<DownloadEntry>
{
    public DownloadStore(bool persist = true) : base("downloads.json", persist)
    {
    }

    public void Add(DownloadEntry entry) => Mutate(items =>
    {
        items.Insert(0, entry);
        if (items.Count > 500) items.RemoveRange(500, items.Count - 500);
    });

    public void Touch() => Mutate(_ => { });

    public void Remove(string id) => Mutate(items => items.RemoveAll(d => d.Id == id));

    public void Clear() => Mutate(items => items.RemoveAll(d => d.State != "In progress"));
}

public sealed class PermissionStore : JsonListStore<SitePermission>
{
    public PermissionStore() : base("permissions.json")
    {
    }

    public bool? Get(string origin, string kind) => Read(items => items.FirstOrDefault(p => p.Origin == origin && p.Kind == kind)?.Allowed);

    public void Set(string origin, string kind, bool allowed) => Mutate(items =>
    {
        items.RemoveAll(p => p.Origin == origin && p.Kind == kind);
        items.Add(new SitePermission { Origin = origin, Kind = kind, Allowed = allowed });
    });

    public void Remove(string origin, string kind) => Mutate(items => items.RemoveAll(p => p.Origin == origin && p.Kind == kind));

    public void Clear() => Mutate(items => items.Clear());
}

public sealed class SessionStore : JsonListStore<SavedTab>
{
    public SessionStore() : base("session.json")
    {
    }

    public List<SavedTab> Load() => Snapshot();

    public void Replace(IEnumerable<SavedTab> tabs)
    {
        var list = tabs.ToList();
        Mutate(items =>
        {
            items.Clear();
            items.AddRange(list);
        });
    }
}

public static class Library
{
    public static HistoryStore History { get; } = new();
    public static BookmarkStore Bookmarks { get; } = new();
    public static DownloadStore Downloads { get; } = new();
    public static DownloadStore PrivateDownloads { get; } = new(false);
    public static PermissionStore Permissions { get; } = new();
    public static SessionStore Session { get; } = new();

    public static void FlushAll()
    {
        History.Flush();
        Bookmarks.Flush();
        Downloads.Flush();
        Permissions.Flush();
        Session.Flush();
    }
}
