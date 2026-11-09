package com.fable.browser.engine

import com.fable.browser.data.model.SuperDeviceRange
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import java.security.SecureRandom
import java.util.UUID

// one fake device per super incognito session
data class SuperIdentity(
    val id: String,
    val label: String,
    val ua: String,
    val platform: String,
    val oscpu: String,
    val appVersion: String,
    val vendor: String,
    val desktop: Boolean,
    val screenWidth: Int,
    val screenHeight: Int,
    val availHeight: Int,
    val availTop: Int,
    val pixelRatio: Double,
    val colorDepth: Int,
    val cores: Int,
    val touchPoints: Int,
    val languages: List<String>,
    val timeZone: String,
    val glVendor: String,
    val glRenderer: String,
    val glLimits: Map<String, Int>,
    val fonts: List<String>,
    val doNotTrack: Boolean,
    val pdfViewer: Boolean,
    val canvasNoise: Int,
    val audioNoise: Int,
    val rectNoise: Boolean,
    val voiceShare: Int,
    val seed: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("ua", ua)
        put("platform", platform)
        put("oscpu", oscpu)
        put("appVersion", appVersion)
        put("vendor", vendor)
        put("desktop", desktop)
        put("screenWidth", screenWidth)
        put("screenHeight", screenHeight)
        put("availHeight", availHeight)
        put("availTop", availTop)
        put("pixelRatio", pixelRatio)
        put("colorDepth", colorDepth)
        put("cores", cores)
        put("touchPoints", touchPoints)
        put("languages", JSONArray(languages))
        put("acceptLanguage", acceptLanguage())
        put("timeZone", timeZone)
        put("glVendor", glVendor)
        put("glRenderer", glRenderer)
        put("glLimits", JSONObject().apply { glLimits.forEach { (k, v) -> put(k, v) } })
        put("fonts", JSONArray(fonts))
        put("doNotTrack", doNotTrack)
        put("pdfViewer", pdfViewer)
        put("canvasNoise", canvasNoise)
        put("audioNoise", audioNoise)
        put("rectNoise", rectNoise)
        put("voiceShare", voiceShare)
        put("seed", seed)
    }

    fun acceptLanguage(): String = languages.mapIndexed { i, l ->
        if (i == 0) l else "$l;q=" + String.format(java.util.Locale.US, "%.1f", (10 - i * 2).coerceAtLeast(1) / 10.0)
    }.joinToString(",")

    fun summary(): String = buildString {
        append(label)
        append(" · ")
        append(screenWidth)
        append("×")
        append(screenHeight)
        append(" · ")
        append(cores)
        append(" cores · ")
        append(fonts.size)
        append(" fonts · ")
        append(if (timeZone.isEmpty()) "real time zone" else timeZone)
    }

    fun withRealBrowser(): SuperIdentity {
        val real = runCatching { GeckoSession.getDefaultUserAgent() }.getOrNull().orEmpty()
        return if (real.isEmpty()) this else copy(ua = real, desktop = false)
    }

    fun details(): List<Pair<String, String>> = listOf(
        "Device" to label,
        "Browser" to ua,
        "Platform" to platform,
        "Screen" to "$screenWidth × $screenHeight at ${pixelRatio}x, $colorDepth-bit color",
        "CPU cores" to cores.toString(),
        "Touch points" to touchPoints.toString(),
        "Languages" to languages.joinToString(", "),
        "Time zone" to timeZone.ifEmpty { "Real time zone" },
        "Graphics" to "$glVendor · $glRenderer",
        "Fonts" to "${fonts.size} fonts: " + fonts.take(8).joinToString(", ") + if (fonts.size > 8) "…" else "",
        "Canvas, audio and layout" to "Random noise level $canvasNoise / $audioNoise" + if (rectNoise) ", element sizes" else "",
        "Do Not Track" to if (doNotTrack) "On" else "Not set",
        "Voices" to "$voiceShare% of system voices"
    )

    companion object {
        const val HIDE_BROWSER = "browser"

        val HIDE_KEYS: List<String> = listOf(
            HIDE_BROWSER, "screen", "cpu", "touch", "languages", "graphics", "webglName",
            "webglLimits", "webglNoise", "fonts", "canvas", "audio", "rects", "voices", "dnt", "webrtc",
            "referrer", "hints", "timezone"
        )

        // turned off by game mode
        private val GAME_KEYS = setOf("screen", "webglLimits", "webglNoise", "canvas", "audio", "rects")

        fun hideJson(reveal: Set<String>, gameMode: Boolean = false): JSONObject = JSONObject().apply {
            HIDE_KEYS.forEach { put(it, it !in reveal && !(gameMode && it in GAME_KEYS)) }
        }

        fun fromJson(o: JSONObject): SuperIdentity? = runCatching {
            val langs = o.getJSONArray("languages")
            val fontsArr = o.optJSONArray("fonts") ?: JSONArray()
            val limitsObj = o.optJSONObject("glLimits") ?: JSONObject()
            val limits = HashMap<String, Int>()
            limitsObj.keys().forEach { k -> limits[k] = limitsObj.optInt(k) }
            SuperIdentity(
                id = o.getString("id"),
                label = o.getString("label"),
                ua = o.getString("ua"),
                platform = o.getString("platform"),
                oscpu = o.optString("oscpu"),
                appVersion = o.optString("appVersion"),
                vendor = o.optString("vendor"),
                desktop = o.optBoolean("desktop"),
                screenWidth = o.getInt("screenWidth"),
                screenHeight = o.getInt("screenHeight"),
                availHeight = o.optInt("availHeight", o.getInt("screenHeight")),
                availTop = o.optInt("availTop", 0),
                pixelRatio = o.optDouble("pixelRatio", 2.0),
                colorDepth = o.optInt("colorDepth", 24),
                cores = o.optInt("cores", 8),
                touchPoints = o.optInt("touchPoints", 5),
                languages = (0 until langs.length()).map { langs.getString(it) },
                timeZone = o.optString("timeZone"),
                glVendor = o.optString("glVendor"),
                glRenderer = o.optString("glRenderer"),
                glLimits = limits,
                fonts = (0 until fontsArr.length()).map { fontsArr.getString(it) },
                doNotTrack = o.optBoolean("doNotTrack"),
                pdfViewer = o.optBoolean("pdfViewer"),
                canvasNoise = o.optInt("canvasNoise", 2),
                audioNoise = o.optInt("audioNoise", 2),
                rectNoise = o.optBoolean("rectNoise", true),
                voiceShare = o.optInt("voiceShare", 100),
                seed = o.getLong("seed")
            )
        }.getOrNull()
    }
}

object SuperIdentityFactory {
    private val rnd = SecureRandom()

    fun gpuChoices(): List<Pair<String, String>> =
        devices.map { it.glVendor to it.glRenderer }.distinct().sortedBy { it.second }

    private fun <T> List<T>.pick(): T = this[rnd.nextInt(size)]

    private fun chance(percent: Int): Boolean = rnd.nextInt(100) < percent

    private class Device(
        val label: String,
        val w: Int,
        val h: Int,
        val dpr: Double,
        val kind: Int,
        val glVendor: String,
        val glRenderer: String,
        val cores: List<Int>,
        val brandFonts: List<String> = emptyList()
    )

    private const val PHONE = 0
    private const val TABLET = 1
    private const val WINDOWS = 2
    private const val MAC = 3
    private const val LINUX = 4
    private const val IPHONE = 5

    private val samsungFonts = listOf("SamsungOne", "Samsung Sans", "SEC Roboto Light")
    private val pixelFonts = listOf("Google Sans", "Google Sans Text", "Product Sans")
    private val xiaomiFonts = listOf("MiSans", "Mi Lanting")
    private val oneplusFonts = listOf("OnePlus Sans", "OPlusSans 3.0")
    private val oppoFonts = listOf("OPPOSans", "OPlusSans 3.0")
    private val motoFonts = listOf("Moto Sans")
    private val sonyFonts = listOf("SST", "Sony Sketch EF")
    private val nothingFonts = listOf("NDot 57", "NType 82")

    private val devices = listOf(
        Device("Samsung Galaxy S24", 360, 780, 3.0, PHONE, "Qualcomm", "Adreno (TM) 750", listOf(8), samsungFonts),
        Device("Samsung Galaxy S23 Ultra", 384, 824, 3.75, PHONE, "Qualcomm", "Adreno (TM) 740", listOf(8), samsungFonts),
        Device("Samsung Galaxy A55", 384, 832, 2.8125, PHONE, "Samsung", "Samsung Xclipse 530", listOf(8), samsungFonts),
        Device("Samsung Galaxy A54", 412, 915, 2.625, PHONE, "ARM", "Mali-G68", listOf(8), samsungFonts),
        Device("Samsung Galaxy A15", 384, 854, 2.8125, PHONE, "ARM", "Mali-G57", listOf(8), samsungFonts),
        Device("Google Pixel 9 Pro", 410, 914, 3.0, PHONE, "ARM", "Mali-G715", listOf(8), pixelFonts),
        Device("Google Pixel 8", 412, 915, 2.625, PHONE, "ARM", "Mali-G715", listOf(9), pixelFonts),
        Device("Google Pixel 7a", 412, 915, 2.625, PHONE, "ARM", "Mali-G710", listOf(8), pixelFonts),
        Device("Xiaomi 14", 393, 873, 3.0, PHONE, "Qualcomm", "Adreno (TM) 750", listOf(8), xiaomiFonts),
        Device("Xiaomi Redmi Note 13", 393, 873, 2.75, PHONE, "Qualcomm", "Adreno (TM) 610", listOf(8), xiaomiFonts),
        Device("POCO X6 Pro", 393, 873, 2.75, PHONE, "ARM", "Mali-G615", listOf(8), xiaomiFonts),
        Device("OnePlus 12", 412, 919, 3.5, PHONE, "Qualcomm", "Adreno (TM) 750", listOf(8), oneplusFonts),
        Device("OnePlus Nord 3", 412, 915, 2.625, PHONE, "ARM", "Mali-G710", listOf(8), oneplusFonts),
        Device("Motorola Edge 40", 412, 915, 2.625, PHONE, "ARM", "Mali-G77", listOf(8), motoFonts),
        Device("Motorola Moto G84", 393, 873, 2.75, PHONE, "Qualcomm", "Adreno (TM) 619", listOf(8), motoFonts),
        Device("Nothing Phone (2)", 412, 915, 2.625, PHONE, "Qualcomm", "Adreno (TM) 730", listOf(8), nothingFonts),
        Device("Sony Xperia 10 V", 360, 840, 3.0, PHONE, "Qualcomm", "Adreno (TM) 619", listOf(8), sonyFonts),
        Device("OPPO Reno 11", 412, 915, 2.625, PHONE, "ARM", "Mali-G610", listOf(8), oppoFonts),
        Device("Samsung Galaxy Tab S9", 800, 1280, 2.0, TABLET, "Qualcomm", "Adreno (TM) 740", listOf(8), samsungFonts),
        Device("Samsung Galaxy Tab A9+", 800, 1340, 1.5, TABLET, "Qualcomm", "Adreno (TM) 619", listOf(8), samsungFonts),
        Device("Google Pixel Tablet", 1280, 800, 2.0, TABLET, "ARM", "Mali-G710", listOf(8), pixelFonts),
        Device("Xiaomi Pad 6", 1200, 800, 2.2, TABLET, "Qualcomm", "Adreno (TM) 650", listOf(8), xiaomiFonts),
        Device("Lenovo Tab P12", 1490, 930, 2.0, TABLET, "ARM", "Mali-G77", listOf(8)),
        Device("Windows PC (NVIDIA)", 1920, 1080, 1.0, WINDOWS, "Google Inc. (NVIDIA)", "ANGLE (NVIDIA, NVIDIA GeForce GTX 1660 Direct3D11 vs_5_0 ps_5_0)", listOf(6, 8, 12)),
        Device("Windows PC (NVIDIA RTX)", 2560, 1440, 1.0, WINDOWS, "Google Inc. (NVIDIA)", "ANGLE (NVIDIA, NVIDIA GeForce RTX 3060 Direct3D11 vs_5_0 ps_5_0)", listOf(8, 12, 16)),
        Device("Windows laptop (Intel)", 1536, 864, 1.25, WINDOWS, "Google Inc. (Intel)", "ANGLE (Intel, Intel(R) UHD Graphics Direct3D11 vs_5_0 ps_5_0)", listOf(4, 8)),
        Device("Windows laptop (Intel Iris)", 1920, 1200, 1.5, WINDOWS, "Google Inc. (Intel)", "ANGLE (Intel, Intel(R) Iris(R) Xe Graphics Direct3D11 vs_5_0 ps_5_0)", listOf(8, 12)),
        Device("Windows PC (AMD)", 2560, 1440, 1.0, WINDOWS, "Google Inc. (AMD)", "ANGLE (AMD, AMD Radeon RX 6600 Direct3D11 vs_5_0 ps_5_0)", listOf(8, 12, 16)),
        Device("Windows laptop (AMD)", 1366, 768, 1.0, WINDOWS, "Google Inc. (AMD)", "ANGLE (AMD, AMD Radeon(TM) Graphics Direct3D11 vs_5_0 ps_5_0)", listOf(6, 8)),
        Device("MacBook Air", 1470, 956, 2.0, MAC, "Apple", "Apple M2", listOf(8)),
        Device("MacBook Pro", 1512, 982, 2.0, MAC, "Apple", "Apple M3 Pro", listOf(11, 12)),
        Device("iMac", 2240, 1260, 2.0, MAC, "Apple", "Apple M1", listOf(8)),
        Device("Mac mini", 1920, 1080, 1.0, MAC, "Apple", "Apple M2", listOf(8)),
        Device("Linux PC (Intel)", 1920, 1080, 1.0, LINUX, "Intel", "Mesa Intel(R) UHD Graphics 630 (CFL GT2)", listOf(4, 8, 12)),
        Device("Linux laptop (AMD)", 1366, 768, 1.0, LINUX, "AMD", "AMD Radeon Graphics (renoir, LLVM 15.0.7, DRM 3.49)", listOf(8, 16)),
        Device("Linux PC (NVIDIA)", 2560, 1440, 1.0, LINUX, "NVIDIA Corporation", "NVIDIA GeForce GTX 1650/PCIe/SSE2", listOf(8, 12)),
        Device("iPhone 16 Pro", 402, 874, 3.0, IPHONE, "Apple Inc.", "Apple GPU", listOf(6)),
        Device("iPhone 15", 393, 852, 3.0, IPHONE, "Apple Inc.", "Apple GPU", listOf(6)),
        Device("iPhone 15 Pro Max", 430, 932, 3.0, IPHONE, "Apple Inc.", "Apple GPU", listOf(6)),
        Device("iPhone 14", 390, 844, 3.0, IPHONE, "Apple Inc.", "Apple GPU", listOf(6)),
        Device("iPhone SE", 375, 667, 2.0, IPHONE, "Apple Inc.", "Apple GPU", listOf(6))
    )

    private val windowsScreens = listOf(
        Triple(1920, 1080, 1.0), Triple(1366, 768, 1.0), Triple(1536, 864, 1.25), Triple(2560, 1440, 1.0),
        Triple(1440, 900, 1.0), Triple(1600, 900, 1.0), Triple(1280, 720, 1.5), Triple(1920, 1200, 1.0),
        Triple(1280, 1024, 1.0), Triple(3440, 1440, 1.0)
    )
    private val linuxScreens = listOf(
        Triple(1920, 1080, 1.0), Triple(1366, 768, 1.0), Triple(2560, 1440, 1.0), Triple(1600, 900, 1.0),
        Triple(1920, 1200, 1.0), Triple(1280, 800, 1.0)
    )

    private val androidBaseFonts = listOf("Roboto", "Noto Sans", "Noto Serif", "Droid Sans Mono", "Noto Color Emoji")
    private val androidExtraFonts = listOf(
        "Roboto Condensed", "Roboto Flex", "Roboto Serif", "Roboto Mono", "Cutive Mono", "Coming Soon",
        "Dancing Script", "Carrois Gothic SC", "Source Sans Pro", "Noto Sans Symbols", "Noto Sans CJK JP",
        "Noto Sans Arabic", "Noto Sans Hebrew", "Noto Sans Thai", "Noto Sans Devanagari", "Noto Naskh Arabic"
    )
    private val windowsBaseFonts = listOf(
        "Arial", "Calibri", "Cambria", "Consolas", "Courier New", "Georgia", "Segoe UI", "Tahoma",
        "Times New Roman", "Trebuchet MS", "Verdana"
    )
    private val windowsExtraFonts = listOf(
        "Arial Black", "Bahnschrift", "Cambria Math", "Candara", "Comic Sans MS", "Constantia", "Corbel",
        "Ebrima", "Franklin Gothic Medium", "Gabriola", "Gadugi", "Impact", "Ink Free", "Javanese Text",
        "Leelawadee UI", "Lucida Console", "Lucida Sans Unicode", "Malgun Gothic", "Marlett",
        "Microsoft Himalaya", "Microsoft JhengHei", "Microsoft Sans Serif", "Microsoft YaHei", "MS Gothic",
        "MV Boli", "Myanmar Text", "Nirmala UI", "Palatino Linotype", "Segoe Print", "Segoe Script",
        "Segoe UI Emoji", "Segoe UI Symbol", "SimSun", "Sitka Text", "Sylfaen", "Symbol", "Webdings",
        "Wingdings", "Yu Gothic", "Aptos", "Book Antiqua", "Bookman Old Style", "Century Gothic", "Garamond",
        "Haettenschweiler", "Monotype Corsiva", "Agency FB", "Algerian", "Bodoni MT", "Calisto MT",
        "Castellar", "Gill Sans MT", "Lucida Bright", "Rockwell", "Tw Cen MT", "Century Schoolbook"
    )
    private val macBaseFonts = listOf(
        "Helvetica", "Helvetica Neue", "Arial", "Times", "Times New Roman", "Courier", "Courier New",
        "Georgia", "Verdana", "Menlo", "Monaco", "Lucida Grande"
    )
    private val macExtraFonts = listOf(
        "American Typewriter", "Andale Mono", "Arial Black", "Arial Narrow", "Arial Rounded MT Bold", "Avenir",
        "Avenir Next", "Baskerville", "Big Caslon", "Bradley Hand", "Brush Script MT", "Chalkboard",
        "Chalkboard SE", "Charter", "Cochin", "Comic Sans MS", "Copperplate", "Didot", "Futura", "Geneva",
        "Gill Sans", "Herculanum", "Hoefler Text", "Impact", "Luminari", "Marker Felt", "Noteworthy",
        "Optima", "Palatino", "Papyrus", "Phosphate", "Rockwell", "Savoye LET", "Skia", "Snell Roundhand",
        "Tahoma", "Trattatello", "Trebuchet MS", "Zapfino", "SF Pro", "SF Mono", "New York"
    )
    private val linuxBaseFonts = listOf(
        "DejaVu Sans", "DejaVu Sans Mono", "DejaVu Serif", "Liberation Sans", "Liberation Serif", "Liberation Mono"
    )
    private val linuxExtraFonts = listOf(
        "Noto Sans", "Noto Serif", "Noto Sans Mono", "Noto Color Emoji", "Ubuntu", "Ubuntu Mono", "Cantarell",
        "FreeSans", "FreeSerif", "FreeMono", "Nimbus Sans", "Nimbus Roman", "Nimbus Mono PS", "URW Bookman",
        "URW Gothic", "C059", "P052", "Droid Sans Fallback", "Bitstream Vera Sans", "Open Sans", "Fira Sans",
        "Source Code Pro", "Hack", "Inter", "Lato", "Roboto"
    )
    private val iosBaseFonts = listOf(
        "Helvetica", "Helvetica Neue", "Arial", "Times New Roman", "Courier", "Courier New", "Georgia", "Verdana"
    )
    private val iosExtraFonts = listOf(
        "Trebuchet MS", "Avenir", "Avenir Next", "Baskerville", "Didot", "Futura", "Gill Sans", "Menlo",
        "Optima", "Palatino", "Papyrus", "Noteworthy", "Chalkboard SE", "Marker Felt", "Snell Roundhand",
        "Zapfino", "American Typewriter", "Copperplate", "Party LET", "Savoye LET", "Kailasa", "Thonburi",
        "Bodoni 72", "Charter", "Rockwell", "Academy Engraved LET", "Arial Rounded MT Bold"
    )

    private val englishVariants = listOf(
        listOf("en-US", "en"),
        listOf("en-GB", "en"),
        listOf("en-CA", "en"),
        listOf("en-AU", "en"),
        listOf("en-IE", "en"),
        listOf("en-NZ", "en"),
        listOf("en-US"),
        listOf("en-GB"),
        listOf("en")
    )
    private val secondLanguages = listOf("fr", "de", "es", "it", "nl", "pl", "pt", "sv", "da", "fi", "nb", "cs")

    private val timeZones = listOf(
        "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles", "America/Toronto",
        "America/Phoenix", "America/Vancouver", "America/Halifax", "Europe/London", "Europe/Dublin",
        "Europe/Berlin", "Europe/Paris", "Europe/Amsterdam", "Europe/Madrid", "Europe/Warsaw",
        "Europe/Stockholm", "Europe/Rome", "Europe/Vienna", "Europe/Prague", "Europe/Helsinki",
        "Europe/Lisbon", "Australia/Sydney", "Australia/Perth", "Australia/Brisbane", "Asia/Singapore",
        "Asia/Tokyo", "Asia/Dubai", "Pacific/Auckland", "UTC"
    )

    private val mobileGl = mapOf(
        "maxTexture" to listOf(4096, 8192, 16384),
        "maxRenderbuffer" to listOf(4096, 8192, 16384),
        "maxViewport" to listOf(8192, 16384),
        "maxVertexUniforms" to listOf(256, 512, 1024),
        "maxFragmentUniforms" to listOf(224, 256, 1024),
        "maxVaryings" to listOf(15, 16, 31, 32),
        "maxAttribs" to listOf(16, 32),
        "maxTextureUnits" to listOf(16, 32)
    )
    private val desktopGl = mapOf(
        "maxTexture" to listOf(8192, 16384, 32768),
        "maxRenderbuffer" to listOf(8192, 16384, 32768),
        "maxViewport" to listOf(16384, 32768),
        "maxVertexUniforms" to listOf(1024, 4096),
        "maxFragmentUniforms" to listOf(1024, 2048, 4096),
        "maxVaryings" to listOf(30, 31, 32),
        "maxAttribs" to listOf(16),
        "maxTextureUnits" to listOf(16, 32)
    )

    data class Choice(val label: String, val group: String)

    fun deviceChoices(): List<Choice> = devices.map { d ->
        Choice(
            d.label,
            when (d.kind) {
                PHONE -> "Android phones"
                TABLET -> "Android tablets"
                WINDOWS -> "Windows"
                MAC -> "Mac"
                LINUX -> "Linux"
                else -> "iPhone"
            }
        )
    }

    private fun geckoMajor(): String = runCatching {
        Regex("rv:(\\d+)").find(GeckoSession.getDefaultUserAgent())?.groupValues?.get(1)
    }.getOrNull() ?: "140"

    private fun subset(base: List<String>, extra: List<String>, brand: List<String>): List<String> {
        val keep = 35 + rnd.nextInt(55)
        val out = ArrayList<String>(base)
        extra.forEach { if (chance(keep)) out += it }
        brand.forEach { if (chance(80)) out += it }
        return out.distinct()
    }

    fun create(
        range: SuperDeviceRange,
        pickLabel: String,
        randomTimeZone: Boolean,
        avoidUserAgents: Set<String> = emptySet()
    ): SuperIdentity {
        var last: SuperIdentity? = null
        repeat(12) {
            val made = build(range, pickLabel, randomTimeZone)
            if (made.ua !in avoidUserAgents) return made
            last = made
        }
        return last!!
    }

    private fun build(range: SuperDeviceRange, pickLabel: String, randomTimeZone: Boolean): SuperIdentity {
        val kinds = when (range) {
            SuperDeviceRange.ANY -> setOf(PHONE, TABLET, WINDOWS, MAC, LINUX, IPHONE)
            SuperDeviceRange.PHONES -> setOf(PHONE)
            SuperDeviceRange.PHONES_TABLETS -> setOf(PHONE, TABLET)
            SuperDeviceRange.COMPUTERS -> setOf(WINDOWS, MAC, LINUX)
            SuperDeviceRange.IPHONES -> setOf(IPHONE)
        }
        val d = devices.firstOrNull { pickLabel.isNotEmpty() && it.label == pickLabel }
            ?: devices.filter { it.kind in kinds }.pick()
        val v = geckoMajor()
        val android = listOf("11", "12", "13", "14", "15", "16").pick()
        val ios = listOf("17_6", "17_7", "18_0", "18_1", "18_2", "18_3", "18_5").pick()
        val ua: String
        val platform: String
        val oscpu: String
        val appVersion: String
        when (d.kind) {
            PHONE, TABLET -> {
                val form = if (d.kind == TABLET) "Tablet" else "Mobile"
                ua = "Mozilla/5.0 (Android $android; $form; rv:$v.0) Gecko/$v.0 Firefox/$v.0"
                platform = listOf("Linux aarch64", "Linux aarch64", "Linux armv8l").pick()
                oscpu = platform
                appVersion = "5.0 (Android $android)"
            }
            WINDOWS -> {
                ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:$v.0) Gecko/20100101 Firefox/$v.0"
                platform = "Win32"
                oscpu = "Windows NT 10.0; Win64; x64"
                appVersion = "5.0 (Windows)"
            }
            MAC -> {
                ua = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:$v.0) Gecko/20100101 Firefox/$v.0"
                platform = "MacIntel"
                oscpu = "Intel Mac OS X 10.15"
                appVersion = "5.0 (Macintosh)"
            }
            LINUX -> {
                val distro = listOf("X11; Ubuntu; Linux x86_64", "X11; Fedora; Linux x86_64", "X11; Ubuntu; Linux x86_64").pick()
                ua = "Mozilla/5.0 ($distro; rv:$v.0) Gecko/20100101 Firefox/$v.0"
                platform = "Linux x86_64"
                oscpu = "Linux x86_64"
                appVersion = "5.0 (X11)"
            }
            else -> {
                ua = "Mozilla/5.0 (iPhone; CPU iPhone OS $ios like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) FxiOS/$v.0 Mobile/15E148 Safari/605.1.15"
                platform = "iPhone"
                oscpu = ""
                appVersion = ua.removePrefix("Mozilla/")
            }
        }
        val desktop = d.kind == WINDOWS || d.kind == MAC || d.kind == LINUX
        val screen = when (d.kind) {
            WINDOWS -> if (chance(50)) Triple(d.w, d.h, d.dpr) else windowsScreens.pick()
            LINUX -> if (chance(50)) Triple(d.w, d.h, d.dpr) else linuxScreens.pick()
            else -> Triple(d.w, d.h, d.dpr)
        }
        val bar = when (d.kind) {
            WINDOWS -> listOf(40, 48).pick()
            MAC -> 25
            LINUX -> listOf(0, 27, 32, 48).pick()
            else -> 0
        }
        val label = when (d.kind) {
            PHONE, TABLET -> "${d.label} (Android $android)"
            IPHONE -> "${d.label} (iOS ${ios.replace('_', '.')})"
            else -> d.label
        }
        val fonts = when (d.kind) {
            PHONE, TABLET -> subset(androidBaseFonts, androidExtraFonts, d.brandFonts)
            WINDOWS -> subset(windowsBaseFonts, windowsExtraFonts, emptyList())
            MAC -> subset(macBaseFonts, macExtraFonts, emptyList())
            LINUX -> subset(linuxBaseFonts, linuxExtraFonts, emptyList())
            else -> subset(iosBaseFonts, iosExtraFonts, emptyList())
        }
        val glTable = if (desktop) desktopGl else mobileGl
        val glLimits = glTable.mapValues { (_, options) -> options.pick() }
        val languages = englishVariants.pick().toMutableList()
        if (chance(45)) languages += secondLanguages.pick()
        val touch = when (d.kind) {
            PHONE, TABLET -> listOf(5, 10).pick()
            IPHONE -> 5
            WINDOWS -> if (chance(15)) 10 else 0
            else -> 0
        }
        return SuperIdentity(
            id = UUID.randomUUID().toString(),
            label = label,
            ua = ua,
            platform = platform,
            oscpu = oscpu,
            appVersion = appVersion,
            vendor = if (d.kind == IPHONE) "Apple Computer, Inc." else "",
            desktop = desktop,
            screenWidth = screen.first,
            screenHeight = screen.second,
            availHeight = screen.second - bar,
            availTop = if (d.kind == MAC) 25 else 0,
            pixelRatio = screen.third,
            colorDepth = if (d.kind == MAC) 30 else 24,
            cores = d.cores.pick(),
            touchPoints = touch,
            languages = languages,
            timeZone = if (randomTimeZone) timeZones.pick() else "",
            glVendor = d.glVendor,
            glRenderer = d.glRenderer,
            glLimits = glLimits,
            fonts = fonts,
            doNotTrack = chance(30),
            pdfViewer = desktop,
            canvasNoise = 1 + rnd.nextInt(3),
            audioNoise = 1 + rnd.nextInt(3),
            rectNoise = chance(70),
            voiceShare = 40 + rnd.nextInt(61),
            seed = (rnd.nextInt(Int.MAX_VALUE - 1) + 1).toLong()
        )
    }
}
