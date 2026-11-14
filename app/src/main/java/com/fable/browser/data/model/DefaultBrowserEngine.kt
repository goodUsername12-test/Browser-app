package com.fable.browser.data.model

enum class DefaultBrowserEngine(val label: String, val summary: String) {
    BLINK_GECKO_AUTO(
        label = "Blink × Gecko Smart Auto (Beta)",
        summary = "Uses Blink × Gecko (Beta) on 3D games, WebGL, WebCodecs video tools, and benchmarks; GeckoView on static pages"
    ),
    BLINK_GECKO_ALL(
        label = "Blink × Gecko Default (Beta)",
        summary = "Runs Blink × Gecko (Beta) by default on all websites and new tabs while keeping Gecko privacy & tracker blocking active"
    ),
    GECKO_ONLY(
        label = "GeckoView Classic",
        summary = "Runs pure Mozilla GeckoView by default on all websites unless toggled in the browser menu"
    )
}
