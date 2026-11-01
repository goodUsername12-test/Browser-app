package com.fable.browser.ui.browser

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fable.browser.engine.BrowserTab
import kotlinx.coroutines.delay
import org.mozilla.geckoview.GeckoView

class GeckoCaptureHandle {
    @Volatile var thumbnailWidth: Int = THUMBNAIL_WIDTH

    internal var view: GeckoView? = null

    fun capture(onDone: (Bitmap?) -> Unit) {
        val target = view
        if (target == null || target.session == null) {
            onDone(null)
            return
        }
        var finished = false
        val finish: (Bitmap?) -> Unit = { bitmap ->
            if (!finished) {
                finished = true
                onDone(bitmap)
            }
        }
        target.postDelayed({ finish(null) }, CAPTURE_TIMEOUT_MS)
        runCatching {
            target.capturePixels().accept(
                { bitmap -> finish(bitmap?.let { shrink(it) }) },
                { finish(null) }
            )
        }.onFailure { finish(null) }
    }

    private fun shrink(source: Bitmap): Bitmap? = runCatching {
        val width = thumbnailWidth.coerceAtMost(source.width)
        val height = (source.height * width.toFloat() / source.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        val compact = scaled.copy(Bitmap.Config.RGB_565, false) ?: scaled
        if (compact !== scaled) scaled.recycle()
        if (source !== scaled && source !== compact) source.recycle()
        compact
    }.getOrNull()

    private companion object {
        const val THUMBNAIL_WIDTH = 360
        const val CAPTURE_TIMEOUT_MS = 400L
    }
}

@Composable
fun GeckoViewHost(
    tab: BrowserTab,
    captureHandle: GeckoCaptureHandle,
    modifier: Modifier = Modifier
) {
    val session by tab.sessionFlow.collectAsStateWithLifecycle()
    var attachTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(session) {
        var attempts = 0
        while (!session.isOpen && attempts < 40) {
            delay(50)
            attempts++
        }
        attachTick++
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            GeckoView(ctx).apply {
                setAutofillEnabled(false)
                captureHandle.view = this
            }
        },
        update = { view ->
            captureHandle.view = view
            val target = session
            if (attachTick >= 0 && view.session !== target && target.isOpen) {
                if (view.session != null) view.releaseSession()
                view.setSession(target)
            }
        },
        onRelease = { view ->
            if (captureHandle.view === view) captureHandle.view = null
            if (view.session != null) view.releaseSession()
        }
    )
}
