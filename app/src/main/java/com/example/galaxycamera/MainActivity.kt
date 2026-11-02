package com.example.galaxycamera

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.YuvImage
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ln
import kotlin.math.min

class MainActivity : Activity() {

    private val modes = listOf("FUN", "PORTRAIT", "PHOTO", "VIDEO", "MORE")

    private lateinit var root: FrameLayout
    private lateinit var texture: TextureView
    private lateinit var toast: TextView
    private lateinit var countdown: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var zoomRuler: ZoomRulerView
    private lateinit var modeBar: LinearLayout
    private lateinit var shutter: ShutterView
    private lateinit var debugText: TextView
    private lateinit var zoomPill: LinearLayout
    private lateinit var dotsBar: LinearLayout
    private lateinit var iconFlash: IconView
    private lateinit var iconTimer: IconView
    private lateinit var iconRatio: IconView
    private lateinit var iconRes: IconView
    private lateinit var dotFlash: IconView
    private lateinit var dotTimer: IconView
    private lateinit var dotRatio: IconView
    private lateinit var dotRes: IconView
    private val modeViews = ArrayList<TextView>()
    private val zoomViews = ArrayList<TextView>()

    private var dotsVisible = false
    private var uiReady = false
    private var flashMode = 0
    private var timerSec = 0
    private var squareMode = false
    private var highRes = true
    private var nightOn = false
    private var zoomLevel = 1f
    private var activeMode = "PHOTO"
    private var video4k = false
    private var isFront = false

    private var rearId: String? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var currentChars: CameraCharacteristics? = null
    private var sensorOrientation = 90
    private var cameraOpening = false
    private var previewSize = Size(1440, 1080)

    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var bgExecutor: ExecutorService? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    private var burstDir: File? = null
    private var burstPortrait = false
    private val frameCount = AtomicInteger(0)
    private val capturing = AtomicBoolean(false)
    private val timerRunning = AtomicBoolean(false)

    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var isRecording = false
    private var recordFps = 60

    private var imageProcessor: ImageProcessor? = null
    private val hideZoomRunnable = Runnable {
        zoomLabel.animate().alpha(0f).setDuration(300).start()
        zoomRuler.animate().alpha(0f).setDuration(300).start()
    }

    // ---------------- Lifecycle ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler()
        val previous = readCrashLog()
        if (previous != null) {
            showErrorScreen(previous)
            return
        }
        loadPrefs()
        buildUi()
        uiReady = true
        updateUi()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 100)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!uiReady) return
        startBackground()
        if (texture.isAvailable) openCamera()
    }

    override fun onPause() {
        if (uiReady) {
            if (isRecording) stopRecording(false)
            closeCamera()
            stopBackground()
        }
        super.onPause()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 100 && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (texture.isAvailable) openCamera()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event?.repeatCount == 0 && uiReady && prefs().getBoolean("set_volume_shutter", true)) {
                onShutter()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------- Prefs ----------------

    private fun prefs() = getSharedPreferences("galaxy_camera", Context.MODE_PRIVATE)

    private fun loadPrefs() {
        val p = prefs()
        flashMode = p.getInt("flash", 0)
        timerSec = p.getInt("timer", 0)
        squareMode = p.getBoolean("square", false)
        highRes = p.getBoolean("highres", true)
        nightOn = p.getBoolean("night", false)
        zoomLevel = p.getFloat("zoom", 1f).coerceIn(1f, 10f)
        isFront = p.getBoolean("front", false)
        activeMode = p.getString("mode", "PHOTO") ?: "PHOTO"
        video4k = p.getBoolean("video4k", false)
    }

    private fun savePrefs() {
        prefs().edit()
            .putInt("flash", flashMode)
            .putInt("timer", timerSec)
            .putBoolean("square", squareMode)
            .putBoolean("highres", highRes)
            .putBoolean("night", nightOn)
            .putFloat("zoom", zoomLevel)
            .putBoolean("front", isFront)
            .putString("mode", activeMode)
            .putBoolean("video4k", video4k)
            .apply()
    }

    // ---------------- Crash log ----------------

    private fun logFile(): File = File(getExternalFilesDir(null) ?: filesDir, "crash_log.txt")

    private fun installCrashHandler() {
        val f = logFile()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                f.appendText("[CRASH " + t.name + "]\n" + Log.getStackTraceString(e) + "\n\n")
            } catch (ignored: Throwable) {
            }
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun readCrashLog(): String? = try {
        val f = logFile()
        if (f.exists() && f.length() > 0) f.readText() else null
    } catch (e: Throwable) {
        null
    }

    private fun logMsg(step: String, msg: String) {
        try {
            logFile().appendText("[" + step + "] " + msg + "\n")
        } catch (ignored: Throwable) {
        }
        runOnUiThread {
            if (prefs().getBoolean("set_debug", false)) {
                debugText.text = step + ": " + msg
                debugText.visibility = View.VISIBLE
            }
        }
    }

    private fun logError(step: String, e: Throwable) {
        logMsg(step, e.javaClass.simpleName + " " + (e.message ?: ""))
        try {
            logFile().appendText(Log.getStackTraceString(e) + "\n")
        } catch (ignored: Throwable) {
        }
    }

    private fun showErrorScreen(text: String) {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setBackgroundColor(Color.BLACK)
        layout.setPadding(24, 90, 24, 24)

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        val copyBtn = Button(this)
        copyBtn.text = "Copy"
        copyBtn.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("log", text))
        }
        val clearBtn = Button(this)
        clearBtn.text = "Clear and continue"
        clearBtn.setOnClickListener {
            try {
                logFile().delete()
            } catch (ignored: Throwable) {
            }
            recreate()
        }
        buttons.addView(copyBtn)
        buttons.addView(clearBtn)
        layout.addView(buttons)

        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(Color.WHITE)
        tv.textSize = 11f
        tv.setTextIsSelectable(true)
        val sv = ScrollView(this)
        sv.addView(tv)
        layout.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(layout)
    }

    // ---------------- UI ----------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundRect(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        texture = TextureView(this)
        texture.surfaceTextureListener = textureListener
        val pinch = ScaleGestureDetector(this, pinchListener)
        texture.setOnTouchListener { _, e ->
            pinch.onTouchEvent(e)
            true
        }
        root.addView(texture, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        debugText = TextView(this).apply {
            setTextColor(Color.parseColor("#FF9090"))
            textSize = 11f
            setBackgroundColor(Color.parseColor("#88000000"))
            visibility = View.GONE
        }
        root.addView(debugText, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(60)
        })

        // Top icon row: flash, timer, ratio, resolution
        val topIcons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        iconFlash = IconView(this, "flash")
        iconTimer = IconView(this, "timer")
        iconRatio = IconView(this, "ratio")
        iconRes = IconView(this, "res")
        iconFlash.setOnClickListener { cycleFlash() }
        iconTimer.setOnClickListener { cycleTimer() }
        iconRatio.setOnClickListener { toggleRatio() }
        iconRes.setOnClickListener { toggleResolution() }
        for (v in listOf(iconFlash, iconTimer, iconRatio, iconRes)) {
            topIcons.addView(v, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                leftMargin = dp(10)
                rightMargin = dp(10)
            })
        }
        root.addView(topIcons, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(40)
        })

        toast = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            alpha = 0f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(Color.parseColor("#88000000"))
        }
        root.addView(toast, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(120)
        })

        countdown = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 64f
            visibility = View.GONE
        }
        root.addView(countdown, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })

        zoomLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = roundRect(Color.parseColor("#88000000"), dp(16).toFloat())
            alpha = 0f
        }
        root.addView(zoomLabel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(560)
        })

        zoomRuler = ZoomRulerView(this)
        zoomRuler.alpha = 0f
        root.addView(zoomRuler, FrameLayout.LayoutParams(dp(300), dp(60)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(500)
        })

        // Zoom pills: .5x, 1x, 2x
        zoomPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundRect(Color.parseColor("#66333333"), dp(30).toFloat())
        }
        val zoomLabels = listOf(".5", "1x", "2")
        val zoomValues = listOf(0.5f, 1f, 2f)
        for (i in zoomLabels.indices) {
            val tv = TextView(this).apply {
                text = zoomLabels[i]
                textSize = 15f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setOnClickListener { setZoom(zoomValues[i]) }
            }
            zoomViews.add(tv)
            zoomPill.addView(tv, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        root.addView(zoomPill, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(290)
        })

        // 4-dots bar (gear, flash, timer, ratio, res, close)
        dotsBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundRect(Color.parseColor("#CC333333"), dp(30).toFloat())
            visibility = View.GONE
        }
        val gear = IconView(this, "gear")
        dotFlash = IconView(this, "flash")
        dotTimer = IconView(this, "timer")
        dotRatio = IconView(this, "ratio")
        dotRes = IconView(this, "res")
        val close = IconView(this, "close")
        gear.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        dotFlash.setOnClickListener { cycleFlash() }
        dotTimer.setOnClickListener { cycleTimer() }
        dotRatio.setOnClickListener { toggleRatio() }
        dotRes.setOnClickListener { toggleResolution() }
        close.setOnClickListener { hideDotsBar() }
        for (v in listOf(gear, dotFlash, dotTimer, dotRatio, dotRes, close)) {
            dotsBar.addView(v, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        root.addView(dotsBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(290)
        })

        // Button that opens the 4-dots bar (shows at the right of the zoom pill)
        val dotsBtn = IconView(this, "dots").apply {
            background = oval(Color.parseColor("#66333333"))
            setOnClickListener { toggleDotsBar() }
        }
        root.addView(dotsBtn, FrameLayout.LayoutParams(dp(44), dp(44)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = dp(24)
            bottomMargin = dp(290)
        })

        modeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (label in modes) {
            val tv = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 13f
                setOnClickListener { onModeClicked(label) }
            }
            modeViews.add(tv)
            modeBar.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(modeBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = dp(200)
        })

        shutter = ShutterView(this)
        shutter.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) onShutter()
            true
        }
        root.addView(shutter, FrameLayout.LayoutParams(dp(72), dp(72)).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(90)
        })

        val gallery = View(this).apply {
            background = roundRect(Color.DKGRAY, dp(30).toFloat())
            setOnClickListener { openGallery() }
        }
        root.addView(gallery, FrameLayout.LayoutParams(dp(60), dp(60)).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = dp(40)
            bottomMargin = dp(96)
        })

        val flip = View(this).apply {
            background = roundRect(Color.parseColor("#2B2B2B"), dp(30).toFloat())
            setOnClickListener { switchCamera() }
        }
        root.addView(flip, FrameLayout.LayoutParams(dp(60), dp(60)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = dp(40)
            bottomMargin = dp(96)
        })

        setContentView(root)
    }

    private fun toggleDotsBar() {
        if (dotsBar.visibility == View.VISIBLE) hideDotsBar() else showDotsBar()
    }

    private fun showDotsBar() {
        zoomPill.visibility = View.GONE
        dotsBar.visibility = View.VISIBLE
        dotsBar.alpha = 0f
        dotsBar.animate().alpha(1f).setDuration(200).start()
    }

    private fun hideDotsBar() {
        dotsBar.visibility = View.GONE
        zoomPill.visibility = View.VISIBLE
    }

    private fun updateUi() {
        if (!uiReady) return
        for (i in modes.indices) {
            val tv = modeViews[i]
            val selected = modes[i] == activeMode
            tv.setTextColor(if (selected) Color.WHITE else Color.parseColor("#888888"))
            tv.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val timerLabel = if (timerSec == 0) "" else timerSec.toString()
        val ratioLabel = if (squareMode) "1:1" else "3:4"
        val resLabel = if (activeMode == "VIDEO") {
            if (video4k) "4K30" else "FHD60"
        } else {
            if (highRes) "12M" else "3M"
        }
        for (v in listOf(iconFlash, dotFlash)) {
            v.state = flashMode
            v.invalidate()
        }
        for (v in listOf(iconTimer, dotTimer)) {
            v.label = timerLabel
            v.invalidate()
        }
        for (v in listOf(iconRatio, dotRatio)) {
            v.label = ratioLabel
            v.invalidate()
        }
        for (v in listOf(iconRes, dotRes)) {
            v.label = resLabel
            v.invalidate()
        }
        for (i in zoomViews.indices) {
            val z = listOf(0.5f, 1f, 2f)[i]
            val selected = abs(zoomLevel - z) < 0.05f
            zoomViews[i].background = if (selected) oval(Color.parseColor("#AA777777")) else null
            zoomViews[i].alpha = if (selected) 1f else 0.75f
        }
        shutter.videoMode = activeMode == "VIDEO"
        shutter.recording = isRecording
        shutter.invalidate()
    }

    private fun showToast(msg: String) {
        runOnUiThread {
            toast.text = msg
            toast.animate().cancel()
            toast.animate().alpha(1f).setDuration(150).withEndAction {
                uiHandler.postDelayed({ toast.animate().alpha(0f).setDuration(200).start() }, 1800)
            }.start()
        }
    }

    private fun openGallery() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setType("image/*"))
        } catch (e: Throwable) {
            showToast("No gallery app found")
        }
    }

    private fun onModeClicked(label: String) {
        when (label) {
            "PHOTO", "PORTRAIT", "VIDEO" -> {
                if (isRecording) {
                    showToast("Stop recording first")
                    return
                }
                activeMode = label
                savePrefs()
                updateUi()
                if (label == "PORTRAIT") showToast("Portrait: background blur on photo")
            }
            else -> showToast(label + " is in the next step")
        }
    }

    private fun onShutter() {
        if (!uiReady || timerRunning.get()) return
        if (timerSec == 0) {
            performShutter()
        } else {
            startCountdown(timerSec)
        }
    }

    private fun performShutter() {
        if (activeMode == "VIDEO") {
            if (isRecording) stopRecording(true) else startRecording()
        } else {
            captureStill()
        }
    }

    private fun startCountdown(seconds: Int) {
        timerRunning.set(true)
        var left = seconds
        val tick = object : Runnable {
            override fun run() {
                if (left <= 0) {
                    countdown.visibility = View.GONE
                    timerRunning.set(false)
                    performShutter()
                } else {
                    countdown.text = left.toString()
                    countdown.visibility = View.VISIBLE
                    left--
                    uiHandler.postDelayed(this, 1000L)
                }
            }
        }
        uiHandler.post(tick)
    }

    // ---------------- Top bar actions ----------------

    private fun cycleFlash() {
        flashMode = (flashMode + 1) % 3
        savePrefs()
        applyPreview()
        updateUi()
        showToast("Flash " + listOf("off", "on", "torch")[flashMode])
    }

    private fun cycleTimer() {
        timerSec = when (timerSec) {
            0 -> 3
            3 -> 10
            else -> 0
        }
        savePrefs()
        updateUi()
        showToast(if (timerSec == 0) "Timer off" else "Timer " + timerSec + "s")
    }

    private fun toggleRatio() {
        if (isRecording) return
        squareMode = !squareMode
        savePrefs()
        restartCamera()
    }

    private fun toggleResolution() {
        if (activeMode == "VIDEO") {
            video4k = !video4k
            savePrefs()
            updateUi()
            showToast(if (video4k) "Video: 4K 30fps" else "Video: 1080p 60fps")
            return
        }
        if (isRecording) return
        highRes = !highRes
        savePrefs()
        restartCamera()
    }

    private fun switchCamera() {
        if (isRecording) return
        isFront = !isFront
        zoomLevel = 1f
        savePrefs()
        restartCamera()
    }

    // ---------------- Zoom ----------------

    private fun setZoom(z: Float) {
        zoomLevel = z
        savePrefs()
        applyPreview()
        showZoomUi()
        updateUi()
    }

    private fun showZoomUi() {
        zoomLabel.text = String.format(Locale.US, "%.1fx", zoomLevel)
        zoomLabel.animate().cancel()
        zoomLabel.alpha = 1f
        zoomRuler.level = zoomLevel
        zoomRuler.invalidate()
        zoomRuler.animate().cancel()
        zoomRuler.alpha = 1f
        uiHandler.removeCallbacks(hideZoomRunnable)
        uiHandler.postDelayed(hideZoomRunnable, 1500L)
    }

    private val pinchListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomLevel = (zoomLevel * d.scaleFactor).coerceIn(1f, 10f)
            applyPreview()
            showZoomUi()
            updateUi()
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            savePrefs()
        }
    }

    // ---------------- Camera ----------------

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
            if (cameraDevice != null) createPreviewSession() else openCamera()
        }

        override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
    }

    private fun startBackground() {
        if (bgThread != null) return
        val t = HandlerThread("CameraBg")
        t.start()
        bgThread = t
        bgHandler = Handler(t.looper)
        bgExecutor = Executors.newSingleThreadExecutor()
    }

    private fun stopBackground() {
        bgThread?.quitSafely()
        try {
            bgThread?.join()
        } catch (ignored: InterruptedException) {
        }
        bgThread = null
        bgHandler = null
        bgExecutor?.shutdown()
        bgExecutor = null
    }

    private fun pickCameraId(): String {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        if (isFront) {
            for (id in manager.cameraIdList) {
                val c = manager.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) return id
            }
        }
        rearId = rearId ?: "0"
        return rearId!!
    }

    private fun openCamera() {
        if (!uiReady || cameraDevice != null || cameraOpening) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (bgHandler == null) startBackground()
        cameraOpening = true
        try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = pickCameraId()
            val chars = manager.getCameraCharacteristics(id)
            currentChars = chars
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            val aw = if (squareMode) 1 else 3
            val ah = if (squareMode) 1 else 4
            previewSize = pickSize(map?.getOutputSizes(SurfaceTexture::class.java), 1440, aw, ah, Size(1440, 1080))

            val maxW = if (highRes) 100000 else 2048
            val captureSize = pickSize(map?.getOutputSizes(ImageFormat.YUV_420_888), maxW, aw, ah, Size(2048, 1536))
            imageReader?.close()
            imageReader = ImageReader.newInstance(captureSize.width, captureSize.height, ImageFormat.YUV_420_888, 4).apply {
                setOnImageAvailableListener(onImageAvailable, bgHandler)
            }
            manager.openCamera(id, cameraCallback, bgHandler)
        } catch (e: Throwable) {
            cameraOpening = false
            logError("openCamera", e)
        }
    }

    private fun pickSize(sizes: Array<Size>?, maxW: Int, aw: Int, ah: Int, fallback: Size): Size {
        if (sizes == null) return fallback
        var best: Size? = null
        for (s in sizes) {
            if (s.width * ah != s.height * aw || s.width > maxW) continue
            val b = best
            if (b == null || s.width * s.height > b.width * b.height) best = s
        }
        return best ?: fallback
    }

    private val cameraCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraOpening = false
            cameraDevice = camera
            createPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraOpening = false
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraOpening = false
            camera.close()
            cameraDevice = null
            logMsg("camera", "open error " + error)
        }
    }

    private fun closeCamera() {
        try { captureSession?.close() } catch (ignored: Throwable) {}
        captureSession = null
        previewBuilder = null
        try { cameraDevice?.close() } catch (ignored: Throwable) {}
        cameraDevice = null
        try { imageReader?.close() } catch (ignored: Throwable) {}
        imageReader = null
        try { previewSurface?.release() } catch (ignored: Throwable) {}
        previewSurface = null
        cameraOpening = false
        capturing.set(false)
    }

    private fun restartCamera() {
        closeCamera()
        openCamera()
    }

    private fun applyCommon(b: CaptureRequest.Builder) {
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        val torch = flashMode == 2 && !isFront
        b.set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, nightEv())
        val chars = currentChars ?: return
        val arr = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val z = zoomLevel.coerceIn(1f, 10f)
        val cw = (arr.width() / z).toInt()
        val ch = (arr.height() / z).toInt()
        val l = arr.left + (arr.width() - cw) / 2
        val t = arr.top + (arr.height() - ch) / 2
        b.set(CaptureRequest.SCALER_CROP_REGION, Rect(l, t, l + cw, t + ch))
    }

    private fun nightEv(): Int {
        if (!nightOn) return 0
        val range = currentChars?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return 0
        return min(6, range.upper)
    }

    private fun createPreviewSession() {
        try {
            val device = cameraDevice ?: return
            val st = texture.surfaceTexture ?: return
            st.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewSurface?.release()
            val surface = Surface(st)
            previewSurface = surface
            val reader = imageReader ?: return

            val rec = if (isRecording) recorder else null
            val outputs = ArrayList<Surface>()
            outputs.add(surface)
            outputs.add(rec?.surface ?: reader.surface)

            val builder = device.createCaptureRequest(if (rec != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            if (rec != null) {
                builder.addTarget(rec.surface)
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(recordFps, recordFps))
                builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            }
            applyCommon(builder)
            previewBuilder = builder

            device.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) {
                        session.close()
                        return
                    }
                    captureSession = session
                    try {
                        session.setRepeatingRequest(builder.build(), null, bgHandler)
                        if (rec != null) rec.start()
                    } catch (e: Throwable) {
                        logError("repeating", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    logMsg("session", "configure failed")
                }
            }, bgHandler)
        } catch (e: Throwable) {
            logError("previewSession", e)
        }
    }

    private fun applyPreview() {
        val b = previewBuilder ?: return
        val s = captureSession ?: return
        try {
            applyCommon(b)
            s.setRepeatingRequest(b.build(), null, bgHandler)
        } catch (e: Throwable) {
            logError("applyPreview", e)
        }
    }

    // ---------------- Still capture ----------------

    private fun captureStill() {
        if (isRecording) return
        if (!capturing.compareAndSet(false, true)) return
        try {
            val device = cameraDevice
            val session = captureSession
            val reader = imageReader
            if (device == null || session == null || reader == null) {
                capturing.set(false)
                showToast("Camera not ready")
                return
            }
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(reader.surface)
            applyCommon(builder)
            if (flashMode == 1 && !isFront) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
            }

            val dir = File(getExternalFilesDir(null) ?: filesDir, "burst/" + System.currentTimeMillis())
            dir.mkdirs()
            burstDir = dir
            burstPortrait = activeMode == "PORTRAIT"
            frameCount.set(0)
            session.capture(builder.build(), null, bgHandler)
        } catch (e: Throwable) {
            capturing.set(false)
            logError("captureStill", e)
        }
    }

    private val onImageAvailable = ImageReader.OnImageAvailableListener { reader ->
        val image = try {
            reader.acquireNextImage()
        } catch (e: Throwable) {
            null
        } ?: return@OnImageAvailableListener

        val dir = burstDir
        val exec = bgExecutor
        if (dir == null || exec == null) {
            image.close()
            return@OnImageAvailableListener
        }
        val index = frameCount.incrementAndGet()
        val portrait = burstPortrait
        exec.execute {
            try {
                val file = File(dir, String.format(Locale.US, "frame_%02d.jpg", index))
                FileOutputStream(file).use { it.write(yuvToJpeg(image)) }
                applyExif(file)
                processFrames(dir, portrait)
            } catch (e: Throwable) {
                logError("saveFrame", e)
            } finally {
                image.close()
                capturing.set(false)
            }
        }
    }

    private fun processFrames(dir: File, portrait: Boolean) {
        try {
            val frames = dir.listFiles()?.filter { it.name.endsWith(".jpg") }?.sortedBy { it.name } ?: return
            if (frames.isEmpty()) return
            showToast(if (portrait) "Processing portrait..." else "Processing...")

            val enhDir = File(getExternalFilesDir(null) ?: filesDir, "enhanced")
            enhDir.mkdirs()
            val outName = "GC_" + System.currentTimeMillis() + (if (portrait) "_portrait" else "") + "_enhanced.jpg"
            val outFile = File(enhDir, outName)

            val processor = imageProcessor ?: ImageProcessor(applicationContext) { step, msg, t ->
                if (t != null) logError(step, t) else logMsg(step, msg)
            }.also { imageProcessor = it }
            val result = processor.process(frames, outFile, portrait)

            applyExif(result.file)
            saveToGallery(result.file.readBytes(), outName, "image/jpeg", "Pictures/GalaxyCamera", MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            showToast(if (result.usedFallback) "Saved sharpest frame" else "Enhanced photo saved")
        } catch (e: Throwable) {
            logError("process", e)
            showToast("Processing failed, see log")
        }
    }

    private fun yuvToJpeg(image: Image): ByteArray {
        val w = image.width
        val h = image.height
        val nv21 = ByteArray(w * h * 3 / 2)
        var pos = 0
        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer
        val yLine = ByteArray(yPlane.rowStride)
        for (row in 0 until h) {
            yBuf.position(row * yPlane.rowStride)
            yBuf.get(yLine, 0, min(yPlane.rowStride, yBuf.remaining()))
            System.arraycopy(yLine, 0, nv21, pos, w)
            pos += w
        }
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uLine = ByteArray(uPlane.rowStride)
        val vLine = ByteArray(vPlane.rowStride)
        for (row in 0 until h / 2) {
            uBuf.position(row * uPlane.rowStride)
            uBuf.get(uLine, 0, min(uPlane.rowStride, uBuf.remaining()))
            vBuf.position(row * vPlane.rowStride)
            vBuf.get(vLine, 0, min(vPlane.rowStride, vBuf.remaining()))
            for (col in 0 until w / 2) {
                nv21[pos++] = vLine[col * vPlane.pixelStride]
                nv21[pos++] = uLine[col * uPlane.pixelStride]
            }
        }
        val out = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 95, out)
        return out.toByteArray()
    }

    private fun applyExif(file: File) {
        try {
            val orientation = when (sensorOrientation) {
                90 -> ExifInterface.ORIENTATION_ROTATE_90
                180 -> ExifInterface.ORIENTATION_ROTATE_180
                270 -> ExifInterface.ORIENTATION_ROTATE_270
                else -> ExifInterface.ORIENTATION_NORMAL
            }
            val exif = ExifInterface(file.absolutePath)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            exif.saveAttributes()
        } catch (e: Throwable) {
            logError("exif", e)
        }
    }

    private fun saveToGallery(bytes: ByteArray, name: String, mime: String, folder: String, collection: android.net.Uri) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            }
            val uri = contentResolver.insert(collection, values) ?: return
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        } catch (e: Throwable) {
            logError("gallery", e)
        }
    }

    // ---------------- Video ----------------

    private fun startRecording() {
        if (isRecording) return
        val chars = currentChars ?: return
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val wantW = if (video4k) 3840 else 1920
        val wantH = if (video4k) 2160 else 1080
        val has4k = map?.getOutputSizes(MediaRecorder::class.java)?.any { it.width == wantW && it.height == wantH } ?: false
        val w = if (has4k || !video4k) wantW else 1920
        val h = if (has4k || !video4k) wantH else 1080
        val fps = if (video4k) 30 else 60

        val dir = File(getExternalFilesDir(null) ?: filesDir, "video")
        dir.mkdirs()
        val file = File(dir, "VID_" + System.currentTimeMillis() + ".mp4")
        try {
            val rec = MediaRecorder(this)
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setOutputFile(file.absolutePath)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoSize(w, h)
            rec.setVideoFrameRate(fps)
            rec.setVideoEncodingBitRate(if (w >= 3840) 50_000_000 else 25_000_000)
            rec.setOrientationHint(sensorOrientation)
            rec.prepare()
            recorder = rec
            recordFile = file
            recordFps = fps
            isRecording = true
            updateUi()
            restartCamera()
        } catch (e: Throwable) {
            logError("startRecording", e)
            recorder = null
            isRecording = false
            updateUi()
            showToast("Could not start video")
        }
    }

    private fun stopRecording(restartPreview: Boolean) {
        if (!isRecording) return
        isRecording = false
        try { captureSession?.close() } catch (ignored: Throwable) {}
        captureSession = null
        val rec = recorder
        recorder = null
        try { rec?.stop() } catch (e: Throwable) { logError("recorderStop", e) }
        try { rec?.release() } catch (ignored: Throwable) {}
        updateUi()
        val f = recordFile
        recordFile = null
        if (restartPreview) restartCamera()
        if (f != null && f.exists()) {
            bgExecutor?.execute {
                try {
                    saveToGallery(f.readBytes(), f.name, "video/mp4", "Movies/GalaxyCamera", MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                    showToast("Video saved to gallery")
                } catch (e: Throwable) {
                    logError("videoSave", e)
                }
            }
        }
    }

    // ---------------- Views ----------------

    private class ZoomRulerView(context: Context) : View(context) {
        var level = 1f
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 26f
            textAlign = Paint.Align.CENTER
        }
        private val marks = listOf(0.5f to "0.5x", 1f to "1x", 2f to "2x", 5f to "5x", 10f to "10x")

        private fun posOf(z: Float): Float {
            val lo = ln(0.5f)
            val hi = ln(10f)
            return ((ln(z.coerceIn(0.5f, 10f)) - lo) / (hi - lo)).coerceIn(0f, 1f)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val padX = 40f
            val y = height * 0.6f
            val left = padX
            val right = width - padX
            canvas.drawLine(left, y, right, y, linePaint)
            for ((value, label) in marks) {
                val x = left + posOf(value) * (right - left)
                canvas.drawLine(x, y - 12f, x, y + 12f, linePaint)
                canvas.drawText(label, x, y - 22f, textPaint)
            }
            val dx = left + posOf(level) * (right - left)
            canvas.drawCircle(dx, y, 14f, dotPaint)
        }
    }

    private class IconView(context: Context, private val kind: String) : View(context) {
        var state = 0
        var label = ""
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            val r = w * 0.34f
            p.strokeWidth = w * 0.055f
            p.color = Color.WHITE
            p.style = Paint.Style.STROKE

            when (kind) {
                "flash" -> {
                    val path = Path()
                    path.moveTo(cx + r * 0.3f, cy - r)
                    path.lineTo(cx - r * 0.6f, cy + r * 0.15f)
                    path.lineTo(cx - r * 0.02f, cy + r * 0.15f)
                    path.lineTo(cx - r * 0.3f, cy + r)
                    path.lineTo(cx + r * 0.6f, cy - r * 0.15f)
                    path.lineTo(cx + r * 0.02f, cy - r * 0.15f)
                    path.close()
                    if (state == 2) p.style = Paint.Style.FILL
                    canvas.drawPath(path, p)
                    p.style = Paint.Style.STROKE
                    if (state == 0) canvas.drawLine(cx - r, cy - r, cx + r, cy + r, p)
                }
                "timer" -> {
                    canvas.drawCircle(cx, cy, r, p)
                    if (label.isNotEmpty()) {
                        drawLabel(canvas, label, cx, cy, r * 1.1f)
                    } else {
                        canvas.drawLine(cx, cy, cx, cy - r * 0.6f, p)
                        canvas.drawLine(cx, cy, cx + r * 0.45f, cy, p)
                    }
                }
                "ratio", "res" -> drawLabel(canvas, label, cx, cy, h * 0.36f)
                "gear" -> {
                    canvas.drawCircle(cx, cy, r, p)
                    canvas.drawCircle(cx, cy, r * 0.35f, p)
                }
                "close" -> {
                    canvas.drawLine(cx - r, cy - r, cx + r, cy + r, p)
                    canvas.drawLine(cx + r, cy - r, cx - r, cy + r, p)
                }
                "dots" -> {
                    p.style = Paint.Style.FILL
                    for (dx in listOf(-0.35f, 0.35f)) {
                        for (dy in listOf(-0.35f, 0.35f)) {
                            canvas.drawCircle(cx + r * dx, cy + r * dy, r * 0.14f, p)
                        }
                    }
                    p.style = Paint.Style.STROKE
                }
            }
        }

        private fun drawLabel(canvas: Canvas, text: String, x: Float, y: Float, size: Float) {
            p.style = Paint.Style.FILL
            p.textSize = size
            p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.DEFAULT_BOLD
            val baseline = y - (p.descent() + p.ascent()) / 2f
            canvas.drawText(text, x, baseline, p)
            p.style = Paint.Style.STROKE
            p.textAlign = Paint.Align.LEFT
            p.typeface = Typeface.DEFAULT
        }
    }

    private class ShutterView(context: Context) : View(context) {
        var videoMode = false
        var recording = false
        private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F0524A") }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = width / 2f
            canvas.drawCircle(cx, cy, r, white)
            if (videoMode) {
                if (recording) {
                    val h = r * 0.45f
                    canvas.drawRect(cx - h, cy - h, cx + h, cy + h, red)
                } else {
                    canvas.drawCircle(cx, cy, r * 0.78f, red)
                }
            }
        }
    }
}
