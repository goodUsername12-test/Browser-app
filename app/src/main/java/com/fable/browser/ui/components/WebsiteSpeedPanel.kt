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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fable.browser.engine.WebsiteSpeedEngine
import kotlin.math.roundToInt

/**
 * Shared visual Speed Control Panel (0.1x to 300x) rendered at real scaled dp/sp dimensions
 * for BOTH the live browser overlay and the Settings live size preview.
 */
@Composable
fun WebsiteSpeedPanel(
    tenths: Int,
    scalePct: Int,
    expanded: Boolean,
    onLiveTenthsChange: (Int) -> Unit,
    onCommitTenths: (Int) -> Unit,
    onToggleExpanded: () -> Unit,
    onTurnOff: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
    maxWidthDp: Dp = 360.dp,
    previewMode: Boolean = false
) {
    val s = WebsiteSpeedEngine.clampPanelScalePct(scalePct) / 100f
    var localTenths by remember { mutableIntStateOf(WebsiteSpeedEngine.clampTenths(tenths)) }
    var isDraggingSlider by remember { mutableStateOf(false) }

    LaunchedEffect(tenths) {
        if (!isDraggingSlider) {
            localTenths = WebsiteSpeedEngine.clampTenths(tenths)
        }
    }

    val activeTenths = if (isDraggingSlider) localTenths else WebsiteSpeedEngine.clampTenths(tenths)
    val label = WebsiteSpeedEngine.formatTenths(activeTenths)
    val corner = (14f * s).coerceIn(8f, 22f).dp
    val borderColor = Color(0xFF38BDF8)
    val bgColor = Color(0xEE0F172A)

    if (!expanded) {
        Surface(
            shape = RoundedCornerShape(corner),
            color = bgColor,
            shadowElevation = 6.dp,
            modifier = modifier
                .then(dragHandleModifier)
                .border(1.dp, borderColor, RoundedCornerShape(corner))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(
                    horizontal = (8f * s).coerceIn(5f, 16f).dp,
                    vertical = (5f * s).coerceIn(3f, 12f).dp
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.DragIndicator,
                    contentDescription = "Move speed panel",
                    tint = Color(0xFF94A3B8),
                    modifier = Modifier.size((16f * s).coerceIn(11f, 26f).dp)
                )
                Spacer(Modifier.width((3f * s).dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(onClick = onToggleExpanded)
                ) {
                    Text(
                        text = label,
                        color = Color(0xFF38BDF8),
                        fontSize = (13f * s).coerceIn(9f, 21f).sp,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = Icons.Filled.ExpandMore,
                        contentDescription = "Expand speed panel",
                        tint = Color(0xFFCBD5E1),
                        modifier = Modifier.size((16f * s).coerceIn(11f, 26f).dp)
                    )
                }
                Spacer(Modifier.width((5f * s).dp))
                Surface(
                    shape = RoundedCornerShape((7f * s).coerceIn(5f, 12f).dp),
                    color = Color(0xFFDC2626),
                    modifier = Modifier.clickable(enabled = !previewMode, onClick = onTurnOff)
                ) {
                    Text(
                        text = "OFF",
                        color = Color.White,
                        fontSize = (10f * s).coerceIn(7.5f, 16f).sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(
                            horizontal = (6f * s).coerceIn(4f, 11f).dp,
                            vertical = (2.5f * s).coerceIn(1.5f, 5f).dp
                        )
                    )
                }
            }
        }
        return
    }

    val focusManager = LocalFocusManager.current
    var inputText by remember(activeTenths) {
        mutableStateOf(
            if (activeTenths % 10 == 0) (activeTenths / 10).toString()
            else "${activeTenths / 10}.${activeTenths % 10}"
        )
    }

    fun applyTypedInput() {
        val parsed = WebsiteSpeedEngine.parseSpeedInputToTenths(inputText)
        if (parsed != null) {
            localTenths = parsed
            onLiveTenthsChange(parsed)
            onCommitTenths(parsed)
            inputText = if (parsed % 10 == 0) (parsed / 10).toString() else "${parsed / 10}.${parsed % 10}"
        }
        focusManager.clearFocus()
    }

    val panelWidth = (244f * s).dp.coerceAtMost(maxWidthDp)
    val hPad = (9f * s).coerceIn(6f, 16f).dp
    val vPad = (7f * s).coerceIn(4f, 13f).dp
    val presets = listOf(1 to "0.1x", 5 to "0.5x", 10 to "1x", 30 to "3x", 100 to "10x", 1000 to "100x", 3000 to "300x")

    Surface(
        shape = RoundedCornerShape(corner),
        color = bgColor,
        shadowElevation = 8.dp,
        modifier = modifier
            .width(panelWidth)
            .border(1.dp, borderColor, RoundedCornerShape(corner))
    ) {
        Column(modifier = Modifier.padding(horizontal = hPad, vertical = vPad)) {
            // Draggable header bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(dragHandleModifier)
            ) {
                Icon(
                    imageVector = Icons.Filled.DragIndicator,
                    contentDescription = "Drag to move speed panel",
                    tint = Color(0xFF94A3B8),
                    modifier = Modifier.size((16f * s).coerceIn(11f, 26f).dp)
                )
                Spacer(Modifier.width((3f * s).dp))
                Text(
                    text = "Speed $label",
                    color = Color(0xFF38BDF8),
                    fontSize = (13f * s).coerceIn(9.5f, 21f).sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape((7f * s).coerceIn(4f, 12f).dp),
                    color = Color(0xFF1E293B),
                    modifier = Modifier.clickable(onClick = onToggleExpanded)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ExpandLess,
                        contentDescription = "Collapse speed panel",
                        tint = Color(0xFFCBD5E1),
                        modifier = Modifier
                            .padding((3f * s).coerceIn(2f, 6f).dp)
                            .size((14f * s).coerceIn(10f, 22f).dp)
                    )
                }
                Spacer(Modifier.width((5f * s).dp))
                Surface(
                    shape = RoundedCornerShape((7f * s).coerceIn(4f, 12f).dp),
                    color = Color(0xFFDC2626),
                    modifier = Modifier.clickable(enabled = !previewMode, onClick = onTurnOff)
                ) {
                    Text(
                        text = "OFF",
                        color = Color.White,
                        fontSize = (10f * s).coerceIn(7.5f, 16f).sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(
                            horizontal = (7f * s).coerceIn(4f, 12f).dp,
                            vertical = (3f * s).coerceIn(1.5f, 6f).dp
                        )
                    )
                }
            }

            Spacer(Modifier.height((3f * s).dp))

            // 4-zone exact precision slider (0.1x to 300x)
            val sliderPos = WebsiteSpeedEngine.sliderPosFromTenths(activeTenths)
            Slider(
                value = sliderPos,
                onValueChange = { p ->
                    val nextTenths = WebsiteSpeedEngine.tenthsFromSliderPos(p)
                    isDraggingSlider = true
                    if (nextTenths != localTenths) {
                        localTenths = nextTenths
                        onLiveTenthsChange(nextTenths)
                    }
                },
                onValueChangeFinished = {
                    val committed = localTenths
                    isDraggingSlider = false
                    onCommitTenths(committed)
                },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF38BDF8),
                    activeTrackColor = Color(0xFF0284C7),
                    inactiveTrackColor = Color(0xFF334155)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height((26f * s).coerceIn(18f, 40f).dp)
            )

            // Scale guide ticks
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                listOf("0.1x", "2x", "10x", "100x", "300x").forEach { tick ->
                    Text(
                        text = tick,
                        color = Color(0xFF94A3B8),
                        fontSize = (8.5f * s).coerceIn(6.5f, 13f).sp
                    )
                }
            }

            Spacer(Modifier.height((5f * s).dp))

            // Fine - / + buttons + exact numeric speed typing + Set
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy((4f * s).coerceIn(2f, 8f).dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = RoundedCornerShape((7f * s).coerceIn(4f, 12f).dp),
                    color = Color(0xFF1E293B),
                    modifier = Modifier.clickable {
                        val next = WebsiteSpeedEngine.nudgeTenths(activeTenths, -1)
                        localTenths = next
                        onLiveTenthsChange(next)
                        onCommitTenths(next)
                    }
                ) {
                    Text(
                        text = "−",
                        color = Color.White,
                        fontSize = (13f * s).coerceIn(9f, 20f).sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(
                            horizontal = (8f * s).coerceIn(5f, 14f).dp,
                            vertical = (3f * s).coerceIn(2f, 6f).dp
                        )
                    )
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF1E293B), RoundedCornerShape((7f * s).coerceIn(4f, 12f).dp))
                        .border(1.dp, Color(0xFF475569), RoundedCornerShape((7f * s).coerceIn(4f, 12f).dp))
                        .padding(
                            horizontal = (6f * s).coerceIn(4f, 10f).dp,
                            vertical = (4f * s).coerceIn(2f, 7f).dp
                        )
                ) {
                    BasicTextField(
                        value = inputText,
                        onValueChange = { raw ->
                            if (raw.length <= 8) inputText = raw
                        },
                        singleLine = true,
                        enabled = !previewMode,
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = (11.5f * s).coerceIn(8.5f, 18f).sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        ),
                        cursorBrush = SolidColor(Color(0xFF38BDF8)),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { applyTypedInput() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Surface(
                    shape = RoundedCornerShape((7f * s).coerceIn(4f, 12f).dp),
                    color = Color(0xFF0284C7),
                    modifier = Modifier.clickable(enabled = !previewMode) { applyTypedInput() }
                ) {
                    Text(
                        text = "Set",
                        color = Color.White,
                        fontSize = (10.5f * s).coerceIn(7.5f, 16f).sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(
                            horizontal = (8f * s).coerceIn(5f, 14f).dp,
                            vertical = (4f * s).coerceIn(2f, 7f).dp
                        )
                    )
                }

                Surface(
                    shape = RoundedCornerShape((7f * s).coerceIn(4f, 12f).dp),
                    color = Color(0xFF1E293B),
                    modifier = Modifier.clickable {
                        val next = WebsiteSpeedEngine.nudgeTenths(activeTenths, +1)
                        localTenths = next
                        onLiveTenthsChange(next)
                        onCommitTenths(next)
                    }
                ) {
                    Text(
                        text = "+",
                        color = Color.White,
                        fontSize = (13f * s).coerceIn(9f, 20f).sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(
                            horizontal = (8f * s).coerceIn(5f, 14f).dp,
                            vertical = (3f * s).coerceIn(2f, 6f).dp
                        )
                    )
                }
            }

            Spacer(Modifier.height((5f * s).dp))

            // Quick one-tap presets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy((3f * s).coerceIn(2f, 6f).dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                presets.forEach { (tVal, txt) ->
                    val active = activeTenths == tVal
                    Surface(
                        shape = RoundedCornerShape((6f * s).coerceIn(4f, 10f).dp),
                        color = if (active) Color(0xFF0284C7) else Color(0xFF1E293B),
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                localTenths = tVal
                                onLiveTenthsChange(tVal)
                                onCommitTenths(tVal)
                            }
                    ) {
                        Text(
                            text = txt,
                            color = Color.White,
                            fontSize = (8.8f * s).coerceIn(6.5f, 14f).sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.padding(
                                horizontal = (2f * s).coerceIn(1f, 4f).dp,
                                vertical = (3f * s).coerceIn(2f, 6f).dp
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Full-screen transparent overlay host that lets the user freely drag the Speed Control Panel
 * anywhere on screen, clamping safely inside screen bounds and persisting position on drag end.
 */
@Composable
fun DraggableWebsiteSpeedPanel(
    tenths: Int,
    scalePct: Int,
    posX: Float,
    posY: Float,
    expanded: Boolean,
    topSafePadding: Dp,
    bottomSafePadding: Dp,
    onLiveTenthsChange: (Int) -> Unit,
    onCommitTenths: (Int) -> Unit,
    onPositionChangeFinished: (Float, Float) -> Unit,
    onToggleExpanded: () -> Unit,
    onTurnOff: () -> Unit,
    modifier: Modifier = Modifier
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
                    val normX = ((offsetX - minX) / spanX).coerceIn(0f, 1f)
                    val normY = ((offsetY - minY) / spanY).coerceIn(0f, 1f)
                    onPositionChangeFinished(normX, normY)
                },
                onDragCancel = {
                    dragging = false
                    val normX = ((offsetX - minX) / spanX).coerceIn(0f, 1f)
                    val normY = ((offsetY - minY) / spanY).coerceIn(0f, 1f)
                    onPositionChangeFinished(normX, normY)
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    offsetX = (offsetX + dragAmount.x).coerceIn(minX, maxX)
                    offsetY = (offsetY + dragAmount.y).coerceIn(minY, maxY)
                }
            )
        }

        WebsiteSpeedPanel(
            tenths = tenths,
            scalePct = scalePct,
            expanded = expanded,
            onLiveTenthsChange = onLiveTenthsChange,
            onCommitTenths = onCommitTenths,
            onToggleExpanded = onToggleExpanded,
            onTurnOff = onTurnOff,
            dragHandleModifier = dragModifier,
            maxWidthDp = (maxWidth - 16.dp).coerceAtLeast(140.dp),
            modifier = Modifier
                .offset { IntOffset(clampedX.roundToInt(), clampedY.roundToInt()) }
                .onSizeChanged { sz ->
                    panelW = sz.width
                    panelH = sz.height
                }
        )
    }
}
