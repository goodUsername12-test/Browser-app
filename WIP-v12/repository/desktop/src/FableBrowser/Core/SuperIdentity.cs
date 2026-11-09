using System.Text.Json;

namespace FableBrowser.Core;

public sealed record DeviceProfile(string Label, string Os, int Width, int Height, int TaskbarHeight, string GlVendor, string GlRenderer, int[] Cores, int[] Memory);

public sealed class SuperIdentity
{
    public static readonly string[] HideKeys = { "browser", "screen", "cpu", "languages", "timezone", "graphics", "canvas", "audio", "webrtc" };

    public static readonly Dictionary<string, string> HideLabels = new()
    {
        ["browser"] = "Browser and operating system",
        ["screen"] = "Screen size",
        ["cpu"] = "CPU cores and memory",
        ["languages"] = "Languages",
        ["timezone"] = "Time zone",
        ["graphics"] = "Graphics card name",
        ["canvas"] = "Canvas fingerprint",
        ["audio"] = "Audio fingerprint",
        ["webrtc"] = "WebRTC"
    };

    static readonly HashSet<string> GameKeys = new() { "screen", "canvas", "audio" };

    public static readonly DeviceProfile[] Devices =
    {
        new("Windows desktop, GeForce GTX 1660", "Windows", 1920, 1080, 40, "Google Inc. (NVIDIA)", "ANGLE (NVIDIA, NVIDIA GeForce GTX 1660 Direct3D11 vs_5_0 ps_5_0, D3D11)", new[] { 6, 8, 12 }, new[] { 8 }),
        new("Windows desktop, GeForce RTX 3060", "Windows", 2560, 1440, 48, "Google Inc. (NVIDIA)", "ANGLE (NVIDIA, NVIDIA GeForce RTX 3060 Direct3D11 vs_5_0 ps_5_0, D3D11)", new[] { 8, 12, 16 }, new[] { 8 }),
        new("Windows laptop, Intel UHD", "Windows", 1536, 864, 40, "Google Inc. (Intel)", "ANGLE (Intel, Intel(R) UHD Graphics Direct3D11 vs_5_0 ps_5_0, D3D11)", new[] { 4, 8 }, new[] { 4, 8 }),
        new("Windows laptop, Intel Iris Xe", "Windows", 1920, 1200, 48, "Google Inc. (Intel)", "ANGLE (Intel, Intel(R) Iris(R) Xe Graphics Direct3D11 vs_5_0 ps_5_0, D3D11)", new[] { 8, 12 }, new[] { 8 }),
        new("Windows desktop, Radeon RX 6600", "Windows", 2560, 1440, 48, "Google Inc. (AMD)", "ANGLE (AMD, AMD Radeon RX 6600 Direct3D11 vs_5_0 ps_5_0, D3D11)", new[] { 8, 12, 16 }, new[] { 8 }),
        new("Windows laptop, Radeon Graphics", "Windows", 1366, 768, 40, "Google Inc. (AMD)", "ANGLE (AMD, AMD Radeon(TM) Graphics Direct3D11 vs_5_0 ps_5_0, D3D11)", new[] { 6, 8 }, new[] { 4, 8 }),
        new("MacBook Air, Apple M2", "macOS", 1470, 956, 37, "Google Inc. (Apple)", "ANGLE (Apple, ANGLE Metal Renderer: Apple M2, Unspecified Version)", new[] { 8 }, new[] { 8 }),
        new("MacBook Pro, Apple M3 Pro", "macOS", 1512, 982, 38, "Google Inc. (Apple)", "ANGLE (Apple, ANGLE Metal Renderer: Apple M3 Pro, Unspecified Version)", new[] { 11, 12 }, new[] { 8 }),
        new("iMac, Apple M1", "macOS", 2240, 1260, 25, "Google Inc. (Apple)", "ANGLE (Apple, ANGLE Metal Renderer: Apple M1, Unspecified Version)", new[] { 8 }, new[] { 8 }),
        new("Linux desktop, Intel UHD 630", "Linux", 1920, 1080, 0, "Google Inc. (Intel)", "ANGLE (Intel, Mesa Intel(R) UHD Graphics 630 (CFL GT2), OpenGL 4.6)", new[] { 4, 8, 12 }, new[] { 8 }),
        new("Linux laptop, AMD Radeon", "Linux", 1366, 768, 0, "Google Inc. (AMD)", "ANGLE (AMD, AMD Radeon Graphics (radeonsi, renoir, LLVM 15.0.7), OpenGL 4.6)", new[] { 8, 16 }, new[] { 8 }),
        new("Linux desktop, GeForce GTX 1650", "Linux", 2560, 1440, 0, "Google Inc. (NVIDIA Corporation)", "ANGLE (NVIDIA Corporation, NVIDIA GeForce GTX 1650/PCIe/SSE2, OpenGL 4.5.0)", new[] { 8, 12 }, new[] { 8 })
    };

    static readonly string[][] LanguageSets =
    {
        new[] { "en-US", "en" },
        new[] { "en-US" },
        new[] { "en-GB", "en-US", "en" },
        new[] { "en-GB", "en" },
        new[] { "en-CA", "en-US", "en" },
        new[] { "en-AU", "en" }
    };

    static readonly string[] TimeZones =
    {
        "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles", "America/Toronto", "America/Sao_Paulo",
        "Europe/London", "Europe/Berlin", "Europe/Paris", "Europe/Madrid", "Europe/Warsaw", "Europe/Amsterdam",
        "Asia/Tokyo", "Asia/Singapore", "Australia/Sydney"
    };

    public required DeviceProfile Device { get; init; }
    public required string Major { get; init; }
    public required int Cores { get; init; }
    public required int Memory { get; init; }
    public required string[] Languages { get; init; }
    public required string TimeZone { get; init; }
    public required int CanvasNoise { get; init; }
    public required int AudioNoise { get; init; }
    public required uint Seed { get; init; }
    public required HashSet<string> Hidden { get; init; }

    public bool Hides(string key) => Hidden.Contains(key);

    public string Platform => Device.Os switch
    {
        "macOS" => "MacIntel",
        "Linux" => "Linux x86_64",
        _ => "Win32"
    };

    public string PlatformVersion => Device.Os switch
    {
        "macOS" => "14.6.1",
        "Linux" => "6.8.0",
        _ => "15.0.0"
    };

    public string Architecture => Device.Os == "macOS" ? "arm" : "x86";

    public string UserAgent => Device.Os switch
    {
        "macOS" => $"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/{Major}.0.0.0 Safari/537.36",
        "Linux" => $"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/{Major}.0.0.0 Safari/537.36",
        _ => $"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/{Major}.0.0.0 Safari/537.36"
    };

    public string AcceptLanguage
    {
        get
        {
            var parts = new List<string>();
            for (var i = 0; i < Languages.Length; i++)
            {
                parts.Add(i == 0 ? Languages[i] : $"{Languages[i]};q={Math.Max(0.1, 1.0 - i * 0.1).ToString("0.0", System.Globalization.CultureInfo.InvariantCulture)}");
            }
            return string.Join(",", parts);
        }
    }

    public static string MajorFromVersion(string? version)
    {
        if (string.IsNullOrEmpty(version)) return "130";
        var dot = version.IndexOf('.');
        var major = dot < 0 ? version : version[..dot];
        return int.TryParse(major, out _) ? major : "130";
    }

    public static SuperIdentity Create(Settings settings, string engineVersion, Random random)
    {
        var pool = settings.SuperRange switch
        {
            DeviceRange.Windows => Devices.Where(d => d.Os == "Windows").ToArray(),
            DeviceRange.Mac => Devices.Where(d => d.Os == "macOS").ToArray(),
            DeviceRange.Linux => Devices.Where(d => d.Os == "Linux").ToArray(),
            _ => Devices
        };
        var device = pool[random.Next(pool.Length)];
        var hidden = new HashSet<string>(HideKeys.Where(k => !settings.SuperReveal.Contains(k)));
        if (settings.SuperGameMode) hidden.ExceptWith(GameKeys);
        return new SuperIdentity
        {
            Device = device,
            Major = MajorFromVersion(engineVersion),
            Cores = device.Cores[random.Next(device.Cores.Length)],
            Memory = device.Memory[random.Next(device.Memory.Length)],
            Languages = LanguageSets[random.Next(LanguageSets.Length)],
            TimeZone = settings.SuperRandomTimezone ? TimeZones[random.Next(TimeZones.Length)] : "",
            CanvasNoise = random.Next(1, 4),
            AudioNoise = random.Next(1, 4),
            Seed = (uint)random.Next(1, int.MaxValue),
            Hidden = hidden
        };
    }

    public string UserAgentOverrideJson()
    {
        var brands = new object[]
        {
            new { brand = "Google Chrome", version = Major },
            new { brand = "Chromium", version = Major },
            new { brand = "Not_A Brand", version = "24" }
        };
        var fullBrands = new object[]
        {
            new { brand = "Google Chrome", version = Major + ".0.0.0" },
            new { brand = "Chromium", version = Major + ".0.0.0" },
            new { brand = "Not_A Brand", version = "24.0.0.0" }
        };
        var payload = new
        {
            userAgent = UserAgent,
            acceptLanguage = Hides("languages") ? AcceptLanguage : null,
            platform = Platform,
            userAgentMetadata = new
            {
                brands,
                fullVersionList = fullBrands,
                fullVersion = Major + ".0.0.0",
                platform = Device.Os == "macOS" ? "macOS" : Device.Os,
                platformVersion = PlatformVersion,
                architecture = Architecture,
                model = "",
                mobile = false,
                bitness = "64",
                wow64 = false
            }
        };
        return JsonSerializer.Serialize(payload, new JsonSerializerOptions { DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull });
    }

    public IEnumerable<(string Label, string Value)> Describe()
    {
        yield return ("Device", Device.Label);
        yield return ("Browser", Hides("browser") ? $"Chrome {Major} on {Device.Os}" : "Real");
        yield return ("Screen", Hides("screen") ? $"{Device.Width} x {Device.Height}" : "Real");
        yield return ("CPU", Hides("cpu") ? $"{Cores} cores, {Memory} GB" : "Real");
        yield return ("Languages", Hides("languages") ? string.Join(", ", Languages) : "Real");
        yield return ("Time zone", Hides("timezone") && TimeZone.Length > 0 ? TimeZone : "Real");
        yield return ("Graphics", Hides("graphics") ? Device.GlRenderer : "Real");
        yield return ("Canvas", Hides("canvas") ? $"Noise level {CanvasNoise}" : "Real");
        yield return ("Audio", Hides("audio") ? $"Noise level {AudioNoise}" : "Real");
        yield return ("WebRTC", Hides("webrtc") ? "Disabled" : "Real");
    }
}
