package com.fable.browser.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fable.browser.engine.WebsiteSpeedEngine
import com.fable.browser.ui.components.WebsiteSpeedPanel
import kotlin.math.roundToInt
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fable.browser.data.model.PerformanceProfile
import com.fable.browser.data.model.TabUnloadMode
import com.fable.browser.data.model.MemoryMode
import com.fable.browser.ui.components.SettingRow
import com.fable.browser.ui.components.ListDivider
import com.fable.browser.ui.components.RadioGroup
import com.fable.browser.ui.components.SectionHeader
import com.fable.browser.ui.components.SliderRow
import com.fable.browser.ui.components.SwitchRow

private data class CeSizePreset(val name: String, val w: Int, val h: Int, val sc: Int)

@Composable
fun PerformanceSettingsScreen(
    onBack: () -> Unit,
    onOpenHybridBeta: () -> Unit = {},
    onOpenBattery: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val profile = settings.performanceProfile
    val profileOptions = if (profile == PerformanceProfile.CUSTOM) {
        PerformanceProfile.entries.toList()
    } else {
        PerformanceProfile.entries.filter { it != PerformanceProfile.CUSTOM }
    }

    val deviceContext = androidx.compose.ui.platform.LocalContext.current
    val weakDevice = remember { com.fable.browser.engine.MemoryPolicy.isWeakDevice(deviceContext) }
    val deviceRamGb = remember { com.fable.browser.engine.MemoryPolicy.totalRamMb(deviceContext) / 1024.0 }
    val deviceCores = remember { com.fable.browser.engine.MemoryPolicy.cpuCores() }

    SettingsPage(title = "Performance", onBack = onBack, viewModel = viewModel) {
        SectionHeader("Battery & Eco Gaming (Rust Power Governor)")
        SettingRow(
            title = "Battery Saver & In-Game Eco Controls",
            subtitle = "Set 3D game FPS cap (${settings.batteryGameMaxFps} FPS), 3D render resolution (${settings.batteryGameRenderScale}%), AFK idle drop, Eco CPU cores, and Thermal Guard",
            onClick = onOpenBattery
        )
        ListDivider()

        SectionHeader("Optimizer")
        SwitchRow(
            title = "Weak device optimizer",
            subtitle = "This device: " + String.format(java.util.Locale.US, "%.1f", deviceRamGb) + " GB RAM, $deviceCores cores. " +
                if (weakDevice) "Detected as a weak device: tabs unload sooner, caches shrink, background tabs are throttled and prefetching stops"
                else "Fast enough, so the optimizer stays idle until memory runs low",
            checked = settings.optimizerEnabled,
            onCheckedChange = viewModel::setOptimizerEnabled
        )
        ListDivider()

        SectionHeader("Profile")
        RadioGroup(
            options = profileOptions,
            selected = profile,
            label = { it.label },
            description = { it.summary },
            onSelect = viewModel::applyPerformanceProfile
        )
        ListDivider()

        SectionHeader("Profile switches")
        SwitchRow(
            title = "Filter-list blocking in normal tabs",
            subtitle = "Incognito tabs have their own switch in Settings > Incognito",
            checked = settings.adBlockEnabled,
            onCheckedChange = viewModel::setAdBlockEnabled
        )
        SwitchRow(
            title = "Fingerprint shield in normal tabs",
            subtitle = "Also cleans request headers. Can slow down games",
            checked = settings.normalTabShield,
            onCheckedChange = viewModel::setNormalTabShield
        )
        SwitchRow(
            title = "Strict tracking protection",
            subtitle = "Blocks more trackers. Some sites and game ads may break",
            checked = settings.strictTrackingProtection,
            onCheckedChange = viewModel::setStrictTrackingProtection
        )
        ListDivider()

        SectionHeader("Memory & speed")
        val context = androidx.compose.ui.platform.LocalContext.current
        val autoInfo = androidx.compose.runtime.remember { viewModel.autoMemoryInfo(context) }
        RadioGroup(
            options = MemoryMode.entries.toList(),
            selected = settings.memoryMode,
            label = {
                if (it == MemoryMode.AUTO) "Automatic (now: ${autoInfo.first.label}, ${(autoInfo.second + 512) / 1024} GB RAM)"
                else it.label
            },
            description = { it.summary },
            onSelect = viewModel::setMemoryMode
        )
        if (settings.memoryMode == MemoryMode.CUSTOM) {
            RadioGroup(
                options = TabUnloadMode.entries.toList(),
                selected = settings.tabUnloadMode,
                label = { it.label },
                description = { it.summary },
                onSelect = viewModel::setTabUnloadMode
            )
            if (settings.tabUnloadMode == TabUnloadMode.SMART) {
                SliderRow(
                    title = "Recent tabs kept loaded",
                    value = settings.keepLoadedTabs,
                    range = 1f..8f,
                    steps = 6,
                    onValueChange = viewModel::setKeepLoadedTabs
                )
            }
            SliderRow(
                title = "Unused tabs sleep after (0 = never)",
                value = settings.idleTabMinutes,
                range = 0f..60f,
                steps = 11,
                suffix = " min",
                onValueChange = viewModel::setIdleTabMinutes
            )
            SwitchRow(
                title = "Use more memory for speed",
                subtitle = "Bigger caches and instant Back/Forward. Best with 6 GB RAM or more",
                checked = settings.speedMemory,
                onCheckedChange = viewModel::setSpeedMemory
            )
        }
        SettingRow(
            title = "Keep a tab always instant",
            subtitle = "In the tab list, tap the pin on a tab. Pinned tabs never go to sleep, so switching to them is instant. Sleeping tabs reload when you open them"
        )
        ListDivider()

        SectionHeader("Cache")
        SwitchRow(
            title = "Clear cache automatically",
            subtitle = "Once a day when you leave the app, or sooner if it grows past 300 MB",
            checked = settings.autoClearCache,
            onCheckedChange = viewModel::setAutoClearCache
        )
        ListDivider()

        SectionHeader("Faster loading")
        SwitchRow(
            title = "Connect early to your search engine",
            subtitle = "Opens the connection when you tap the address bar, so results appear sooner. Normal tabs only",
            checked = settings.preconnectSearch,
            onCheckedChange = viewModel::setPreconnectSearch
        )
        SwitchRow(
            title = "Connect early to typed addresses",
            subtitle = "Connects to a site while you are still typing its address. Faster, but the site sees a connection even if you do not open it",
            checked = settings.preconnectTyped,
            onCheckedChange = viewModel::setPreconnectTyped
        )
        ListDivider()

        SectionHeader("Beta: GeckoView + Blink Combined Engine")
        SwitchRow(
            title = "GeckoView + Blink Combined Mode (Beta)",
            subtitle = "Runs GeckoView for privacy, tracker blocking, local-IP hiding, and Super/Incognito identity while using Chromium Blink simultaneously for 3D WebGL, WebAssembly, 90/120 Hz gaming, and fast font layout",
            checked = settings.hybridGeckoBlinkBeta,
            onCheckedChange = viewModel::setHybridGeckoBlinkBeta
        )
        SwitchRow(
            title = "Auto-combine on 3D game & benchmark sites",
            subtitle = "When Beta is on, automatically activates Combined Gecko + Blink on 3D WebGL games and benchmarks (Bloxd.io, Speedometer, Poki, CrazyGames, Krunker) while keeping normal pages on pure GeckoView unless toggled in the menu",
            checked = settings.hybridAutoForGames,
            onCheckedChange = viewModel::setHybridAutoForGames
        )
        SettingRow(
            title = "Edit all Beta Gecko + Blink settings",
            subtitle = "Configure custom domains, Cortex-A75 CPU boost, 1 ns timer slack, WebAssembly core count, camera anti-shutter, 20+ CPS clicks, and privacy controls",
            onClick = onOpenHybridBeta
        )
        ListDivider()

        SectionHeader("Gaming")
        SwitchRow(
            title = "Game mode in full screen",
            subtitle = "Gives the game all free memory and top priority so it does not crash while loading",
            checked = settings.gameMode,
            onCheckedChange = viewModel::setGameMode
        )
        SwitchRow(
            title = "Fix touch controls in games",
            subtitle = "Joystick + camera at the same time: each finger is measured on its own, and the browser never zooms or scrolls a game canvas. Fixes camera turning too fast or not at all",
            checked = settings.gameTouchFix,
            onCheckedChange = viewModel::setGameTouchFix
        )
        SwitchRow(
            title = "Highest screen refresh rate",
            subtitle = "Uses 90/120 Hz when your screen supports it for smoother games and scrolling",
            checked = settings.highRefreshRate,
            onCheckedChange = viewModel::setHighRefreshRate
        )
        SwitchRow(
            title = "Vibration for games",
            subtitle = "Lets pages use navigator.vibrate() for haptic feedback",
            checked = settings.vibrationApi,
            onCheckedChange = viewModel::setVibrationApi
        )
        ListDivider()

        SectionHeader("Website Speed Control (0.1x to 300x)")
        SwitchRow(
            title = "Enable website speed multiplier",
            subtitle = "Slow down (0.1x) or speed up (up to 300x) timers, rAF loops, CSS/Web animations, media, Web Audio, and workers live without modifying page source. Completely unloaded when OFF.",
            checked = settings.timeScaleEnabled,
            onCheckedChange = viewModel::setTimeScaleEnabled
        )
        if (settings.timeScaleEnabled) {
            val focusManager = LocalFocusManager.current
            var sliderTenths by remember { mutableIntStateOf(WebsiteSpeedEngine.clampTenths(settings.timeScaleMultiplierTenths)) }
            var draggingSpeed by remember { mutableStateOf(false) }
            LaunchedEffect(settings.timeScaleMultiplierTenths) {
                if (!draggingSpeed) {
                    sliderTenths = WebsiteSpeedEngine.clampTenths(settings.timeScaleMultiplierTenths)
                }
            }
            val displayTenths = if (draggingSpeed) sliderTenths else WebsiteSpeedEngine.clampTenths(settings.timeScaleMultiplierTenths)
            val curLabel = WebsiteSpeedEngine.formatTenths(displayTenths)
            var typedSpeed by remember(displayTenths) {
                mutableStateOf(
                    if (displayTenths % 10 == 0) (displayTenths / 10).toString()
                    else "${displayTenths / 10}.${displayTenths % 10}"
                )
            }
            var inputError by remember { mutableStateOf<String?>(null) }

            fun commitTypedSpeed() {
                val parsed = WebsiteSpeedEngine.parseSpeedInputToTenths(typedSpeed)
                if (parsed == null) {
                    inputError = "Enter a number from 0.1 to 300"
                } else {
                    inputError = null
                    sliderTenths = parsed
                    viewModel.setTimeScaleMultiplierTenths(parsed)
                    focusManager.clearFocus()
                }
            }

            // 1. Exact Speed Control Card (0.1x to 300x slider + exact numeric typing + nudge + presets)
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Current speed: $curLabel",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Applies live in GeckoView and Blink Combined without refreshing",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(onClick = {
                            sliderTenths = 10
                            inputError = null
                            viewModel.setTimeScaleMultiplierTenths(10)
                        }) {
                            Text("Reset 1x")
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    Slider(
                        value = WebsiteSpeedEngine.sliderPosFromTenths(displayTenths),
                        onValueChange = { pos ->
                            draggingSpeed = true
                            sliderTenths = WebsiteSpeedEngine.tenthsFromSliderPos(pos)
                        },
                        onValueChangeFinished = {
                            draggingSpeed = false
                            inputError = null
                            viewModel.setTimeScaleMultiplierTenths(sliderTenths)
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        listOf("0.1x", "2x", "10x", "100x", "300x").forEach { mark ->
                            Text(
                                text = mark,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Exact numeric speed input + -/+ fine controls
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(onClick = {
                            val next = WebsiteSpeedEngine.nudgeTenths(displayTenths, -1)
                            sliderTenths = next
                            inputError = null
                            viewModel.setTimeScaleMultiplierTenths(next)
                        }) {
                            Text("−")
                        }
                        OutlinedTextField(
                            value = typedSpeed,
                            onValueChange = { raw ->
                                if (raw.length <= 8) {
                                    typedSpeed = raw
                                    inputError = null
                                }
                            },
                            label = { Text("Exact speed (0.1–300)") },
                            singleLine = true,
                            isError = inputError != null,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Decimal,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { commitTypedSpeed() }),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(onClick = {
                            val next = WebsiteSpeedEngine.nudgeTenths(displayTenths, +1)
                            sliderTenths = next
                            inputError = null
                            viewModel.setTimeScaleMultiplierTenths(next)
                        }) {
                            Text("+")
                        }
                    }
                    if (inputError != null) {
                        Text(
                            text = inputError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { commitTypedSpeed() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Apply exact speed")
                    }

                    Spacer(Modifier.height(10.dp))

                    // Quick presets (scrollable row so exact 3x, 3.5x, 6x, 100x, 300x are 1 tap)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        WebsiteSpeedEngine.PRESET_TENTHS.forEach { presetTenths ->
                            val selected = displayTenths == presetTenths
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                modifier = Modifier
                                    .border(
                                        1.dp,
                                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        sliderTenths = presetTenths
                                        inputError = null
                                        viewModel.setTimeScaleMultiplierTenths(presetTenths)
                                    }
                            ) {
                                Text(
                                    text = WebsiteSpeedEngine.formatTenths(presetTenths),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 2. Comfortable Speed Control Panel Size & Live Preview Card
            var localScalePct by remember { mutableIntStateOf(WebsiteSpeedEngine.clampPanelScalePct(settings.timeScalePanelScalePct)) }
            var draggingScale by remember { mutableStateOf(false) }
            var previewExpanded by remember { mutableStateOf(true) }
            LaunchedEffect(settings.timeScalePanelScalePct) {
                if (!draggingScale) {
                    localScalePct = WebsiteSpeedEngine.clampPanelScalePct(settings.timeScalePanelScalePct)
                }
            }
            val shownScalePct = if (draggingScale) localScalePct else WebsiteSpeedEngine.clampPanelScalePct(settings.timeScalePanelScalePct)

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Speed Control Panel size: $shownScalePct%",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Drag the slider below and see the exact size live. Drag the panel on any webpage to move it wherever you want.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Slider(
                        value = shownScalePct.toFloat(),
                        onValueChange = { raw ->
                            draggingScale = true
                            val snapped = ((raw / 5f).roundToInt() * 5).coerceIn(
                                WebsiteSpeedEngine.MIN_PANEL_SCALE_PCT,
                                WebsiteSpeedEngine.MAX_PANEL_SCALE_PCT
                            )
                            localScalePct = snapped
                        },
                        onValueChangeFinished = {
                            draggingScale = false
                            viewModel.setTimeScalePanelScalePct(localScalePct)
                        },
                        valueRange = WebsiteSpeedEngine.MIN_PANEL_SCALE_PCT.toFloat()..WebsiteSpeedEngine.MAX_PANEL_SCALE_PCT.toFloat(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        listOf(65 to "Tiny", 80 to "Small", 100 to "Normal", 125 to "Large", 150 to "XL").forEach { (pct, name) ->
                            val selected = shownScalePct == pct
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                modifier = Modifier
                                    .border(
                                        1.dp,
                                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        localScalePct = pct
                                        viewModel.setTimeScalePanelScalePct(pct)
                                    }
                            ) {
                                Text(
                                    text = "$name ($pct%)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Live preview header + Expanded / Collapsed preview toggle
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Live actual-size preview",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (previewExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                            modifier = Modifier.clickable { previewExpanded = true }
                        ) {
                            Text(
                                text = "Expanded",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (previewExpanded) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (!previewExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                            modifier = Modifier.clickable { previewExpanded = false }
                        ) {
                            Text(
                                text = "Mini pill",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (!previewExpanded) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Real-size preview frame rendering the actual WebsiteSpeedPanel composable!
                    BoxWithConstraints(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0B1120), RoundedCornerShape(12.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                            .padding(vertical = 16.dp, horizontal = 8.dp)
                    ) {
                        WebsiteSpeedPanel(
                            tenths = displayTenths,
                            scalePct = shownScalePct,
                            expanded = previewExpanded,
                            onLiveTenthsChange = { t -> sliderTenths = t },
                            onCommitTenths = { t ->
                                sliderTenths = t
                                viewModel.setTimeScaleMultiplierTenths(t)
                            },
                            onToggleExpanded = { previewExpanded = !previewExpanded },
                            onTurnOff = {},
                            maxWidthDp = (maxWidth - 8.dp).coerceAtLeast(140.dp),
                            previewMode = true
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    val pxPct = (settings.timeScalePanelPosX * 100f).roundToInt()
                    val pyPct = (settings.timeScalePanelPosY * 100f).roundToInt()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Saved panel position: X $pxPct% · Y $pyPct%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(onClick = {
                            localScalePct = WebsiteSpeedEngine.DEFAULT_PANEL_SCALE_PCT
                            viewModel.resetTimeScalePanelLayout()
                        }) {
                            Text("Reset layout")
                        }
                    }
                }
            }

            SwitchRow(
                title = "Show moveable Speed Control Panel while ON",
                subtitle = "Draggable, resizable panel with 0.1x–300x slider, exact typing, collapse pill, and instant OFF (hidden when OFF)",
                checked = settings.timeScaleShowHud,
                onCheckedChange = viewModel::setTimeScaleShowHud
            )
            SwitchRow(
                title = "Scale CSS & Web Animations",
                subtitle = "Slows or speeds up moving CSS elements, keyframes, and transitions together with click hit-boxes",
                checked = settings.timeScaleCssAnimations,
                onCheckedChange = viewModel::setTimeScaleCssAnimations
            )
            SwitchRow(
                title = "Scale video, audio & Web Audio playback",
                subtitle = "Adjusts HTML5 media and Web Audio playbackRate within safe browser limits",
                checked = settings.timeScaleMedia,
                onCheckedChange = viewModel::setTimeScaleMedia
            )
            SwitchRow(
                title = "Scale Web Workers (best-effort)",
                subtitle = "Propagates stealth virtual clock to same-origin classic Web Workers up to 300x",
                checked = settings.timeScaleWorkers,
                onCheckedChange = viewModel::setTimeScaleWorkers
            )
        }
        ListDivider()

        SectionHeader("Memory Engine (Mobile Cheat Engine)")
        SwitchRow(
            title = "Enable Memory Engine (Self-Process + Page/WASM)",
            subtitle = "Non-root mobile Cheat Engine inside Fable: First/Next/Unknown/Group/XOR/Pointer scans, 5-Rule Titanium Freeze (= / >= / <= / [a..b] / +=/s), Write-Watcher Spy, Live Hex Editor & Struct Dissector, and Persistent Cheat Tables. Completely unloaded when OFF.",
            checked = settings.memoryEngineEnabled,
            onCheckedChange = viewModel::setMemoryEngineEnabled
        )
        run {
            val savedWidth = com.fable.browser.engine.WebsiteMemoryEngine.clampPanelWidthPct(settings.memoryEnginePanelWidthPct)
            val savedHeight = com.fable.browser.engine.WebsiteMemoryEngine.clampPanelHeightPct(settings.memoryEnginePanelHeightPct)
            val savedScale = com.fable.browser.engine.WebsiteMemoryEngine.clampPanelScalePct(settings.memoryEnginePanelScalePct)
            var draftWidth by remember { mutableIntStateOf(savedWidth) }
            var draftHeight by remember { mutableIntStateOf(savedHeight) }
            var draftScale by remember { mutableIntStateOf(savedScale) }
            var previewTabIdx by remember { mutableIntStateOf(0) } // 0=Scan, 1=Watch/Freeze, 2=Hex/Struct, 3=Mini pill
            var previewLandscape by remember { mutableStateOf(false) }

            LaunchedEffect(savedWidth, savedHeight, savedScale) {
                draftWidth = savedWidth
                draftHeight = savedHeight
                draftScale = savedScale
            }
            val hasPendingSizeChange = (draftWidth != savedWidth) || (draftHeight != savedHeight) || (draftScale != savedScale)

            val sampleMemState = remember {
                com.fable.browser.engine.MemoryEngineUiState(
                    totalMatches = 4,
                    hasActiveScan = true,
                    canUndo = true,
                    undoDepth = 2,
                    wasmCount = 1,
                    bufCount = 2,
                    detectedEngines = listOf("Unity"),
                    results = listOf(
                        com.fable.browser.engine.MemScanResultItem("wasm#0+0x40:i32", "1500", "1200", "i32", "wasm", "Unity"),
                        com.fable.browser.engine.MemScanResultItem("js:player.coins", "1500", "1500", "i32", "js", "Unity"),
                        com.fable.browser.engine.MemScanResultItem("wasm#0+0x304:i32^@0x300", "1500", "900", "i32", "wasm", "Unity", xorMode = 2),
                        com.fable.browser.engine.MemScanResultItem("ls:save$.gems", "1500", "1000", "i32", "lsjson", "Unity")
                    ),
                    watchList = listOf(
                        com.fable.browser.engine.MemWatchEntry(
                            id = "preview_1",
                            scope = com.fable.browser.engine.MemScope.PAGE_WASM,
                            addr = "js:player.coins",
                            label = "Coins (Accessor)",
                            dataType = com.fable.browser.engine.MemDataType.INT32,
                            currentValue = "999999",
                            originalValue = "1500",
                            frozen = true,
                            frozenValue = "999999",
                            freezeRule = com.fable.browser.engine.MemFreezeRule.EXACT,
                            freezeMode = "accessor-trap",
                            blockedWrites = 18L,
                            freezeStatus = "enforcing"
                        ),
                        com.fable.browser.engine.MemWatchEntry(
                            id = "preview_2",
                            scope = com.fable.browser.engine.MemScope.PAGE_WASM,
                            addr = "wasm#0+0x40:i32",
                            label = "Health Clamp",
                            dataType = com.fable.browser.engine.MemDataType.INT32,
                            currentValue = "100",
                            originalValue = "100",
                            frozen = true,
                            frozenValue = "50..100",
                            freezeRule = com.fable.browser.engine.MemFreezeRule.RANGE,
                            freezeMode = "250Hz",
                            blockedWrites = 7L,
                            freezeStatus = "enforcing",
                            tracing = true,
                            traceCount = 3
                        )
                    ),
                    hex = com.fable.browser.engine.MemHexState(
                        targetAddr = "wasm#0+0x40:i32",
                        scope = com.fable.browser.engine.MemScope.PAGE_WASM,
                        displayAddr = "0x40",
                        baseOffset = 0x40L,
                        hexBytes = "DC0500000000C8420100000064000000E7913C5AE7973C5A0000000010270000"
                    ),
                    structFields = listOf(
                        com.fable.browser.engine.MemStructField("wasm#0+0x40:i32", "+0x0", "i32", "1500", "1500", "0.000", "1500"),
                        com.fable.browser.engine.MemStructField("wasm#0+0x44:f32", "+0x4", "f32", "100.0", "1120403456", "100.000", "1120403456")
                    ),
                    statusText = "Preview: drag Horizontal, Vertical & Scale sliders above"
                )
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "CE Panel Size Manager (Horizontal × Vertical)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (hasPendingSizeChange) {
                            "Saved: ${savedWidth}% W × ${savedHeight}% H (${savedScale}% text)  ->  Draft: ${draftWidth}% W × ${draftHeight}% H (${draftScale}% text)"
                        } else {
                            "Active: ${savedWidth}% Horizontal × ${savedHeight}% Vertical · ${savedScale}% Text/UI Scale"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasPendingSizeChange) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (hasPendingSizeChange) FontWeight.SemiBold else FontWeight.Normal
                    )

                    Spacer(Modifier.height(10.dp))

                    // 6 One-tap Size Presets
                    val presets = listOf(
                        CeSizePreset("Mini", 65, 45, 80),
                        CeSizePreset("Compact", 75, 52, 85),
                        CeSizePreset("Balanced", 88, 62, 100),
                        CeSizePreset("Wide", 100, 55, 100),
                        CeSizePreset("Tall", 85, 82, 100),
                        CeSizePreset("Max Dock", 100, 92, 110)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        presets.forEach { p ->
                            val isSel = draftWidth == p.w && draftHeight == p.h && draftScale == p.sc
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                modifier = Modifier
                                    .border(
                                        1.dp,
                                        if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        draftWidth = p.w
                                        draftHeight = p.h
                                        draftScale = p.sc
                                    }
                            ) {
                                Text(
                                    text = "${p.name} (${p.w}×${p.h})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // 1. Horizontal Size (Width %) Slider + Fine Nudge
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Horizontal size (width): $draftWidth%",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.clickable {
                                draftWidth = com.fable.browser.engine.WebsiteMemoryEngine.clampPanelWidthPct(draftWidth - 5)
                            }
                        ) { Text(" − ", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) }
                        Spacer(Modifier.width(4.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.clickable {
                                draftWidth = com.fable.browser.engine.WebsiteMemoryEngine.clampPanelWidthPct(draftWidth + 5)
                            }
                        ) { Text(" + ", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) }
                    }
                    Slider(
                        value = draftWidth.toFloat(),
                        onValueChange = { raw ->
                            draftWidth = com.fable.browser.engine.WebsiteMemoryEngine.clampPanelWidthPct((raw / 5f).roundToInt() * 5)
                        },
                        valueRange = com.fable.browser.engine.WebsiteMemoryEngine.MIN_PANEL_WIDTH_PCT.toFloat()..com.fable.browser.engine.WebsiteMemoryEngine.MAX_PANEL_WIDTH_PCT.toFloat(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // 2. Vertical Size (Height %) Slider + Fine Nudge
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Vertical size (height): $draftHeight%",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.clickable {
                                draftHeight = com.fable.browser.engine.WebsiteMemoryEngine.clampPanelHeightPct(draftHeight - 5)
                            }
                        ) { Text(" − ", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) }
                        Spacer(Modifier.width(4.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.clickable {
                                draftHeight = com.fable.browser.engine.WebsiteMemoryEngine.clampPanelHeightPct(draftHeight + 5)
                            }
                        ) { Text(" + ", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) }
                    }
                    Slider(
                        value = draftHeight.toFloat(),
                        onValueChange = { raw ->
                            draftHeight = com.fable.browser.engine.WebsiteMemoryEngine.clampPanelHeightPct((raw / 5f).roundToInt() * 5)
                        },
                        valueRange = com.fable.browser.engine.WebsiteMemoryEngine.MIN_PANEL_HEIGHT_PCT.toFloat()..com.fable.browser.engine.WebsiteMemoryEngine.MAX_PANEL_HEIGHT_PCT.toFloat(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // 3. UI & Text Scale Slider + Fine Nudge
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "UI & text density scale: $draftScale%",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.clickable {
                                draftScale = com.fable.browser.engine.WebsiteMemoryEngine.clampPanelScalePct(draftScale - 5)
                            }
                        ) { Text(" − ", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) }
                        Spacer(Modifier.width(4.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.clickable {
                                draftScale = com.fable.browser.engine.WebsiteMemoryEngine.clampPanelScalePct(draftScale + 5)
                            }
                        ) { Text(" + ", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) }
                    }
                    Slider(
                        value = draftScale.toFloat(),
                        onValueChange = { raw ->
                            draftScale = com.fable.browser.engine.WebsiteMemoryEngine.clampPanelScalePct((raw / 5f).roundToInt() * 5)
                        },
                        valueRange = com.fable.browser.engine.WebsiteMemoryEngine.MIN_PANEL_SCALE_PCT.toFloat()..com.fable.browser.engine.WebsiteMemoryEngine.MAX_PANEL_SCALE_PCT.toFloat(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(6.dp))

                    // Orientation & Preview Tab Controls
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = "Live Preview:",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        listOf(false to "Portrait", true to "Landscape").forEach { (isLand, lbl) ->
                            val sel = previewLandscape == isLand
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (sel) Color(0xFF0F766E) else MaterialTheme.colorScheme.surface,
                                modifier = Modifier.clickable { previewLandscape = isLand }
                            ) {
                                Text(
                                    text = lbl,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (sel) Color.White else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                        listOf(0 to "Scan", 1 to "Watch", 2 to "Hex", 3 to "Pill").forEach { (idx, lbl) ->
                            val sel = previewTabIdx == idx
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                modifier = Modifier.clickable { previewTabIdx = idx }
                            ) {
                                Text(
                                    text = lbl,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Simulated Phone Viewport Frame showing exact Horizontal & Vertical footprint over webpage!
                    BoxWithConstraints(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF060B16), RoundedCornerShape(14.dp))
                            .border(
                                width = if (hasPendingSizeChange) 1.5.dp else 1.dp,
                                color = if (hasPendingSizeChange) Color(0xFF10B981) else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .padding(8.dp)
                    ) {
                        val simMaxW = (maxWidth - 4.dp).coerceAtLeast(200.dp)
                        val simHeight = if (previewLandscape) 260.dp else 440.dp
                        val previewWdp = (simMaxW.value * (draftWidth / 100f)).roundToInt()
                        val previewHdp = (simHeight.value * (draftHeight / 100f)).roundToInt()
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "${if (previewLandscape) "Landscape" else "Portrait"} screen viewport · CE box ≈ ${previewWdp}×${previewHdp}dp ($draftWidth% W × $draftHeight% H)",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF94A3B8),
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .width(simMaxW)
                                    .height(simHeight)
                                    .background(Color(0xFF0F172A), RoundedCornerShape(12.dp))
                                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(12.dp))
                                    .padding(4.dp)
                            ) {
                                // Subtle mock webpage backdrop so user sees remaining visible game area
                                Column(
                                    verticalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth().height(simHeight - 16.dp).padding(6.dp)
                                ) {
                                    Text("fable://game-viewport (visible game area)", color = Color(0xFF334155), style = MaterialTheme.typography.labelSmall)
                                    Text("Bottom HUD / Controls", color = Color(0xFF334155), style = MaterialTheme.typography.labelSmall)
                                }
                                key(previewTabIdx, previewLandscape) {
                                    com.fable.browser.ui.components.MemoryEnginePanel(
                                        state = sampleMemState,
                                        widthPct = draftWidth,
                                        heightPct = draftHeight,
                                        scalePct = draftScale,
                                        expanded = previewTabIdx != 3,
                                        previewInitialTab = if (previewTabIdx in 0..2) previewTabIdx else 0,
                                        onSelectScope = {},
                                        onSelectDataType = {},
                                        onSelectScanType = {},
                                        onFirstScan = { _, _ -> },
                                        onNextScan = { _, _ -> },
                                        onUndoScan = {},
                                        onResetScan = {},
                                        onAddWatch = {},
                                        onSetWatchValue = { _, _ -> },
                                        onRestoreWatchOriginal = {},
                                        onToggleWatchFreeze = {},
                                        onRemoveWatch = {},
                                        onUnfreezeAll = {},
                                        onInspectHex = { _, _, _ -> },
                                        onRefreshRegions = {},
                                        onToggleExpanded = { previewTabIdx = if (previewTabIdx == 3) 0 else 3 },
                                        onTurnOff = {},
                                        maxWidthDp = (simMaxW - 8.dp).coerceAtLeast(180.dp),
                                        maxHeightDp = (simHeight - 8.dp).coerceAtLeast(180.dp),
                                        previewMode = true
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = { viewModel.setMemoryEnginePanelSize(draftWidth, draftHeight, draftScale) },
                            enabled = hasPendingSizeChange,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (hasPendingSizeChange) "Apply (${draftWidth}%×${draftHeight}%)" else "Size Applied")
                        }
                        if (hasPendingSizeChange) {
                            OutlinedButton(onClick = {
                                draftWidth = savedWidth
                                draftHeight = savedHeight
                                draftScale = savedScale
                            }) {
                                Text("Revert")
                            }
                        }
                        OutlinedButton(onClick = {
                            draftWidth = com.fable.browser.engine.WebsiteMemoryEngine.DEFAULT_PANEL_WIDTH_PCT
                            draftHeight = com.fable.browser.engine.WebsiteMemoryEngine.DEFAULT_PANEL_HEIGHT_PCT
                            draftScale = com.fable.browser.engine.WebsiteMemoryEngine.DEFAULT_PANEL_SCALE_PCT
                            viewModel.resetMemoryEnginePanelLayout()
                        }) {
                            Text("Reset")
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Saved position: X ${(settings.memoryEnginePanelPosX * 100f).roundToInt()}% · Y ${(settings.memoryEnginePanelPosY * 100f).roundToInt()}% (drag floating header anytime)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (settings.memoryEngineEnabled) {
                SwitchRow(
                    title = "Show floating Memory Engine panel while ON",
                    subtitle = "Moveable, resizable panel with Scan, Watch/Freeze/Trace/Tables, Live Hex Editor & Struct Dissector, and Memory Map",
                    checked = settings.memoryEngineShowPanel,
                    onCheckedChange = viewModel::setMemoryEngineShowPanel
                )
                SwitchRow(
                    title = "Auto-Hook & Auto-Reattach Freeze on Page Refresh",
                    subtitle = "Automatically hooks pages at document_start on refresh without pressing Hook Reload, and re-arms frozen Watch entries cleanly.",
                    checked = settings.memoryEngineAutoReattach,
                    onCheckedChange = viewModel::setMemoryEngineAutoReattach
                )
                SwitchRow(
                    title = "Prefer literal Infinity for MAX / Unlimited (with auto-fallback)",
                    subtitle = "Attempts JavaScript Infinity where supported and automatically falls back to type MAX SAFE if a game converts Infinity to null/NaN/0.",
                    checked = settings.memoryEnginePreferInfinity,
                    onCheckedChange = viewModel::setMemoryEnginePreferInfinity
                )
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(text = "Performance & Battery Profile", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Balances watch-loop polling speed, freeze frequency, and battery consumption",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        com.fable.browser.engine.MemPerfProfile.entries.forEach { prof ->
                            val sel = settings.memoryEnginePerfProfile == prof.level
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { viewModel.setMemoryEnginePerfProfile(prof.level) }
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp, vertical = 9.dp)) {
                                    Text(
                                        text = prof.shortLabel,
                                        color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(
                        text = "Freeze Strength Mode",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Normal (60Hz/250Hz battery), Strong (250Hz/667Hz default), or MAX Titan (1000Hz MessageChannel burst + 2500Hz native + WASM grow survival)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        com.fable.browser.engine.MemFreezeStrength.entries.forEach { stg ->
                            val sel = settings.memoryEngineFreezeStrength == stg.level
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { viewModel.setMemoryEngineFreezeStrength(stg.level) }
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp)
                                ) {
                                    Text(
                                        text = stg.shortLabel,
                                        color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        ListDivider()

        SectionHeader("Live Performance Diagnostics")
        SwitchRow(
            title = "Live Performance HUD overlay",
            subtitle = "Shows cold launch ms, page load ms, live FPS, p95/p99 frame times, jank %, display Hz, memory MB, and active engine",
            checked = settings.perfHudEnabled,
            onCheckedChange = viewModel::setPerfHudEnabled
        )
        ListDivider()

        SectionHeader("Stability")
        SwitchRow(
            title = "Separate graphics process",
            subtitle = "Off (recommended): no freezes when switching apps or opening the keyboard on some phones (Xiaomi), and less RAM. On: a graphics driver crash only blanks the page instead of closing the app. Restart the app to apply",
            checked = settings.gpuProcess,
            onCheckedChange = viewModel::setGpuProcess
        )
        SwitchRow(
            title = "Freeze detector",
            subtitle = "If the screen is stuck for more than 2.5 seconds, saves what it was waiting for and shows it in the next crash report",
            checked = settings.freezeDetector,
            onCheckedChange = viewModel::setFreezeDetector
        )
        ListDivider()

        SectionHeader("Optional web features (restart the app after changing)")
        SwitchRow(
            title = "WebGPU (experimental)",
            subtitle = "Next-generation 3D. Not finished on Android: some games may fail or crash with it on",
            checked = settings.featureWebGpu,
            onCheckedChange = viewModel::setFeatureWebGpu
        )
        SwitchRow(
            title = "Draft WebGL extensions",
            subtitle = "Extra 3D extensions that are still being standardised",
            checked = settings.featureWebGlDraft,
            onCheckedChange = viewModel::setFeatureWebGlDraft
        )
        SwitchRow(
            title = "Experimental WebAssembly",
            subtitle = "Relaxed SIMD and other new WebAssembly features used by some game engines",
            checked = settings.featureWasmExperimental,
            onCheckedChange = viewModel::setFeatureWasmExperimental
        )
        SwitchRow(
            title = "Battery Status API",
            subtitle = "Lets sites read your battery level. Can be used to track you",
            checked = settings.featureBatteryApi,
            onCheckedChange = viewModel::setFeatureBatteryApi
        )
        SwitchRow(
            title = "Network Information API",
            subtitle = "Lets sites see your connection type. Can be used to track you",
            checked = settings.featureNetworkInfo,
            onCheckedChange = viewModel::setFeatureNetworkInfo
        )
        SwitchRow(
            title = "Speech recognition API",
            subtitle = "Turns on the API. The engine has no built-in speech service on Android, so many sites still cannot hear you",
            checked = settings.featureSpeechRecognition,
            onCheckedChange = viewModel::setFeatureSpeechRecognition
        )
    }
}
