package com.fable.browser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fable.browser.engine.MemFreezeRule
import com.fable.browser.engine.MemFreezeStrength
import com.fable.browser.engine.MemPerfProfile
import com.fable.browser.engine.MemScanResultItem
import com.fable.browser.engine.MemScope
import com.fable.browser.engine.MemStructField
import com.fable.browser.engine.MemWatchEntry
import com.fable.browser.engine.MemoryEngineUiState

private val CE_TAG_COLORS = listOf(
    Color(0xFF475569),
    Color(0xFF10B981),
    Color(0xFF38BDF8),
    Color(0xFFF59E0B),
    Color(0xFFA855F7),
    Color(0xFFEF4444)
)

@Composable
internal fun CeFindSamePanel(
    state: MemoryEngineUiState,
    s: Float,
    accent: Color,
    minBtnH: Dp,
    fsBody: TextUnit,
    fsSmall: TextUnit,
    fsTiny: TextUnit,
    previewMode: Boolean,
    onAddWatch: (MemScanResultItem) -> Unit,
    onSetAllMirrorsValue: (String, Boolean, Boolean) -> Unit,
    onLockAllMirrors: (Boolean) -> Unit,
    onUndoLastTransaction: () -> Unit,
    onSwitchTab: (Int) -> Unit
) {
    if (state.mirrorResults.isEmpty() && state.mirrorUiResults.isEmpty()) return
    var mirrorValDraft by remember { mutableStateOf("") }
    var showUiCopies by remember { mutableStateOf(false) }
    var includeUiCopies by remember { mutableStateOf(false) }
    val activeTargetCount = (state.mirrorResults.size + if (includeUiCopies) state.mirrorUiResults.size else 0).coerceAtMost(30)
    val focusManager = LocalFocusManager.current

    Spacer(Modifier.height((5f * s).dp))
    Column(
        verticalArrangement = Arrangement.spacedBy((4f * s).dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0C1E35), RoundedCornerShape((8f * s).dp))
            .border(1.dp, Color(0xFF0284C7), RoundedCornerShape((8f * s).dp))
            .padding((6f * s).dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((4f * s).dp)) {
            Text(
                text = "Find Same · ${state.mirrorResults.size} game / ${state.mirrorUiResults.size} UI",
                color = Color(0xFF67E8F9),
                fontSize = fsTiny,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (state.canUndoTx) {
                Surface(
                    shape = RoundedCornerShape((5f * s).dp),
                    color = Color(0xFF475569),
                    modifier = Modifier.clickable(enabled = !previewMode, onClick = onUndoLastTransaction)
                ) {
                    Text(text = "Undo Tx", color = Color.White, fontSize = fsTiny, modifier = Modifier.padding(horizontal = (5f * s).dp, vertical = (2.5f * s).dp))
                }
            }
            if (state.mirrorUiResults.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape((5f * s).dp),
                    color = if (includeUiCopies) Color(0xFFD97706) else Color(0xFF1E293B),
                    modifier = Modifier.clickable(enabled = !previewMode) { includeUiCopies = !includeUiCopies }
                ) {
                    Text(
                        text = if (includeUiCopies) "Incl. UI ✓" else "+UI",
                        color = Color.White,
                        fontSize = fsTiny,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = (5f * s).dp, vertical = (2.5f * s).dp)
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape((5f * s).dp),
                color = Color(0xFF0284C7),
                modifier = Modifier.clickable(enabled = !previewMode && activeTargetCount > 0) {
                    onLockAllMirrors(includeUiCopies)
                    onSwitchTab(1)
                }
            ) {
                Text(
                    text = "Lock All ($activeTargetCount)",
                    color = Color.White,
                    fontSize = fsTiny,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = (6f * s).dp, vertical = (2.5f * s).dp)
                )
            }
        }
        if (state.mirrorResults.isNotEmpty() || (includeUiCopies && state.mirrorUiResults.isNotEmpty())) {
            Row(horizontalArrangement = Arrangement.spacedBy((4f * s).dp), verticalAlignment = Alignment.CenterVertically) {
                CeInputField(
                    value = mirrorValDraft,
                    onValueChange = { mirrorValDraft = it },
                    placeholder = if (includeUiCopies) "Set game + UI copies (e.g. 999999 or MAX)" else "Set all gameplay copies (e.g. 999999 or MAX)",
                    fontSize = fsSmall,
                    enabled = !previewMode,
                    onDone = {
                        onSetAllMirrorsValue(mirrorValDraft, true, includeUiCopies)
                        mirrorValDraft = ""
                        focusManager.clearFocus()
                        onSwitchTab(1)
                    },
                    modifier = Modifier.weight(1f)
                )
                CeButton(
                    text = "Set+Link",
                    bg = accent,
                    fg = Color(0xFF042F2E),
                    fontSize = fsTiny,
                    minHeight = (28f * s).coerceIn(22f, 36f).dp,
                    enabled = !previewMode && mirrorValDraft.isNotBlank(),
                    onClick = {
                        onSetAllMirrorsValue(mirrorValDraft, true, includeUiCopies)
                        mirrorValDraft = ""
                        focusManager.clearFocus()
                        onSwitchTab(1)
                    }
                )
                CeButton(
                    text = "MAX",
                    bg = Color(0xFF7C3AED),
                    fg = Color.White,
                    fontSize = fsTiny,
                    minHeight = (28f * s).coerceIn(22f, 36f).dp,
                    enabled = !previewMode,
                    onClick = { onSetAllMirrorsValue("MAX", true, includeUiCopies); onSwitchTab(1) }
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy((3f * s).dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = (88f * s).coerceIn(50f, 130f).dp)
                    .verticalScroll(rememberScrollState())
            ) {
                state.mirrorResults.take(16).forEach { mr ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0F2942), RoundedCornerShape((6f * s).dp))
                            .padding(horizontal = (6f * s).dp, vertical = (2.5f * s).dp)
                    ) {
                        Text(
                            text = "[${mr.role.uppercase()} ${mr.score}%] ${mr.addr} = ${mr.value}",
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
        if (state.mirrorUiResults.isNotEmpty()) {
            Text(
                text = if (showUiCopies) "▾ Hide ${state.mirrorUiResults.size} likely UI display copies" else "▸ Show ${state.mirrorUiResults.size} likely UI display copies (excluded by default)",
                color = Color(0xFFFBBF24),
                fontSize = fsTiny,
                modifier = Modifier.clickable { showUiCopies = !showUiCopies }
            )
            if (showUiCopies) {
                state.mirrorUiResults.take(10).forEach { ur ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = (1.5f * s).dp)
                    ) {
                        Text(
                            text = "[UI?] ${ur.addr} = ${ur.value}",
                            color = Color(0xFFFDE68A),
                            fontSize = fsTiny,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            shape = RoundedCornerShape((5f * s).dp),
                            color = Color(0xFFD97706),
                            modifier = Modifier.clickable(enabled = !previewMode) { onAddWatch(ur) }
                        ) {
                            Text(text = "+W", color = Color.White, fontSize = fsTiny, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = (5f * s).dp, vertical = (1.5f * s).dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CeWatchTabContent(
    state: MemoryEngineUiState,
    s: Float,
    accent: Color,
    cardBg: Color,
    minBtnH: Dp,
    listMaxHeight: Dp,
    fsBody: TextUnit,
    fsSmall: TextUnit,
    fsTiny: TextUnit,
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
    onSetPerfProfile: (MemPerfProfile) -> Unit,
    onFindMirrorsForWatch: (String) -> Unit,
    onFindRealValueFromUiWatch: (String) -> Unit,
    onTestWatchAuthority: (String) -> Unit,
    onSetWatchSafeMax: (String, Boolean) -> Unit,
    onInspectWatchTypes: (String) -> Unit,
    onRetypeWatch: (String, String, Int) -> Unit,
    onClearTypeInspect: () -> Unit,
    onToggleWatchFavorite: (String) -> Unit,
    onCycleWatchColorTag: (String) -> Unit,
    onSaveCheatRecipe: (String) -> Unit,
    onRunCheatRecipe: (String) -> Unit,
    onDeleteCheatRecipe: (String) -> Unit,
    onUndoLastTransaction: () -> Unit
) {
    var watchQuery by remember { mutableStateOf("") }
    var watchFilter by remember { mutableStateOf("all") }
    var watchSort by remember { mutableStateOf("default") }
    var showBulkWatchEdit by remember { mutableStateOf(false) }
    var bulkWatchDraft by remember { mutableStateOf("") }
    var editingWatchId by remember { mutableStateOf<String?>(null) }
    var showTablesDrawer by remember { mutableStateOf(false) }
    var tableNameDraft by remember { mutableStateOf("") }
    var tableImportDraft by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val clipboard = LocalClipboardManager.current

    val displayedWatches = remember(state.watchList, watchQuery, watchFilter, watchSort) {
        val q = watchQuery.trim().lowercase()
        val filtered = state.watchList.filter { w ->
            val qOk = q.isEmpty() || w.label.lowercase().contains(q) || w.addr.lowercase().contains(q) || w.groupTag.lowercase().contains(q)
            val fOk = when (watchFilter) {
                "fav" -> w.favorite
                "frozen" -> w.frozen
                "ui" -> w.role == "ui" || w.uiWarningReason.isNotBlank()
                "lost" -> !w.attached || w.freezeStatus == "detached" || w.freezeStatus == "failed"
                else -> true
            }
            qOk && fOk
        }
        when (watchSort) {
            "auth" -> filtered.sortedByDescending { if (it.authorityScore >= 0) it.authorityScore else (if (it.role == "ui") 10 else 80) }
            "blk" -> filtered.sortedByDescending { it.blockedWrites }
            else -> filtered.sortedByDescending { it.favorite }
        }
    }

    val allWatchSelected = displayedWatches.isNotEmpty() && displayedWatches.all { it.id in state.selectedWatchIds }
    val wSelCount = state.selectedWatchIds.size
    val wEffCount = if (wSelCount > 0) wSelCount else state.watchList.size

    // Freeze Strength & Performance Profile bar
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((3.5f * s).dp), modifier = Modifier.fillMaxWidth()) {
        Text(text = "Freeze:", color = Color(0xFF94A3B8), fontSize = fsTiny, fontWeight = FontWeight.SemiBold)
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
                    Text(text = stg.shortLabel, color = Color.White, fontSize = fsTiny, fontWeight = if (activeStg) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
        Surface(
            shape = RoundedCornerShape((6f * s).dp),
            color = Color(0xFF312E81),
            modifier = Modifier
                .heightIn(min = (24f * s).coerceIn(20f, 32f).dp)
                .clickable(enabled = !previewMode) {
                    val nextProf = MemPerfProfile.entries[(state.perfProfile.ordinal + 1) % MemPerfProfile.entries.size]
                    onSetPerfProfile(nextProf)
                }
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (5f * s).dp)) {
                Text(text = state.perfProfile.shortLabel, color = Color(0xFFE0E7FF), fontSize = fsTiny, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }

    Spacer(Modifier.height((4f * s).dp))

    // Header row: Watch count + Tables/Recipes + Undo Tx + Unlock All
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((4f * s).dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Watch (${displayedWatches.size}/${state.watchList.size})",
            color = Color(0xFFCBD5E1),
            fontSize = fsSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        if (state.canUndoTx) {
            Surface(
                shape = RoundedCornerShape((6f * s).dp),
                color = Color(0xFF475569),
                modifier = Modifier.heightIn(min = (24f * s).coerceIn(20f, 32f).dp).clickable(enabled = !previewMode, onClick = onUndoLastTransaction)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (6f * s).dp)) {
                    Text(text = "UndoTx", color = Color.White, fontSize = fsTiny, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Surface(
            shape = RoundedCornerShape((7f * s).dp),
            color = if (showTablesDrawer) Color(0xFF0284C7) else Color(0xFF1E293B),
            modifier = Modifier
                .heightIn(min = (24f * s).coerceIn(20f, 32f).dp)
                .clickable(enabled = !previewMode) { showTablesDrawer = !showTablesDrawer }
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (6f * s).dp)) {
                Text(text = "Tables/Rec(${state.savedTables.size + state.savedRecipes.size})", color = Color(0xFF93C5FD), fontSize = fsTiny, fontWeight = FontWeight.SemiBold)
            }
        }
        if (state.watchList.any { it.frozen }) {
            Surface(
                shape = RoundedCornerShape((7f * s).dp),
                color = Color(0xFFB45309),
                modifier = Modifier
                    .heightIn(min = (24f * s).coerceIn(20f, 32f).dp)
                    .clickable(enabled = !previewMode, onClick = onUnfreezeAll)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (6f * s).dp)) {
                    Text(text = "Unlock", color = Color.White, fontSize = fsTiny, fontWeight = FontWeight.SemiBold)
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
            if (state.savedRecipes.isNotEmpty()) {
                Text(text = "Saved Cheat Recipes (${state.savedRecipes.size})", color = Color(0xFF6EE7B7), fontSize = fsTiny, fontWeight = FontWeight.Bold)
                state.savedRecipes.take(6).forEach { rec ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy((4f * s).dp),
                        modifier = Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape((6f * s).dp)).padding((5f * s).dp)
                    ) {
                        Text(
                            text = "${rec.name} · ${rec.rule.symbol}",
                            color = Color.White,
                            fontSize = fsTiny,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            shape = RoundedCornerShape((5f * s).dp),
                            color = Color(0xFF059669),
                            modifier = Modifier.clickable(enabled = !previewMode) { onRunCheatRecipe(rec.id); showTablesDrawer = false }
                        ) {
                            Text(text = "Run", color = Color.White, fontSize = fsTiny, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = (6f * s).dp, vertical = (2.5f * s).dp))
                        }
                        Surface(
                            shape = RoundedCornerShape((5f * s).dp),
                            color = Color(0xFF7F1D1D),
                            modifier = Modifier.clickable(enabled = !previewMode) { onDeleteCheatRecipe(rec.id) }
                        ) {
                            Text(text = "✕", color = Color.White, fontSize = fsTiny, modifier = Modifier.padding(horizontal = (5f * s).dp, vertical = (2.5f * s).dp))
                        }
                    }
                }
            }
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
                    onDone = { if (onImportCheatTableJson(tableImportDraft)) tableImportDraft = ""; focusManager.clearFocus() },
                    modifier = Modifier.weight(1f)
                )
                CeButton(
                    text = "Import",
                    bg = Color(0xFF0284C7),
                    fg = Color.White,
                    fontSize = fsTiny,
                    minHeight = (28f * s).coerceIn(24f, 36f).dp,
                    enabled = !previewMode && tableImportDraft.isNotBlank(),
                    onClick = { if (onImportCheatTableJson(tableImportDraft)) tableImportDraft = ""; focusManager.clearFocus() }
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
        // Search + Filter + Sort bar
        Spacer(Modifier.height((4f * s).dp))
        Row(horizontalArrangement = Arrangement.spacedBy((4f * s).dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            CeInputField(
                value = watchQuery,
                onValueChange = { watchQuery = it },
                placeholder = "Search watch...",
                fontSize = fsSmall,
                enabled = !previewMode,
                onDone = { focusManager.clearFocus() },
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = RoundedCornerShape((6f * s).dp),
                color = Color(0xFF1E293B),
                modifier = Modifier.heightIn(min = (26f * s).coerceIn(22f, 34f).dp).clickable {
                    watchSort = when (watchSort) { "default" -> "auth"; "auth" -> "blk"; else -> "default" }
                }
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (6f * s).dp)) {
                    Text(
                        text = when (watchSort) { "auth" -> "Sort:Auth"; "blk" -> "Sort:Blk"; else -> "Sort:Fav" },
                        color = Color(0xFF93C5FD),
                        fontSize = fsTiny,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        Spacer(Modifier.height((3.5f * s).dp))
        Row(horizontalArrangement = Arrangement.spacedBy((3f * s).dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            listOf("all" to "All", "fav" to "★ Fav", "frozen" to "Locked", "ui" to "UI?", "lost" to "Lost").forEach { (k, lbl) ->
                val act = watchFilter == k
                Surface(
                    shape = RoundedCornerShape((6f * s).dp),
                    color = if (act) Color(0xFF0284C7) else Color(0xFF1E293B),
                    modifier = Modifier.clickable { watchFilter = k }
                ) {
                    Text(text = lbl, color = Color.White, fontSize = fsTiny, fontWeight = if (act) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(horizontal = (7f * s).dp, vertical = (3f * s).dp))
                }
            }
        }

        // Bulk actions bar
        Spacer(Modifier.height((4f * s).dp))
        Row(horizontalArrangement = Arrangement.spacedBy((3.5f * s).dp), modifier = Modifier.fillMaxWidth()) {
            Surface(
                shape = RoundedCornerShape((6f * s).dp),
                color = if (allWatchSelected) Color(0xFF0284C7) else Color(0xFF334155),
                modifier = Modifier.heightIn(min = (26f * s).coerceIn(22f, 34f).dp).clickable(enabled = !previewMode) {
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
                    onDone = { onEditSelectedWatchEntries(bulkWatchDraft); showBulkWatchEdit = false; focusManager.clearFocus() },
                    modifier = Modifier.weight(1f)
                )
                CeButton(
                    text = "Write",
                    bg = accent,
                    fg = Color(0xFF042F2E),
                    fontSize = fsSmall,
                    minHeight = minBtnH,
                    enabled = !previewMode && bulkWatchDraft.isNotBlank(),
                    onClick = { onEditSelectedWatchEntries(bulkWatchDraft); showBulkWatchEdit = false; focusManager.clearFocus() }
                )
            }
        }
    }

    Spacer(Modifier.height((5f * s).dp))
    if (displayedWatches.isEmpty()) {
        Text(
            text = if (state.watchList.isEmpty()) {
                "No watched values yet. Run a scan and tap -> Watch to edit, use 10 Persistent Rules (=, >=, <=, [a..b], +=/s, Δ->V, ↓↩, Δ+X, Δ×X, ∞Safe), test Authority, Find Same, or save Recipes."
            } else {
                "No watch entries match the current filter."
            },
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
            state.typeInspect?.let { ti ->
                Column(
                    verticalArrangement = Arrangement.spacedBy((4f * s).dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0B1F2E), RoundedCornerShape((8f * s).dp))
                        .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape((8f * s).dp))
                        .padding((6f * s).dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Raw Byte Types (${ti.currentType}) · ${ti.hex.ifBlank { "JS/DOM" }}",
                            color = Color(0xFF7DD3FC),
                            fontSize = fsTiny,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "✕",
                            color = Color(0xFFF87171),
                            fontSize = fsSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable(onClick = onClearTypeInspect).padding(horizontal = (4f * s).dp)
                        )
                    }
                    if (ti.diagnosis.isNotBlank()) {
                        Text(text = "⚠ ${ti.diagnosis}", color = Color(0xFFFBBF24), fontSize = fsTiny, fontWeight = FontWeight.SemiBold)
                    }
                    val opts = listOf(
                        Triple("i32", ti.i32, 0), Triple("u32", ti.u32, 0),
                        Triple("f32", ti.f32, 0), Triple("f64", ti.f64, 0),
                        Triple("i64", ti.i64, 0), Triple("u64", ti.u64, 0),
                        Triple("i16", ti.i16, 0), Triple("u16", ti.u16, 0),
                        Triple("i8", ti.i8, 0), Triple("u8", ti.u8, 0),
                        Triple("i32", ti.x10, 10), Triple("i32", ti.x100, 100), Triple("i32", ti.x1000, 1000)
                    ).filter { it.second.isNotBlank() }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy((4f * s).dp),
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    ) {
                        opts.forEach { (dtName, valTxt, scVal) ->
                            val lbl = if (scVal > 0) "/$scVal" else dtName
                            val isSug = ti.suggestedType == dtName && scVal == 0
                            Surface(
                                shape = RoundedCornerShape((6f * s).dp),
                                color = if (isSug) Color(0xFF065F46) else Color(0xFF1E293B),
                                modifier = Modifier.clickable(enabled = !previewMode) { onRetypeWatch(ti.watchId, dtName, scVal) }
                            ) {
                                Text(
                                    text = "$lbl: $valTxt",
                                    color = if (isSug) Color(0xFFA7F3D0) else Color(0xFFE2E8F0),
                                    fontSize = fsTiny,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = (6f * s).dp, vertical = (3f * s).dp)
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height((4f * s).dp))
            }
            displayedWatches.forEach { w ->
                CeWatchEntryCard(
                    w = w,
                    isSelected = w.id in state.selectedWatchIds,
                    isEditing = editingWatchId == w.id,
                    s = s,
                    accent = accent,
                    cardBg = cardBg,
                    minBtnH = minBtnH,
                    fsBody = fsBody,
                    fsSmall = fsSmall,
                    fsTiny = fsTiny,
                    previewMode = previewMode,
                    onToggleSelect = { onToggleSelectWatchId(w.id) },
                    onToggleEdit = { editingWatchId = if (editingWatchId == w.id) null else w.id },
                    onToggleFreeze = { onToggleWatchFreeze(w.id) },
                    onCycleRule = { onCycleWatchFreezeRule(w.id) },
                    onSetValue = { onSetWatchValue(w.id, it) },
                    onRestoreOriginal = { onRestoreWatchOriginal(w.id) },
                    onQuickMath = { onWatchQuickMath(w.id, it) },
                    onSetRuleParam = { onSetWatchFreezeParam(w.id, it) },
                    onRename = { onRenameWatch(w.id, it) },
                    onPeekStruct = { onPeekNearbyStruct(w.addr, w.scope); onSwitchTab(2) },
                    onPointerScan = { onPointerScan(w.addr, w.scope); onSwitchTab(0) },
                    onFindSame = { onFindMirrorsForWatch(w.id); onSwitchTab(0) },
                    onTestAuthority = { onTestWatchAuthority(w.id) },
                    onSetSafeMax = { useInf -> onSetWatchSafeMax(w.id, useInf) },
                    onInspectTypes = { onInspectWatchTypes(w.id) },
                    onQuickRetype = { sug -> onRetypeWatch(w.id, sug, 0) },
                    onSaveRecipe = { onSaveCheatRecipe(w.id) },
                    onToggleFavorite = { onToggleWatchFavorite(w.id) },
                    onCycleColor = { onCycleWatchColorTag(w.id) },
                    onToggleTrace = { onToggleWatchTrace(w.id) },
                    onClearTrace = { onClearWatchTrace(w.id) },
                    onFindReal = { onFindRealValueFromUiWatch(w.id); onSwitchTab(0) },
                    onRemove = { onRemoveWatch(w.id) }
                )
            }
        }
    }
}

@Composable
private fun CeWatchEntryCard(
    w: MemWatchEntry,
    isSelected: Boolean,
    isEditing: Boolean,
    s: Float,
    accent: Color,
    cardBg: Color,
    minBtnH: Dp,
    fsBody: TextUnit,
    fsSmall: TextUnit,
    fsTiny: TextUnit,
    previewMode: Boolean,
    onToggleSelect: () -> Unit,
    onToggleEdit: () -> Unit,
    onToggleFreeze: () -> Unit,
    onCycleRule: () -> Unit,
    onSetValue: (String) -> Unit,
    onRestoreOriginal: () -> Unit,
    onQuickMath: (String) -> Unit,
    onSetRuleParam: (String) -> Unit,
    onRename: (String) -> Unit,
    onPeekStruct: () -> Unit,
    onPointerScan: () -> Unit,
    onFindSame: () -> Unit,
    onTestAuthority: () -> Unit,
    onSetSafeMax: (Boolean) -> Unit,
    onInspectTypes: () -> Unit,
    onQuickRetype: (String) -> Unit,
    onSaveRecipe: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCycleColor: () -> Unit,
    onToggleTrace: () -> Unit,
    onClearTrace: () -> Unit,
    onFindReal: () -> Unit,
    onRemove: () -> Unit
) {
    var editDraft by remember(w.id, isEditing) { mutableStateOf(w.currentValue) }
    var ruleParamDraft by remember(w.id, isEditing) { mutableStateOf(w.frozenValue.ifBlank { w.currentValue }) }
    var renameDraft by remember(w.id, isEditing) { mutableStateOf(w.label) }
    val focusManager = LocalFocusManager.current
    val tagCol = CE_TAG_COLORS[w.colorTag.coerceIn(0, CE_TAG_COLORS.lastIndex)]

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) Color(0xFF1E3A5F) else cardBg, RoundedCornerShape((7f * s).dp))
            .border(
                width = if (isSelected || w.frozen || !w.attached) 1.dp else 0.dp,
                color = when {
                    !w.attached -> Color(0xFFF59E0B)
                    isSelected -> Color(0xFF38BDF8)
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
                    .size((21f * s).coerceIn(17f, 28f).dp)
                    .background(if (isSelected) Color(0xFF0284C7) else Color(0xFF0F172A), RoundedCornerShape((5f * s).dp))
                    .border(1.dp, if (isSelected) Color(0xFF38BDF8) else Color(0xFF475569), RoundedCornerShape((5f * s).dp))
                    .clickable(enabled = !previewMode, onClick = onToggleSelect)
            ) {
                Text(text = if (isSelected) "✓" else "", color = Color.White, fontSize = fsSmall, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width((3f * s).dp))
            Box(
                modifier = Modifier
                    .size((10f * s).coerceIn(8f, 14f).dp)
                    .background(tagCol, CircleShape)
                    .clickable(enabled = !previewMode, onClick = onCycleColor)
            )
            Spacer(Modifier.width((3f * s).dp))
            Text(
                text = if (w.favorite) "★" else "☆",
                color = if (w.favorite) Color(0xFFFBBF24) else Color(0xFF64748B),
                fontSize = fsBody,
                modifier = Modifier.clickable(enabled = !previewMode, onClick = onToggleFavorite)
            )
            Spacer(Modifier.width((3f * s).dp))
            Surface(
                shape = RoundedCornerShape((6f * s).dp),
                color = when {
                    w.freezeStatus == "failed" || w.freezeStatus == "detached" -> Color(0xFFB91C1C)
                    w.frozen -> Color(0xFF0284C7)
                    else -> Color(0xFF334155)
                },
                modifier = Modifier.heightIn(min = (25f * s).coerceIn(21f, 34f).dp).clickable(enabled = !previewMode, onClick = onToggleFreeze)
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
                    MemFreezeRule.MIN, MemFreezeRule.ON_DEC_RESTORE -> Color(0xFF065F46)
                    MemFreezeRule.MAX -> Color(0xFF7C2D12)
                    MemFreezeRule.RANGE, MemFreezeRule.UNLIMITED_MAX -> Color(0xFF4C1D95)
                    else -> Color(0xFF0F766E)
                },
                modifier = Modifier.heightIn(min = (25f * s).coerceIn(21f, 34f).dp).clickable(enabled = !previewMode, onClick = onCycleRule)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (4.5f * s).dp)) {
                    Text(text = w.freezeRule.symbol, color = Color(0xFFE2E8F0), fontSize = fsTiny, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width((4f * s).dp))
            Column(modifier = Modifier.weight(1f).clickable(enabled = !previewMode, onClick = onToggleSelect)) {
                val headLbl = buildString {
                    if (!w.attached) append("[LOST] ")
                    if (w.groupTag.isNotBlank()) append("[${w.groupTag}] ")
                    append(w.label)
                    if (w.authorityScore >= 0) append(" (${w.authorityScore}%)")
                }
                Text(
                    text = headLbl,
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
                if (w.reattachStatus.isNotBlank()) {
                    Text(text = w.reattachStatus, color = Color(0xFF6EE7B7), fontSize = fsTiny, maxLines = 1)
                }
                if (w.authorityVerdict.isNotBlank()) {
                    val isGoodAuth = w.authorityVerdict.contains("authoritative", ignoreCase = true) || w.authorityVerdict.contains("verified", ignoreCase = true) || w.authorityVerdict.contains("ui-state", ignoreCase = true)
                    Text(
                        text = "Verdict: ${w.authorityVerdict}",
                        color = if (isGoodAuth) Color(0xFF34D399) else Color(0xFFFBBF24),
                        fontSize = fsTiny,
                        maxLines = 1
                    )
                }
                if (w.typeHint.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((4f * s).dp)) {
                        Text(
                            text = "⚠ ${w.typeHint}",
                            color = Color(0xFFFBBF24),
                            fontSize = fsTiny,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (w.suggestedType.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape((4f * s).dp),
                                color = Color(0xFF065F46),
                                modifier = Modifier.clickable(enabled = !previewMode) { onQuickRetype(w.suggestedType) }
                            ) {
                                Text(text = "Fix→${w.suggestedType}", color = Color(0xFFA7F3D0), fontSize = fsTiny, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = (4f * s).dp, vertical = (1f * s).dp))
                            }
                        }
                    }
                }
                if (w.frozen || w.freezeStatus == "failed" || w.freezeStatus == "detached") {
                    val modeTxt = w.freezeMode.ifBlank { "Lock" }
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
                modifier = Modifier.heightIn(min = (25f * s).coerceIn(21f, 34f).dp).clickable(enabled = !previewMode, onClick = onToggleTrace)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (4.5f * s).dp)) {
                    Text(text = if (w.tracing) "●${w.traceCount}" else "Spy", color = if (w.tracing) Color(0xFFFCA5A5) else Color(0xFF94A3B8), fontSize = fsTiny, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width((2.5f * s).dp))
            Surface(
                shape = RoundedCornerShape((6f * s).dp),
                color = Color(0xFF065F46),
                modifier = Modifier.heightIn(min = (25f * s).coerceIn(21f, 34f).dp).clickable(enabled = !previewMode, onClick = onToggleEdit)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = (5f * s).dp)) {
                    Text(text = "Edit", color = Color(0xFFA7F3D0), fontSize = fsTiny)
                }
            }
            Spacer(Modifier.width((2f * s).dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.heightIn(min = (25f * s).coerceIn(21f, 34f).dp).clickable(enabled = !previewMode, onClick = onRemove).padding(horizontal = (3f * s).dp)
            ) {
                Text(text = "✕", color = Color(0xFFF87171), fontSize = fsBody, fontWeight = FontWeight.Bold)
            }
        }

        // Quick 1-tap strip right on card: Find Same · Test Real · MAX SAFE · ∞
        Spacer(Modifier.height((3.5f * s).dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy((3.5f * s).dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            listOf(
                Triple("Find Same", Color(0xFF0369A1), onFindSame),
                Triple("Test Real", Color(0xFF4C1D95), onTestAuthority),
                Triple("Types", Color(0xFF0F766E), onInspectTypes),
                Triple("MAX SAFE", Color(0xFF065F46), { onSetSafeMax(false) }),
                Triple("∞ Inf", Color(0xFF7C2D12), { onSetSafeMax(true) }),
                Triple("Recipe+", Color(0xFF1E293B), onSaveRecipe)
            ).forEach { (lbl, col, act) ->
                Surface(
                    shape = RoundedCornerShape((5f * s).dp),
                    color = col,
                    modifier = Modifier.clickable(enabled = !previewMode, onClick = act)
                ) {
                    Text(text = lbl, color = Color.White, fontSize = fsTiny, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = (6f * s).dp, vertical = (2.5f * s).dp))
                }
            }
        }

        if (isEditing) {
            Spacer(Modifier.height((4f * s).dp))
            Row(horizontalArrangement = Arrangement.spacedBy((4f * s).dp), verticalAlignment = Alignment.CenterVertically) {
                CeInputField(
                    value = editDraft,
                    onValueChange = { editDraft = it },
                    placeholder = "Val or +100 / x2 / --50",
                    fontSize = fsBody,
                    enabled = !previewMode,
                    onDone = { onSetValue(editDraft); onToggleEdit(); focusManager.clearFocus() },
                    modifier = Modifier.weight(1f)
                )
                CeButton(
                    text = "Write",
                    bg = accent,
                    fg = Color(0xFF042F2E),
                    fontSize = fsSmall,
                    minHeight = minBtnH,
                    enabled = !previewMode,
                    onClick = { onSetValue(editDraft); onToggleEdit(); focusManager.clearFocus() }
                )
                CeButton(text = "Undo", bg = Color(0xFF475569), fg = Color.White, fontSize = fsTiny, minHeight = minBtnH, enabled = !previewMode, onClick = onRestoreOriginal)
            }
            Spacer(Modifier.height((3.5f * s).dp))
            Row(horizontalArrangement = Arrangement.spacedBy((4f * s).dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                listOf("+100", "+1000", "--100", "x2", "x10", "/2", "999999", "0").forEach { mop ->
                    Surface(shape = RoundedCornerShape((6f * s).dp), color = Color(0xFF1E293B), modifier = Modifier.clickable(enabled = !previewMode) { onQuickMath(mop) }) {
                        Text(text = mop, color = Color(0xFF34D399), fontSize = fsTiny, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = (6f * s).dp, vertical = (3f * s).dp))
                    }
                }
            }
            val needsParam = w.freezeRule == MemFreezeRule.RANGE || w.freezeRule == MemFreezeRule.STEP ||
                w.freezeRule == MemFreezeRule.ON_CHANGE_SET || w.freezeRule == MemFreezeRule.ON_CHANGE_ADD || w.freezeRule == MemFreezeRule.ON_CHANGE_MUL
            if (needsParam) {
                Spacer(Modifier.height((3.5f * s).dp))
                Row(horizontalArrangement = Arrangement.spacedBy((4f * s).dp), verticalAlignment = Alignment.CenterVertically) {
                    CeInputField(
                        value = ruleParamDraft,
                        onValueChange = { ruleParamDraft = it },
                        placeholder = "Rule param (${w.freezeRule.label})",
                        fontSize = fsSmall,
                        enabled = !previewMode,
                        onDone = { onSetRuleParam(ruleParamDraft); focusManager.clearFocus() },
                        modifier = Modifier.weight(1f)
                    )
                    CeButton(
                        text = "Set ${w.freezeRule.symbol}",
                        bg = Color(0xFF4C1D95),
                        fg = Color.White,
                        fontSize = fsTiny,
                        minHeight = (26f * s).coerceIn(22f, 34f).dp,
                        enabled = !previewMode,
                        onClick = { onSetRuleParam(ruleParamDraft); focusManager.clearFocus() }
                    )
                }
            }
            Spacer(Modifier.height((3.5f * s).dp))
            Row(horizontalArrangement = Arrangement.spacedBy((4f * s).dp), verticalAlignment = Alignment.CenterVertically) {
                CeInputField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    placeholder = "Label name (e.g. Coins)",
                    fontSize = fsSmall,
                    enabled = !previewMode,
                    onDone = { onRename(renameDraft); focusManager.clearFocus() },
                    modifier = Modifier.weight(1f)
                )
                CeButton(text = "Name", bg = Color(0xFF334155), fg = Color.White, fontSize = fsTiny, minHeight = (26f * s).coerceIn(22f, 34f).dp, enabled = !previewMode, onClick = { onRename(renameDraft); focusManager.clearFocus() })
                CeButton(text = "Struct", bg = Color(0xFF0F766E), fg = Color.White, fontSize = fsTiny, minHeight = (26f * s).coerceIn(22f, 34f).dp, enabled = !previewMode, onClick = onPeekStruct)
                CeButton(text = "Ptr", bg = Color(0xFF4C1D95), fg = Color.White, fontSize = fsTiny, minHeight = (26f * s).coerceIn(22f, 34f).dp, enabled = !previewMode, onClick = onPointerScan)
            }
        }

        if (w.valueHistory.size >= 2) {
            Spacer(Modifier.height((3f * s).dp))
            Text(
                text = "History: " + w.valueHistory.takeLast(6).joinToString(" -> "),
                color = Color(0xFF94A3B8),
                fontSize = fsTiny,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (w.uiWarningReason.isNotBlank()) {
            Spacer(Modifier.height((3.5f * s).dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy((4f * s).dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF451A03), RoundedCornerShape((6f * s).dp))
                    .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape((6f * s).dp))
                    .padding(horizontal = (6f * s).dp, vertical = (4f * s).dp)
            ) {
                Text(text = "⚠ ${w.uiWarningReason}", color = Color(0xFFFDE68A), fontSize = fsTiny, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Surface(shape = RoundedCornerShape((5f * s).dp), color = Color(0xFFF59E0B), modifier = Modifier.clickable(enabled = !previewMode, onClick = onFindReal)) {
                    Text(text = "Find Real", color = Color(0xFF1F2937), fontSize = fsTiny, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = (6f * s).dp, vertical = (3f * s).dp))
                }
            }
        }

        if (w.tracing || w.traceEvents.isNotEmpty()) {
            Spacer(Modifier.height((3.5f * s).dp))
            Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF070B14), RoundedCornerShape((6f * s).dp)).padding((5f * s).dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "Write-Watcher (${w.traceCount} writes)", color = Color(0xFFFCA5A5), fontSize = fsTiny, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(text = "Clear", color = Color(0xFF94A3B8), fontSize = fsTiny, modifier = Modifier.clickable(enabled = !previewMode, onClick = onClearTrace))
                }
                if (w.traceEvents.isEmpty()) {
                    Text(text = "Waiting for external game writes...", color = Color(0xFF64748B), fontSize = fsTiny)
                } else {
                    w.traceEvents.takeLast(5).reversed().forEach { ev ->
                        Text(
                            text = "${ev.oldVal} -> ${ev.newVal} (${ev.delta}) · ${ev.caller}",
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

@Composable
internal fun CeHexTabContent(
    state: MemoryEngineUiState,
    s: Float,
    cardBg: Color,
    listMaxHeight: Dp,
    fsSmall: TextUnit,
    fsTiny: TextUnit,
    previewMode: Boolean,
    hexCursorByte: Int,
    onSetHexCursor: (Int) -> Unit,
    onInspectHex: (String, MemScope, Long) -> Unit,
    onToggleHexLiveRefresh: () -> Unit,
    onPeekNearbyStruct: (String, MemScope) -> Unit,
    onWriteHexBytes: (String, Long) -> Unit,
    onJumpHexOffset: (String) -> Unit,
    onAddStructFieldToWatch: (MemStructField) -> Unit,
    onSaveStructAsCheatTable: () -> Unit
) {
    var hexPatchDraft by remember { mutableStateOf("") }
    var hexJumpDraft by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val hx = state.hex

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((4f * s).dp), modifier = Modifier.fillMaxWidth()) {
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
            CeButton(text = "-64", bg = cardBg, fg = Color.White, fontSize = fsTiny, minHeight = (26f * s).dp, enabled = !previewMode, onClick = { onSetHexCursor(0); onInspectHex(hx.targetAddr, hx.scope, -64L) })
            CeButton(text = "+64", bg = cardBg, fg = Color.White, fontSize = fsTiny, minHeight = (26f * s).dp, enabled = !previewMode, onClick = { onSetHexCursor(0); onInspectHex(hx.targetAddr, hx.scope, +64L) })
            CeButton(text = if (state.hexLiveRefresh) "●Live" else "Live", bg = if (state.hexLiveRefresh) Color(0xFF059669) else cardBg, fg = Color.White, fontSize = fsTiny, minHeight = (26f * s).dp, enabled = !previewMode, onClick = onToggleHexLiveRefresh)
            CeButton(text = "Struct", bg = Color(0xFF0F766E), fg = Color.White, fontSize = fsTiny, minHeight = (26f * s).dp, enabled = !previewMode, onClick = { onPeekNearbyStruct(hx.targetAddr, hx.scope) })
        }
    }
    Spacer(Modifier.height((4f * s).dp))
    if (hx.error != null) {
        Text(text = hx.error, color = Color(0xFFFCA5A5), fontSize = fsSmall)
    } else if (hx.hexBytes.isBlank()) {
        Text(text = "Tap Hex on any scan result (Native, WASM, ArrayBuffer, TypedArray) to view & patch live bytes, decode multi-types, or reconstruct structs.", color = Color(0xFF94A3B8), fontSize = fsSmall)
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
            CeButton(text = "Patch", bg = Color(0xFFB45309), fg = Color.White, fontSize = fsTiny, minHeight = (28f * s).coerceIn(24f, 36f).dp, enabled = !previewMode && hexPatchDraft.isNotBlank(), onClick = { onWriteHexBytes(hexPatchDraft, safeCursor.toLong()); hexPatchDraft = ""; focusManager.clearFocus() })
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
            CeButton(text = "Go", bg = Color(0xFF334155), fg = Color.White, fontSize = fsTiny, minHeight = (28f * s).coerceIn(24f, 36f).dp, enabled = !previewMode && hexJumpDraft.isNotBlank(), onClick = { onSetHexCursor(0); onJumpHexOffset(hexJumpDraft); focusManager.clearFocus() })
        }
    }

    if (state.structFields.isNotEmpty()) {
        Spacer(Modifier.height((6f * s).dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(text = "Struct Fields (${state.structFields.size}) · tap +W", color = Color(0xFF5EEAD4), fontSize = fsTiny, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Surface(shape = RoundedCornerShape((5f * s).dp), color = Color(0xFF0284C7), modifier = Modifier.clickable(enabled = !previewMode, onClick = onSaveStructAsCheatTable)) {
                Text(text = "Save Table", color = Color.White, fontSize = fsTiny, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = (6f * s).dp, vertical = (2.5f * s).dp))
            }
        }
        Spacer(Modifier.height((3f * s).dp))
        Column(
            verticalArrangement = Arrangement.spacedBy((3f * s).dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = (listMaxHeight * 0.55f).coerceIn(56.dp, 200.dp)).verticalScroll(rememberScrollState())
        ) {
            state.structFields.take(36).forEach { sf ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape((6f * s).dp)).padding(horizontal = (6f * s).dp, vertical = (3f * s).dp)) {
                    Text(text = sf.delta, color = Color(0xFF38BDF8), fontSize = fsTiny, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width((52f * s).dp))
                    Text(
                        text = if (sf.u32Val.isNotBlank() && sf.u32Val != sf.i32Val) "i32=${sf.i32Val} u32=${sf.u32Val} f32=${sf.f32Val}" else "i32=${sf.i32Val} f32=${sf.f32Val}",
                        color = Color.White,
                        fontSize = fsTiny,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(shape = RoundedCornerShape((5f * s).dp), color = Color(0xFF065F46), modifier = Modifier.clickable(enabled = !previewMode) { onAddStructFieldToWatch(sf) }) {
                        Text(text = "+W", color = Color(0xFFA7F3D0), fontSize = fsTiny, modifier = Modifier.padding(horizontal = (6f * s).dp, vertical = (2.5f * s).dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun CeMapTabContent(
    state: MemoryEngineUiState,
    s: Float,
    cardBg: Color,
    minBtnH: Dp,
    listMaxHeight: Dp,
    fsSmall: TextUnit,
    fsTiny: TextUnit,
    previewMode: Boolean,
    onRefreshRegions: () -> Unit
) {
    val engText = if (state.detectedEngines.isNotEmpty()) state.detectedEngines.joinToString(", ") else "Auto"
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "${state.regions.size} regions · ${state.regionsTotalKb} KB · ${state.perfProfile.label}", color = Color(0xFFCBD5E1), fontSize = fsSmall, fontWeight = FontWeight.SemiBold)
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
        modifier = Modifier.fillMaxWidth().heightIn(max = listMaxHeight).verticalScroll(rememberScrollState())
    ) {
        if (state.regions.isEmpty()) {
            Text(text = "Tap Refresh (or run a scan) to list readable/writable memory regions and detected game engines.", color = Color(0xFF94A3B8), fontSize = fsSmall)
        } else {
            state.regions.forEach { r ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape((6f * s).dp)).padding(horizontal = (7f * s).dp, vertical = (4f * s).dp)
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
