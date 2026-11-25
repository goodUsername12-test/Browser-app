package com.fable.browser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fable.browser.engine.MemDataType
import com.fable.browser.engine.MemScanResultItem
import com.fable.browser.engine.MemScanType
import com.fable.browser.engine.MemScope
import com.fable.browser.engine.MemoryEngineUiState
import com.fable.browser.engine.WebsiteMemoryEngine
import kotlin.math.roundToInt

@Composable
fun MemoryEnginePanel(
    state: MemoryEngineUiState,
    scalePct: Int,
    expanded: Boolean,
    onSelectScope: (MemScope) -> Unit,
    onSelectDataType: (MemDataType) -> Unit,
    onSelectScanType: (MemScanType) -> Unit,
    onFirstScan: (String, String) -> Unit,
    onNextScan: (String, String) -> Unit,
    onUndoScan: () -> Unit,
    onResetScan: () -> Unit,
    onAddWatch: (MemScanResultItem) -> Unit,
    onSetWatchValue: (String, String) -> Unit,
    onRestoreWatchOriginal: (String) -> Unit,
    onToggleWatchFreeze: (String) -> Unit,
    onRemoveWatch: (String) -> Unit,
    onUnfreezeAll: () -> Unit,
    onInspectHex: (String, MemScope, Long) -> Unit,
    onRefreshRegions: () -> Unit,
    onToggleExpanded: () -> Unit,
    onTurnOff: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
    maxWidthDp: Dp = 360.dp,
    previewMode: Boolean = false,
    onRefreshScan: () -> Unit = {},
    onQuickEditResult: (MemScanResultItem, String) -> Unit = { _, _ -> },
    onWriteAllResults: (String) -> Unit = {},
    onReloadAndHook: () -> Unit = {},
    onSetScanTimeoutSec: (Int) -> Unit = {},
    onCancelScan: () -> Unit = {},
    onToggleSelectScanAddr: (String) -> Unit = {},
    onSelectAllScanResults: (List<String>) -> Unit = {},
    onClearScanSelection: () -> Unit = {},
    onAddSelectedToWatch: (Boolean) -> Unit = {},
    onEditSelectedScanResults: (String) -> Unit = {},
    onToggleSelectWatchId: (String) -> Unit = {},
    onSelectAllWatchEntries: () -> Unit = {},
    onClearWatchSelection: () -> Unit = {},
    onEditSelectedWatchEntries: (String) -> Unit = {},
    onFreezeSelectedWatchEntries: (Boolean) -> Unit = {},
    onRestoreSelectedWatchEntries: () -> Unit = {},
    onRemoveSelectedWatchEntries: () -> Unit = {}
) {
    val s = WebsiteMemoryEngine.clampPanelScalePct(scalePct) / 100f
    val corner = (14f * s).coerceIn(8f, 22f).dp
    val accent = Color(0xFF10B981)
    val bg = Color(0xF50B1220)
    val cardBg = Color(0xFF162033)
    val minBtnH = (34f * s).coerceIn(28f, 46f).dp

    if (!expanded) {
        val frozenCount = state.watchList.count { it.frozen }
        val pillLabel = if (frozenCount > 0) "CE · ${state.totalMatches} · F:$frozenCount" else "CE · ${state.totalMatches}"
        Surface(
            shape = RoundedCornerShape(corner),
            color = bg,
            shadowElevation = 6.dp,
            modifier = modifier
                .then(dragHandleModifier)
                .border(1.dp, accent, RoundedCornerShape(corner))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(
                    horizontal = (9f * s).coerceIn(6f, 15f).dp,
                    vertical = (6f * s).coerceIn(4f, 11f).dp
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.DragIndicator,
                    contentDescription = "Move Memory Engine",
                    tint = Color(0xFF94A3B8),
                    modifier = Modifier.size((16f * s).coerceIn(12f, 24f).dp)
                )
                Spacer(Modifier.width((4f * s).dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(onClick = onToggleExpanded)
                ) {
                    Text(
                        text = pillLabel,
                        color = accent,
                        fontSize = (12.5f * s).coerceIn(9f, 19f).sp,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = Icons.Filled.ExpandMore,
                        contentDescription = "Expand Memory Engine",
                        tint = Color(0xFFCBD5E1),
                        modifier = Modifier.size((16f * s).coerceIn(12f, 24f).dp)
                    )
                }
                Spacer(Modifier.width((6f * s).dp))
                Surface(
                    shape = RoundedCornerShape((7f * s).coerceIn(4f, 11f).dp),
                    color = Color(0xFFDC2626),
                    modifier = Modifier.clickable(enabled = !previewMode, onClick = onTurnOff)
                ) {
                    Text(
                        text = "OFF",
                        color = Color.White,
                        fontSize = (10f * s).coerceIn(7.5f, 15f).sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(
                            horizontal = (8f * s).coerceIn(5f, 12f).dp,
                            vertical = (4f * s).coerceIn(2.5f, 7f).dp
                        )
                    )
                }
            }
        }
        return
    }

    var activeTab by remember { mutableIntStateOf(0) }
    var query1 by remember { mutableStateOf("") }
    var query2 by remember { mutableStateOf("") }
    var showTypePicker by remember { mutableStateOf(false) }
    var showModePicker by remember { mutableStateOf(false) }
    var showTimeoutPicker by remember { mutableStateOf(false) }
    var sourceFilter by remember { mutableStateOf("all") } // all, wasm, buf, js, iframe
    var showBulkScanEdit by remember { mutableStateOf(false) }
    var bulkScanDraft by remember { mutableStateOf("") }
    var showBulkWatchEdit by remember { mutableStateOf(false) }
    var bulkWatchDraft by remember { mutableStateOf("") }
    var editingScanAddr by remember { mutableStateOf<String?>(null) }
    var scanEditDraft by remember { mutableStateOf("") }
    var editingWatchId by remember { mutableStateOf<String?>(null) }
    var editDraft by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    val panelWidth = (332f * s).dp.coerceAtMost(maxWidthDp)
    val hPad = (10f * s).coerceIn(7f, 16f).dp
    val vPad = (8f * s).coerceIn(6f, 13f).dp
    val fsTitle = (13f * s).coerceIn(9.5f, 19f).sp
    val fsBody = (11.5f * s).coerceIn(8.5f, 16f).sp
    val fsSmall = (10f * s).coerceIn(7.5f, 14.5f).sp
    val fsTiny = (9f * s).coerceIn(7f, 13f).sp

    Surface(
        shape = RoundedCornerShape(corner),
        color = bg,
        shadowElevation = 9.dp,
        modifier = modifier
            .width(panelWidth)
            .border(1.dp, accent, RoundedCornerShape(corner))
    ) {
        Column(modifier = Modifier.padding(horizontal = hPad, vertical = vPad)) {
            // Top Drag / Header Bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minBtnH)
                    .then(dragHandleModifier)
            ) {
                Icon(
                    imageVector = Icons.Filled.DragIndicator,
                    contentDescription = "Drag Memory Engine",
                    tint = Color(0xFF94A3B8),
                    modifier = Modifier.size((18f * s).coerceIn(13f, 26f).dp)
                )
                Spacer(Modifier.width((4f * s).dp))
                Text(
                    text = "Memory CE · ${state.scope.label}",
                    color = accent,
                    fontSize = fsTitle,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape((8f * s).coerceIn(5f, 11f).dp),
                    color = cardBg,
                    modifier = Modifier
                        .heightIn(min = (30f * s).coerceIn(24f, 40f).dp)
                        .clickable(onClick = onToggleExpanded)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (8f * s).dp)) {
                        Icon(
                            imageVector = Icons.Filled.ExpandLess,
                            contentDescription = "Hide to mini pill",
                            tint = Color(0xFFCBD5E1),
                            modifier = Modifier.size((16f * s).coerceIn(12f, 24f).dp)
                        )
                    }
                }
                Spacer(Modifier.width((6f * s).dp))
                Surface(
                    shape = RoundedCornerShape((8f * s).coerceIn(5f, 11f).dp),
                    color = Color(0xFFDC2626),
                    modifier = Modifier
                        .heightIn(min = (30f * s).coerceIn(24f, 40f).dp)
                        .clickable(enabled = !previewMode, onClick = onTurnOff)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (10f * s).dp)) {
                        Text(
                            text = "OFF",
                            color = Color.White,
                            fontSize = fsSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.height((5f * s).dp))

            // Scope Switcher
            Row(
                horizontalArrangement = Arrangement.spacedBy((5f * s).dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                MemScope.entries.forEach { sc ->
                    val sel = state.scope == sc
                    Surface(
                        shape = RoundedCornerShape((8f * s).coerceIn(5f, 11f).dp),
                        color = if (sel) Color(0xFF065F46) else cardBg,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = minBtnH)
                            .clickable(enabled = !previewMode) { onSelectScope(sc) }
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (6f * s).dp)) {
                            Text(
                                text = sc.label,
                                color = if (sel) Color(0xFFA7F3D0) else Color(0xFFCBD5E1),
                                fontSize = fsSmall,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height((5f * s).dp))

            // Main Tabs
            Row(
                horizontalArrangement = Arrangement.spacedBy((4f * s).dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val tabs = listOf(
                    "Scan (${state.totalMatches})",
                    "Watch (${state.watchList.size})",
                    "Hex",
                    "Map"
                )
                tabs.forEachIndexed { idx, title ->
                    val sel = activeTab == idx
                    Surface(
                        shape = RoundedCornerShape((8f * s).coerceIn(5f, 11f).dp),
                        color = if (sel) accent else cardBg,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = minBtnH)
                            .clickable {
                                activeTab = idx
                                if (idx == 3 && !previewMode) onRefreshRegions()
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (4f * s).dp)) {
                            Text(
                                text = title,
                                color = if (sel) Color(0xFF042F2E) else Color.White,
                                fontSize = fsSmall,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height((6f * s).dp))

            when (activeTab) {
                // ==================== SCAN TAB ====================
                0 -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy((4f * s).dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Surface(
                            shape = RoundedCornerShape((8f * s).coerceIn(5f, 11f).dp),
                            color = if (showTypePicker) Color(0xFF0284C7) else Color(0xFF1E293B),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = minBtnH)
                                .border(1.dp, Color(0xFF0284C7), RoundedCornerShape((8f * s).coerceIn(5f, 11f).dp))
                                .clickable(enabled = !previewMode) {
                                    showTypePicker = !showTypePicker
                                    if (showTypePicker) { showModePicker = false; showTimeoutPicker = false }
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (5f * s).dp)) {
                                Text(
                                    text = "${state.dataType.shortLabel} ${if (showTypePicker) "▴" else "▾"}",
                                    color = Color.White,
                                    fontSize = fsSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape((8f * s).coerceIn(5f, 11f).dp),
                            color = if (showModePicker) Color(0xFF7C3AED) else Color(0xFF1E293B),
                            modifier = Modifier
                                .weight(1.25f)
                                .heightIn(min = minBtnH)
                                .border(1.dp, Color(0xFF7C3AED), RoundedCornerShape((8f * s).coerceIn(5f, 11f).dp))
                                .clickable(enabled = !previewMode) {
                                    showModePicker = !showModePicker
                                    if (showModePicker) { showTypePicker = false; showTimeoutPicker = false }
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (5f * s).dp)) {
                                Text(
                                    text = "${state.scanType.label} ${if (showModePicker) "▴" else "▾"}",
                                    color = Color.White,
                                    fontSize = fsSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        // Configurable 1s..80s Scan Timeout Button
                        Surface(
                            shape = RoundedCornerShape((8f * s).coerceIn(5f, 11f).dp),
                            color = if (showTimeoutPicker) Color(0xFF0F766E) else Color(0xFF1E293B),
                            modifier = Modifier
                                .heightIn(min = minBtnH)
                                .border(1.dp, Color(0xFF14B8A6), RoundedCornerShape((8f * s).coerceIn(5f, 11f).dp))
                                .clickable(enabled = !previewMode) {
                                    showTimeoutPicker = !showTimeoutPicker
                                    if (showTimeoutPicker) { showTypePicker = false; showModePicker = false }
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (7f * s).dp)) {
                                Text(
                                    text = "${state.scanTimeoutSec}s",
                                    color = Color(0xFF5EEAD4),
                                    fontSize = fsSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    if (showTimeoutPicker) {
                        Spacer(Modifier.height((5f * s).dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(cardBg, RoundedCornerShape((8f * s).dp))
                                .padding(horizontal = (8f * s).dp, vertical = (6f * s).dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Max Scan Timeout: ${state.scanTimeoutSec}s (1–80s)",
                                    color = Color(0xFF5EEAD4),
                                    fontSize = fsSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "Done",
                                    color = accent,
                                    fontSize = fsSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { showTimeoutPicker = false }
                                )
                            }
                            Slider(
                                value = state.scanTimeoutSec.toFloat(),
                                onValueChange = { onSetScanTimeoutSec(it.roundToInt()) },
                                valueRange = WebsiteMemoryEngine.MIN_TIMEOUT_SEC.toFloat()..WebsiteMemoryEngine.MAX_TIMEOUT_SEC.toFloat(),
                                colors = SliderDefaults.colors(
                                    thumbColor = accent,
                                    activeTrackColor = Color(0xFF14B8A6),
                                    inactiveTrackColor = Color(0xFF334155)
                                ),
                                modifier = Modifier.fillMaxWidth().height((28f * s).dp)
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy((4f * s).dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                listOf(3, 10, 25, 50, 80).forEach { sec ->
                                    val sel = state.scanTimeoutSec == sec
                                    Surface(
                                        shape = RoundedCornerShape((6f * s).dp),
                                        color = if (sel) Color(0xFF0F766E) else Color(0xFF1E293B),
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = (26f * s).dp)
                                            .clickable { onSetScanTimeoutSec(sec) }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = "${sec}s",
                                                color = Color.White,
                                                fontSize = fsTiny,
                                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (showTypePicker) {
                        Spacer(Modifier.height((5f * s).dp))
                        CeOptionGrid(
                            items = MemDataType.entries,
                            columns = 3,
                            minHeight = minBtnH,
                            selected = state.dataType,
                            selectedColor = Color(0xFF0284C7),
                            cardBg = cardBg,
                            fontSize = fsSmall,
                            labelOf = { it.shortLabel },
                            enabled = !previewMode,
                            onSelect = {
                                onSelectDataType(it)
                                showTypePicker = false
                            }
                        )
                    }

                    val availableScans = if (state.hasActiveScan) {
                        MemScanType.entries.filter { it != MemScanType.UNKNOWN }
                    } else {
                        MemScanType.entries.filter { !it.nextOnly }
                    }

                    if (showModePicker) {
                        Spacer(Modifier.height((5f * s).dp))
                        CeOptionGrid(
                            items = availableScans,
                            columns = 2,
                            minHeight = minBtnH,
                            selected = state.scanType,
                            selectedColor = Color(0xFF7C3AED),
                            cardBg = cardBg,
                            fontSize = fsSmall,
                            labelOf = { it.label },
                            enabled = !previewMode,
                            onSelect = {
                                onSelectScanType(it)
                                showModePicker = false
                            }
                        )
                    } else if (!showTypePicker && !showTimeoutPicker) {
                        Spacer(Modifier.height((5f * s).dp))
                        val quickModes = if (state.hasActiveScan) {
                            listOf(MemScanType.EXACT, MemScanType.CHANGED, MemScanType.INCREASED, MemScanType.DECREASED)
                        } else {
                            listOf(MemScanType.EXACT, MemScanType.UNKNOWN, MemScanType.BETWEEN)
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy((4f * s).dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            quickModes.forEach { st ->
                                val sel = state.scanType == st
                                val shortName = when (st) {
                                    MemScanType.EXACT -> "Exact"
                                    MemScanType.UNKNOWN -> "Unknown"
                                    MemScanType.INCREASED -> "Inc +"
                                    MemScanType.DECREASED -> "Dec -"
                                    else -> st.label
                                }
                                Surface(
                                    shape = RoundedCornerShape((7f * s).coerceIn(4f, 10f).dp),
                                    color = if (sel) Color(0xFF7C3AED) else cardBg,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = minBtnH)
                                        .clickable(enabled = !previewMode) { onSelectScanType(st) }
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (4f * s).dp)) {
                                        Text(
                                            text = shortName,
                                            color = Color.White,
                                            fontSize = fsSmall,
                                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height((5f * s).dp))

                    val hideInput = !state.hasActiveScan && state.scanType == MemScanType.UNKNOWN
                    val isOptionalNextValue = state.hasActiveScan && (
                        state.scanType == MemScanType.CHANGED ||
                            state.scanType == MemScanType.INCREASED ||
                            state.scanType == MemScanType.DECREASED ||
                            state.scanType == MemScanType.UNCHANGED
                        )
                    if (!hideInput) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy((5f * s).dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CeInputField(
                                value = query1,
                                onValueChange = { query1 = it },
                                placeholder = if (state.dataType == MemDataType.AOB) "48 8B ?? 01"
                                else if (state.scanType == MemScanType.BETWEEN) "Min value"
                                else if (isOptionalNextValue) "New val (or blank for ${state.scanType.label})"
                                else "Value (e.g. 100 or 4,976,216)",
                                fontSize = fsBody,
                                enabled = !previewMode,
                                onDone = {
                                    focusManager.clearFocus()
                                    if (state.hasActiveScan) onNextScan(query1, query2) else onFirstScan(query1, query2)
                                },
                                modifier = Modifier.weight(1f)
                            )
                            if (state.scanType == MemScanType.BETWEEN) {
                                CeInputField(
                                    value = query2,
                                    onValueChange = { query2 = it },
                                    placeholder = "Max",
                                    fontSize = fsBody,
                                    enabled = !previewMode,
                                    onDone = {
                                        focusManager.clearFocus()
                                        if (state.hasActiveScan) onNextScan(query1, query2) else onFirstScan(query1, query2)
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Spacer(Modifier.height((5f * s).dp))
                    }

                    // Primary Scan Buttons (First / Next / Undo / New or Cancel while busy)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy((4f * s).dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.busy) {
                            val secNow = String.format(java.util.Locale.US, "%.1fs", state.scanElapsedMs / 1000.0)
                            CeButton(
                                text = "Scanning $secNow / ${state.scanTimeoutSec}s",
                                bg = Color(0xFF1E293B),
                                fg = Color(0xFF5EEAD4),
                                fontSize = fsSmall,
                                minHeight = minBtnH,
                                enabled = false,
                                onClick = {},
                                modifier = Modifier.weight(1.7f)
                            )
                            CeButton(
                                text = "Cancel",
                                bg = Color(0xFFDC2626),
                                fg = Color.White,
                                fontSize = fsSmall,
                                minHeight = minBtnH,
                                enabled = !previewMode,
                                onClick = onCancelScan,
                                modifier = Modifier.weight(0.9f)
                            )
                        } else {
                            CeButton(
                                text = "First",
                                bg = accent,
                                fg = Color(0xFF042F2E),
                                fontSize = fsSmall,
                                minHeight = minBtnH,
                                enabled = !previewMode,
                                onClick = {
                                    focusManager.clearFocus()
                                    showModePicker = false; showTypePicker = false; showTimeoutPicker = false
                                    sourceFilter = "all"
                                    onFirstScan(query1, query2)
                                },
                                modifier = Modifier.weight(1f)
                            )
                            CeButton(
                                text = "Next",
                                bg = if (state.hasActiveScan) Color(0xFF0284C7) else Color(0xFF1E293B),
                                fg = Color.White,
                                fontSize = fsSmall,
                                minHeight = minBtnH,
                                enabled = !previewMode && state.hasActiveScan,
                                onClick = {
                                    focusManager.clearFocus()
                                    showModePicker = false; showTypePicker = false; showTimeoutPicker = false
                                    onNextScan(query1, query2)
                                },
                                modifier = Modifier.weight(1f)
                            )
                            CeButton(
                                text = "Undo",
                                bg = if (state.canUndo) Color(0xFF475569) else Color(0xFF1E293B),
                                fg = Color.White,
                                fontSize = fsSmall,
                                minHeight = minBtnH,
                                enabled = !previewMode && state.canUndo,
                                onClick = onUndoScan,
                                modifier = Modifier.weight(0.78f)
                            )
                            CeButton(
                                text = "New",
                                bg = Color(0xFF334155),
                                fg = Color.White,
                                fontSize = fsSmall,
                                minHeight = minBtnH,
                                enabled = !previewMode,
                                onClick = { editingScanAddr = null; showBulkScanEdit = false; sourceFilter = "all"; onResetScan() },
                                modifier = Modifier.weight(0.72f)
                            )
                        }
                    }

                    Spacer(Modifier.height((4f * s).dp))

                    // Status + Hook Reload + Refresh Strip
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = state.statusText,
                            color = if (state.lastScanTimedOut) Color(0xFFFBBF24) else Color(0xFF94A3B8),
                            fontSize = fsTiny,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (state.scope == MemScope.PAGE_WASM) {
                            Spacer(Modifier.width((4f * s).dp))
                            Surface(
                                shape = RoundedCornerShape((6f * s).dp),
                                color = Color(0xFF065F46),
                                modifier = Modifier
                                    .heightIn(min = (26f * s).coerceIn(22f, 34f).dp)
                                    .clickable(enabled = !previewMode && !state.busy, onClick = onReloadAndHook)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (6f * s).dp)) {
                                    Text(text = "Hook Reload", color = Color(0xFFA7F3D0), fontSize = fsTiny, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        if (state.hasActiveScan && state.results.isNotEmpty()) {
                            Spacer(Modifier.width((4f * s).dp))
                            Surface(
                                shape = RoundedCornerShape((6f * s).dp),
                                color = Color(0xFF1E293B),
                                modifier = Modifier
                                    .heightIn(min = (26f * s).coerceIn(22f, 34f).dp)
                                    .clickable(enabled = !previewMode && !state.busy, onClick = onRefreshScan)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (6f * s).dp)) {
                                    Text(text = "Refresh", color = Color(0xFF38BDF8), fontSize = fsTiny, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    // Filtered results & Bulk Selection Action Bar
                    if (state.results.isNotEmpty()) {
                        val filteredResults = remember(state.results, sourceFilter) {
                            when (sourceFilter) {
                                "wasm" -> state.results.filter { it.kind == "wasm" }
                                "buf" -> state.results.filter { it.kind == "ab" || it.kind == "ta" }
                                "js" -> state.results.filter { it.kind == "js" || it.kind == "map" || it.kind == "ls" || it.kind == "ss" || it.kind == "lsjson" || it.kind == "ssjson" }
                                "iframe" -> state.results.filter { it.addr.startsWith("@") }
                                else -> state.results
                            }
                        }
                        val visibleSlice = remember(filteredResults) { filteredResults.take(120) }
                        val allVisibleSelected = visibleSlice.isNotEmpty() && visibleSlice.all { it.addr in state.selectedScanAddrs }
                        val selCount = state.selectedScanAddrs.size
                        val effCount = if (selCount > 0) selCount else visibleSlice.size

                        // Compact Source Filter Pills (only when multiple sources exist in Page & WASM)
                        if (state.scope == MemScope.PAGE_WASM) {
                            val wasmCntR = state.results.count { it.kind == "wasm" }
                            val bufCntR = state.results.count { it.kind == "ab" || it.kind == "ta" }
                            val jsCntR = state.results.count { it.kind == "js" || it.kind == "map" || it.kind.startsWith("ls") || it.kind.startsWith("ss") }
                            val iframeCntR = state.results.count { it.addr.startsWith("@") }
                            val filterOpts = buildList {
                                add("all" to "All (${state.results.size})")
                                if (wasmCntR > 0) add("wasm" to "WASM ($wasmCntR)")
                                if (bufCntR > 0) add("buf" to "Buf ($bufCntR)")
                                if (jsCntR > 0) add("js" to "JS ($jsCntR)")
                                if (iframeCntR > 0) add("iframe" to "Iframe ($iframeCntR)")
                            }
                            if (filterOpts.size > 2) {
                                Spacer(Modifier.height((4f * s).dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy((3f * s).dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    filterOpts.forEach { (key, lbl) ->
                                        val active = sourceFilter == key
                                        Surface(
                                            shape = RoundedCornerShape((6f * s).dp),
                                            color = if (active) Color(0xFF0284C7) else Color(0xFF1E293B),
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = (24f * s).dp)
                                                .clickable { sourceFilter = key }
                                        ) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (3f * s).dp)) {
                                                Text(text = lbl, color = Color.White, fontSize = fsTiny, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // BULK SELECTION BAR: Select All | -> Watch | Edit | Lock
                        Spacer(Modifier.height((4f * s).dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy((4f * s).dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Surface(
                                shape = RoundedCornerShape((6f * s).dp),
                                color = if (allVisibleSelected) Color(0xFF0284C7) else Color(0xFF334155),
                                modifier = Modifier
                                    .heightIn(min = (28f * s).coerceIn(24f, 36f).dp)
                                    .clickable(enabled = !previewMode) {
                                        if (allVisibleSelected) onClearScanSelection()
                                        else onSelectAllScanResults(visibleSlice.map { it.addr })
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (7f * s).dp)) {
                                    Text(
                                        text = if (allVisibleSelected) "☑ Clear" else "☐ All",
                                        color = Color.White,
                                        fontSize = fsTiny,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape((6f * s).dp),
                                color = accent,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = (28f * s).coerceIn(24f, 36f).dp)
                                    .clickable(enabled = !previewMode && !state.busy) {
                                        onAddSelectedToWatch(false)
                                        activeTab = 1
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (4f * s).dp)) {
                                    Text(
                                        text = "→ Watch ($effCount)",
                                        color = Color(0xFF042F2E),
                                        fontSize = fsTiny,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape((6f * s).dp),
                                color = Color(0xFFB45309),
                                modifier = Modifier
                                    .heightIn(min = (28f * s).coerceIn(24f, 36f).dp)
                                    .clickable(enabled = !previewMode && !state.busy) {
                                        showBulkScanEdit = !showBulkScanEdit
                                        if (showBulkScanEdit && bulkScanDraft.isBlank()) bulkScanDraft = query1
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (7f * s).dp)) {
                                    Text(text = "Edit ($effCount)", color = Color.White, fontSize = fsTiny, fontWeight = FontWeight.Bold)
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape((6f * s).dp),
                                color = Color(0xFF0284C7),
                                modifier = Modifier
                                    .heightIn(min = (28f * s).coerceIn(24f, 36f).dp)
                                    .clickable(enabled = !previewMode && !state.busy) {
                                        onAddSelectedToWatch(true)
                                        activeTab = 1
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (7f * s).dp)) {
                                    Text(text = "Lock", color = Color.White, fontSize = fsTiny, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        if (showBulkScanEdit) {
                            Spacer(Modifier.height((4f * s).dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy((4f * s).dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                CeInputField(
                                    value = bulkScanDraft,
                                    onValueChange = { bulkScanDraft = it },
                                    placeholder = "Write value to $effCount item(s)",
                                    fontSize = fsBody,
                                    enabled = !previewMode,
                                    onDone = {
                                        if (selCount > 0) onEditSelectedScanResults(bulkScanDraft)
                                        else onWriteAllResults(bulkScanDraft)
                                        showBulkScanEdit = false
                                        focusManager.clearFocus()
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                CeButton(
                                    text = "Apply",
                                    bg = Color(0xFFB45309),
                                    fg = Color.White,
                                    fontSize = fsSmall,
                                    minHeight = minBtnH,
                                    enabled = !previewMode && bulkScanDraft.isNotBlank(),
                                    onClick = {
                                        if (selCount > 0) onEditSelectedScanResults(bulkScanDraft)
                                        else onWriteAllResults(bulkScanDraft)
                                        showBulkScanEdit = false
                                        focusManager.clearFocus()
                                    }
                                )
                            }
                        }

                        // Results Scrollable List
                        Spacer(Modifier.height((5f * s).dp))
                        Column(
                            verticalArrangement = Arrangement.spacedBy((4f * s).dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = (175f * s).coerceIn(105f, 275f).dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            visibleSlice.forEach { item ->
                                val isSel = item.addr in state.selectedScanAddrs
                                val changed = item.prevValue.isNotEmpty() && item.prevValue != item.value
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (isSel) Color(0xFF1E3A5F) else cardBg,
                                            RoundedCornerShape((7f * s).dp)
                                        )
                                        .border(
                                            width = if (isSel) 1.dp else 0.dp,
                                            color = if (isSel) Color(0xFF38BDF8) else Color.Transparent,
                                            shape = RoundedCornerShape((7f * s).dp)
                                        )
                                        .padding(horizontal = (6f * s).dp, vertical = (5f * s).dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // Row Checkbox
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size((24f * s).coerceIn(20f, 32f).dp)
                                                .background(
                                                    if (isSel) Color(0xFF0284C7) else Color(0xFF0F172A),
                                                    RoundedCornerShape((5f * s).dp)
                                                )
                                                .border(1.dp, if (isSel) Color(0xFF38BDF8) else Color(0xFF475569), RoundedCornerShape((5f * s).dp))
                                                .clickable(enabled = !previewMode) { onToggleSelectScanAddr(item.addr) }
                                        ) {
                                            Text(
                                                text = if (isSel) "✓" else "",
                                                color = Color.White,
                                                fontSize = fsSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Spacer(Modifier.width((5f * s).dp))
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable(enabled = !previewMode) { onToggleSelectScanAddr(item.addr) }
                                        ) {
                                            val tagPrefix = buildString {
                                                if (item.engine.isNotBlank()) append("[${item.engine}] ")
                                                append(item.addr)
                                            }
                                            Text(
                                                text = tagPrefix,
                                                color = Color(0xFF93C5FD),
                                                fontSize = fsTiny,
                                                fontFamily = FontFamily.Monospace,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = if (changed) "${item.value} (was ${item.prevValue}) · ${item.type}" else "${item.value} · ${item.type}",
                                                color = if (changed) Color(0xFF4ADE80) else Color.White,
                                                fontSize = fsBody,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Surface(
                                            shape = RoundedCornerShape((6f * s).dp),
                                            color = Color(0xFF065F46),
                                            modifier = Modifier
                                                .heightIn(min = (28f * s).coerceIn(24f, 36f).dp)
                                                .clickable(enabled = !previewMode) {
                                                    editingScanAddr = if (editingScanAddr == item.addr) null else item.addr
                                                    scanEditDraft = item.value
                                                }
                                        ) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (6f * s).dp)) {
                                                Text(text = "Edit", color = Color(0xFFA7F3D0), fontSize = fsTiny, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                        Spacer(Modifier.width((3f * s).dp))
                                        if (state.scope == MemScope.FABLE_NATIVE || item.kind == "wasm" || item.kind == "ta" || item.kind == "ab") {
                                            Surface(
                                                shape = RoundedCornerShape((6f * s).dp),
                                                color = Color(0xFF334155),
                                                modifier = Modifier
                                                    .heightIn(min = (28f * s).coerceIn(24f, 36f).dp)
                                                    .clickable(enabled = !previewMode) {
                                                        onInspectHex(item.addr, state.scope, 0L)
                                                        activeTab = 2
                                                    }
                                            ) {
                                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (5f * s).dp)) {
                                                    Text(text = "Hex", color = Color(0xFFE2E8F0), fontSize = fsTiny)
                                                }
                                            }
                                            Spacer(Modifier.width((3f * s).dp))
                                        }
                                        Surface(
                                            shape = RoundedCornerShape((6f * s).dp),
                                            color = accent,
                                            modifier = Modifier
                                                .heightIn(min = (28f * s).coerceIn(24f, 36f).dp)
                                                .clickable(enabled = !previewMode) { onAddWatch(item) }
                                        ) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (6f * s).dp)) {
                                                Text(text = "+W", color = Color(0xFF042F2E), fontSize = fsTiny, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                    if (editingScanAddr == item.addr) {
                                        Spacer(Modifier.height((5f * s).dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy((5f * s).dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            CeInputField(
                                                value = scanEditDraft,
                                                onValueChange = { scanEditDraft = it },
                                                placeholder = "New value",
                                                fontSize = fsBody,
                                                enabled = !previewMode,
                                                onDone = {
                                                    onQuickEditResult(item, scanEditDraft)
                                                    editingScanAddr = null
                                                    focusManager.clearFocus()
                                                },
                                                modifier = Modifier.weight(1f)
                                            )
                                            CeButton(
                                                text = "Write",
                                                bg = accent,
                                                fg = Color(0xFF042F2E),
                                                fontSize = fsSmall,
                                                minHeight = minBtnH,
                                                enabled = !previewMode,
                                                onClick = {
                                                    onQuickEditResult(item, scanEditDraft)
                                                    editingScanAddr = null
                                                    focusManager.clearFocus()
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ==================== WATCH (EDIT & FREEZE TABLE) TAB ====================
                1 -> {
                    val allWatchSelected = state.watchList.isNotEmpty() && state.watchList.all { it.id in state.selectedWatchIds }
                    val wSelCount = state.selectedWatchIds.size
                    val wEffCount = if (wSelCount > 0) wSelCount else state.watchList.size

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Edit & Freeze Table (${state.watchList.size})",
                            color = Color(0xFFCBD5E1),
                            fontSize = fsBody,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        if (state.watchList.any { it.frozen }) {
                            Surface(
                                shape = RoundedCornerShape((7f * s).dp),
                                color = Color(0xFFB45309),
                                modifier = Modifier
                                    .heightIn(min = (28f * s).coerceIn(24f, 36f).dp)
                                    .clickable(enabled = !previewMode, onClick = onUnfreezeAll)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (8f * s).dp)) {
                                    Text(text = "Unlock All", color = Color.White, fontSize = fsTiny, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    if (state.watchList.isNotEmpty()) {
                        // BULK WATCH CONTROLS: All | Edit | Lock | Free | Undo | Del
                        Spacer(Modifier.height((5f * s).dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy((3.5f * s).dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Surface(
                                shape = RoundedCornerShape((6f * s).dp),
                                color = if (allWatchSelected) Color(0xFF0284C7) else Color(0xFF334155),
                                modifier = Modifier
                                    .heightIn(min = (28f * s).coerceIn(24f, 36f).dp)
                                    .clickable(enabled = !previewMode) {
                                        if (allWatchSelected) onClearWatchSelection() else onSelectAllWatchEntries()
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (6f * s).dp)) {
                                    Text(
                                        text = if (allWatchSelected) "☑ None" else "☐ All",
                                        color = Color.White,
                                        fontSize = fsTiny,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape((6f * s).dp),
                                color = Color(0xFF065F46),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = (28f * s).coerceIn(24f, 36f).dp)
                                    .clickable(enabled = !previewMode) { showBulkWatchEdit = !showBulkWatchEdit }
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (3f * s).dp)) {
                                    Text(text = "Edit($wEffCount)", color = Color(0xFFA7F3D0), fontSize = fsTiny, fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape((6f * s).dp),
                                color = Color(0xFF0284C7),
                                modifier = Modifier
                                    .weight(0.85f)
                                    .heightIn(min = (28f * s).coerceIn(24f, 36f).dp)
                                    .clickable(enabled = !previewMode) { onFreezeSelectedWatchEntries(true) }
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (3f * s).dp)) {
                                    Text(text = "Lock", color = Color.White, fontSize = fsTiny, fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape((6f * s).dp),
                                color = Color(0xFF475569),
                                modifier = Modifier
                                    .weight(0.8f)
                                    .heightIn(min = (28f * s).coerceIn(24f, 36f).dp)
                                    .clickable(enabled = !previewMode) { onFreezeSelectedWatchEntries(false) }
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (3f * s).dp)) {
                                    Text(text = "Free", color = Color.White, fontSize = fsTiny, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape((6f * s).dp),
                                color = Color(0xFF334155),
                                modifier = Modifier
                                    .weight(0.8f)
                                    .heightIn(min = (28f * s).coerceIn(24f, 36f).dp)
                                    .clickable(enabled = !previewMode) { onRestoreSelectedWatchEntries() }
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (3f * s).dp)) {
                                    Text(text = "Undo", color = Color.White, fontSize = fsTiny, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape((6f * s).dp),
                                color = Color(0xFF991B1B),
                                modifier = Modifier
                                    .heightIn(min = (28f * s).coerceIn(24f, 36f).dp)
                                    .clickable(enabled = !previewMode) { onRemoveSelectedWatchEntries() }
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (6f * s).dp)) {
                                    Text(text = "Del", color = Color.White, fontSize = fsTiny, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        if (showBulkWatchEdit) {
                            Spacer(Modifier.height((5f * s).dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy((4f * s).dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                CeInputField(
                                    value = bulkWatchDraft,
                                    onValueChange = { bulkWatchDraft = it },
                                    placeholder = "Set value for $wEffCount watched item(s)",
                                    fontSize = fsBody,
                                    enabled = !previewMode,
                                    onDone = {
                                        onEditSelectedWatchEntries(bulkWatchDraft)
                                        showBulkWatchEdit = false
                                        focusManager.clearFocus()
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                CeButton(
                                    text = "Write",
                                    bg = accent,
                                    fg = Color(0xFF042F2E),
                                    fontSize = fsSmall,
                                    minHeight = minBtnH,
                                    enabled = !previewMode && bulkWatchDraft.isNotBlank(),
                                    onClick = {
                                        onEditSelectedWatchEntries(bulkWatchDraft)
                                        showBulkWatchEdit = false
                                        focusManager.clearFocus()
                                    }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height((5f * s).dp))
                    if (state.watchList.isEmpty()) {
                        Text(
                            text = "No watched values yet. Run a scan, select items (or tap All), and tap → Watch to bulk edit, Titanium-freeze, or restore.",
                            color = Color(0xFF94A3B8),
                            fontSize = fsSmall
                        )
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy((5f * s).dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = (195f * s).coerceIn(115f, 290f).dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            state.watchList.forEach { w ->
                                val isWSel = w.id in state.selectedWatchIds
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (isWSel) Color(0xFF1E3A5F) else cardBg, RoundedCornerShape((7f * s).dp))
                                        .border(
                                            width = if (isWSel) 1.dp else if (w.frozen) 1.dp else 0.dp,
                                            color = if (isWSel) Color(0xFF38BDF8) else if (w.frozen) Color(0xFF0284C7) else Color.Transparent,
                                            shape = RoundedCornerShape((7f * s).dp)
                                        )
                                        .padding((6f * s).dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size((24f * s).coerceIn(20f, 32f).dp)
                                                .background(
                                                    if (isWSel) Color(0xFF0284C7) else Color(0xFF0F172A),
                                                    RoundedCornerShape((5f * s).dp)
                                                )
                                                .border(1.dp, if (isWSel) Color(0xFF38BDF8) else Color(0xFF475569), RoundedCornerShape((5f * s).dp))
                                                .clickable(enabled = !previewMode) { onToggleSelectWatchId(w.id) }
                                        ) {
                                            Text(text = if (isWSel) "✓" else "", color = Color.White, fontSize = fsSmall, fontWeight = FontWeight.Bold)
                                        }
                                        Spacer(Modifier.width((4f * s).dp))
                                        Surface(
                                            shape = RoundedCornerShape((6f * s).dp),
                                            color = if (w.frozen) Color(0xFF0284C7) else Color(0xFF334155),
                                            modifier = Modifier
                                                .heightIn(min = (28f * s).coerceIn(24f, 36f).dp)
                                                .clickable(enabled = !previewMode) { onToggleWatchFreeze(w.id) }
                                        ) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (6f * s).dp)) {
                                                Text(text = if (w.frozen) "LOCK" else "Free", color = Color.White, fontSize = fsTiny, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        Spacer(Modifier.width((5f * s).dp))
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable(enabled = !previewMode) { onToggleSelectWatchId(w.id) }
                                        ) {
                                            Text(
                                                text = w.label,
                                                color = Color(0xFF93C5FD),
                                                fontSize = fsTiny,
                                                fontFamily = FontFamily.Monospace,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${w.currentValue} (orig: ${w.originalValue})",
                                                color = Color.White,
                                                fontSize = fsBody,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (w.frozen) {
                                                val modeTxt = when (w.freezeMode) {
                                                    "trap" -> "Hard Trap"
                                                    "250Hz" -> "250Hz Lock"
                                                    "500Hz" -> "500Hz Native"
                                                    else -> "Locked"
                                                }
                                                val badgeTxt = when (w.freezeStatus) {
                                                    "detached" -> "DETACHED / GC'd"
                                                    "fighting" -> "$modeTxt · ${w.blockedWrites} blocked (ext/server fighting)"
                                                    "enforcing" -> "$modeTxt · ${w.blockedWrites} blocked"
                                                    else -> "$modeTxt @ ${w.frozenValue}"
                                                }
                                                val badgeColor = when (w.freezeStatus) {
                                                    "detached" -> Color(0xFFF87171)
                                                    "fighting" -> Color(0xFFFBBF24)
                                                    else -> Color(0xFF38BDF8)
                                                }
                                                Text(
                                                    text = badgeTxt,
                                                    color = badgeColor,
                                                    fontSize = fsTiny,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                        Surface(
                                            shape = RoundedCornerShape((6f * s).dp),
                                            color = Color(0xFF065F46),
                                            modifier = Modifier
                                                .heightIn(min = (28f * s).coerceIn(24f, 36f).dp)
                                                .clickable(enabled = !previewMode) {
                                                    editingWatchId = if (editingWatchId == w.id) null else w.id
                                                    editDraft = w.currentValue
                                                }
                                        ) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (6f * s).dp)) {
                                                Text(text = "Edit", color = Color(0xFFA7F3D0), fontSize = fsTiny)
                                            }
                                        }
                                        Spacer(Modifier.width((3f * s).dp))
                                        Surface(
                                            shape = RoundedCornerShape((6f * s).dp),
                                            color = Color(0xFF475569),
                                            modifier = Modifier
                                                .heightIn(min = (28f * s).coerceIn(24f, 36f).dp)
                                                .clickable(enabled = !previewMode) { onRestoreWatchOriginal(w.id) }
                                        ) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (6f * s).dp)) {
                                                Text(text = "Undo", color = Color.White, fontSize = fsTiny)
                                            }
                                        }
                                        Spacer(Modifier.width((3f * s).dp))
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .heightIn(min = (28f * s).coerceIn(24f, 36f).dp)
                                                .clickable(enabled = !previewMode) { onRemoveWatch(w.id) }
                                                .padding(horizontal = (4f * s).dp)
                                        ) {
                                            Text(text = "✕", color = Color(0xFFF87171), fontSize = fsBody, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    if (editingWatchId == w.id) {
                                        Spacer(Modifier.height((5f * s).dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy((5f * s).dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            CeInputField(
                                                value = editDraft,
                                                onValueChange = { editDraft = it },
                                                placeholder = "New value",
                                                fontSize = fsBody,
                                                enabled = !previewMode,
                                                onDone = {
                                                    onSetWatchValue(w.id, editDraft)
                                                    editingWatchId = null
                                                    focusManager.clearFocus()
                                                },
                                                modifier = Modifier.weight(1f)
                                            )
                                            CeButton(
                                                text = "Write",
                                                bg = accent,
                                                fg = Color(0xFF042F2E),
                                                fontSize = fsSmall,
                                                minHeight = minBtnH,
                                                enabled = !previewMode,
                                                onClick = {
                                                    onSetWatchValue(w.id, editDraft)
                                                    editingWatchId = null
                                                    focusManager.clearFocus()
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ==================== HEX TAB ====================
                2 -> {
                    val hx = state.hex
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy((4f * s).dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (hx.displayAddr.isNotBlank()) "Hex @ ${hx.displayAddr}" else "Hex Memory Viewer",
                            color = Color(0xFF93C5FD),
                            fontSize = fsSmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        if (hx.targetAddr.isNotBlank()) {
                            CeButton(text = "-64", bg = cardBg, fg = Color.White, fontSize = fsSmall, minHeight = minBtnH, enabled = !previewMode, onClick = {
                                onInspectHex(hx.targetAddr, hx.scope, -64L)
                            })
                            CeButton(text = "+64", bg = cardBg, fg = Color.White, fontSize = fsSmall, minHeight = minBtnH, enabled = !previewMode, onClick = {
                                onInspectHex(hx.targetAddr, hx.scope, +64L)
                            })
                        }
                    }
                    Spacer(Modifier.height((5f * s).dp))
                    if (hx.error != null) {
                        Text(text = hx.error, color = Color(0xFFFCA5A5), fontSize = fsSmall)
                    } else if (hx.hexBytes.isBlank()) {
                        Text(
                            text = "Tap Hex on any scan result (Fable Process, WASM, ArrayBuffer, or TypedArray) to view live raw bytes + ASCII.",
                            color = Color(0xFF94A3B8),
                            fontSize = fsSmall
                        )
                    } else {
                        val lines = remember(hx.hexBytes) { formatHexLines(hx.hexBytes) }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF070B14), RoundedCornerShape((6f * s).dp))
                                .heightIn(max = (170f * s).coerceIn(100f, 250f).dp)
                                .verticalScroll(rememberScrollState())
                                .padding((6f * s).dp)
                        ) {
                            lines.forEach { line ->
                                Text(
                                    text = line,
                                    color = Color(0xFF86EFAC),
                                    fontSize = (8.5f * s).coerceIn(6.5f, 13f).sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                // ==================== MAP / DIAGNOSTICS TAB ====================
                3 -> {
                    val engText = if (state.detectedEngines.isNotEmpty()) state.detectedEngines.joinToString(", ") else "Auto"
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${state.regions.size} regions · ${state.regionsTotalKb} KB",
                                color = Color(0xFFCBD5E1),
                                fontSize = fsSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Engines: $engText · ${state.frameCount}f · ${state.workerCount}w · ${state.wasmCount} WASM",
                                color = Color(0xFF94A3B8),
                                fontSize = fsTiny,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        CeButton(text = "Refresh", bg = cardBg, fg = Color.White, fontSize = fsSmall, minHeight = minBtnH, enabled = !previewMode, onClick = onRefreshRegions)
                    }
                    Spacer(Modifier.height((5f * s).dp))
                    Column(
                        verticalArrangement = Arrangement.spacedBy((4f * s).dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = (170f * s).coerceIn(100f, 250f).dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (state.regions.isEmpty()) {
                            Text(
                                text = "Tap Refresh (or run a scan) to list readable/writable memory regions and detected game engines.",
                                color = Color(0xFF94A3B8),
                                fontSize = fsSmall
                            )
                        } else {
                            state.regions.forEach { r ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(cardBg, RoundedCornerShape((6f * s).dp))
                                        .padding(horizontal = (7f * s).dp, vertical = (4.5f * s).dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${r.start} (${r.perms})",
                                            color = Color(0xFF93C5FD),
                                            fontSize = fsSmall,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = "${r.label} · ${r.kb} KB",
                                            color = Color(0xFFE2E8F0),
                                            fontSize = fsSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> CeOptionGrid(
    items: List<T>,
    columns: Int,
    minHeight: Dp,
    selected: T,
    selectedColor: Color,
    cardBg: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    labelOf: (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        items.chunked(columns).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                rowItems.forEach { item ->
                    val sel = item == selected
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = if (sel) selectedColor else cardBg,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = minHeight)
                            .clickable(enabled = enabled) { onSelect(item) }
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 5.dp, vertical = 4.dp)) {
                            Text(
                                text = labelOf(item),
                                color = Color.White,
                                fontSize = fontSize,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                repeat(columns - rowItems.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CeInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    enabled: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .heightIn(min = 36.dp)
            .background(Color(0xFF1E293B), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF475569), RoundedCornerShape(8.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp)
    ) {
        if (value.isEmpty()) {
            Text(text = placeholder, color = Color(0xFF64748B), fontSize = fontSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            enabled = enabled,
            textStyle = TextStyle(color = Color.White, fontSize = fontSize),
            cursorBrush = SolidColor(Color(0xFF10B981)),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun CeButton(
    text: String,
    bg: Color,
    fg: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    minHeight: Dp = 34.dp,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (enabled) bg else bg.copy(alpha = 0.45f),
        modifier = modifier
            .heightIn(min = minHeight)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp)) {
            Text(text = text, color = fg, fontSize = fontSize, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

private fun formatHexLines(hex: String): List<String> {
    val byteCount = hex.length / 2
    val lines = ArrayList<String>()
    var off = 0
    while (off < byteCount) {
        val end = (off + 8).coerceAtMost(byteCount)
        val hexPart = StringBuilder()
        val ascPart = StringBuilder()
        for (i in off until end) {
            val pair = hex.substring(i * 2, i * 2 + 2)
            hexPart.append(pair).append(' ')
            val b = pair.toIntOrNull(16) ?: 0
            ascPart.append(if (b in 32..126) b.toChar() else '.')
        }
        while (end - off < 8 && hexPart.length < 24) hexPart.append("   ")
        lines.add("${off.toString(16).uppercase().padStart(2, '0')}: $hexPart| $ascPart")
        off += 8
    }
    return lines
}

@Composable
fun DraggableMemoryEnginePanel(
    state: MemoryEngineUiState,
    scalePct: Int,
    posX: Float,
    posY: Float,
    expanded: Boolean,
    topSafePadding: Dp,
    bottomSafePadding: Dp,
    onSelectScope: (MemScope) -> Unit,
    onSelectDataType: (MemDataType) -> Unit,
    onSelectScanType: (MemScanType) -> Unit,
    onFirstScan: (String, String) -> Unit,
    onNextScan: (String, String) -> Unit,
    onUndoScan: () -> Unit,
    onResetScan: () -> Unit,
    onAddWatch: (MemScanResultItem) -> Unit,
    onSetWatchValue: (String, String) -> Unit,
    onRestoreWatchOriginal: (String) -> Unit,
    onToggleWatchFreeze: (String) -> Unit,
    onRemoveWatch: (String) -> Unit,
    onUnfreezeAll: () -> Unit,
    onInspectHex: (String, MemScope, Long) -> Unit,
    onRefreshRegions: () -> Unit,
    onPositionChangeFinished: (Float, Float) -> Unit,
    onToggleExpanded: () -> Unit,
    onTurnOff: () -> Unit,
    modifier: Modifier = Modifier,
    onRefreshScan: () -> Unit = {},
    onQuickEditResult: (MemScanResultItem, String) -> Unit = { _, _ -> },
    onWriteAllResults: (String) -> Unit = {},
    onReloadAndHook: () -> Unit = {},
    onSetScanTimeoutSec: (Int) -> Unit = {},
    onCancelScan: () -> Unit = {},
    onToggleSelectScanAddr: (String) -> Unit = {},
    onSelectAllScanResults: (List<String>) -> Unit = {},
    onClearScanSelection: () -> Unit = {},
    onAddSelectedToWatch: (Boolean) -> Unit = {},
    onEditSelectedScanResults: (String) -> Unit = {},
    onToggleSelectWatchId: (String) -> Unit = {},
    onSelectAllWatchEntries: () -> Unit = {},
    onClearWatchSelection: () -> Unit = {},
    onEditSelectedWatchEntries: (String) -> Unit = {},
    onFreezeSelectedWatchEntries: (Boolean) -> Unit = {},
    onRestoreSelectedWatchEntries: () -> Unit = {},
    onRemoveSelectedWatchEntries: () -> Unit = {}
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val containerW = with(density) { maxWidth.toPx() }
        val containerH = with(density) { maxHeight.toPx() }
        val marginPx = with(density) { 6.dp.toPx() }
        val topSafePx = with(density) { topSafePadding.toPx() }
        val bottomSafePx = with(density) { bottomSafePadding.toPx() }

        var panelW by remember { mutableIntStateOf(0) }
        var panelH by remember { mutableIntStateOf(0) }

        val minX = marginPx
        val maxX = (containerW - panelW - marginPx).coerceAtLeast(minX)
        val minY = topSafePx.coerceAtLeast(marginPx)
        val maxY = (containerH - panelH - bottomSafePx).coerceAtLeast(minY)
        val spanX = (maxX - minX).coerceAtLeast(1f)
        val spanY = (maxY - minY).coerceAtLeast(1f)

        var dragging by remember { mutableStateOf(false) }
        var offsetX by remember { mutableFloatStateOf(minX + posX.coerceIn(0f, 1f) * spanX) }
        var offsetY by remember { mutableFloatStateOf(minY + posY.coerceIn(0f, 1f) * spanY) }

        LaunchedEffect(posX, posY, panelW, panelH, containerW, containerH, topSafePx, bottomSafePx) {
            if (!dragging) {
                offsetX = (minX + posX.coerceIn(0f, 1f) * spanX).coerceIn(minX, maxX)
                offsetY = (minY + posY.coerceIn(0f, 1f) * spanY).coerceIn(minY, maxY)
            }
        }

        val clampedX = offsetX.coerceIn(minX, maxX)
        val clampedY = offsetY.coerceIn(minY, maxY)

        val dragModifier = Modifier.pointerInput(minX, maxX, minY, maxY) {
            detectDragGestures(
                onDragStart = { dragging = true },
                onDragEnd = {
                    dragging = false
                    onPositionChangeFinished(
                        ((offsetX - minX) / spanX).coerceIn(0f, 1f),
                        ((offsetY - minY) / spanY).coerceIn(0f, 1f)
                    )
                },
                onDragCancel = {
                    dragging = false
                    onPositionChangeFinished(
                        ((offsetX - minX) / spanX).coerceIn(0f, 1f),
                        ((offsetY - minY) / spanY).coerceIn(0f, 1f)
                    )
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    offsetX = (offsetX + dragAmount.x).coerceIn(minX, maxX)
                    offsetY = (offsetY + dragAmount.y).coerceIn(minY, maxY)
                }
            )
        }

        MemoryEnginePanel(
            state = state,
            scalePct = scalePct,
            expanded = expanded,
            onSelectScope = onSelectScope,
            onSelectDataType = onSelectDataType,
            onSelectScanType = onSelectScanType,
            onFirstScan = onFirstScan,
            onNextScan = onNextScan,
            onUndoScan = onUndoScan,
            onResetScan = onResetScan,
            onAddWatch = onAddWatch,
            onSetWatchValue = onSetWatchValue,
            onRestoreWatchOriginal = onRestoreWatchOriginal,
            onToggleWatchFreeze = onToggleWatchFreeze,
            onRemoveWatch = onRemoveWatch,
            onUnfreezeAll = onUnfreezeAll,
            onInspectHex = onInspectHex,
            onRefreshRegions = onRefreshRegions,
            onToggleExpanded = onToggleExpanded,
            onTurnOff = onTurnOff,
            dragHandleModifier = dragModifier,
            maxWidthDp = (maxWidth - 12.dp).coerceAtLeast(220.dp),
            onRefreshScan = onRefreshScan,
            onQuickEditResult = onQuickEditResult,
            onWriteAllResults = onWriteAllResults,
            onReloadAndHook = onReloadAndHook,
            onSetScanTimeoutSec = onSetScanTimeoutSec,
            onCancelScan = onCancelScan,
            onToggleSelectScanAddr = onToggleSelectScanAddr,
            onSelectAllScanResults = onSelectAllScanResults,
            onClearScanSelection = onClearScanSelection,
            onAddSelectedToWatch = onAddSelectedToWatch,
            onEditSelectedScanResults = onEditSelectedScanResults,
            onToggleSelectWatchId = onToggleSelectWatchId,
            onSelectAllWatchEntries = onSelectAllWatchEntries,
            onClearWatchSelection = onClearWatchSelection,
            onEditSelectedWatchEntries = onEditSelectedWatchEntries,
            onFreezeSelectedWatchEntries = onFreezeSelectedWatchEntries,
            onRestoreSelectedWatchEntries = onRestoreSelectedWatchEntries,
            onRemoveSelectedWatchEntries = onRemoveSelectedWatchEntries,
            modifier = Modifier
                .offset { IntOffset(clampedX.roundToInt(), clampedY.roundToInt()) }
                .onSizeChanged {
                    panelW = it.width
                    panelH = it.height
                }
        )
    }
}
