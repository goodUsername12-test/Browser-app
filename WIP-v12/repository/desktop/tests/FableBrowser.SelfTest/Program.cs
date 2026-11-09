using System.Text.Json;
using FableBrowser.Core;

var passed = 0;
var failed = 0;

void Check(string name, bool condition)
{
    if (condition)
    {
        passed++;
        return;
    }
    failed++;
    Console.Error.WriteLine("FAIL " + name);
}

void Same(string name, string? actual, string? expected)
{
    if (string.Equals(actual, expected, StringComparison.Ordinal))
    {
        passed++;
        return;
    }
    failed++;
    Console.Error.WriteLine("FAIL " + name + Environment.NewLine + "  expected: " + expected + Environment.NewLine + "  actual:   " + actual);
}

const string search = "https://search.test/?q=%s";

Same("normalize domain", UrlUtils.Normalize("example.com", search), "https://example.com");
Same("normalize path", UrlUtils.Normalize("example.com/a?b=1", search), "https://example.com/a?b=1");
Same("normalize search", UrlUtils.Normalize("hello world", search), "https://search.test/?q=hello%20world");
Same("normalize single word", UrlUtils.Normalize("bloxd", search), "https://search.test/?q=bloxd");
Same("normalize localhost", UrlUtils.Normalize("localhost:3000", search), "http://localhost:3000");
Same("normalize lan ip", UrlUtils.Normalize("192.168.1.1", search), "http://192.168.1.1");
Same("normalize keeps scheme", UrlUtils.Normalize("https://a.example/x", search), "https://a.example/x");
Same("normalize empty", UrlUtils.Normalize("   ", search), UrlUtils.NewTabUrl);
Check("likely url about", UrlUtils.IsLikelyUrl("about:blank"));
Check("not url with space", !UrlUtils.IsLikelyUrl("what is 2+2"));

Same("strip utm", UrlUtils.StripTrackingParams("https://a.com/p?utm_source=x&id=5#top"), "https://a.com/p?id=5#top");
Same("strip all", UrlUtils.StripTrackingParams("https://a.com/?fbclid=1&gclid=2"), "https://a.com/");
Same("strip none", UrlUtils.StripTrackingParams("https://a.com/?q=1"), "https://a.com/?q=1");

Same("fast host", UrlUtils.FastHost("https://user@Sub.Example.COM.:8080/x?y"), "sub.example.com");
Same("fast host ipv6", UrlUtils.FastHost("http://[::1]:80/"), "[::1]");
Same("display host", UrlUtils.DisplayHost("https://www.example.com/"), "example.com");
Same("site", UrlUtils.Site("a.b.example.co.uk"), "example.co.uk");
Check("same site", UrlUtils.IsSameSite("cdn.example.com", "www.example.com"));
Check("other site", !UrlUtils.IsSameSite("example.com", "example.org"));
Same("upgrade", UrlUtils.UpgradeToHttps("http://a.com/"), "https://a.com/");
Same("downgrade", UrlUtils.DowngradeToHttp("https://a.com/"), "http://a.com/");
Check("host list", UrlUtils.HostMatchesList("news.example.com", new[] { "example.com" }));
Check("host list miss", !UrlUtils.HostMatchesList("badexample.com", new[] { "example.com" }));

var blocker = new ContentBlocker();
blocker.LoadFromLines(new[]
{
    "! comment",
    "[Adblock Plus 2.0]",
    "||ads.example.com^",
    "@@||good.ads.example.com^",
    "/banner/ad-",
    "##.ad-box",
    "||tracker.test^$third-party"
});
Check("rules loaded", blocker.RuleCount >= 4);
Check("block domain", blocker.ShouldBlock("https://ads.example.com/x.js", "news.test"));
Check("block subdomain", blocker.ShouldBlock("https://cdn.ads.example.com/x.js", "news.test"));
Check("exception wins", !blocker.ShouldBlock("https://good.ads.example.com/x.js", "news.test"));
Check("block substring", blocker.ShouldBlock("https://cdn.test/banner/ad-1.png", "news.test"));
Check("first party allowed", !blocker.ShouldBlock("https://ads.example.com/x", "ads.example.com"));
Check("third party option", blocker.ShouldBlock("https://tracker.test/p.gif", "news.test"));
Check("unrelated allowed", !blocker.ShouldBlock("https://news.test/app.js", "news.test"));

var settings = new Settings();
Check("no pin", !PinLock.HasPin(settings));
PinLock.SetPin(settings, "4821");
Check("pin set", PinLock.HasPin(settings));
Check("pin ok", PinLock.Verify(settings, "4821"));
Check("pin wrong", !PinLock.Verify(settings, "0000"));
PinLock.ClearPin(settings);
Check("pin cleared", !PinLock.HasPin(settings));

for (var i = 0; i < 25; i++)
{
    var identity = SuperIdentity.Create(new Settings(), "142.0.3595.53", new Random(i));
    Check("ua chrome " + i, identity.UserAgent.Contains("Chrome/142.0.0.0", StringComparison.Ordinal));
    Check("ua no edge " + i, !identity.UserAgent.Contains("Edg/", StringComparison.Ordinal));
    using var json = JsonDocument.Parse(identity.UserAgentOverrideJson());
    Check("ua json " + i, json.RootElement.TryGetProperty("userAgent", out _));
    Check("languages " + i, identity.Languages.Length > 0);
    Check("cores " + i, identity.Cores > 0);
}

var roundTrip = JsonSerializer.Deserialize<Settings>(JsonSerializer.Serialize(new Settings(), JsonStore.Options), JsonStore.Options);
Check("settings round trip", roundTrip != null);

Console.WriteLine(passed + " passed, " + failed + " failed");
return failed == 0 ? 0 : 1;
