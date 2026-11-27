package com.fable.browser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
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
import com.fable.browser.engine.MemFreezeRule
import com.fable.browser.engine.MemFreezeStrength
import com.fable.browser.engine.MemScanResultItem
import com.fable.browser.engine.MemScanType
import com.fable.browser.engine.MemScope
import com.fable.browser.engine.MemStructField
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
    maxHeightDp: Dp = 560.dp,
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
    onRemoveSelectedWatchEntries: () -> Unit = {},
    widthPct: Int = WebsiteMemoryEngine.DEFAULT_PANEL_WIDTH_PCT,
    heightPct: Int = WebsiteMemoryEngine.DEFAULT_PANEL_HEIGHT_PCT,
    previewInitialTab: Int = 0,
    onCycleWatchFreezeRule: (String) -> Unit = {},
    onSetWatchFreezeParam: (String, String) -> Unit = { _, _ -> },
    onRenameWatch: (String, String) -> Unit = { _, _ -> },
    onWatchQuickMath: (String, String) -> Unit = { _, _ -> },
    onPeekNearbyStruct: (String, MemScope) -> Unit = { _, _ -> },
    onAddStructFieldToWatch: (MemStructField) -> Unit = {},
    onToggleLiveScanRefresh: () -> Unit = {},
    onToggleHexLiveRefresh: () -> Unit = {},
    onWriteHexBytes: (String, Long) -> Unit = { _, _ -> },
    onJumpHexOffset: (String) -> Unit = {},
    onPointerScan: (String, MemScope) -> Unit = { _, _ -> },
    onToggleWatchTrace: (String) -> Unit = {},
    onClearWatchTrace: (String) -> Unit = {},
    onSaveCheatTable: (String) -> Unit = {},
    onLoadCheatTable: (String) -> Unit = {},
    onDeleteCheatTable: (String) -> Unit = {},
    onExportCheatTableJson: () -> String = { "" },
    onImportCheatTableJson: (String) -> Boolean = { false },
    onSetFreezeStrength: (MemFreezeStrength) -> Unit = {},
    onToggleHideUiNoise: () -> Unit = {},
    onFindMirrorsForWatch: (String) -> Unit = {},
    onLockAllMirrors: () -> Unit = {},
    onFindRealValueFromUiWatch: (String) -> Unit = {}
) {
    val s = WebsiteMemoryEngine.clampPanelScalePct(scalePct) / 100f
    val wFrac = WebsiteMemoryEngine.clampPanelWidthPct(widthPct) / 100f
    val hFrac = WebsiteMemoryEngine.clampPanelHeightPct(heightPct) / 100f
    val corner = (14f * s).coerceIn(8f, 20f).dp
    val accent = Color(0xFF10B981)
    val bg = Color(0xF50B1220)
    val cardBg = Color(0xFF162033)
    val minBtnH = (32f * s).coerceIn(24f, 44f).dp

    if (!expanded) {
        val frozenCount = state.watchList.count { it.frozen }
        val traceCount = state.watchList.count { it.tracing }
        val pillLabel = buildString {
            append("CE · ${state.totalMatches}")
            if (frozenCount > 0) append(" · F:$frozenCount")
            if (traceCount > 0) append(" · T:$traceCount")
        }
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
                        fontSize = (12.5f * s).coerceIn(9f, 18f).sp,
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
                        fontSize = (10f * s).coerceIn(7.5f, 14f).sp,
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

    var activeTab by remember { mutableIntStateOf(previewInitialTab) }
    var query1 by remember { mutableStateOf("") }
    var query2 by remember { mutableStateOf("") }
    var showTypePicker by remember { mutableStateOf(false) }
    var showModePicker by remember { mutableStateOf(false) }
    var showTimeoutPicker by remember { mutableStateOf(false) }
    var sourceFilter by remember { mutableStateOf("all") }
    var showBulkScanEdit by remember { mutableStateOf(false) }
    var bulkScanDraft by remember { mutableStateOf("") }
    var editingScanAddr by remember { mutableStateOf<String?>(null) }
    var scanEditDraft by remember { mutableStateOf("") }
    var hexCursorByte by remember { mutableIntStateOf(0) }
    val focusManager = LocalFocusManager.current

    val minSafeW = 190.dp.coerceAtMost(maxWidthDp)
    val panelWidth = (maxWidthDp * wFrac).coerceIn(minSafeW, maxWidthDp)
    val minSafeH = 180.dp.coerceAtMost(maxHeightDp)
    val panelMaxHeight = (maxHeightDp * hFrac).coerceIn(minSafeH, maxHeightDp)
    val listMaxHeight = (panelMaxHeight * 0.44f).coerceIn(64.dp, (panelMaxHeight - 110.dp).coerceAtLeast(64.dp))

    val hPad = (9f * s).coerceIn(6f, 15f).dp
    val vPad = (7f * s).coerceIn(5f, 12f).dp
    val fsTitle = (12.5f * s).coerceIn(9f, 17f).sp
    val fsBody = (11f * s).coerceIn(8.5f, 15f).sp
    val fsSmall = (9.8f * s).coerceIn(7.5f, 13.5f).sp
    val fsTiny = (8.8f * s).coerceIn(7f, 12.5f).sp

    Surface(
        shape = RoundedCornerShape(corner),
        color = bg,
        shadowElevation = 9.dp,
        modifier = modifier
            .width(panelWidth)
            .heightIn(max = panelMaxHeight)
            .border(1.dp, accent, RoundedCornerShape(corner))
    ) {
        Column(modifier = Modifier.padding(horizontal = hPad, vertical = vPad)) {
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
                    modifier = Modifier.size((17f * s).coerceIn(12f, 24f).dp)
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
                if (state.scope == MemScope.PAGE_WASM && state.pageHookLive) {
                    Surface(
                        shape = RoundedCornerShape((6f * s).dp),
                        color = if (state.hookedEarly) Color(0xFF064E3B) else Color(0xFF164E63),
                        modifier = Modifier.padding(end = (4f * s).dp)
                    ) {
                        Text(
                            text = if (state.hookedEarly) "Auto✓" else "Hook✓",
                            color = if (state.hookedEarly) Color(0xFF6EE7B7) else Color(0xFF67E8F9),
                            fontSize = fsTiny,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = (5f * s).dp, vertical = (2f * s).dp)
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape((8f * s).coerceIn(5f, 11f).dp),
                    color = cardBg,
                    modifier = Modifier
                        .heightIn(min = (28f * s).coerceIn(22f, 38f).dp)
                        .clickable(onClick = onToggleExpanded)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (7f * s).dp)) {
                        Icon(
                            imageVector = Icons.Filled.ExpandLess,
                            contentDescription = "Hide to mini pill",
                            tint = Color(0xFFCBD5E1),
                            modifier = Modifier.size((16f * s).coerceIn(12f, 22f).dp)
                        )
                    }
                }
                Spacer(Modifier.width((5f * s).dp))
                Surface(
                    shape = RoundedCornerShape((8f * s).coerceIn(5f, 11f).dp),
                    color = Color(0xFFDC2626),
                    modifier = Modifier
                        .heightIn(min = (28f * s).coerceIn(22f, 38f).dp)
                        .clickable(enabled = !previewMode, onClick = onTurnOff)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (9f * s).dp)) {
                        Text(text = "OFF", color = Color.White, fontSize = fsSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height((4f * s).dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
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
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (5f * s).dp)) {
                                Text(
                                    text = sc.label,
                                    color = if (sel) Color(0xFFA7F3D0) else Color(0xFFCBD5E1),
                                    fontSize = fsSmall,
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height((4f * s).dp))

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
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (3f * s).dp)) {
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

                Spacer(Modifier.height((5f * s).dp))

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
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (4f * s).dp)) {
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
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (4f * s).dp)) {
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
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (6f * s).dp)) {
                                    Text(text = "${state.scanTimeoutSec}s", color = Color(0xFF5EEAD4), fontSize = fsSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        if (showTimeoutPicker) {
                            Spacer(Modifier.height((4f * s).dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(cardBg, RoundedCornerShape((8f * s).dp))
                                    .padding(horizontal = (8f * s).dp, vertical = (6f * s).dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
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
                                Row(horizontalArrangement = Arrangement.spacedBy((4f * s).dp), modifier = Modifier.fillMaxWidth()) {
                                    listOf(3, 10, 25, 50, 80).forEach { sec ->
                                        val sel = state.scanTimeoutSec == sec
                                        Surface(
                                            shape = RoundedCornerShape((6f * s).dp),
                                            color = if (sel) Color(0xFF0F766E) else Color(0xFF1E293B),
                                            modifier = Modifier.weight(1f).heightIn(min = (24f * s).dp).clickable { onSetScanTimeoutSec(sec) }
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(text = "${sec}s", color = Color.White, fontSize = fsTiny, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (showTypePicker) {
                            Spacer(Modifier.height((4f * s).dp))
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
                            MemScanType.entries.filter { it != MemScanType.UNKNOWN && it != MemScanType.GROUP && it != MemScanType.XOR_ENCRYPTED }
                        } else {
                            MemScanType.entries.filter { !it.nextOnly }
                        }

                        if (showModePicker) {
                            Spacer(Modifier.height((4f * s).dp))
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
                            Spacer(Modifier.height((4f * s).dp))
                            val quickModes = if (state.hasActiveScan) {
                                listOf(MemScanType.EXACT, MemScanType.CHANGED, MemScanType.INCREASED, MemScanType.DECREASED)
                            } else {
                                listOf(MemScanType.EXACT, MemScanType.UNKNOWN, MemScanType.GROUP, MemScanType.XOR_ENCRYPTED)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy((4f * s).dp), modifier = Modifier.fillMaxWidth()) {
                                quickModes.forEach { st ->
                                    val sel = state.scanType == st
                                    val shortName = when (st) {
                                        MemScanType.EXACT -> "Exact"
                                        MemScanType.UNKNOWN -> "Unknown"
                                        MemScanType.GROUP -> "Group"
                                        MemScanType.XOR_ENCRYPTED -> "XOR"
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
                                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (3f * s).dp)) {
                                            Text(text = shortName, color = Color.White, fontSize = fsSmall, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height((5f * s).dp))

                        val isValuelessNext = state.hasActiveScan && (
                            state.scanType == MemScanType.CHANGED ||
                                state.scanType == MemScanType.INCREASED ||
                                state.scanType == MemScanType.DECREASED ||
                                state.scanType == MemScanType.UNCHANGED
                            )
                        val hideInput = (!state.hasActiveScan && state.scanType == MemScanType.UNKNOWN) || isValuelessNext
                        if (isValuelessNext) {
                            Text(
                                text = "Compare mode: ${state.scanType.label} — tap Next to filter",
                                color = Color(0xFFC4B5FD),
                                fontSize = fsTiny,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = (4f * s).dp)
                            )
                        } else if (!hideInput) {
                            Row(horizontalArrangement = Arrangement.spacedBy((5f * s).dp), modifier = Modifier.fillMaxWidth()) {
                                CeInputField(
                                    value = query1,
                                    onValueChange = { query1 = it },
                                    placeholder = when {
                                        state.scanType == MemScanType.GROUP -> "100;200;50.5f::64 (:: unord / : ord)"
                                        state.scanType == MemScanType.XOR_ENCRYPTED -> "Real integer value (e.g. 100)"
                                        state.dataType == MemDataType.AOB -> "48 8B ?? 01"
                                        state.scanType == MemScanType.BETWEEN -> "Min value"
                                        else -> "Value (e.g. 100 or 0x64)"
                                    },
                                    fontSize = fsBody,
                                    enabled = !previewMode,
                                    onDone = {
                                        focusManager.clearFocus()
                                        if (state.hasActiveScan) onNextScan(query1, query2) else onFirstScan(query1, query2)
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                if (state.scanType == MemScanType.BETWEEN || state.scanType == MemScanType.XOR_ENCRYPTED) {
                                    CeInputField(
                                        value = query2,
                                        onValueChange = { query2 = it },
                                        placeholder = if (state.scanType == MemScanType.XOR_ENCRYPTED) "Key (blank=Auto)" else "Max",
                                        fontSize = fsBody,
                                        enabled = !previewMode,
                                        onDone = {
                                            focusManager.clearFocus()
                                            if (state.hasActiveScan) onNextScan(query1, query2) else onFirstScan(query1, query2)
                                        },
                                        modifier = Modifier.weight(0.85f)
                                    )
                                }
                            }
                            Spacer(Modifier.height((5f * s).dp))
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy((4f * s).dp), modifier = Modifier.fillMaxWidth()) {
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
                                    text = if (state.undoDepth > 1) "Undo(${state.undoDepth})" else "Undo",
                                    bg = if (state.canUndo) Color(0xFF475569) else Color(0xFF1E293B),
                                    fg = Color.White,
                                    fontSize = fsSmall,
                                    minHeight = minBtnH,
                                    enabled = !previewMode && state.canUndo,
                                    onClick = onUndoScan,
                                    modifier = Modifier.weight(0.85f)
                                )
                                CeButton(
                                    text = "New",
                                    bg = Color(0xFF334155),
                                    fg = Color.White,
                                    fontSize = fsSmall,
                                    minHeight = minBtnH,
                                    enabled = !previewMode,
                                    onClick = { editingScanAddr = null; showBulkScanEdit = false; sourceFilter = "all"; onResetScan() },
                                    modifier = Modifier.weight(0.7f)
                                )
                            }
                        }

                        Spacer(Modifier.height((4f * s).dp))

                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = state.statusText,
                                color = if (state.lastScanTimedOut) Color(0xFFFBBF24) else Color(0xFF94A3B8),
                                fontSize = fsTiny,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (state.scope == MemScope.PAGE_WASM) {
                                Spacer(Modifier.width((3f * s).dp))
                                Surface(
                                    shape = RoundedCornerShape((6f * s).dp),
                                    color = if (state.hideUiNoise) Color(0xFF0284C7) else Color(0xFF1E293B),
                                    modifier = Modifier
                                        .heightIn(min = (24f * s).coerceIn(20f, 32f).dp)
                                        .clickable(enabled = !previewMode, onClick = onToggleHideUiNoise)
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (5f * s).dp)) {
                                        Text(
                                            text = if (state.hideUiNoise) "No-UI✓" else "HideUI",
                                            color = if (state.hideUiNoise) Color.White else Color(0xFFCBD5E1),
                                            fontSize = fsTiny,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                                Spacer(Modifier.width((3f * s).dp))
                                Surface(
                                    shape = RoundedCornerShape((6f * s).dp),
                                    color = Color(0xFF1E293B),
                                    modifier = Modifier
                                        .heightIn(min = (24f * s).coerceIn(20f, 32f).dp)
                                        .clickable(enabled = !previewMode && !state.busy, onClick = onReloadAndHook)
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (5f * s).dp)) {
                                        Text(text = "↻Hook", color = Color(0xFFA7F3D0), fontSize = fsTiny, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                            if (state.hasActiveScan && state.results.isNotEmpty()) {
                                Spacer(Modifier.width((3f * s).dp))
                                Surface(
                                    shape = RoundedCornerShape((6f * s).dp),
                                    color = if (state.liveScanRefresh) Color(0xFF059669) else Color(0xFF1E293B),
                                    modifier = Modifier
                                        .heightIn(min = (24f * s).coerceIn(20f, 32f).dp)
                                        .clickable(enabled = !previewMode, onClick = onToggleLiveScanRefresh)
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (5f * s).dp)) {
                                        Text(text = if (state.liveScanRefresh) "●Live" else "Live", color = Color.White, fontSize = fsTiny, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                Spacer(Modifier.width((3f * s).dp))
                                Surface(
                                    shape = RoundedCornerShape((6f * s).dp),
                                    color = Color(0xFF1E293B),
                                    modifier = Modifier
                                        .heightIn(min = (24f * s).coerceIn(20f, 32f).dp)
                                        .clickable(enabled = !previewMode && !state.busy, onClick = onRefreshScan)
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (5f * s).dp)) {
                                        Text(text = "↻", color = Color(0xFF38BDF8), fontSize = fsSmall, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        if (state.results.isNotEmpty()) {
                            val filteredResults = remember(state.results, sourceFilter) {
                                when (sourceFilter) {
                                    "wasm" -> state.results.filter { it.kind == "wasm" }
                                    "buf" -> state.results.filter { it.kind == "ab" || it.kind == "ta" }
                                    "js" -> state.results.filter { it.kind == "js" || it.kind == "map" || it.kind.startsWith("ls") || it.kind.startsWith("ss") }
                                    "iframe" -> state.results.filter { it.addr.startsWith("@") }
                                    else -> state.results
                                }
                            }
                            val visibleSlice = remember(filteredResults) { filteredResults.take(120) }
                            val allVisibleSelected = visibleSlice.isNotEmpty() && visibleSlice.all { it.addr in state.selectedScanAddrs }
                            val selCount = state.selectedScanAddrs.size
                            val effCount = if (selCount > 0) selCount else visibleSlice.size

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
                                    Row(horizontalArrangement = Arrangement.spacedBy((3f * s).dp), modifier = Modifier.fillMaxWidth()) {
                                        filterOpts.forEach { (key, lbl) ->
                                            val active = sourceFilter == key
                                            Surface(
                                                shape = RoundedCornerShape((6f * s).dp),
                                                color = if (active) Color(0xFF0284C7) else Color(0xFF1E293B),
                                                modifier = Modifier.weight(1f).heightIn(min = (22f * s).dp).clickable { sourceFilter = key }
                                            ) {
                                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (3f * s).dp)) {
                                                    Text(text = lbl, color = Color.White, fontSize = fsTiny, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height((4f * s).dp))
                            Row(horizontalArrangement = Arrangement.spacedBy((4f * s).dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Surface(
                                    shape = RoundedCornerShape((6f * s).dp),
                                    color = if (allVisibleSelected) Color(0xFF0284C7) else Color(0xFF334155),
                                    modifier = Modifier
                                        .heightIn(min = (26f * s).coerceIn(22f, 34f).dp)
                                        .clickable(enabled = !previewMode) {
                                            if (allVisibleSelected) onClearScanSelection()
                                            else onSelectAllScanResults(visibleSlice.map { it.addr })
                                        }
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (6f * s).dp)) {
                                        Text(text = if (allVisibleSelected) "☑ Clear" else "☐ All", color = Color.White, fontSize = fsTiny, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape((6f * s).dp),
                                    color = accent,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = (26f * s).coerceIn(22f, 34f).dp)
                                        .clickable(enabled = !previewMode && !state.busy) {
                                            onAddSelectedToWatch(false)
                                            activeTab = 1
                                        }
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (4f * s).dp)) {
                                        Text(text = "→ Watch ($effCount)", color = Color(0xFF042F2E), fontSize = fsTiny, fontWeight = FontWeight.Bold, maxLines = 1)
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape((6f * s).dp),
                                    color = Color(0xFFB45309),
                                    modifier = Modifier
                                        .heightIn(min = (26f * s).coerceIn(22f, 34f).dp)
                                        .clickable(enabled = !previewMode && !state.busy) {
                                            showBulkScanEdit = !showBulkScanEdit
                                            if (showBulkScanEdit && bulkScanDraft.isBlank()) bulkScanDraft = query1
                                        }
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (6f * s).dp)) {
                                        Text(text = "Edit($effCount)", color = Color.White, fontSize = fsTiny, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape((6f * s).dp),
                                    color = Color(0xFF0284C7),
                                    modifier = Modifier
                                        .heightIn(min = (26f * s).coerceIn(22f, 34f).dp)
                                        .clickable(enabled = !previewMode && !state.busy) {
                                            onAddSelectedToWatch(true)
                                            activeTab = 1
                                        }
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (6f * s).dp)) {
                                        Text(text = "Lock", color = Color.White, fontSize = fsTiny, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            if (showBulkScanEdit) {
                                Spacer(Modifier.height((4f * s).dp))
                                Row(horizontalArrangement = Arrangement.spacedBy((4f * s).dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    CeInputField(
                                        value = bulkScanDraft,
                                        onValueChange = { bulkScanDraft = it },
                                        placeholder = "Val or +100 / x2 / --50 ($effCount)",
                                        fontSize = fsBody,
                                        enabled = !previewMode,
                                        onDone = {
                                            if (selCount > 0) onEditSelectedScanResults(bulkScanDraft) else onWriteAllResults(bulkScanDraft)
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
                                            if (selCount > 0) onEditSelectedScanResults(bulkScanDraft) else onWriteAllResults(bulkScanDraft)
                                            showBulkScanEdit = false
                                            focusManager.clearFocus()
                                        }
                                    )
                                }
                            }

                            Spacer(Modifier.height((5f * s).dp))
                            Column(
                                verticalArrangement = Arrangement.spacedBy((4f * s).dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = listMaxHeight)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                visibleSlice.forEach { item ->
                                    val isSel = item.addr in state.selectedScanAddrs
                                    val changed = item.prevValue.isNotEmpty() && item.prevValue != item.value
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(if (isSel) Color(0xFF1E3A5F) else cardBg, RoundedCornerShape((7f * s).dp))
                                            .border(if (isSel) 1.dp else 0.dp, if (isSel) Color(0xFF38BDF8) else Color.Transparent, RoundedCornerShape((7f * s).dp))
                                            .padding(horizontal = (6f * s).dp, vertical = (4.5f * s).dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .size((22f * s).coerceIn(18f, 30f).dp)
                                                    .background(if (isSel) Color(0xFF0284C7) else Color(0xFF0F172A), RoundedCornerShape((5f * s).dp))
                                                    .border(1.dp, if (isSel) Color(0xFF38BDF8) else Color(0xFF475569), RoundedCornerShape((5f * s).dp))
                                                    .clickable(enabled = !previewMode) { onToggleSelectScanAddr(item.addr) }
                                            ) {
                                                Text(text = if (isSel) "✓" else "", color = Color.White, fontSize = fsSmall, fontWeight = FontWeight.Bold)
                                            }
                                            Spacer(Modifier.width((4f * s).dp))
                                            Column(modifier = Modifier.weight(1f).clickable(enabled = !previewMode) { onToggleSelectScanAddr(item.addr) }) {
                                                val roleTag = when (item.role) {
                                                    "wasm" -> "[WASM] "
                                                    "buf" -> "[BUF] "
                                                    "ui" -> "[UI?] "
                                                    "store" -> "[STORE] "
                                                    else -> if (item.score >= 88) "[GAME] " else ""
                                                }
                                                val tagPrefix = buildString {
                                                    append(roleTag)
                                                    if (item.engine.isNotBlank()) append("[${item.engine}] ")
                                                    if (item.xorMode != 0) append("[XOR] ")
                                                    append(item.addr)
                                                }
                                                Text(
                                                    text = tagPrefix,
                                                    color = when (item.role) {
                                                        "ui" -> Color(0xFFFBBF24)
                                                        "wasm" -> Color(0xFF67E8F9)
                                                        else -> if (item.score >= 88) Color(0xFF6EE7B7) else Color(0xFF93C5FD)
                                                    },
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
                                                    .heightIn(min = (26f * s).coerceIn(22f, 34f).dp)
                                                    .clickable(enabled = !previewMode) {
                                                        editingScanAddr = if (editingScanAddr == item.addr) null else item.addr
                                                        scanEditDraft = item.value
                                                    }
                                            ) {
                                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (5f * s).dp)) {
                                                    Text(text = "Edit", color = Color(0xFFA7F3D0), fontSize = fsTiny, fontWeight = FontWeight.SemiBold)
                                                }
                                            }
                                            Spacer(Modifier.width((3f * s).dp))
                                            if (state.scope == MemScope.FABLE_NATIVE || item.kind == "wasm" || item.kind == "ta" || item.kind == "ab") {
                                                Surface(
                                                    shape = RoundedCornerShape((6f * s).dp),
                                                    color = Color(0xFF334155),
                                                    modifier = Modifier
                                                        .heightIn(min = (26f * s).coerceIn(22f, 34f).dp)
                                                        .clickable(enabled = !previewMode) {
                                                            hexCursorByte = 0
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
                                                color = Color(0xFF4C1D95),
                                                modifier = Modifier
                                                    .heightIn(min = (26f * s).coerceIn(22f, 34f).dp)
                                                    .clickable(enabled = !previewMode) { onPointerScan(item.addr, state.scope) }
                                            ) {
                                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (5f * s).dp)) {
                                                    Text(text = "Ptr", color = Color(0xFFDDD6FE), fontSize = fsTiny)
                                                }
                                            }
                                            Spacer(Modifier.width((3f * s).dp))
                                            Surface(
                                                shape = RoundedCornerShape((6f * s).dp),
                                                color = accent,
                                                modifier = Modifier
                                                    .heightIn(min = (26f * s).coerceIn(22f, 34f).dp)
                                                    .clickable(enabled = !previewMode) { onAddWatch(item) }
                                            ) {
                                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (5f * s).dp)) {
                                                    Text(text = "+W", color = Color(0xFF042F2E), fontSize = fsTiny, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                        if (editingScanAddr == item.addr) {
                                            Spacer(Modifier.height((4f * s).dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy((4f * s).dp), verticalAlignment = Alignment.CenterVertically) {
                                                CeInputField(
                                                    value = scanEditDraft,
                                                    onValueChange = { scanEditDraft = it },
                                                    placeholder = "Val or +100 / x2 / --50",
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

                        if (state.pointerResults.isNotEmpty()) {
                            Spacer(Modifier.height((5f * s).dp))
                            Text(
                                text = "Pointers -> ${state.pointerOriginAddr.takeLast(20)} (${state.pointerResults.size})",
                                color = Color(0xFFDDD6FE),
                                fontSize = fsTiny,
                                fontWeight = FontWeight.SemiBold
                            )
                            Column(
                                verticalArrangement = Arrangement.spacedBy((3f * s).dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = (90f * s).coerceIn(55f, 140f).dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                state.pointerResults.take(25).forEach { pr ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFF1E1B4B), RoundedCornerShape((6f * s).dp))
                                            .padding(horizontal = (6f * s).dp, vertical = (3f * s).dp)
                                    ) {
                                        Text(
                                            text = "${pr.ptrAddr} [${pr.offset}] (${pr.region})",
                                            color = Color(0xFFE0E7FF),
                                            fontSize = fsTiny,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (!pr.ptrAddr.startsWith("js:")) {
                                            Surface(
                                                shape = RoundedCornerShape((5f * s).dp),
                                                color = Color(0xFF334155),
                                                modifier = Modifier.clickable(enabled = !previewMode) {
                                                    hexCursorByte = 0
                                                    onInspectHex(pr.ptrAddr, state.pointerScope, 0L)
                                                    activeTab = 2
                                                }
                                            ) {
                                                Text(text = "Hex", color = Color.White, fontSize = fsTiny, modifier = Modifier.padding(horizontal = (5f * s).dp, vertical = (2f * s).dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (state.mirrorResults.isNotEmpty()) {
                            Spacer(Modifier.height((5f * s).dp))
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Mirrors of ${state.mirrorOriginAddr.takeLast(18)} (${state.mirrorResults.size})",
                                    color = Color(0xFF67E8F9),
                                    fontSize = fsTiny,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                Surface(
                                    shape = RoundedCornerShape((6f * s).dp),
                                    color = Color(0xFF0284C7),
                                    modifier = Modifier.clickable(enabled = !previewMode) {
                                        onLockAllMirrors()
                                        activeTab = 1
                                    }
                                ) {
                                    Text(
                                        text = "Lock All (${state.mirrorResults.size.coerceAtMost(30)})",
                                        color = Color.White,
                                        fontSize = fsTiny,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = (6f * s).dp, vertical = (2.5f * s).dp)
                                    )
                                }
                            }
                            Column(
                                verticalArrangement = Arrangement.spacedBy((3f * s).dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = (80f * s).coerceIn(50f, 120f).dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                state.mirrorResults.take(15).forEach { mr ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFF0F2942), RoundedCornerShape((6f * s).dp))
                                            .padding(horizontal = (6f * s).dp, vertical = (2.5f * s).dp)
                                    ) {
                                        Text(
                                            text = "[${mr.role.uppercase()}] ${mr.addr} = ${mr.value}",
                                            color = Color(0xFFE0F2FE),
                                            fontSize = fsTiny,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Surface(
                                            shape = RoundedCornerShape((5f * s).dp),
                                            color = accent,
                                            modifier = Modifier.clickable(enabled = !previewMode) { onAddWatch(mr) }
                                        ) {
                                            Text(text = "+W", color = Color(0xFF042F2E), fontSize = fsTiny, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = (5f * s).dp, vertical = (2f * s).dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ==================== WATCH (EDIT, FREEZE, TRACE, CHEAT TABLES) TAB ====================
                    1 -> {
                        CeWatchTabContent(
                            state = state,
                            s = s,
                            accent = accent,
                            cardBg = cardBg,
                            minBtnH = minBtnH,
                            listMaxHeight = listMaxHeight,
                            fsBody = fsBody,
                            fsSmall = fsSmall,
                            fsTiny = fsTiny,
                            previewMode = previewMode,
                            onSwitchTab = { activeTab = it },
                            onSetWatchValue = onSetWatchValue,
                            onRestoreWatchOriginal = onRestoreWatchOriginal,
                            onToggleWatchFreeze = onToggleWatchFreeze,
                            onRemoveWatch = onRemoveWatch,
                            onUnfreezeAll = onUnfreezeAll,
                            onToggleSelectWatchId = onToggleSelectWatchId,
                            onSelectAllWatchEntries = onSelectAllWatchEntries,
                            onClearWatchSelection = onClearWatchSelection,
                            onEditSelectedWatchEntries = onEditSelectedWatchEntries,
                            onFreezeSelectedWatchEntries = onFreezeSelectedWatchEntries,
                            onRestoreSelectedWatchEntries = onRestoreSelectedWatchEntries,
                            onRemoveSelectedWatchEntries = onRemoveSelectedWatchEntries,
                            onCycleWatchFreezeRule = onCycleWatchFreezeRule,
                            onSetWatchFreezeParam = onSetWatchFreezeParam,
                            onRenameWatch = onRenameWatch,
                            onWatchQuickMath = onWatchQuickMath,
                            onPeekNearbyStruct = onPeekNearbyStruct,
                            onPointerScan = onPointerScan,
                            onToggleWatchTrace = onToggleWatchTrace,
                            onClearWatchTrace = onClearWatchTrace,
                            onSaveCheatTable = onSaveCheatTable,
                            onLoadCheatTable = onLoadCheatTable,
                            onDeleteCheatTable = onDeleteCheatTable,
                            onExportCheatTableJson = onExportCheatTableJson,
                            onImportCheatTableJson = onImportCheatTableJson,
                            onSetFreezeStrength = onSetFreezeStrength,
                            onFindMirrorsForWatch = onFindMirrorsForWatch,
                            onFindRealValueFromUiWatch = onFindRealValueFromUiWatch
                        )
                    }

                    // ==================== LIVE HEX EDITOR & STRUCT DISSECTOR TAB ====================
                    2 -> {
                        CeHexTabContent(
                            state = state,
                            s = s,
                            cardBg = cardBg,
                            listMaxHeight = listMaxHeight,
                            fsSmall = fsSmall,
                            fsTiny = fsTiny,
                            previewMode = previewMode,
                            hexCursorByte = hexCursorByte,
                            onSetHexCursor = { hexCursorByte = it },
                            onInspectHex = onInspectHex,
                            onToggleHexLiveRefresh = onToggleHexLiveRefresh,
                            onPeekNearbyStruct = onPeekNearbyStruct,
                            onWriteHexBytes = onWriteHexBytes,
                            onJumpHexOffset = onJumpHexOffset,
                            onAddStructFieldToWatch = onAddStructFieldToWatch
                        )
                    }

                    // ==================== MAP / DIAGNOSTICS TAB ====================
                    3 -> {
                        CeMapTabContent(
                            state = state,
                            s = s,
                            cardBg = cardBg,
                            minBtnH = minBtnH,
                            listMaxHeight = listMaxHeight,
                            fsSmall = fsSmall,
                            fsTiny = fsTiny,
                            previewMode = previewMode,
                            onRefreshRegions = onRefreshRegions
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CeWatchTabContent(
    state: MemoryEngineUiState,
    s: Float,
    accent: Color,
    cardBg: Color,
    minBtnH: Dp,
    listMaxHeight: Dp,
    fsBody: androidx.compose.ui.unit.TextUnit,
    fsSmall: androidx.compose.ui.unit.TextUnit,
    fsTiny: androidx.compose.ui.unit.TextUnit,
    previewMode: Boolean,
    onSwitchTab: (Int) -> Unit,
    onSetWatchValue: (String, String) -> Unit,
    onRestoreWatchOriginal: (String) -> Unit,
    onToggleWatchFreeze: (String) -> Unit,
    onRemoveWatch: (String) -> Unit,
    onUnfreezeAll: () -> Unit,
    onToggleSelectWatchId: (String) -> Unit,
    onSelectAllWatchEntries: () -> Unit,
    onClearWatchSelection: () -> Unit,
    onEditSelectedWatchEntries: (String) -> Unit,
    onFreezeSelectedWatchEntries: (Boolean) -> Unit,
    onRestoreSelectedWatchEntries: () -> Unit,
    onRemoveSelectedWatchEntries: () -> Unit,
    onCycleWatchFreezeRule: (String) -> Unit,
    onSetWatchFreezeParam: (String, String) -> Unit,
    onRenameWatch: (String, String) -> Unit,
    onWatchQuickMath: (String, String) -> Unit,
    onPeekNearbyStruct: (String, MemScope) -> Unit,
    onPointerScan: (String, MemScope) -> Unit,
    onToggleWatchTrace: (String) -> Unit,
    onClearWatchTrace: (String) -> Unit,
    onSaveCheatTable: (String) -> Unit,
    onLoadCheatTable: (String) -> Unit,
    onDeleteCheatTable: (String) -> Unit,
    onExportCheatTableJson: () -> String,
    onImportCheatTableJson: (String) -> Boolean,
    onSetFreezeStrength: (MemFreezeStrength) -> Unit,
    onFindMirrorsForWatch: (String) -> Unit,
    onFindRealValueFromUiWatch: (String) -> Unit
) {
    var showBulkWatchEdit by remember { mutableStateOf(false) }
    var bulkWatchDraft by remember { mutableStateOf("") }
    var editingWatchId by remember { mutableStateOf<String?>(null) }
    var editDraft by remember { mutableStateOf("") }
    var ruleParamDraft by remember { mutableStateOf("") }
    var renameDraft by remember { mutableStateOf("") }
    var showTablesDrawer by remember { mutableStateOf(false) }
    var tableNameDraft by remember { mutableStateOf("") }
    var tableImportDraft by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val clipboard = LocalClipboardManager.current
                        val allWatchSelected = state.watchList.isNotEmpty() && state.watchList.all { it.id in state.selectedWatchIds }
                        val wSelCount = state.selectedWatchIds.size
                        val wEffCount = if (wSelCount > 0) wSelCount else state.watchList.size

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((3.5f * s).dp), modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Freeze:",
                                color = Color(0xFF94A3B8),
                                fontSize = fsTiny,
                                fontWeight = FontWeight.SemiBold
                            )
                            MemFreezeStrength.entries.forEach { stg ->
                                val activeStg = state.freezeStrength == stg
                                Surface(
                                    shape = RoundedCornerShape((6f * s).dp),
                                    color = when {
                                        activeStg && stg == MemFreezeStrength.MAX -> Color(0xFFDC2626)
                                        activeStg && stg == MemFreezeStrength.STRONG -> Color(0xFF0284C7)
                                        activeStg -> Color(0xFF065F46)
                                        else -> Color(0xFF1E293B)
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = (24f * s).coerceIn(20f, 32f).dp)
                                        .clickable(enabled = !previewMode) { onSetFreezeStrength(stg) }
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (3f * s).dp)) {
                                        Text(
                                            text = stg.shortLabel,
                                            color = Color.White,
                                            fontSize = fsTiny,
                                            fontWeight = if (activeStg) FontWeight.Bold else FontWeight.Medium,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height((4f * s).dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((4f * s).dp), modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Watch (${state.watchList.size})",
                                color = Color(0xFFCBD5E1),
                                fontSize = fsBody,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(
                                shape = RoundedCornerShape((7f * s).dp),
                                color = if (showTablesDrawer) Color(0xFF0284C7) else Color(0xFF1E293B),
                                modifier = Modifier
                                    .heightIn(min = (26f * s).coerceIn(22f, 34f).dp)
                                    .clickable(enabled = !previewMode) { showTablesDrawer = !showTablesDrawer }
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (7f * s).dp)) {
                                    Text(text = "Tables(${state.savedTables.size})", color = Color(0xFF93C5FD), fontSize = fsTiny, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            if (state.watchList.any { it.frozen }) {
                                Surface(
                                    shape = RoundedCornerShape((7f * s).dp),
                                    color = Color(0xFFB45309),
                                    modifier = Modifier
                                        .heightIn(min = (26f * s).coerceIn(22f, 34f).dp)
                                        .clickable(enabled = !previewMode, onClick = onUnfreezeAll)
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (7f * s).dp)) {
                                        Text(text = "Unlock All", color = Color.White, fontSize = fsTiny, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }

                        if (showTablesDrawer) {
                            Spacer(Modifier.height((5f * s).dp))
                            Column(
                                verticalArrangement = Arrangement.spacedBy((4f * s).dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF0F172A), RoundedCornerShape((8f * s).dp))
                                    .border(1.dp, Color(0xFF334155), RoundedCornerShape((8f * s).dp))
                                    .padding((6f * s).dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy((4f * s).dp), verticalAlignment = Alignment.CenterVertically) {
                                    CeInputField(
                                        value = tableNameDraft,
                                        onValueChange = { tableNameDraft = it },
                                        placeholder = "Table name (optional)",
                                        fontSize = fsSmall,
                                        enabled = !previewMode,
                                        onDone = { onSaveCheatTable(tableNameDraft); tableNameDraft = ""; focusManager.clearFocus() },
                                        modifier = Modifier.weight(1f)
                                    )
                                    CeButton(
                                        text = "Save",
                                        bg = accent,
                                        fg = Color(0xFF042F2E),
                                        fontSize = fsTiny,
                                        minHeight = (28f * s).coerceIn(24f, 36f).dp,
                                        enabled = !previewMode && state.watchList.isNotEmpty(),
                                        onClick = { onSaveCheatTable(tableNameDraft); tableNameDraft = ""; focusManager.clearFocus() }
                                    )
                                    CeButton(
                                        text = "Copy",
                                        bg = Color(0xFF334155),
                                        fg = Color.White,
                                        fontSize = fsTiny,
                                        minHeight = (28f * s).coerceIn(24f, 36f).dp,
                                        enabled = !previewMode && state.watchList.isNotEmpty(),
                                        onClick = {
                                            val exported = onExportCheatTableJson()
                                            if (exported.isNotBlank()) clipboard.setText(AnnotatedString(exported))
                                        }
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy((4f * s).dp), verticalAlignment = Alignment.CenterVertically) {
                                    CeInputField(
                                        value = tableImportDraft,
                                        onValueChange = { tableImportDraft = it },
                                        placeholder = "Paste Cheat Table JSON to import",
                                        fontSize = fsSmall,
                                        enabled = !previewMode,
                                        onDone = {
                                            if (onImportCheatTableJson(tableImportDraft)) tableImportDraft = ""
                                            focusManager.clearFocus()
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    CeButton(
                                        text = "Import",
                                        bg = Color(0xFF0284C7),
                                        fg = Color.White,
                                        fontSize = fsTiny,
                                        minHeight = (28f * s).coerceIn(24f, 36f).dp,
                                        enabled = !previewMode && tableImportDraft.isNotBlank(),
                                        onClick = {
                                            if (onImportCheatTableJson(tableImportDraft)) tableImportDraft = ""
                                            focusManager.clearFocus()
                                        }
                                    )
                                }
                                if (state.savedTables.isNotEmpty()) {
                                    state.savedTables.take(6).forEach { tbl ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy((4f * s).dp),
                                            modifier = Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape((6f * s).dp)).padding((5f * s).dp)
                                        ) {
                                            Text(
                                                text = "${tbl.name} (${tbl.entries.size})",
                                                color = Color.White,
                                                fontSize = fsTiny,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Surface(
                                                shape = RoundedCornerShape((5f * s).dp),
                                                color = Color(0xFF065F46),
                                                modifier = Modifier.clickable(enabled = !previewMode) { onLoadCheatTable(tbl.id); showTablesDrawer = false }
                                            ) {
                                                Text(text = "Load", color = Color(0xFFA7F3D0), fontSize = fsTiny, modifier = Modifier.padding(horizontal = (6f * s).dp, vertical = (2.5f * s).dp))
                                            }
                                            Surface(
                                                shape = RoundedCornerShape((5f * s).dp),
                                                color = Color(0xFF7F1D1D),
                                                modifier = Modifier.clickable(enabled = !previewMode) { onDeleteCheatTable(tbl.id) }
                                            ) {
                                                Text(text = "✕", color = Color.White, fontSize = fsTiny, modifier = Modifier.padding(horizontal = (5f * s).dp, vertical = (2.5f * s).dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (state.watchList.isNotEmpty()) {
                            Spacer(Modifier.height((5f * s).dp))
                            Row(horizontalArrangement = Arrangement.spacedBy((3.5f * s).dp), modifier = Modifier.fillMaxWidth()) {
                                Surface(
                                    shape = RoundedCornerShape((6f * s).dp),
                                    color = if (allWatchSelected) Color(0xFF0284C7) else Color(0xFF334155),
                                    modifier = Modifier
                                        .heightIn(min = (26f * s).coerceIn(22f, 34f).dp)
                                        .clickable(enabled = !previewMode) {
                                            if (allWatchSelected) onClearWatchSelection() else onSelectAllWatchEntries()
                                        }
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (6f * s).dp)) {
                                        Text(text = if (allWatchSelected) "☑" else "☐", color = Color.White, fontSize = fsTiny, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape((6f * s).dp),
                                    color = Color(0xFF065F46),
                                    modifier = Modifier.weight(1f).heightIn(min = (26f * s).coerceIn(22f, 34f).dp).clickable(enabled = !previewMode) { showBulkWatchEdit = !showBulkWatchEdit }
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (3f * s).dp)) {
                                        Text(text = "Edit($wEffCount)", color = Color(0xFFA7F3D0), fontSize = fsTiny, fontWeight = FontWeight.Bold, maxLines = 1)
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape((6f * s).dp),
                                    color = Color(0xFF0284C7),
                                    modifier = Modifier.weight(0.85f).heightIn(min = (26f * s).coerceIn(22f, 34f).dp).clickable(enabled = !previewMode) { onFreezeSelectedWatchEntries(true) }
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (3f * s).dp)) {
                                        Text(text = "Lock", color = Color.White, fontSize = fsTiny, fontWeight = FontWeight.Bold, maxLines = 1)
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape((6f * s).dp),
                                    color = Color(0xFF475569),
                                    modifier = Modifier.weight(0.8f).heightIn(min = (26f * s).coerceIn(22f, 34f).dp).clickable(enabled = !previewMode) { onFreezeSelectedWatchEntries(false) }
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (3f * s).dp)) {
                                        Text(text = "Free", color = Color.White, fontSize = fsTiny, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape((6f * s).dp),
                                    color = Color(0xFF334155),
                                    modifier = Modifier.weight(0.8f).heightIn(min = (26f * s).coerceIn(22f, 34f).dp).clickable(enabled = !previewMode) { onRestoreSelectedWatchEntries() }
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (3f * s).dp)) {
                                        Text(text = "Undo", color = Color.White, fontSize = fsTiny, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape((6f * s).dp),
                                    color = Color(0xFF991B1B),
                                    modifier = Modifier.heightIn(min = (26f * s).coerceIn(22f, 34f).dp).clickable(enabled = !previewMode) { onRemoveSelectedWatchEntries() }
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (6f * s).dp)) {
                                        Text(text = "Del", color = Color.White, fontSize = fsTiny, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            if (showBulkWatchEdit) {
                                Spacer(Modifier.height((4f * s).dp))
                                Row(horizontalArrangement = Arrangement.spacedBy((4f * s).dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    CeInputField(
                                        value = bulkWatchDraft,
                                        onValueChange = { bulkWatchDraft = it },
                                        placeholder = "Val or +100 / x2 / --50 ($wEffCount)",
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
                                text = "No watched values yet. Run a scan and tap → Watch to edit, Titanium-freeze (= / >= / <= / [a..b] / +=/s), trace writes, or save Cheat Tables.",
                                color = Color(0xFF94A3B8),
                                fontSize = fsSmall
                            )
                        } else {
                            Column(
                                verticalArrangement = Arrangement.spacedBy((5f * s).dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = listMaxHeight)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                state.watchList.forEach { w ->
                                    val isWSel = w.id in state.selectedWatchIds
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(if (isWSel) Color(0xFF1E3A5F) else cardBg, RoundedCornerShape((7f * s).dp))
                                            .border(
                                                width = if (isWSel || w.frozen || !w.attached) 1.dp else 0.dp,
                                                color = when {
                                                    !w.attached -> Color(0xFFF59E0B)
                                                    isWSel -> Color(0xFF38BDF8)
                                                    w.frozen -> Color(0xFF0284C7)
                                                    else -> Color.Transparent
                                                },
                                                shape = RoundedCornerShape((7f * s).dp)
                                            )
                                            .padding((6f * s).dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .size((22f * s).coerceIn(18f, 30f).dp)
                                                    .background(if (isWSel) Color(0xFF0284C7) else Color(0xFF0F172A), RoundedCornerShape((5f * s).dp))
                                                    .border(1.dp, if (isWSel) Color(0xFF38BDF8) else Color(0xFF475569), RoundedCornerShape((5f * s).dp))
                                                    .clickable(enabled = !previewMode) { onToggleSelectWatchId(w.id) }
                                            ) {
                                                Text(text = if (isWSel) "✓" else "", color = Color.White, fontSize = fsSmall, fontWeight = FontWeight.Bold)
                                            }
                                            Spacer(Modifier.width((3.5f * s).dp))
                                            Surface(
                                                shape = RoundedCornerShape((6f * s).dp),
                                                color = when {
                                                    w.freezeStatus == "failed" || w.freezeStatus == "detached" -> Color(0xFFB91C1C)
                                                    w.frozen -> Color(0xFF0284C7)
                                                    else -> Color(0xFF334155)
                                                },
                                                modifier = Modifier
                                                    .heightIn(min = (26f * s).coerceIn(22f, 34f).dp)
                                                    .clickable(enabled = !previewMode) { onToggleWatchFreeze(w.id) }
                                            ) {
                                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (5f * s).dp)) {
                                                    Text(text = if (w.frozen) "LOCK" else "Free", color = Color.White, fontSize = fsTiny, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                            Spacer(Modifier.width((3f * s).dp))
                                            Surface(
                                                shape = RoundedCornerShape((6f * s).dp),
                                                color = when (w.freezeRule) {
                                                    MemFreezeRule.EXACT -> Color(0xFF1E293B)
                                                    MemFreezeRule.MIN -> Color(0xFF065F46)
                                                    MemFreezeRule.MAX -> Color(0xFF7C2D12)
                                                    MemFreezeRule.RANGE -> Color(0xFF4C1D95)
                                                    MemFreezeRule.STEP -> Color(0xFF0F766E)
                                                },
                                                modifier = Modifier
                                                    .heightIn(min = (26f * s).coerceIn(22f, 34f).dp)
                                                    .clickable(enabled = !previewMode) { onCycleWatchFreezeRule(w.id) }
                                            ) {
                                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (4.5f * s).dp)) {
                                                    Text(text = w.freezeRule.symbol, color = Color(0xFFE2E8F0), fontSize = fsTiny, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                            Spacer(Modifier.width((4f * s).dp))
                                            Column(modifier = Modifier.weight(1f).clickable(enabled = !previewMode) { onToggleSelectWatchId(w.id) }) {
                                                Text(
                                                    text = if (!w.attached) "[UNATTACHED] ${w.label}" else w.label,
                                                    color = if (!w.attached) Color(0xFFFBBF24) else Color(0xFF93C5FD),
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
                                                if (w.frozen || w.freezeStatus == "failed" || w.freezeStatus == "detached") {
                                                    val modeTxt = when (w.freezeMode) {
                                                        "trap" -> "Trap"
                                                        "trap+poll" -> "Trap+Poll"
                                                        "accessor-trap" -> "Accessor"
                                                        "MAX-1000Hz", "MAX-XOR", "2500Hz", "667Hz", "667Hz-XOR", "250Hz", "250Hz-XOR", "60Hz", "60Hz-XOR", "500Hz", "500Hz-XOR" -> w.freezeMode
                                                        else -> "Lock"
                                                    }
                                                    val ruleTxt = w.freezeRule.symbol
                                                    val rpsTxt = if (w.writesPerSec > 0) " (${w.writesPerSec}/s)" else ""
                                                    val badgeTxt = when (w.freezeStatus) {
                                                        "failed" -> "FREEZE FAILED"
                                                        "detached" -> "DETACHED / GC'd"
                                                        "arming" -> "Arming $ruleTxt..."
                                                        "fighting" -> "$modeTxt ($ruleTxt) · ${w.blockedWrites} blk$rpsTxt FIGHTING"
                                                        "enforcing" -> "$modeTxt ($ruleTxt) · ${w.blockedWrites} blk$rpsTxt"
                                                        else -> "$modeTxt ($ruleTxt ${w.frozenValue})"
                                                    }
                                                    val badgeColor = when (w.freezeStatus) {
                                                        "failed", "detached" -> Color(0xFFF87171)
                                                        "arming" -> Color(0xFF94A3B8)
                                                        "fighting" -> Color(0xFFFBBF24)
                                                        "enforcing" -> Color(0xFF34D399)
                                                        else -> Color(0xFF38BDF8)
                                                    }
                                                    Text(text = badgeTxt, color = badgeColor, fontSize = fsTiny, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                            Surface(
                                                shape = RoundedCornerShape((6f * s).dp),
                                                color = if (w.tracing) Color(0xFF991B1B) else Color(0xFF1E293B),
                                                modifier = Modifier
                                                    .heightIn(min = (26f * s).coerceIn(22f, 34f).dp)
                                                    .clickable(enabled = !previewMode) { onToggleWatchTrace(w.id) }
                                            ) {
                                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (5f * s).dp)) {
                                                    Text(
                                                        text = if (w.tracing) "●${w.traceCount}" else "Spy",
                                                        color = if (w.tracing) Color(0xFFFCA5A5) else Color(0xFF94A3B8),
                                                        fontSize = fsTiny,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.width((3f * s).dp))
                                            Surface(
                                                shape = RoundedCornerShape((6f * s).dp),
                                                color = Color(0xFF065F46),
                                                modifier = Modifier
                                                    .heightIn(min = (26f * s).coerceIn(22f, 34f).dp)
                                                    .clickable(enabled = !previewMode) {
                                                        editingWatchId = if (editingWatchId == w.id) null else w.id
                                                        editDraft = w.currentValue
                                                        ruleParamDraft = w.frozenValue.ifBlank { w.currentValue }
                                                        renameDraft = w.label
                                                    }
                                            ) {
                                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (5f * s).dp)) {
                                                    Text(text = "Edit", color = Color(0xFFA7F3D0), fontSize = fsTiny)
                                                }
                                            }
                                            Spacer(Modifier.width((2.5f * s).dp))
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .heightIn(min = (26f * s).coerceIn(22f, 34f).dp)
                                                    .clickable(enabled = !previewMode) { onRemoveWatch(w.id) }
                                                    .padding(horizontal = (3.5f * s).dp)
                                            ) {
                                                Text(text = "✕", color = Color(0xFFF87171), fontSize = fsBody, fontWeight = FontWeight.Bold)
                                            }
                                        }

                                        if (editingWatchId == w.id) {
                                            Spacer(Modifier.height((4f * s).dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy((4f * s).dp), verticalAlignment = Alignment.CenterVertically) {
                                                CeInputField(
                                                    value = editDraft,
                                                    onValueChange = { editDraft = it },
                                                    placeholder = "Val or +100 / x2 / --50",
                                                    fontSize = fsBody,
                                                    enabled = !previewMode,
                                                    onDone = { onSetWatchValue(w.id, editDraft); editingWatchId = null; focusManager.clearFocus() },
                                                    modifier = Modifier.weight(1f)
                                                )
                                                CeButton(
                                                    text = "Write",
                                                    bg = accent,
                                                    fg = Color(0xFF042F2E),
                                                    fontSize = fsSmall,
                                                    minHeight = minBtnH,
                                                    enabled = !previewMode,
                                                    onClick = { onSetWatchValue(w.id, editDraft); editingWatchId = null; focusManager.clearFocus() }
                                                )
                                                CeButton(
                                                    text = "Undo",
                                                    bg = Color(0xFF475569),
                                                    fg = Color.White,
                                                    fontSize = fsTiny,
                                                    minHeight = minBtnH,
                                                    enabled = !previewMode,
                                                    onClick = { onRestoreWatchOriginal(w.id) }
                                                )
                                            }
                                            Spacer(Modifier.height((4f * s).dp))
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy((4f * s).dp),
                                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                            ) {
                                                listOf("+100", "+1000", "--100", "x2", "x10", "/2", "999999", "0").forEach { mop ->
                                                    Surface(
                                                        shape = RoundedCornerShape((6f * s).dp),
                                                        color = Color(0xFF1E293B),
                                                        modifier = Modifier.clickable(enabled = !previewMode) { onWatchQuickMath(w.id, mop) }
                                                    ) {
                                                        Text(
                                                            text = mop,
                                                            color = Color(0xFF34D399),
                                                            fontSize = fsTiny,
                                                            fontWeight = FontWeight.SemiBold,
                                                            modifier = Modifier.padding(horizontal = (6.5f * s).dp, vertical = (3.5f * s).dp)
                                                        )
                                                    }
                                                }
                                            }
                                            if (w.freezeRule == MemFreezeRule.RANGE || w.freezeRule == MemFreezeRule.STEP) {
                                                Spacer(Modifier.height((4f * s).dp))
                                                Row(horizontalArrangement = Arrangement.spacedBy((4f * s).dp), verticalAlignment = Alignment.CenterVertically) {
                                                    CeInputField(
                                                        value = ruleParamDraft,
                                                        onValueChange = { ruleParamDraft = it },
                                                        placeholder = if (w.freezeRule == MemFreezeRule.RANGE) "Range e.g. 50..100" else "Step/sec e.g. 10 or -5",
                                                        fontSize = fsSmall,
                                                        enabled = !previewMode,
                                                        onDone = { onSetWatchFreezeParam(w.id, ruleParamDraft); focusManager.clearFocus() },
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    CeButton(
                                                        text = "Set ${w.freezeRule.symbol}",
                                                        bg = Color(0xFF4C1D95),
                                                        fg = Color.White,
                                                        fontSize = fsTiny,
                                                        minHeight = (28f * s).coerceIn(24f, 36f).dp,
                                                        enabled = !previewMode,
                                                        onClick = { onSetWatchFreezeParam(w.id, ruleParamDraft); focusManager.clearFocus() }
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.height((4f * s).dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy((4f * s).dp), verticalAlignment = Alignment.CenterVertically) {
                                                CeInputField(
                                                    value = renameDraft,
                                                    onValueChange = { renameDraft = it },
                                                    placeholder = "Label name (e.g. Coins)",
                                                    fontSize = fsSmall,
                                                    enabled = !previewMode,
                                                    onDone = { onRenameWatch(w.id, renameDraft); focusManager.clearFocus() },
                                                    modifier = Modifier.weight(1f)
                                                )
                                                CeButton(
                                                    text = "Name",
                                                    bg = Color(0xFF334155),
                                                    fg = Color.White,
                                                    fontSize = fsTiny,
                                                    minHeight = (26f * s).coerceIn(22f, 34f).dp,
                                                    enabled = !previewMode,
                                                    onClick = { onRenameWatch(w.id, renameDraft); focusManager.clearFocus() }
                                                )
                                                CeButton(
                                                    text = "Struct",
                                                    bg = Color(0xFF0F766E),
                                                    fg = Color.White,
                                                    fontSize = fsTiny,
                                                    minHeight = (26f * s).coerceIn(22f, 34f).dp,
                                                    enabled = !previewMode,
                                                    onClick = { onPeekNearbyStruct(w.addr, w.scope); onSwitchTab(2) }
                                                )
                                                CeButton(
                                                    text = "Ptr",
                                                    bg = Color(0xFF4C1D95),
                                                    fg = Color.White,
                                                    fontSize = fsTiny,
                                                    minHeight = (26f * s).coerceIn(22f, 34f).dp,
                                                    enabled = !previewMode,
                                                    onClick = { onPointerScan(w.addr, w.scope); onSwitchTab(0) }
                                                )
                                                CeButton(
                                                    text = "Mirrors",
                                                    bg = Color(0xFF0369A1),
                                                    fg = Color.White,
                                                    fontSize = fsTiny,
                                                    minHeight = (26f * s).coerceIn(22f, 34f).dp,
                                                    enabled = !previewMode,
                                                    onClick = { onFindMirrorsForWatch(w.id); onSwitchTab(0) }
                                                )
                                            }
                                        }

                                        if (w.valueHistory.size >= 2) {
                                            Spacer(Modifier.height((3f * s).dp))
                                            Text(
                                                text = "History: " + w.valueHistory.takeLast(6).joinToString(" → "),
                                                color = Color(0xFF94A3B8),
                                                fontSize = fsTiny,
                                                fontFamily = FontFamily.Monospace,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        if (w.uiWarningReason.isNotBlank()) {
                                            Spacer(Modifier.height((4f * s).dp))
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy((4f * s).dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(Color(0xFF451A03), RoundedCornerShape((6f * s).dp))
                                                    .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape((6f * s).dp))
                                                    .padding(horizontal = (6f * s).dp, vertical = (4f * s).dp)
                                            ) {
                                                Text(
                                                    text = "⚠ ${w.uiWarningReason}",
                                                    color = Color(0xFFFDE68A),
                                                    fontSize = fsTiny,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Surface(
                                                    shape = RoundedCornerShape((5f * s).dp),
                                                    color = Color(0xFFF59E0B),
                                                    modifier = Modifier.clickable(enabled = !previewMode) {
                                                        onFindRealValueFromUiWatch(w.id)
                                                        onSwitchTab(0)
                                                    }
                                                ) {
                                                    Text(
                                                        text = "Find Real",
                                                        color = Color(0xFF1F2937),
                                                        fontSize = fsTiny,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = (6f * s).dp, vertical = (3f * s).dp)
                                                    )
                                                }
                                            }
                                        }

                                        if (w.tracing || w.traceEvents.isNotEmpty()) {
                                            Spacer(Modifier.height((4f * s).dp))
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(Color(0xFF070B14), RoundedCornerShape((6f * s).dp))
                                                    .padding((5f * s).dp)
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = "Write-Watcher (${w.traceCount} writes)",
                                                        color = Color(0xFFFCA5A5),
                                                        fontSize = fsTiny,
                                                        fontWeight = FontWeight.SemiBold,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    Text(
                                                        text = "Clear",
                                                        color = Color(0xFF94A3B8),
                                                        fontSize = fsTiny,
                                                        modifier = Modifier.clickable(enabled = !previewMode) { onClearWatchTrace(w.id) }
                                                    )
                                                }
                                                if (w.traceEvents.isEmpty()) {
                                                    Text(text = "Waiting for external game writes...", color = Color(0xFF64748B), fontSize = fsTiny)
                                                } else {
                                                    w.traceEvents.takeLast(5).reversed().forEach { ev ->
                                                        Text(
                                                            text = "${ev.oldVal} → ${ev.newVal} (${ev.delta}) · ${ev.caller}",
                                                            color = Color(0xFFE2E8F0),
                                                            fontSize = fsTiny,
                                                            fontFamily = FontFamily.Monospace,
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

@Composable
private fun CeHexTabContent(
    state: MemoryEngineUiState,
    s: Float,
    cardBg: Color,
    listMaxHeight: Dp,
    fsSmall: androidx.compose.ui.unit.TextUnit,
    fsTiny: androidx.compose.ui.unit.TextUnit,
    previewMode: Boolean,
    hexCursorByte: Int,
    onSetHexCursor: (Int) -> Unit,
    onInspectHex: (String, MemScope, Long) -> Unit,
    onToggleHexLiveRefresh: () -> Unit,
    onPeekNearbyStruct: (String, MemScope) -> Unit,
    onWriteHexBytes: (String, Long) -> Unit,
    onJumpHexOffset: (String) -> Unit,
    onAddStructFieldToWatch: (MemStructField) -> Unit
) {
    var hexPatchDraft by remember { mutableStateOf("") }
    var hexJumpDraft by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
                        val hx = state.hex
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy((4f * s).dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (hx.displayAddr.isNotBlank()) "Hex @ ${hx.displayAddr}" else "Live Hex Editor",
                                color = Color(0xFF93C5FD),
                                fontSize = fsSmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (hx.targetAddr.isNotBlank()) {
                                CeButton(text = "-64", bg = cardBg, fg = Color.White, fontSize = fsTiny, minHeight = (26f * s).dp, enabled = !previewMode, onClick = {
                                    onSetHexCursor(0); onInspectHex(hx.targetAddr, hx.scope, -64L)
                                })
                                CeButton(text = "+64", bg = cardBg, fg = Color.White, fontSize = fsTiny, minHeight = (26f * s).dp, enabled = !previewMode, onClick = {
                                    onSetHexCursor(0); onInspectHex(hx.targetAddr, hx.scope, +64L)
                                })
                                CeButton(
                                    text = if (state.hexLiveRefresh) "●Live" else "Live",
                                    bg = if (state.hexLiveRefresh) Color(0xFF059669) else cardBg,
                                    fg = Color.White,
                                    fontSize = fsTiny,
                                    minHeight = (26f * s).dp,
                                    enabled = !previewMode,
                                    onClick = onToggleHexLiveRefresh
                                )
                                CeButton(text = "Struct", bg = Color(0xFF0F766E), fg = Color.White, fontSize = fsTiny, minHeight = (26f * s).dp, enabled = !previewMode, onClick = {
                                    onPeekNearbyStruct(hx.targetAddr, hx.scope)
                                })
                            }
                        }
                        Spacer(Modifier.height((4f * s).dp))
                        if (hx.error != null) {
                            Text(text = hx.error, color = Color(0xFFFCA5A5), fontSize = fsSmall)
                        } else if (hx.hexBytes.isBlank()) {
                            Text(
                                text = "Tap Hex on any scan result (Native, WASM, ArrayBuffer, TypedArray) to view & patch live bytes, decode multi-types, or inspect structs.",
                                color = Color(0xFF94A3B8),
                                fontSize = fsSmall
                            )
                        } else {
                            val lines = remember(hx.hexBytes) { formatHexLines(hx.hexBytes) }
                            val totalBytes = hx.hexBytes.length / 2
                            val safeCursor = hexCursorByte.coerceIn(0, (totalBytes - 1).coerceAtLeast(0))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF070B14), RoundedCornerShape((6f * s).dp))
                                    .heightIn(max = (listMaxHeight * 0.72f).coerceIn(72.dp, 260.dp))
                                    .verticalScroll(rememberScrollState())
                                    .padding((5f * s).dp)
                            ) {
                                lines.forEachIndexed { lineIdx, line ->
                                    val lineStartByte = lineIdx * 8
                                    val isCurLine = safeCursor in lineStartByte until (lineStartByte + 8)
                                    Text(
                                        text = line,
                                        color = if (isCurLine) Color(0xFFFDE68A) else Color(0xFF86EFAC),
                                        fontSize = (8.5f * s).coerceIn(6.5f, 12.5f).sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(if (isCurLine) Color(0xFF1E293B) else Color.Transparent)
                                            .clickable(enabled = !previewMode) { onSetHexCursor(lineStartByte) }
                                            .padding(vertical = 1.dp)
                                    )
                                }
                            }
                            val decodedLine = remember(hx.hexBytes, safeCursor) { decodeHexValuesAt(hx.hexBytes, safeCursor) }
                            if (decodedLine.isNotBlank()) {
                                Spacer(Modifier.height((4f * s).dp))
                                Text(text = decodedLine, color = Color(0xFFFDE68A), fontSize = fsTiny, fontFamily = FontFamily.Monospace)
                            }
                            Spacer(Modifier.height((4f * s).dp))
                            Row(horizontalArrangement = Arrangement.spacedBy((4f * s).dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                CeInputField(
                                    value = hexPatchDraft,
                                    onValueChange = { hexPatchDraft = it },
                                    placeholder = "Patch @ +0x${safeCursor.toString(16).uppercase()} (e.g. FF ?? 00 01)",
                                    fontSize = fsSmall,
                                    enabled = !previewMode,
                                    onDone = { onWriteHexBytes(hexPatchDraft, safeCursor.toLong()); hexPatchDraft = ""; focusManager.clearFocus() },
                                    modifier = Modifier.weight(1f)
                                )
                                CeButton(
                                    text = "Patch",
                                    bg = Color(0xFFB45309),
                                    fg = Color.White,
                                    fontSize = fsTiny,
                                    minHeight = (28f * s).coerceIn(24f, 36f).dp,
                                    enabled = !previewMode && hexPatchDraft.isNotBlank(),
                                    onClick = { onWriteHexBytes(hexPatchDraft, safeCursor.toLong()); hexPatchDraft = ""; focusManager.clearFocus() }
                                )
                            }
                            Spacer(Modifier.height((3f * s).dp))
                            Row(horizontalArrangement = Arrangement.spacedBy((4f * s).dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                CeInputField(
                                    value = hexJumpDraft,
                                    onValueChange = { hexJumpDraft = it },
                                    placeholder = "Jump to 0xOFFSET / address",
                                    fontSize = fsSmall,
                                    enabled = !previewMode,
                                    onDone = { onSetHexCursor(0); onJumpHexOffset(hexJumpDraft); focusManager.clearFocus() },
                                    modifier = Modifier.weight(1f)
                                )
                                CeButton(
                                    text = "Go",
                                    bg = Color(0xFF334155),
                                    fg = Color.White,
                                    fontSize = fsTiny,
                                    minHeight = (28f * s).coerceIn(24f, 36f).dp,
                                    enabled = !previewMode && hexJumpDraft.isNotBlank(),
                                    onClick = { onSetHexCursor(0); onJumpHexOffset(hexJumpDraft); focusManager.clearFocus() }
                                )
                            }
                        }

                        if (state.structFields.isNotEmpty()) {
                            Spacer(Modifier.height((6f * s).dp))
                            Text(text = "Nearby Struct Fields (${state.structFields.size}) · tap +W", color = Color(0xFF5EEAD4), fontSize = fsTiny, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height((3f * s).dp))
                            Column(
                                verticalArrangement = Arrangement.spacedBy((3f * s).dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = (listMaxHeight * 0.55f).coerceIn(56.dp, 200.dp))
                                    .verticalScroll(rememberScrollState())
                            ) {
                                state.structFields.take(36).forEach { sf ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(cardBg, RoundedCornerShape((6f * s).dp))
                                            .padding(horizontal = (6f * s).dp, vertical = (3f * s).dp)
                                    ) {
                                        Text(
                                            text = sf.delta,
                                            color = Color(0xFF38BDF8),
                                            fontSize = fsTiny,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.width((52f * s).dp)
                                        )
                                        Text(
                                            text = if (sf.u32Val.isNotBlank() && sf.u32Val != sf.i32Val) {
                                                "i32=${sf.i32Val} u32=${sf.u32Val} f32=${sf.f32Val}"
                                            } else {
                                                "i32=${sf.i32Val} f32=${sf.f32Val}"
                                            },
                                            color = Color.White,
                                            fontSize = fsTiny,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Surface(
                                            shape = RoundedCornerShape((5f * s).dp),
                                            color = Color(0xFF065F46),
                                            modifier = Modifier.clickable(enabled = !previewMode) { onAddStructFieldToWatch(sf) }
                                        ) {
                                            Text(text = "+W", color = Color(0xFFA7F3D0), fontSize = fsTiny, modifier = Modifier.padding(horizontal = (6f * s).dp, vertical = (2.5f * s).dp))
                                        }
                                    }
                                }
                            }
                        }
}

@Composable
private fun CeMapTabContent(
    state: MemoryEngineUiState,
    s: Float,
    cardBg: Color,
    minBtnH: Dp,
    listMaxHeight: Dp,
    fsSmall: androidx.compose.ui.unit.TextUnit,
    fsTiny: androidx.compose.ui.unit.TextUnit,
    previewMode: Boolean,
    onRefreshRegions: () -> Unit
) {
                        val engText = if (state.detectedEngines.isNotEmpty()) state.detectedEngines.joinToString(", ") else "Auto"
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "${state.regions.size} regions · ${state.regionsTotalKb} KB", color = Color(0xFFCBD5E1), fontSize = fsSmall, fontWeight = FontWeight.SemiBold)
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
                                .heightIn(max = listMaxHeight)
                                .verticalScroll(rememberScrollState())
                        ) {
                            if (state.regions.isEmpty()) {
                                Text(text = "Tap Refresh (or run a scan) to list readable/writable memory regions and detected game engines.", color = Color(0xFF94A3B8), fontSize = fsSmall)
                            } else {
                                state.regions.forEach { r ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(cardBg, RoundedCornerShape((6f * s).dp))
                                            .padding(horizontal = (7f * s).dp, vertical = (4f * s).dp)
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(text = "${r.start} (${r.perms})", color = Color(0xFF93C5FD), fontSize = fsSmall, fontFamily = FontFamily.Monospace, maxLines = 1)
                                            Text(text = "${r.label} · ${r.kb} KB", color = Color(0xFFE2E8F0), fontSize = fsSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                            Text(text = labelOf(item), color = Color.White, fontSize = fontSize, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
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
            .heightIn(min = 34.dp)
            .background(Color(0xFF1E293B), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF475569), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
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
    minHeight: Dp = 32.dp,
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
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp)) {
            Text(text = text, color = fg, fontSize = fontSize, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

private fun decodeHexValuesAt(hex: String, byteOffset: Int): String {
    val totalBytes = hex.length / 2
    if (byteOffset < 0 || byteOffset >= totalBytes) return ""
    val rem = (totalBytes - byteOffset).coerceAtMost(8)
    return runCatching {
        val b = ByteArray(rem) { i ->
            val idx = (byteOffset + i) * 2
            hex.substring(idx, idx + 2).toInt(16).toByte()
        }
        val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val i8 = b[0].toInt()
        val u8 = b[0].toInt() and 0xFF
        val offHex = "+0x${byteOffset.toString(16).uppercase()}"
        if (rem < 2) return "@$offHex: i8=$i8 · u8=$u8"
        val i16 = bb.getShort(0).toInt()
        if (rem < 4) return "@$offHex: i8=$i8 · i16=$i16"
        val i32 = bb.getInt(0)
        val u32 = i32.toLong() and 0xFFFFFFFFL
        val f32 = bb.getFloat(0)
        val f32Txt = if (f32.isFinite() && kotlin.math.abs(f32) < 1e9f) String.format(java.util.Locale.US, "%.3f", f32) else "NaN"
        if (rem >= 8) {
            val f64 = bb.getDouble(0)
            val f64Txt = if (f64.isFinite() && kotlin.math.abs(f64) < 1e12) String.format(java.util.Locale.US, "%.3f", f64) else "NaN"
            "@$offHex: i16=$i16 · i32=$i32 · u32=$u32 · f32=$f32Txt · f64=$f64Txt"
        } else {
            "@$offHex: i16=$i16 · i32=$i32 · u32=$u32 · f32=$f32Txt"
        }
    }.getOrDefault("")
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
    onRemoveSelectedWatchEntries: () -> Unit = {},
    widthPct: Int = WebsiteMemoryEngine.DEFAULT_PANEL_WIDTH_PCT,
    heightPct: Int = WebsiteMemoryEngine.DEFAULT_PANEL_HEIGHT_PCT,
    onCycleWatchFreezeRule: (String) -> Unit = {},
    onSetWatchFreezeParam: (String, String) -> Unit = { _, _ -> },
    onRenameWatch: (String, String) -> Unit = { _, _ -> },
    onWatchQuickMath: (String, String) -> Unit = { _, _ -> },
    onPeekNearbyStruct: (String, MemScope) -> Unit = { _, _ -> },
    onAddStructFieldToWatch: (MemStructField) -> Unit = {},
    onToggleLiveScanRefresh: () -> Unit = {},
    onToggleHexLiveRefresh: () -> Unit = {},
    onWriteHexBytes: (String, Long) -> Unit = { _, _ -> },
    onJumpHexOffset: (String) -> Unit = {},
    onPointerScan: (String, MemScope) -> Unit = { _, _ -> },
    onToggleWatchTrace: (String) -> Unit = {},
    onClearWatchTrace: (String) -> Unit = {},
    onSaveCheatTable: (String) -> Unit = {},
    onLoadCheatTable: (String) -> Unit = {},
    onDeleteCheatTable: (String) -> Unit = {},
    onExportCheatTableJson: () -> String = { "" },
    onImportCheatTableJson: (String) -> Boolean = { false },
    onSetFreezeStrength: (MemFreezeStrength) -> Unit = {},
    onToggleHideUiNoise: () -> Unit = {},
    onFindMirrorsForWatch: (String) -> Unit = {},
    onLockAllMirrors: () -> Unit = {},
    onFindRealValueFromUiWatch: (String) -> Unit = {}
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

        val availWidth = (maxWidth - 12.dp).coerceAtLeast(220.dp)
        val availHeight = (maxHeight - topSafePadding - bottomSafePadding - 12.dp).coerceAtLeast(220.dp)

        MemoryEnginePanel(
            state = state,
            scalePct = scalePct,
            widthPct = widthPct,
            heightPct = heightPct,
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
            maxWidthDp = availWidth,
            maxHeightDp = availHeight,
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
            onCycleWatchFreezeRule = onCycleWatchFreezeRule,
            onSetWatchFreezeParam = onSetWatchFreezeParam,
            onRenameWatch = onRenameWatch,
            onWatchQuickMath = onWatchQuickMath,
            onPeekNearbyStruct = onPeekNearbyStruct,
            onAddStructFieldToWatch = onAddStructFieldToWatch,
            onToggleLiveScanRefresh = onToggleLiveScanRefresh,
            onToggleHexLiveRefresh = onToggleHexLiveRefresh,
            onWriteHexBytes = onWriteHexBytes,
            onJumpHexOffset = onJumpHexOffset,
            onPointerScan = onPointerScan,
            onToggleWatchTrace = onToggleWatchTrace,
            onClearWatchTrace = onClearWatchTrace,
            onSaveCheatTable = onSaveCheatTable,
            onLoadCheatTable = onLoadCheatTable,
            onDeleteCheatTable = onDeleteCheatTable,
            onExportCheatTableJson = onExportCheatTableJson,
            onImportCheatTableJson = onImportCheatTableJson,
            onSetFreezeStrength = onSetFreezeStrength,
            onToggleHideUiNoise = onToggleHideUiNoise,
            onFindMirrorsForWatch = onFindMirrorsForWatch,
            onLockAllMirrors = onLockAllMirrors,
            onFindRealValueFromUiWatch = onFindRealValueFromUiWatch,
            modifier = Modifier
                .offset { IntOffset(clampedX.roundToInt(), clampedY.roundToInt()) }
                .onSizeChanged {
                    panelW = it.width
                    panelH = it.height
                }
        )
    }
}
