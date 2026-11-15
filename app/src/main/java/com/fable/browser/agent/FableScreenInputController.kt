package com.fable.browser.agent

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Pure OS/View input controller for Fable AI Agent:
 * - Simultaneous 4-pointer multi-touch (Pointer 0 = Virtual Joystick, Pointer 1 = Smooth Camera Drag,
 *   Pointer 2 = Holdable/Tappable Primary/Attack/Mine button, Pointer 3 = Jump/Crouch/Secondary button).
 * - Screen-safe natural text typing via Android KeyCharacterMap + KeyEvents (zero JS/DOM injection).
 * - Hardware/PC KeyEvent hold support with automatic 80 ms watchdog release on stop/focus-loss/timeout.
 */
object FableScreenInputController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile private var targetViewRef: WeakReference<View>? = null

    private var sessionDownTime = 0L

    private var joyHeld = false
    private var joyCurX = 0f
    private var joyCurY = 0f
    @Volatile private var joyLeaseUntilMs = 0L

    private var camHeld = false
    private var camCurX = 0f
    private var camCurY = 0f
    @Volatile private var camLeaseUntilMs = 0L

    private var primaryBtnHeld = false
    private var primaryBtnX = 0f
    private var primaryBtnY = 0f
    @Volatile private var primaryLeaseUntilMs = 0L

    private val heldKeyCodes = LinkedHashSet<Int>()
    @Volatile private var keysLeaseUntilMs = 0L

    @Volatile var activeSummary: String = "None"
        private set

    init {
        watchScope.launch {
            while (true) {
                delay(80L)
                val now = SystemClock.uptimeMillis()
                val view = targetViewRef?.get()
                val anyTouchHeld = joyHeld || camHeld || primaryBtnHeld
                val anyKeysHeld = heldKeyCodes.isNotEmpty()
                if (anyTouchHeld || anyKeysHeld) {
                    val viewInvalid = view == null || !view.isAttachedToWindow || !view.hasWindowFocus()
                    val touchExpired = (joyHeld && now > joyLeaseUntilMs) ||
                        (camHeld && now > camLeaseUntilMs) ||
                        (primaryBtnHeld && now > primaryLeaseUntilMs)
                    val keysExpired = anyKeysHeld && now > keysLeaseUntilMs
                    if (viewInvalid || touchExpired || keysExpired) {
                        releaseAllImmediately()
                    }
                }
            }
        }
    }

    fun attachSurfaceView(view: View?) {
        if (view == null) {
            releaseAllImmediately()
            targetViewRef = null
        } else {
            targetViewRef = WeakReference(view)
        }
    }

    fun getAttachedView(): View? = targetViewRef?.get()

    fun releaseAllImmediately() {
        mainHandler.post {
            val view = targetViewRef?.get()
            val now = SystemClock.uptimeMillis()
            if ((joyHeld || camHeld || primaryBtnHeld) && view != null) {
                dispatchMultiState(view, MotionEvent.ACTION_CANCEL, now)
            }
            if (heldKeyCodes.isNotEmpty() && view != null) {
                for (kc in heldKeyCodes) {
                    runCatching {
                        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, kc, 0))
                    }
                }
            }
            heldKeyCodes.clear()
            joyHeld = false
            camHeld = false
            primaryBtnHeld = false
            sessionDownTime = 0L
            joyLeaseUntilMs = 0L
            camLeaseUntilMs = 0L
            primaryLeaseUntilMs = 0L
            keysLeaseUntilMs = 0L
            activeSummary = "None"
        }
    }

    suspend fun tapNormalized(normX: Int, normY: Int, holdMs: Long = 55L): Boolean = withContext(Dispatchers.Main) {
        val view = targetViewRef?.get()?.takeIf { it.width > 10 && it.height > 10 } ?: return@withContext false
        val x = (normX.coerceIn(20, 980) / 1000f) * view.width
        val y = (normY.coerceIn(20, 980) / 1000f) * view.height
        if (joyHeld || camHeld || primaryBtnHeld) {
            val now = SystemClock.uptimeMillis()
            dispatchMultiState(view, MotionEvent.ACTION_POINTER_DOWN, now, transientTap = x to y)
            delay(holdMs.coerceIn(28L, 250L))
            dispatchMultiState(view, MotionEvent.ACTION_POINTER_UP, SystemClock.uptimeMillis(), transientTap = x to y)
            return@withContext true
        }
        val down = SystemClock.uptimeMillis()
        activeSummary = "Tap (${normX / 10}%, ${normY / 10}%)"
        sendSingleMotion(view, down, down, MotionEvent.ACTION_DOWN, x, y)
        delay(holdMs.coerceIn(28L, 450L))
        val up = SystemClock.uptimeMillis()
        sendSingleMotion(view, down, up, MotionEvent.ACTION_UP, x, y)
        activeSummary = "None"
        true
    }

    suspend fun swipeNormalized(
        startNormX: Int,
        startNormY: Int,
        endNormX: Int,
        endNormY: Int,
        durationMs: Long = 220L
    ): Boolean = withContext(Dispatchers.Main) {
        val view = targetViewRef?.get()?.takeIf { it.width > 10 && it.height > 10 } ?: return@withContext false
        releaseAllImmediately()
        val x0 = (startNormX.coerceIn(30, 970) / 1000f) * view.width
        val y0 = (startNormY.coerceIn(30, 970) / 1000f) * view.height
        val x1 = (endNormX.coerceIn(30, 970) / 1000f) * view.width
        val y1 = (endNormY.coerceIn(30, 970) / 1000f) * view.height
        val down = SystemClock.uptimeMillis()
        activeSummary = "Swipe"
        sendSingleMotion(view, down, down, MotionEvent.ACTION_DOWN, x0, y0)
        val steps = 10
        val stepWait = (durationMs.coerceIn(80L, 900L) / steps).coerceAtLeast(10L)
        for (i in 1..steps) {
            delay(stepWait)
            val t = i.toFloat() / steps.toFloat()
            val cx = x0 + (x1 - x0) * t
            val cy = y0 + (y1 - y0) * t
            sendSingleMotion(view, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, cx, cy)
        }
        sendSingleMotion(view, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x1, y1)
        activeSummary = "None"
        true
    }

    /**
     * Smoothly turns/tilts the camera in 3D games using pure touch drag on the right look-pad area,
     * automatically re-anchoring mid-turn if needed so 180°/360° turns never hit the screen edge.
     */
    suspend fun moveCameraSmooth(
        yawPercent: Int,
        pitchPercent: Int,
        durationMs: Long = 380L,
        sensitivityPct: Int = 100
    ): Boolean = withContext(Dispatchers.Main) {
        val view = targetViewRef?.get()?.takeIf { it.width > 10 && it.height > 10 } ?: return@withContext false
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        val sens = sensitivityPct.coerceIn(40, 220) / 100f

        val totalDxPx = (yawPercent.coerceIn(-360, 360) / 100f) * (w * 0.24f) * sens
        val totalDyPx = (pitchPercent.coerceIn(-180, 180) / 100f) * (h * 0.20f) * sens

        val maxSingleStrokeX = w * 0.28f
        val segments = (abs(totalDxPx) / maxSingleStrokeX).toInt().coerceAtLeast(0) + 1
        val segDx = totalDxPx / segments
        val segDy = totalDyPx / segments
        val segDur = (durationMs.coerceIn(90L, 2_500L) / segments).coerceAtLeast(60L)

        activeSummary = "Camera(${yawPercent}%, ${pitchPercent}%)"
        for (s in 0 until segments) {
            val anchorX = w * 0.68f
            val anchorY = h * 0.46f
            val steps = 8
            val down = SystemClock.uptimeMillis()
            sendSingleMotion(view, down, down, MotionEvent.ACTION_DOWN, anchorX, anchorY)
            val waitMs = (segDur / steps).coerceAtLeast(8L)
            for (i in 1..steps) {
                delay(waitMs)
                val frac = i.toFloat() / steps.toFloat()
                val cx = (anchorX + segDx * frac).coerceIn(w * 0.36f, w * 0.96f)
                val cy = (anchorY + segDy * frac).coerceIn(h * 0.10f, h * 0.88f)
                sendSingleMotion(view, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, cx, cy)
            }
            val endX = (anchorX + segDx).coerceIn(w * 0.36f, w * 0.96f)
            val endY = (anchorY + segDy).coerceIn(h * 0.10f, h * 0.88f)
            sendSingleMotion(view, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, endX, endY)
            if (s < segments - 1) delay(18L)
        }
        activeSummary = "None"
        true
    }

    /**
     * Pure OS/View natural typing — ZERO JavaScript or DOM injection!
     * Optionally taps target coordinates to focus an input/chat box first, clears existing characters
     * via KEYCODE_DEL if requested, types every character via KeyCharacterMap / InputConnection, and
     * optionally presses Enter.
     */
    suspend fun typeTextOnScreen(
        textToType: String,
        focusNormX: Int? = null,
        focusNormY: Int? = null,
        clearExistingFirst: Boolean = false,
        pressEnter: Boolean = false
    ): Boolean = withContext(Dispatchers.Main) {
        val rootView = targetViewRef?.get()?.takeIf { it.width > 10 && it.height > 10 } ?: return@withContext false
        releaseAllImmediately()

        if (focusNormX != null && focusNormY != null) {
            tapNormalized(focusNormX, focusNormY, holdMs = 60L)
            delay(180L)
        }

        val target = rootView.findFocus() ?: rootView
        target.requestFocus()

        if (clearExistingFirst) {
            val now = SystemClock.uptimeMillis()
            repeat(24) {
                target.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0))
                target.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 0))
            }
            delay(30L)
        }

        activeSummary = "Typing (${textToType.length} chars)"
        val kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)

        for (ch in textToType) {
            val events = kcm.getEvents(charArrayOf(ch))
            if (events != null && events.isNotEmpty()) {
                for (ev in events) {
                    target.dispatchKeyEvent(ev)
                }
            } else {
                // Non-ASCII / Unicode fallback via standard Android InputConnection commitText (no JS!)
                val ic = BaseInputConnection(target, false)
                ic.commitText(ch.toString(), 1)
            }
            delay(14L)
        }

        if (pressEnter) {
            delay(35L)
            val now = SystemClock.uptimeMillis()
            target.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
            target.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
        }

        activeSummary = "None"
        true
    }

    suspend fun holdHardwareKeys(
        keyCodes: List<Int>,
        durationMs: Long
    ) = withContext(Dispatchers.Main) {
        val view = targetViewRef?.get() ?: return@withContext
        val target = view.findFocus() ?: view
        val clampedMs = durationMs.coerceIn(80L, 30_000L)
        val downAt = SystemClock.uptimeMillis()
        keysLeaseUntilMs = downAt + clampedMs + 250L
        for (kc in keyCodes) {
            heldKeyCodes.add(kc)
            target.dispatchKeyEvent(KeyEvent(downAt, downAt, KeyEvent.ACTION_DOWN, kc, 0))
        }
        activeSummary = "Keys(${keyCodes.size} held)"
        try {
            delay(clampedMs)
        } finally {
            val upAt = SystemClock.uptimeMillis()
            for (kc in heldKeyCodes.toList()) {
                target.dispatchKeyEvent(KeyEvent(downAt, upAt, KeyEvent.ACTION_UP, kc, 0))
            }
            heldKeyCodes.clear()
            keysLeaseUntilMs = 0L
            activeSummary = "None"
        }
    }

    suspend fun stepGamingControl(
        joyCenterXPercent: Int,
        joyCenterYPercent: Int,
        joyXPercent: Int,
        joyYPercent: Int,
        camDxPercent: Int,
        camDyPercent: Int,
        tapAttack: Boolean,
        holdPrimaryButton: Boolean = false,
        tapJumpButton: Boolean = false,
        attackBtnXPercent: Int = 78,
        attackBtnYPercent: Int = 58,
        jumpBtnXPercent: Int = 86,
        jumpBtnYPercent: Int = 74,
        leaseMs: Long = 320L
    ) = withContext(Dispatchers.Main) {
        val view = targetViewRef?.get()?.takeIf { it.width > 10 && it.height > 10 } ?: return@withContext
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        val now = SystemClock.uptimeMillis()

        val jCenterX = (joyCenterXPercent.coerceIn(8, 42) / 100f) * w
        val jCenterY = (joyCenterYPercent.coerceIn(48, 92) / 100f) * h
        val joyRadius = minOf(w, h) * 0.135f

        val wantJoy = hypot(joyXPercent.toDouble(), joyYPercent.toDouble()) >= 12.0
        val wantCam = abs(camDxPercent) >= 4 || abs(camDyPercent) >= 4
        val wantHoldPrim = holdPrimaryButton

        if (sessionDownTime == 0L && (wantJoy || wantCam || wantHoldPrim)) {
            sessionDownTime = now
        }

        // 1. Update Joystick pointer (id=0)
        if (!wantJoy && joyHeld) {
            if (camHeld || primaryBtnHeld) {
                dispatchMultiState(view, MotionEvent.ACTION_POINTER_UP, now, droppingId = 0)
            } else {
                sendSingleMotion(view, sessionDownTime, now, MotionEvent.ACTION_UP, joyCurX, joyCurY)
            }
            joyHeld = false
        } else if (wantJoy) {
            val targetJx = (jCenterX + (joyXPercent.coerceIn(-100, 100) / 100f) * joyRadius).coerceIn(8f, w * 0.48f)
            val targetJy = (jCenterY + (joyYPercent.coerceIn(-100, 100) / 100f) * joyRadius).coerceIn(h * 0.34f, h - 8f)
            if (!joyHeld) {
                joyCurX = jCenterX
                joyCurY = jCenterY
                joyHeld = true
                if (camHeld || primaryBtnHeld) {
                    dispatchMultiState(view, MotionEvent.ACTION_POINTER_DOWN, now, addingId = 0)
                } else {
                    sendSingleMotion(view, sessionDownTime, now, MotionEvent.ACTION_DOWN, joyCurX, joyCurY)
                }
            }
            joyCurX = targetJx
            joyCurY = targetJy
            joyLeaseUntilMs = now + leaseMs.coerceIn(150L, 2_000L)
        }

        // 2. Update Camera pointer (id=1) with seamless edge re-anchoring
        if (wantCam) {
            val camAnchorX = w * 0.66f
            val camAnchorY = h * 0.44f
            val nearEdge = camHeld && (camCurX < w * 0.42f || camCurX > w * 0.92f || camCurY < h * 0.14f || camCurY > h * 0.82f)
            if (!camHeld || nearEdge) {
                if (camHeld) {
                    if (joyHeld || primaryBtnHeld) {
                        dispatchMultiState(view, MotionEvent.ACTION_POINTER_UP, now, droppingId = 1)
                    } else {
                        sendSingleMotion(view, sessionDownTime, now, MotionEvent.ACTION_UP, camCurX, camCurY)
                    }
                    camHeld = false
                }
                camCurX = camAnchorX
                camCurY = camAnchorY
                camHeld = true
                if (joyHeld || primaryBtnHeld) {
                    dispatchMultiState(view, MotionEvent.ACTION_POINTER_DOWN, now, addingId = 1)
                } else {
                    sendSingleMotion(view, sessionDownTime, now, MotionEvent.ACTION_DOWN, camCurX, camCurY)
                }
            }
            val stepPxX = (camDxPercent.coerceIn(-100, 100) / 100f) * (w * 0.048f)
            val stepPxY = (camDyPercent.coerceIn(-100, 100) / 100f) * (h * 0.045f)
            camCurX = (camCurX + stepPxX).coerceIn(w * 0.40f, w * 0.95f)
            camCurY = (camCurY + stepPxY).coerceIn(h * 0.12f, h * 0.86f)
            camLeaseUntilMs = now + leaseMs.coerceIn(150L, 2_000L)
        } else if (camHeld) {
            if (joyHeld || primaryBtnHeld) {
                dispatchMultiState(view, MotionEvent.ACTION_POINTER_UP, now, droppingId = 1)
            } else {
                sendSingleMotion(view, sessionDownTime, now, MotionEvent.ACTION_UP, camCurX, camCurY)
            }
            camHeld = false
        }

        // 3. Update Holdable Primary/Mine/Attack button pointer (id=2)
        val pBtnX = (attackBtnXPercent.coerceIn(45, 96) / 100f) * w
        val pBtnY = (attackBtnYPercent.coerceIn(25, 94) / 100f) * h
        if (wantHoldPrim) {
            primaryBtnX = pBtnX
            primaryBtnY = pBtnY
            if (!primaryBtnHeld) {
                primaryBtnHeld = true
                if (joyHeld || camHeld) {
                    dispatchMultiState(view, MotionEvent.ACTION_POINTER_DOWN, now, addingId = 2)
                } else {
                    sendSingleMotion(view, sessionDownTime, now, MotionEvent.ACTION_DOWN, primaryBtnX, primaryBtnY)
                }
            }
            primaryLeaseUntilMs = now + leaseMs.coerceIn(150L, 2_000L)
        } else if (primaryBtnHeld) {
            if (joyHeld || camHeld) {
                dispatchMultiState(view, MotionEvent.ACTION_POINTER_UP, now, droppingId = 2)
            } else {
                sendSingleMotion(view, sessionDownTime, now, MotionEvent.ACTION_UP, primaryBtnX, primaryBtnY)
            }
            primaryBtnHeld = false
        }

        if (joyHeld || camHeld || primaryBtnHeld) {
            dispatchMultiState(view, MotionEvent.ACTION_MOVE, SystemClock.uptimeMillis())
        } else {
            sessionDownTime = 0L
        }

        // 4. Transient Attack tap (when not already holding primary button)
        if (tapAttack && !primaryBtnHeld) {
            pulseTransientButton(view, pBtnX, pBtnY, holdMs = 26L)
        }

        // 5. Transient Jump / Secondary button tap (id=3)
        if (tapJumpButton) {
            val jBtnX = (jumpBtnXPercent.coerceIn(45, 96) / 100f) * w
            val jBtnY = (jumpBtnYPercent.coerceIn(30, 95) / 100f) * h
            pulseTransientButton(view, jBtnX, jBtnY, holdMs = 32L)
        }

        val parts = ArrayList<String>(4)
        if (joyHeld) parts.add("Joy(${joyXPercent}%,${joyYPercent}%)")
        if (camHeld) parts.add("Cam(${camDxPercent}%,${camDyPercent}%)")
        if (primaryBtnHeld) parts.add("HoldBtn")
        else if (tapAttack) parts.add("Attack")
        if (tapJumpButton) parts.add("Jump")
        activeSummary = if (parts.isEmpty()) "Idle" else parts.joinToString(" + ")
    }

    private suspend fun pulseTransientButton(view: View, x: Float, y: Float, holdMs: Long) {
        if (joyHeld || camHeld || primaryBtnHeld) {
            val tDown = SystemClock.uptimeMillis()
            dispatchMultiState(view, MotionEvent.ACTION_POINTER_DOWN, tDown, transientTap = x to y)
            delay(holdMs)
            dispatchMultiState(view, MotionEvent.ACTION_POINTER_UP, SystemClock.uptimeMillis(), transientTap = x to y)
        } else {
            val tDown = SystemClock.uptimeMillis()
            sendSingleMotion(view, tDown, tDown, MotionEvent.ACTION_DOWN, x, y)
            delay(holdMs)
            sendSingleMotion(view, tDown, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
        }
    }

    private fun sendSingleMotion(view: View, downTime: Long, eventTime: Long, action: Int, x: Float, y: Float) {
        runCatching {
            val ev = MotionEvent.obtain(if (downTime > 0L) downTime else eventTime, eventTime, action, x, y, 0)
            ev.source = InputDevice.SOURCE_TOUCHSCREEN
            view.dispatchTouchEvent(ev)
            ev.recycle()
        }
    }

    private fun dispatchMultiState(
        view: View,
        baseAction: Int,
        eventTime: Long,
        transientTap: Pair<Float, Float>? = null,
        addingId: Int = -1,
        droppingId: Int = -1
    ) {
        val ptrs = ArrayList<Triple<Int, Float, Float>>(4)
        if (joyHeld) ptrs.add(Triple(0, joyCurX, joyCurY))
        if (camHeld) ptrs.add(Triple(1, camCurX, camCurY))
        if (primaryBtnHeld) ptrs.add(Triple(2, primaryBtnX, primaryBtnY))
        if (transientTap != null) ptrs.add(Triple(3, transientTap.first, transientTap.second))
        if (ptrs.isEmpty()) return

        val count = ptrs.size
        val props = Array(count) { i ->
            MotionEvent.PointerProperties().apply {
                id = ptrs[i].first
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(count) { i ->
            MotionEvent.PointerCoords().apply {
                x = ptrs[i].second
                y = ptrs[i].third
                pressure = 1.0f
                size = 0.2f
            }
        }
        val actionIndex = when {
            transientTap != null -> count - 1
            addingId >= 0 -> ptrs.indexOfFirst { it.first == addingId }.coerceAtLeast(0)
            droppingId >= 0 -> ptrs.indexOfFirst { it.first == droppingId }.coerceAtLeast(0)
            else -> 0
        }
        val fullAction = when (baseAction) {
            MotionEvent.ACTION_POINTER_DOWN ->
                if (count == 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            MotionEvent.ACTION_POINTER_UP ->
                if (count == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            else -> baseAction
        }
        runCatching {
            val ev = MotionEvent.obtain(
                if (sessionDownTime > 0L) sessionDownTime else eventTime,
                eventTime,
                fullAction,
                count,
                props,
                coords,
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0
            )
            view.dispatchTouchEvent(ev)
            ev.recycle()
        }
    }
}
