package com.fable.browser.agent

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.math.hypot

object FableScreenInputController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile private var targetViewRef: WeakReference<View>? = null

    private var joyHeld = false
    private var joyDownTime = 0L
    private var joyCurX = 0f
    private var joyCurY = 0f
    @Volatile private var joyLeaseUntilMs = 0L

    private var camHeld = false
    private var camCurX = 0f
    private var camCurY = 0f
    @Volatile private var camLeaseUntilMs = 0L

    @Volatile var activeSummary: String = "None"
        private set

    init {
        watchScope.launch {
            while (true) {
                delay(80L)
                val now = SystemClock.uptimeMillis()
                val view = targetViewRef?.get()
                if (joyHeld || camHeld) {
                    if (view == null || !view.isAttachedToWindow || !view.hasWindowFocus() ||
                        (joyHeld && now > joyLeaseUntilMs) || (camHeld && now > camLeaseUntilMs)) {
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
            if ((joyHeld || camHeld) && view != null) {
                val now = SystemClock.uptimeMillis()
                dispatchMultiState(view, MotionEvent.ACTION_CANCEL, now, includeTap = null)
            }
            joyHeld = false
            camHeld = false
            joyLeaseUntilMs = 0L
            camLeaseUntilMs = 0L
            activeSummary = "None"
        }
    }

    suspend fun tapNormalized(normX: Int, normY: Int, holdMs: Long = 55L): Boolean = withContext(Dispatchers.Main) {
        val view = targetViewRef?.get()?.takeIf { it.width > 10 && it.height > 10 } ?: return@withContext false
        val x = (normX.coerceIn(20, 980) / 1000f) * view.width
        val y = (normY.coerceIn(20, 980) / 1000f) * view.height
        if (joyHeld || camHeld) {
            val now = SystemClock.uptimeMillis()
            dispatchMultiState(view, MotionEvent.ACTION_POINTER_DOWN, now, includeTap = x to y)
            delay(holdMs.coerceIn(30L, 250L))
            dispatchMultiState(view, MotionEvent.ACTION_POINTER_UP, SystemClock.uptimeMillis(), includeTap = x to y)
            return@withContext true
        }
        val down = SystemClock.uptimeMillis()
        activeSummary = "Tap (${normX / 10}%, ${normY / 10}%)"
        sendSingleMotion(view, down, down, MotionEvent.ACTION_DOWN, x, y)
        delay(holdMs.coerceIn(30L, 400L))
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
        val steps = 8
        val stepWait = (durationMs.coerceIn(80L, 800L) / steps).coerceAtLeast(10L)
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

    suspend fun stepGamingControl(
        joyCenterXPercent: Int,
        joyCenterYPercent: Int,
        joyXPercent: Int,
        joyYPercent: Int,
        camDxPercent: Int,
        camDyPercent: Int,
        tapAttack: Boolean,
        leaseMs: Long = 320L
    ) = withContext(Dispatchers.Main) {
        val view = targetViewRef?.get()?.takeIf { it.width > 10 && it.height > 10 } ?: return@withContext
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        val now = SystemClock.uptimeMillis()

        val jCenterX = (joyCenterXPercent.coerceIn(8, 40) / 100f) * w
        val jCenterY = (joyCenterYPercent.coerceIn(50, 90) / 100f) * h
        val joyRadius = minOf(w, h) * 0.13f

        val wantJoy = hypot(joyXPercent.toDouble(), joyYPercent.toDouble()) >= 12.0
        val wantCam = kotlin.math.abs(camDxPercent) >= 4 || kotlin.math.abs(camDyPercent) >= 4

        if (!wantJoy && joyHeld) {
            if (camHeld) {
                dispatchMultiState(view, MotionEvent.ACTION_POINTER_UP, now, dropJoy = true)
            } else {
                sendSingleMotion(view, joyDownTime, now, MotionEvent.ACTION_UP, joyCurX, joyCurY)
            }
            joyHeld = false
        }

        if (wantJoy) {
            val targetJx = (jCenterX + (joyXPercent.coerceIn(-100, 100) / 100f) * joyRadius).coerceIn(8f, w * 0.48f)
            val targetJy = (jCenterY + (joyYPercent.coerceIn(-100, 100) / 100f) * joyRadius).coerceIn(h * 0.35f, h - 8f)
            if (!joyHeld) {
                joyDownTime = now
                joyCurX = jCenterX
                joyCurY = jCenterY
                joyHeld = true
                sendSingleMotion(view, joyDownTime, now, MotionEvent.ACTION_DOWN, joyCurX, joyCurY)
            }
            joyCurX = targetJx
            joyCurY = targetJy
            joyLeaseUntilMs = now + leaseMs.coerceIn(150L, 1800L)
        }

        if (wantCam) {
            val camAnchorX = w * 0.68f
            val camAnchorY = h * 0.46f
            if (!camHeld || hypot((camCurX - camAnchorX).toDouble(), (camCurY - camAnchorY).toDouble()) > w * 0.22) {
                if (camHeld) {
                    if (joyHeld) {
                        dispatchMultiState(view, MotionEvent.ACTION_POINTER_UP, now, dropCam = true)
                    } else {
                        sendSingleMotion(view, joyDownTime, now, MotionEvent.ACTION_UP, camCurX, camCurY)
                    }
                }
                camCurX = camAnchorX
                camCurY = camAnchorY
                camHeld = true
                if (!joyHeld) joyDownTime = now
                if (joyHeld) {
                    dispatchMultiState(view, MotionEvent.ACTION_POINTER_DOWN, now, camJustDown = true)
                } else {
                    sendSingleMotion(view, joyDownTime, now, MotionEvent.ACTION_DOWN, camCurX, camCurY)
                }
            }
            val stepPxX = (camDxPercent.coerceIn(-100, 100) / 100f) * (w * 0.045f)
            val stepPxY = (camDyPercent.coerceIn(-100, 100) / 100f) * (h * 0.045f)
            camCurX = (camCurX + stepPxX).coerceIn(w * 0.48f, w * 0.94f)
            camCurY = (camCurY + stepPxY).coerceIn(h * 0.14f, h * 0.84f)
            camLeaseUntilMs = now + leaseMs.coerceIn(150L, 1800L)
        } else if (camHeld) {
            if (joyHeld) {
                dispatchMultiState(view, MotionEvent.ACTION_POINTER_UP, now, dropCam = true)
            } else {
                sendSingleMotion(view, joyDownTime, now, MotionEvent.ACTION_UP, camCurX, camCurY)
            }
            camHeld = false
        }

        if (joyHeld || camHeld) {
            dispatchMultiState(view, MotionEvent.ACTION_MOVE, SystemClock.uptimeMillis(), includeTap = null)
        }

        if (tapAttack) {
            val atkX = w * 0.76f
            val atkY = h * 0.56f
            if (joyHeld || camHeld) {
                val tDown = SystemClock.uptimeMillis()
                dispatchMultiState(view, MotionEvent.ACTION_POINTER_DOWN, tDown, includeTap = atkX to atkY)
                delay(28L)
                dispatchMultiState(view, MotionEvent.ACTION_POINTER_UP, SystemClock.uptimeMillis(), includeTap = atkX to atkY)
            } else {
                val tDown = SystemClock.uptimeMillis()
                sendSingleMotion(view, tDown, tDown, MotionEvent.ACTION_DOWN, atkX, atkY)
                delay(28L)
                sendSingleMotion(view, tDown, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, atkX, atkY)
            }
        }

        val parts = ArrayList<String>(3)
        if (joyHeld) parts.add("Joystick(${joyXPercent}%,${joyYPercent}%)")
        if (camHeld) parts.add("Cam(${camDxPercent}%,${camDyPercent}%)")
        if (tapAttack) parts.add("Attack")
        activeSummary = if (parts.isEmpty()) "Idle" else parts.joinToString(" + ")
    }

    private fun sendSingleMotion(view: View, downTime: Long, eventTime: Long, action: Int, x: Float, y: Float) {
        runCatching {
            val ev = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
            ev.source = InputDevice.SOURCE_TOUCHSCREEN
            view.dispatchTouchEvent(ev)
            ev.recycle()
        }
    }

    private fun dispatchMultiState(
        view: View,
        baseAction: Int,
        eventTime: Long,
        includeTap: Pair<Float, Float>? = null,
        camJustDown: Boolean = false,
        dropCam: Boolean = false,
        dropJoy: Boolean = false
    ) {
        val ptrs = ArrayList<Triple<Int, Float, Float>>(3)
        if (joyHeld) ptrs.add(Triple(0, joyCurX, joyCurY))
        if (camHeld) ptrs.add(Triple(1, camCurX, camCurY))
        if (includeTap != null) ptrs.add(Triple(2, includeTap.first, includeTap.second))
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
            includeTap != null -> count - 1
            camJustDown || dropCam -> ptrs.indexOfFirst { it.first == 1 }.coerceAtLeast(0)
            dropJoy -> ptrs.indexOfFirst { it.first == 0 }.coerceAtLeast(0)
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
                if (joyDownTime > 0L) joyDownTime else eventTime,
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
