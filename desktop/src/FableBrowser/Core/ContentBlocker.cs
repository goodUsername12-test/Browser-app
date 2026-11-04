using System.Buffers;

namespace FableBrowser.Core;

public sealed class ContentBlocker
{
    public static readonly string[] ListNames = { "easylist.txt", "easyprivacy.txt" };

    public static readonly Dictionary<string, string> ListSources = new()
    {
        ["easylist.txt"] = "https://easylist.to/easylist/easylist.txt",
        ["easyprivacy.txt"] = "https://easylist.to/easylist/easyprivacy.txt"
    };

    static readonly HashSet<string> SupportedOptions = new(StringComparer.Ordinal)
    {
        "third-party", "3p", "script", "image", "xmlhttprequest", "subdocument", "media", "font", "ping", "other", "websocket"
    };

    sealed class FilterSet
    {
        public FilterSet(HashSet<string> blockedDomains, List<string> blockedSubstrings, HashSet<string> exceptionDomains, List<string> exceptionSubstrings)
        {
            BlockedDomains = blockedDomains;
            ExceptionDomains = exceptionDomains;
            BlockedSubstrings = blockedSubstrings.Count == 0 ? null : SearchValues.Create(blockedSubstrings.ToArray(), StringComparison.OrdinalIgnoreCase);
            ExceptionSubstrings = exceptionSubstrings.Count == 0 ? null : SearchValues.Create(exceptionSubstrings.ToArray(), StringComparison.OrdinalIgnoreCase);
            RuleCount = blockedDomains.Count + blockedSubstrings.Count + exceptionDomains.Count + exceptionSubstrings.Count;
        }

        public HashSet<string> BlockedDomains { get; }
        public HashSet<string> ExceptionDomains { get; }
        public SearchValues<string>? BlockedSubstrings { get; }
        public SearchValues<string>? ExceptionSubstrings { get; }
        public int RuleCount { get; }
    }

    volatile FilterSet? filters;

    public int RuleCount => filters?.RuleCount ?? 0;

    public bool IsReady => filters != null;

    public event Action? Reloaded;

    public void LoadFromLines(IEnumerable<string> lines)
    {
        var blockedDomains = new HashSet<string>(StringComparer.Ordinal);
        var blockedSubstrings = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var exceptionDomains = new HashSet<string>(StringComparer.Ordinal);
        var exceptionSubstrings = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var raw in lines)
        {
            ParseRule(raw.Trim(), blockedDomains, blockedSubstrings, exceptionDomains, exceptionSubstrings);
        }
        filters = new FilterSet(blockedDomains, blockedSubstrings.ToList(), exceptionDomains, exceptionSubstrings.ToList());
        Reloaded?.Invoke();
    }

    public Task LoadAsync() => Task.Run(() =>
    {
        try
        {
            LoadFromLines(ReadAllLists());
        }
        catch (Exception ex)
        {
            CrashLog.Write("Could not load filter lists", ex);
            LoadFromLines(Array.Empty<string>());
        }
    });

    static IEnumerable<string> ReadAllLists()
    {
        foreach (var name in ListNames)
        {
            var downloaded = Path.Combine(AppPaths.Filters, name);
            if (File.Exists(downloaded) && new FileInfo(downloaded).Length > 1024)
            {
                foreach (var line in File.ReadLines(downloaded)) yield return line;
                continue;
            }
            using var stream = typeof(ContentBlocker).Assembly.GetManifestResourceStream("Fable." + name);
            if (stream == null) continue;
            using var reader = new StreamReader(stream);
            string? line2;
            while ((line2 = reader.ReadLine()) != null) yield return line2;
        }
    }

    public async Task<int> UpdateListsAsync(HttpClient client, CancellationToken token)
    {
        var updated = 0;
        foreach (var name in ListNames)
        {
            var text = await client.GetStringAsync(ListSources[name], token).ConfigureAwait(false);
            if (text.Length < 1024 || !text.Contains("[Adblock", StringComparison.OrdinalIgnoreCase)) continue;
            var target = Path.Combine(AppPaths.Filters, name);
            await File.WriteAllTextAsync(target + ".tmp", text, token).ConfigureAwait(false);
            File.Move(target + ".tmp", target, true);
            updated++;
        }
        await LoadAsync().ConfigureAwait(false);
        return updated;
    }

    static void ParseRule(string line, HashSet<string> blockedDomains, HashSet<string> blockedSubstrings, HashSet<string> exceptionDomains, HashSet<string> exceptionSubstrings)
    {
        if (line.Length == 0 || line[0] == '!' || line[0] == '[') return;
        if (line.Contains("##") || line.Contains("#@#") || line.Contains("#?#") || line.Contains("#$#")) return;
        var exception = line.StartsWith("@@", StringComparison.Ordinal);
        var rule = exception ? line[2..] : line;
        var dollar = rule.IndexOf('$');
        if (dollar >= 0)
        {
            var options = rule[(dollar + 1)..].Split(',');
            foreach (var option in options)
            {
                var name = option.Trim().TrimStart('~');
                if (!SupportedOptions.Contains(name)) return;
            }
            rule = rule[..dollar];
        }
        if (rule.Length == 0 || (rule.Contains('*') && !rule.StartsWith("||", StringComparison.Ordinal))) return;
        if (rule.StartsWith('/') && rule.EndsWith('/') && rule.Length > 1) return;
        if (rule.StartsWith("||", StringComparison.Ordinal))
        {
            var body = rule[2..];
            var end = body.IndexOfAny(new[] { '^', '/', '*', '|' });
            var domain = (end < 0 ? body : body[..end]).ToLowerInvariant();
            if (!IsDomain(domain)) return;
            var tail = end < 0 ? "" : body[end..];
            if (tail.Length == 0 || tail == "^" || tail == "^|" || tail == "|")
            {
                (exception ? exceptionDomains : blockedDomains).Add(domain);
                return;
            }
            if (tail[0] == '/' && !tail.Contains('*'))
            {
                var path = tail.TrimEnd('^', '|');
                if (path.Length > 1) (exception ? exceptionSubstrings : blockedSubstrings).Add(domain + path);
            }
            return;
        }
        var plain = rule.Trim('|');
        if (plain.Length < 6 || plain.Contains('^') || plain.Contains('*')) return;
        (exception ? exceptionSubstrings : blockedSubstrings).Add(plain);
    }

    static bool IsDomain(string value)
    {
        if (value.Length < 3 || !value.Contains('.') || value.StartsWith('.') || value.EndsWith('.')) return false;
        foreach (var c in value)
        {
            if (!(char.IsAsciiLetterOrDigit(c) || c == '-' || c == '.')) return false;
        }
        return true;
    }

    static bool MatchesDomain(string host, HashSet<string> set)
    {
        if (set.Count == 0) return false;
        var candidate = host;
        while (true)
        {
            if (set.Contains(candidate)) return true;
            var dot = candidate.IndexOf('.');
            if (dot < 0 || dot == candidate.Length - 1) return false;
            candidate = candidate[(dot + 1)..];
            if (!candidate.Contains('.')) return false;
        }
    }

    public bool ShouldBlock(string url, string? documentHost)
    {
        var set = filters;
        if (set == null) return false;
        var host = UrlUtils.FastHost(url);
        if (host.Length == 0) return false;
        if (documentHost != null && host == documentHost) return false;
        if (MatchesDomain(host, set.ExceptionDomains)) return false;
        var span = url.AsSpan();
        if (set.ExceptionSubstrings != null && span.IndexOfAny(set.ExceptionSubstrings) >= 0) return false;
        if (MatchesDomain(host, set.BlockedDomains)) return !UrlUtils.IsSameSite(host, documentHost);
        return set.BlockedSubstrings != null && span.IndexOfAny(set.BlockedSubstrings) >= 0;
    }
}
