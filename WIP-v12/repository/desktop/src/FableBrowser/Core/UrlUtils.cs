using System.Text;
using System.Text.RegularExpressions;

namespace FableBrowser.Core;

public static partial class UrlUtils
{
    public const string InternalHost = "fable.local";
    public const string InternalOrigin = "https://fable.local/";
    public const string NewTabUrl = "https://fable.local/newtab.html";

    static readonly string[] TrackingPrefixes = { "utm_", "pk_", "piwik_", "matomo_", "mtm_" };

    static readonly HashSet<string> TrackingParams = new(StringComparer.OrdinalIgnoreCase)
    {
        "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "msclkid", "mc_cid", "mc_eid",
        "yclid", "twclid", "ttclid", "igshid", "_hsenc", "_hsmi", "mkt_tok", "oly_anon_id", "oly_enc_id",
        "vero_id", "rb_clickid", "s_cid", "ref_src", "srsltid", "si"
    };

    static readonly HashSet<string> KnownSchemes = new(StringComparer.OrdinalIgnoreCase)
    {
        "http", "https", "about", "data", "file", "blob", "view-source", "mailto", "tel", "ftp", "ws", "wss", "edge", "chrome"
    };

    static readonly HashSet<string> SecondLevelLabels = new(StringComparer.Ordinal)
    {
        "co", "com", "org", "net", "gov", "ac", "edu", "ne", "or", "go", "gob", "mil", "nic"
    };

    [GeneratedRegex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:")]
    private static partial Regex SchemeRegex();

    [GeneratedRegex("^(\\d{1,3}\\.){3}\\d{1,3}(:\\d+)?([/?#].*)?$")]
    private static partial Regex Ipv4Regex();

    public static bool IsInternal(string? url) => url != null && url.StartsWith(InternalOrigin, StringComparison.OrdinalIgnoreCase);

    public static bool IsNewTab(string? url) => url != null && url.StartsWith(NewTabUrl, StringComparison.OrdinalIgnoreCase);

    public static bool HasScheme(string input)
    {
        var match = SchemeRegex().Match(input);
        if (!match.Success) return false;
        var scheme = match.Value[..^1];
        if (KnownSchemes.Contains(scheme)) return true;
        return input.Length > match.Length + 1 && string.CompareOrdinal(input, match.Length, "//", 0, 2) == 0;
    }

    static string HostPart(string input)
    {
        var end = input.IndexOfAny(new[] { '/', '?', '#' });
        return end < 0 ? input : input[..end];
    }

    static string StripPort(string hostPart)
    {
        if (hostPart.StartsWith('[')) return hostPart;
        var colon = hostPart.LastIndexOf(':');
        return colon < 0 ? hostPart : hostPart[..colon];
    }

    public static bool IsLikelyUrl(string input)
    {
        var text = input.Trim();
        if (text.Length == 0 || text.Any(char.IsWhiteSpace)) return false;
        if (HasScheme(text)) return true;
        var hostPart = HostPart(text);
        var host = StripPort(hostPart);
        if (host.Equals("localhost", StringComparison.OrdinalIgnoreCase)) return true;
        if (hostPart.StartsWith('[') && hostPart.Contains(']')) return true;
        if (Ipv4Regex().IsMatch(text)) return true;
        var dot = host.LastIndexOf('.');
        if (dot < 1 || dot >= host.Length - 1) return false;
        var tld = host[(dot + 1)..];
        if (tld.Length < 2 || !tld.Any(char.IsLetter)) return false;
        return host.All(c => char.IsLetterOrDigit(c) || c == '-' || c == '.' || c == '_');
    }

    public static string SearchUrl(string query, string template) => template.Replace("%s", Uri.EscapeDataString(query.Trim()));

    public static string Normalize(string input, string searchTemplate)
    {
        var text = input.Trim();
        if (text.Length == 0) return NewTabUrl;
        if (!IsLikelyUrl(text)) return SearchUrl(text, searchTemplate);
        if (HasScheme(text)) return text;
        var host = StripPort(HostPart(text));
        if (IsLocalHost(host)) return "http://" + text;
        return "https://" + text;
    }

    public static bool IsLocalHost(string host)
    {
        var h = host.Trim('[', ']').ToLowerInvariant();
        if (h == "localhost" || h.EndsWith(".localhost", StringComparison.Ordinal) || h == "::1") return true;
        if (h.StartsWith("127.", StringComparison.Ordinal) || h.StartsWith("10.", StringComparison.Ordinal) || h.StartsWith("192.168.", StringComparison.Ordinal)) return true;
        if (h.StartsWith("172.", StringComparison.Ordinal))
        {
            var parts = h.Split('.');
            if (parts.Length > 1 && int.TryParse(parts[1], out var second) && second >= 16 && second <= 31) return true;
        }
        return h.EndsWith(".local", StringComparison.Ordinal) || h.EndsWith(".lan", StringComparison.Ordinal) || h.EndsWith(".internal", StringComparison.Ordinal);
    }

    public static bool IsHttp(string url) => url.StartsWith("http://", StringComparison.OrdinalIgnoreCase);

    public static string UpgradeToHttps(string url) => IsHttp(url) ? "https://" + url[7..] : url;

    public static string DowngradeToHttp(string url) => url.StartsWith("https://", StringComparison.OrdinalIgnoreCase) ? "http://" + url[8..] : url;

    public static string StripTrackingParams(string url)
    {
        var q = url.IndexOf('?');
        if (q < 0) return url;
        var hash = url.IndexOf('#', q);
        var query = hash < 0 ? url[(q + 1)..] : url[(q + 1)..hash];
        if (query.Length == 0) return url;
        var parts = query.Split('&');
        var kept = new List<string>(parts.Length);
        foreach (var part in parts)
        {
            if (part.Length == 0) continue;
            var eq = part.IndexOf('=');
            var key = eq < 0 ? part : part[..eq];
            if (IsTrackingParam(key)) continue;
            kept.Add(part);
        }
        if (kept.Count == parts.Length) return url;
        var builder = new StringBuilder(url.Length);
        builder.Append(url, 0, q);
        if (kept.Count > 0) builder.Append('?').Append(string.Join('&', kept));
        if (hash >= 0) builder.Append(url, hash, url.Length - hash);
        return builder.ToString();
    }

    static bool IsTrackingParam(string key)
    {
        string decoded;
        try
        {
            decoded = Uri.UnescapeDataString(key);
        }
        catch (UriFormatException)
        {
            decoded = key;
        }
        if (TrackingParams.Contains(decoded)) return true;
        foreach (var prefix in TrackingPrefixes)
        {
            if (decoded.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) return true;
        }
        return false;
    }

    public static string FastHost(string url)
    {
        var start = url.IndexOf("://", StringComparison.Ordinal);
        if (start < 0) return "";
        start += 3;
        var end = start;
        while (end < url.Length)
        {
            var c = url[end];
            if (c == '/' || c == '?' || c == '#' || c == '\\') break;
            end++;
        }
        var authority = url.AsSpan(start, end - start);
        var at = authority.LastIndexOf('@');
        if (at >= 0) authority = authority[(at + 1)..];
        if (authority.Length > 0 && authority[0] == '[')
        {
            var close = authority.IndexOf(']');
            return close < 0 ? "" : authority[..(close + 1)].ToString().ToLowerInvariant();
        }
        var colon = authority.IndexOf(':');
        if (colon >= 0) authority = authority[..colon];
        if (authority.Length > 0 && authority[^1] == '.') authority = authority[..^1];
        return authority.ToString().ToLowerInvariant();
    }

    public static string? Host(string? url)
    {
        if (string.IsNullOrEmpty(url)) return null;
        var host = FastHost(url);
        return host.Length == 0 ? null : host;
    }

    public static string Origin(string? url)
    {
        if (url != null && Uri.TryCreate(url, UriKind.Absolute, out var uri) && !string.IsNullOrEmpty(uri.Host))
        {
            return uri.GetLeftPart(UriPartial.Authority).ToLowerInvariant();
        }
        return url ?? "";
    }

    public static string DisplayHost(string? url)
    {
        var host = Host(url) ?? "";
        return host.StartsWith("www.", StringComparison.Ordinal) ? host[4..] : host;
    }

    public static string Site(string host)
    {
        if (host.Length == 0 || host.Contains(':') || host.StartsWith('[') || char.IsDigit(host[^1])) return host;
        var parts = host.Split('.');
        var n = parts.Length;
        if (n <= 2) return host;
        if (parts[n - 1].Length == 2 && SecondLevelLabels.Contains(parts[n - 2])) return parts[n - 3] + "." + parts[n - 2] + "." + parts[n - 1];
        return parts[n - 2] + "." + parts[n - 1];
    }

    public static bool IsSameSite(string? a, string? b)
    {
        if (string.IsNullOrEmpty(a) || string.IsNullOrEmpty(b)) return false;
        return string.Equals(Site(a), Site(b), StringComparison.Ordinal);
    }

    public static bool HostMatchesList(string? host, IEnumerable<string> list)
    {
        if (string.IsNullOrEmpty(host)) return false;
        foreach (var raw in list)
        {
            var entry = raw.Trim().ToLowerInvariant();
            if (entry.Length == 0) continue;
            if (host == entry || host.EndsWith("." + entry, StringComparison.Ordinal)) return true;
        }
        return false;
    }

    public static string DisplayUrl(string? url)
    {
        if (string.IsNullOrEmpty(url) || IsNewTab(url)) return "";
        return url;
    }
}
