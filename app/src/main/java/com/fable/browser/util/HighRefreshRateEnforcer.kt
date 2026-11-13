package com.fable.browser.util

import android.app.Activity
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.Window

object HighRefreshRateEnforcer {
    @Volatile
    var targetRefreshRate: Float = 120f
        private set

    fun resolveMaxRefreshRate(activity: Activity): Float {
        val display = resolveDisplay(activity) ?: return 120f
        val maxSupported = display.supportedModes.maxOfOrNull { it.refreshRate } ?: display.refreshRate
        val target = if (maxSupported >= 89f) maxSupported else 120f
        targetRefreshRate = target
        return target
    }

    @Suppress("DEPRECATION")
    private fun resolveDisplay(activity: Activity): Display? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display ?: activity.windowManager.defaultDisplay
        } else {
            activity.windowManager.defaultDisplay
        }
    }.getOrNull()

    fun applyToWindow(activity: Activity, highRefresh: Boolean) {
        runCatching {
            val window = activity.window ?: return
            val display = resolveDisplay(activity) ?: return
            val current = display.mode
            val modes = display.supportedModes.toList()
            val sameResBest = modes
                .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                .maxByOrNull { it.refreshRate }
            val overallBest = modes.maxByOrNull { it.refreshRate }
            val chosen = if (highRefresh) {
                if (sameResBest != null && overallBest != null && overallBest.refreshRate > sameResBest.refreshRate + 1f) {
                    overallBest
                } else {
                    sameResBest ?: overallBest
                }
            } else {
                null
            }
            val desiredRate = if (highRefresh) {
                (chosen?.refreshRate ?: 120f).coerceAtLeast(120f)
            } else {
                0f
            }
            targetRefreshRate = if (highRefresh) desiredRate else 60f

            val attrs = window.attributes
            val desiredModeId = chosen?.modeId ?: 0
            if (attrs.preferredDisplayModeId != desiredModeId || attrs.preferredRefreshRate != desiredRate) {
                attrs.preferredDisplayModeId = desiredModeId
                attrs.preferredRefreshRate = desiredRate
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    attrs.preferMinimalPostProcessing = highRefresh
                }
                window.attributes = attrs
            }

            setPowerSavingsBalanced(window, !highRefresh)
            applyToViewTree(window.decorView, if (highRefresh) desiredRate else 60f)
        }
    }

    private fun setPowerSavingsBalanced(window: Window, balanced: Boolean) {
        if (Build.VERSION.SDK_INT >= 35) {
            runCatching {
                val m = Window::class.java.getMethod("setFrameRatePowerSavingsBalanced", Boolean::class.javaPrimitiveType)
                m.invoke(window, balanced)
            }
        }
    }

    fun applyToViewTree(root: View?, rate: Float = targetRefreshRate) {
        if (root == null) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                runCatching {
                    val m = View::class.java.getMethod("setRequestedFrameRate", Float::class.javaPrimitiveType)
                    m.invoke(root, rate)
                }
            }
            if (root is SurfaceView) {
                applyToSurface(root.holder?.surface, rate)
            }
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    applyToViewTree(root.getChildAt(i), rate)
                }
            }
        }
    }

    fun applyToSurface(surface: Surface?, rate: Float = targetRefreshRate) {
        if (surface == null || !surface.isValid) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                    rate,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                surface.setFrameRate(
                    rate,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
                )
            }
        }
    }

    fun createFrameRateVoterSurfaceView(context: android.content.Context): SurfaceView {
        return SurfaceView(context).apply {
            setZOrderOnTop(false)
            alpha = 0f
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    applyToSurface(holder.surface, targetRefreshRate)
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    applyToSurface(holder.surface, targetRefreshRate)
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            })
        }
    }
}
