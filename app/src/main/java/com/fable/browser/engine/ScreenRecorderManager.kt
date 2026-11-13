package com.fable.browser.engine

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.PixelCopy
import android.view.Window
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Choreographer
import android.view.Surface
import android.view.View
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class RecResolution(val label: String, val width: Int, val height: Int) {
    RES_1640_720("1640*720", 1640, 720),
    RES_800_480("800*480", 800, 480)
}

enum class RecBitrate(val label: String, val mbps: Int) {
    MBPS_50("50Mbps", 50),
    MBPS_32("32Mbps", 32),
    MBPS_24("24Mbps", 24),
    MBPS_16("16Mbps", 16),
    MBPS_8("8Mbps", 8),
    MBPS_6("6Mbps", 6),
    MBPS_4("4Mbps", 4),
    MBPS_1("1Mbps", 1)
}

enum class RecOrientation(val label: String, val ruLabel: String) {
    AUTO("Auto", "Авто"),
    PORTRAIT("Portrait", "Книжная"),
    LANDSCAPE("Landscape", "Альбомная")
}

enum class RecAudioSource(val label: String, val ruLabel: String) {
    SYSTEM("System sounds", "Системные звуки"),
    MIC("Microphone", "Микрофон"),
    SYSTEM_AND_MIC("System + Mic", "Системные звуки + Микрофон"),
    MUTE("No sound", "Без звука")
}

enum class RecFrameRate(val label: String, val fps: Int) {
    FPS_15("15fps", 15),
    FPS_24("24fps", 24),
    FPS_30("30fps", 30),
    FPS_48("48fps", 48),
    FPS_60("60fps", 60),
    FPS_90("90fps", 90),
    FPS_120("120fps", 120)
}

data class RecorderConfig(
    val resolution: RecResolution = RecResolution.RES_1640_720,
    val bitrate: RecBitrate = RecBitrate.MBPS_50,
    val orientation: RecOrientation = RecOrientation.LANDSCAPE,
    val audioSource: RecAudioSource = RecAudioSource.SYSTEM,
    val frameRate: RecFrameRate = RecFrameRate.FPS_60,
    val stopOnScreenLock: Boolean = true,
    val showTouches: Boolean = false,
    val showButtonPresses: Boolean = false,
    val zeroLagBlinkMode: Boolean = true
)

data class TouchIndicatorPoint(
    val id: Int,
    val x: Float,
    val y: Float
)

object ScreenRecorderManager {
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    private val _lastSavedFile = MutableStateFlow<String?>(null)
    val lastSavedFile: StateFlow<String?> = _lastSavedFile.asStateFlow()

    private val _activeTouches = MutableStateFlow<List<TouchIndicatorPoint>>(emptyList())
    val activeTouches: StateFlow<List<TouchIndicatorPoint>> = _activeTouches.asStateFlow()

    private val _lastButtonPress = MutableStateFlow<String?>(null)
    val lastButtonPress: StateFlow<String?> = _lastButtonPress.asStateFlow()

    private val running = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var activeGameViewRef: WeakReference<View>? = null
    private var videoThread: HandlerThread? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var captureBitmap: Bitmap? = null
    private val frameInFlight = AtomicBoolean(false)
    private var audioThread: HandlerThread? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false
    private val muxerLock = Any()
    private var outputFile: File? = null
    private var startElapsedRealtime = 0L
    private var screenOffReceiver: BroadcastReceiver? = null
    private var appContextRef: Context? = null
    private var activeRecordWidth = 1640
    private var activeRecordHeight = 720
    private var activeRecordFps = 60
    private var lastCapturedFrameNs = 0L
    private val touchDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val touchRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 40, 140, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running.get()) return
            val sec = ((SystemClock.elapsedRealtime() - startElapsedRealtime) / 1000L).coerceAtLeast(0L)
            _elapsedSeconds.value = sec
            mainHandler.postDelayed(this, 500L)
        }
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running.get() || virtualDisplay != null) return
            val minIntervalNs = 1_000_000_000L / activeRecordFps.coerceIn(15, 120)
            if (frameTimeNanos - lastCapturedFrameNs >= minIntervalNs - 1_200_000L) {
                lastCapturedFrameNs = frameTimeNanos
                renderDirectHardwareFrame()
            }
            if (running.get() && virtualDisplay == null) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    fun attachActiveGameView(view: View?) {
        activeGameViewRef = if (view != null) WeakReference(view) else null
    }

    fun onTouchEvent(points: List<TouchIndicatorPoint>, enabled: Boolean) {
        if (!enabled || !_isRecording.value) {
            if (_activeTouches.value.isNotEmpty()) _activeTouches.value = emptyList()
            return
        }
        _activeTouches.value = points
    }

    fun onButtonPress(label: String, enabled: Boolean) {
        if (!enabled || !_isRecording.value) return
        _lastButtonPress.value = label
        mainHandler.postDelayed({
            if (_lastButtonPress.value == label) _lastButtonPress.value = null
        }, 900L)
    }

    fun createCaptureIntent(context: Context): Intent? = runCatching {
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        mgr?.createScreenCaptureIntent()
    }.getOrNull()

    fun startWithProjectionResult(
        context: Context,
        resultCode: Int,
        data: Intent?,
        config: RecorderConfig,
        onStatus: (String) -> Unit = {}
    ) {
        val projection = if (resultCode == Activity.RESULT_OK && data != null) {
            runCatching {
                val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                mgr?.getMediaProjection(resultCode, data)
            }.getOrNull()
        } else null
        if (projection != null) {
            mediaProjection = projection
        }
        startInternal(context.applicationContext, projection, config, onStatus)
    }

    fun startDirectWindowRecording(
        context: Context,
        config: RecorderConfig,
        onStatus: (String) -> Unit = {}
    ) {
        val proj = if (config.zeroLagBlinkMode) null else mediaProjection
        startInternal(context.applicationContext, proj, config, onStatus)
    }

    private fun findWindow(ctx: Context?): Window? {
        var c = ctx
        while (c is ContextWrapper) {
            if (c is Activity) return c.window
            c = c.baseContext
        }
        return null
    }

    private fun drawOverlaysToSurfaceCanvas(canvas: Canvas, viewW: Int, viewH: Int) {
        val touches = _activeTouches.value
        if (touches.isNotEmpty() && viewW > 0 && viewH > 0) {
            val sx = activeRecordWidth.toFloat() / viewW.toFloat()
            val sy = activeRecordHeight.toFloat() / viewH.toFloat()
            for (pt in touches) {
                val cx = pt.x * sx
                val cy = pt.y * sy
                canvas.drawCircle(cx, cy, 18f, touchDotPaint)
                canvas.drawCircle(cx, cy, 20f, touchRingPaint)
            }
        }
    }

    private fun renderDirectHardwareFrame() {
        val surface = inputSurface ?: return
        if (!surface.isValid || !frameInFlight.compareAndSet(false, true)) return
        val targetView = activeGameViewRef?.get()
        if (targetView == null || targetView.width <= 0 || targetView.height <= 0) {
            frameInFlight.set(false)
            return
        }
        val viewW = targetView.width
        val viewH = targetView.height
        val cHandler = captureHandler
        val bmp = captureBitmap
        val win = findWindow(targetView.context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && win != null && cHandler != null && bmp != null && !bmp.isRecycled) {
            val loc = IntArray(2)
            targetView.getLocationInWindow(loc)
            val srcRect = Rect(loc[0], loc[1], loc[0] + viewW, loc[1] + viewH)
            val requested = runCatching {
                PixelCopy.request(
                    win,
                    srcRect,
                    bmp,
                    { copyResult ->
                        try {
                            if (copyResult == PixelCopy.SUCCESS && running.get() && surface.isValid) {
                                val canvas = surface.lockHardwareCanvas()
                                try {
                                    canvas.drawBitmap(bmp, 0f, 0f, null)
                                    drawOverlaysToSurfaceCanvas(canvas, viewW, viewH)
                                } finally {
                                    surface.unlockCanvasAndPost(canvas)
                                }
                            }
                        } catch (_: Throwable) {
                        } finally {
                            frameInFlight.set(false)
                        }
                    },
                    cHandler
                )
                true
            }.getOrDefault(false)
            if (requested) return
        }

        // Fallback when PixelCopy is unavailable
        try {
            val canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                surface.lockHardwareCanvas()
            } else {
                surface.lockCanvas(null)
            }
            try {
                canvas.drawColor(Color.BLACK)
                val sx = activeRecordWidth.toFloat() / viewW.toFloat()
                val sy = activeRecordHeight.toFloat() / viewH.toFloat()
                canvas.save()
                canvas.scale(sx, sy)
                targetView.draw(canvas)
                canvas.restore()
                drawOverlaysToSurfaceCanvas(canvas, viewW, viewH)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } catch (_: Throwable) {
        } finally {
            frameInFlight.set(false)
        }
    }

    private fun resolveDimensions(context: Context, config: RecorderConfig): Pair<Int, Int> {
        val baseLong = config.resolution.width
        val baseShort = config.resolution.height
        val isPortrait = when (config.orientation) {
            RecOrientation.PORTRAIT -> true
            RecOrientation.LANDSCAPE -> false
            RecOrientation.AUTO -> {
                val dm = context.resources.displayMetrics
                dm.heightPixels >= dm.widthPixels
            }
        }
        val rawW = if (isPortrait) baseShort else baseLong
        val rawH = if (isPortrait) baseLong else baseShort
        val w = (rawW / 16).coerceAtLeast(16) * 16
        val h = (rawH / 16).coerceAtLeast(16) * 16
        return w to h
    }

    private fun startInternal(
        appContext: Context,
        projection: MediaProjection?,
        config: RecorderConfig,
        onStatus: (String) -> Unit
    ) {
        if (running.getAndSet(true)) return
        appContextRef = appContext

        NativeFastEngine.setScreenRecording(true)
        NativeFastEngine.setGameBoost(true)

        val (width, height) = resolveDimensions(appContext, config)
        val requestedFps = config.frameRate.fps
        val requestedBitrate = config.bitrate.mbps * 1_000_000

        val outDir = File(
            appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: appContext.filesDir,
            "FableRecorder"
        ).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(outDir, "Fable_${width}x${height}_${requestedFps}fps_${stamp}.mp4")
        outputFile = outFile

        val vThread = HandlerThread("FableRecVideo", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        videoThread = vThread

        var activeFps = requestedFps
        var codec: MediaCodec? = null
        var surface: Surface? = null

        val tryRates = listOf(requestedFps, 60, 30).distinct()
        for (fpsCandidate in tryRates) {
            val attempt = runCatching {
                val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, requestedBitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fpsCandidate)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        setInteger(MediaFormat.KEY_PRIORITY, 1)
                        setFloat(MediaFormat.KEY_OPERATING_RATE, fpsCandidate.toFloat())
                    }
                }
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val s = c.createInputSurface()
                c.start()
                Triple(c, s, fpsCandidate)
            }.getOrNull()
            if (attempt != null) {
                codec = attempt.first
                surface = attempt.second
                activeFps = attempt.third
                break
            }
        }

        if (codec == null || surface == null) {
            running.set(false)
            NativeFastEngine.setScreenRecording(false)
            onStatus("Hardware encoder unavailable for ${width}x${height}")
            return
        }

        activeRecordWidth = width
        activeRecordHeight = height
        activeRecordFps = activeFps
        videoCodec = codec
        inputSurface = surface
        videoTrackIndex = -1
        audioTrackIndex = -1
        muxerStarted = false

        val createdMuxer = runCatching {
            MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }.getOrNull()
        if (createdMuxer == null) {
            cleanupLocked()
            running.set(false)
            NativeFastEngine.setScreenRecording(false)
            onStatus("Could not initialize MP4 muxer")
            return
        }
        muxer = createdMuxer

        if (projection != null && !config.zeroLagBlinkMode) {
            val density = appContext.resources.displayMetrics.densityDpi.coerceAtLeast(160)
            virtualDisplay = runCatching {
                projection.createVirtualDisplay(
                    "FableZeroLagRecorder",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    Handler(vThread.looper)
                )
            }.getOrNull()
        } else {
            virtualDisplay = null
        }

        val wantAudio = config.audioSource != RecAudioSource.MUTE &&
            (projection != null || config.audioSource == RecAudioSource.MIC || config.audioSource == RecAudioSource.SYSTEM_AND_MIC)
        if (wantAudio) {
            startAudioPipeline(projection, config.audioSource)
        }

        startVideoDrainLoop(vThread, codec, wantAudio)

        if (virtualDisplay == null) {
            frameInFlight.set(false)
            val cThread = HandlerThread("FableRecCapture", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
            captureThread = cThread
            val cHandler = Handler(cThread.looper)
            captureHandler = cHandler
            cHandler.post {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                NativeFastEngine.setScreenRecording(true)
            }
            captureBitmap = runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }.getOrNull()
            lastCapturedFrameNs = 0L
            mainHandler.post {
                renderDirectHardwareFrame()
                Choreographer.getInstance().postFrameCallback(frameCallback)
            }
        }

        if (config.stopOnScreenLock) {
            registerScreenOffReceiver(appContext)
        }

        startElapsedRealtime = SystemClock.elapsedRealtime()
        _elapsedSeconds.value = 0L
        _isRecording.value = true
        mainHandler.post(ticker)
        NativeFastEngine.setScreenRecording(true)
        val modeTag = if (virtualDisplay == null) "Blink Surface Direct" else "Screen Projection"
        onStatus("Recording ($modeTag) ${width}x${height} @ ${activeFps}fps (${config.bitrate.label})")
    }

    @SuppressLint("MissingPermission")
    private fun startAudioPipeline(
        projection: MediaProjection?,
        source: RecAudioSource
    ) {
        runCatching {
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_STEREO
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val record: AudioRecord? = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                projection != null &&
                (source == RecAudioSource.SYSTEM || source == RecAudioSource.SYSTEM_AND_MIC)
            ) {
                val playbackCfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                val fmt = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
                AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(minBuf * 2)
                    .setAudioPlaybackCaptureConfig(playbackCfg)
                    .build()
            } else if (source == RecAudioSource.MIC || source == RecAudioSource.SYSTEM_AND_MIC) {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
            } else {
                null
            }

            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                record?.release()
                return
            }

            val aCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            aCodec.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            aCodec.start()
            record.startRecording()

            audioRecord = record
            audioCodec = aCodec

            val aThread = HandlerThread("FableRecAudio", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
            audioThread = aThread
            Handler(aThread.looper).post {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                NativeFastEngine.setScreenRecording(true)
                val pcmBuf = ByteArray(4096)
                val info = MediaCodec.BufferInfo()
                while (running.get()) {
                    val read = runCatching { record.read(pcmBuf, 0, pcmBuf.size) }.getOrDefault(-1)
                    if (read > 0) {
                        val inIdx = runCatching { aCodec.dequeueInputBuffer(5_000) }.getOrDefault(-1)
                        if (inIdx >= 0) {
                            val inBuf = aCodec.getInputBuffer(inIdx)
                            inBuf?.clear()
                            inBuf?.put(pcmBuf, 0, read)
                            val ptsUs = System.nanoTime() / 1000L
                            runCatching { aCodec.queueInputBuffer(inIdx, 0, read, ptsUs, 0) }
                        }
                    }
                    while (true) {
                        val outIdx = runCatching { aCodec.dequeueOutputBuffer(info, 0) }.getOrDefault(MediaCodec.INFO_TRY_AGAIN_LATER)
                        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            synchronized(muxerLock) {
                                if (!muxerStarted) {
                                    audioTrackIndex = muxer?.addTrack(aCodec.outputFormat) ?: -1
                                    if (videoTrackIndex >= 0 && audioTrackIndex >= 0) {
                                        muxer?.start()
                                        muxerStarted = true
                                    }
                                }
                            }
                        } else if (outIdx >= 0) {
                            val outBuf = aCodec.getOutputBuffer(outIdx)
                            if (outBuf != null && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                                synchronized(muxerLock) {
                                    if (muxerStarted && audioTrackIndex >= 0) {
                                        outBuf.position(info.offset)
                                        outBuf.limit(info.offset + info.size)
                                        runCatching { muxer?.writeSampleData(audioTrackIndex, outBuf, info) }
                                    }
                                }
                            }
                            runCatching { aCodec.releaseOutputBuffer(outIdx, false) }
                        } else {
                            break
                        }
                    }
                }
            }
        }
    }

    private fun startVideoDrainLoop(
        vThread: HandlerThread,
        codec: MediaCodec,
        waitForAudio: Boolean
    ) {
        Handler(vThread.looper).post {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            NativeFastEngine.setScreenRecording(true)
            val info = MediaCodec.BufferInfo()
            val waitDeadline = SystemClock.elapsedRealtime() + 600L
            while (running.get()) {
                val outIdx = runCatching { codec.dequeueOutputBuffer(info, 10_000) }.getOrDefault(MediaCodec.INFO_TRY_AGAIN_LATER)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized(muxerLock) {
                        if (!muxerStarted) {
                            videoTrackIndex = muxer?.addTrack(codec.outputFormat) ?: -1
                            val audioReady = !waitForAudio || audioCodec == null || audioTrackIndex >= 0 || SystemClock.elapsedRealtime() >= waitDeadline
                            if (videoTrackIndex >= 0 && audioReady) {
                                muxer?.start()
                                muxerStarted = true
                            }
                        }
                    }
                } else if (outIdx >= 0) {
                    synchronized(muxerLock) {
                        if (!muxerStarted && videoTrackIndex >= 0 && (!waitForAudio || audioCodec == null || SystemClock.elapsedRealtime() >= waitDeadline)) {
                            muxer?.start()
                            muxerStarted = true
                        }
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && muxerStarted && videoTrackIndex >= 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            runCatching { muxer?.writeSampleData(videoTrackIndex, outBuf, info) }
                        }
                    }
                    runCatching { codec.releaseOutputBuffer(outIdx, false) }
                }
            }
        }
    }

    private fun registerScreenOffReceiver(context: Context) {
        unregisterScreenOffReceiver(context)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF && running.get()) {
                    stopRecording()
                }
            }
        }
        screenOffReceiver = receiver
        runCatching {
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        }
    }

    private fun unregisterScreenOffReceiver(context: Context?) {
        val r = screenOffReceiver ?: return
        screenOffReceiver = null
        runCatching { context?.unregisterReceiver(r) }
    }

    fun stopRecording(onSaved: (String?) -> Unit = {}) {
        if (!running.getAndSet(false)) {
            onSaved(null)
            return
        }
        mainHandler.removeCallbacks(ticker)
        mainHandler.post {
            runCatching { Choreographer.getInstance().removeFrameCallback(frameCallback) }
        }
        _isRecording.value = false
        _activeTouches.value = emptyList()
        _lastButtonPress.value = null
        val ctx = appContextRef
        unregisterScreenOffReceiver(ctx)

        val savedFile = outputFile
        cleanupLocked()
        NativeFastEngine.setScreenRecording(false)

        val finalPath = if (savedFile != null && savedFile.exists() && savedFile.length() > 0L) {
            publishToMediaStore(ctx, savedFile)
            savedFile.name
        } else {
            null
        }
        _lastSavedFile.value = finalPath
        onSaved(finalPath)
    }

    private fun publishToMediaStore(context: Context?, file: File) {
        if (context == null) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/FableRecorder")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { input -> input.copyTo(out, 64 * 1024) }
                    }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            } else {
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
            }
        }
    }

    private fun cleanupLocked() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { videoCodec?.signalEndOfInputStream() }
        runCatching { videoThread?.join(350) }
        runCatching { audioThread?.join(350) }
        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null
        runCatching {
            audioCodec?.stop()
            audioCodec?.release()
        }
        audioCodec = null
        runCatching {
            videoCodec?.stop()
            videoCodec?.release()
        }
        videoCodec = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        synchronized(muxerLock) {
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
            muxer = null
            muxerStarted = false
            videoTrackIndex = -1
            audioTrackIndex = -1
        }
        frameInFlight.set(false)
        runCatching { captureThread?.quitSafely() }
        captureThread = null
        captureHandler = null
        runCatching { captureBitmap?.recycle() }
        captureBitmap = null
        runCatching { videoThread?.quitSafely() }
        videoThread = null
        runCatching { audioThread?.quitSafely() }
        audioThread = null
    }
}
