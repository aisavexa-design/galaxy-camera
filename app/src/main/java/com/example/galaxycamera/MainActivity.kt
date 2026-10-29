package com.example.galaxycamera

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
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
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
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
import kotlin.math.min

class MainActivity : Activity() {

    private lateinit var rootView: FrameLayout
    private lateinit var textureView: TextureView
    private lateinit var toastText: TextView
    private lateinit var countdownText: TextView
    private lateinit var iconFlash: IconView
    private lateinit var iconTimer: IconView
    private lateinit var iconRatio: IconView
    private lateinit var iconRes: IconView
    private lateinit var moonView: IconView
    private val zoomViews = ArrayList<TextView>()
    private val modeViews = ArrayList<TextView>()
    private var debugText: TextView? = null
    private var uiReady = false
    private var screenWpx = 1080
    private var screenHpx = 2340

    // Settings (saved in SharedPreferences)
    private var flashMode = 0          // 0 off, 1 on at capture, 2 torch
    private var timerSec = 0           // 0, 3, 10
    private var squareMode = false     // false = 3:4, true = 1:1
    private var highRes = true         // true = 12M, false = 3M
    private var nightOn = false
    private var zoomLevel = 1f         // 1 or 2 (digital)
    private var useUltra = false       // true = 0.5x ultrawide
    private var isFront = false
    private var activeMode = "PHOTO"

    // Camera state
    private var rearId: String? = null
    private var frontId: String? = null
    private var ultraId: String? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var currentChars: CameraCharacteristics? = null
    private var cameraOpening = false
    private var useJpeg = false
    private var sensorOrientation = 90
    private var previewSize = Size(1440, 1080)
    private val framesPerBurst = 1

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var backgroundExecutor: ExecutorService? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    private var currentBurstDir: File? = null
    private var currentBurstTotal = 1
    private val burstFrameCount = AtomicInteger(0)
    private val burstInProgress = AtomicBoolean(false)
    private val burstId = AtomicInteger(0)
    private val timerRunning = AtomicBoolean(false)

    private var nv21Buffer: ByteArray? = null
    private val debugLines = ArrayList<String>()

    private var imageProcessor: ImageProcessor? = null
    private val processorLog: (String, String, Throwable?) -> Unit = { step, msg, t ->
        if (t != null) logError(step, t) else logMsg(step, msg)
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler()

        val previous = readPreviousLog()
        if (previous != null) {
            showErrorScreen(previous)
            return
        }

        loadPrefs()

        try {
            buildUi()
            uiReady = true
            updateAllIcons()
        } catch (e: Throwable) {
            logError("onCreate", e)
            showErrorScreen(Log.getStackTraceString(e))
            return
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 100)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!uiReady) return
        startBackgroundThread()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            showDebug("Camera permission needed")
            return
        }
        if (textureView.isAvailable) {
            openCamera()
        }
    }

    override fun onPause() {
        if (uiReady) {
            closeCamera()
            stopBackgroundThread()
        }
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            debugText?.visibility = View.GONE
            if (textureView.isAvailable) openCamera()
        }
    }

    // Volume keys take the photo, just like the shutter button.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event?.repeatCount == 0 && uiReady) {
                onShutter()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------
    // Settings persistence
    // ------------------------------------------------------------------

    private fun prefs(): SharedPreferences =
        getSharedPreferences("galaxy_camera", Context.MODE_PRIVATE)

    private fun loadPrefs() {
        val p = prefs()
        flashMode = p.getInt("flash", 0)
        timerSec = p.getInt("timer", 0)
        squareMode = p.getBoolean("square", false)
        highRes = p.getBoolean("highres", true)
        nightOn = p.getBoolean("night", false)
        zoomLevel = p.getFloat("zoom", 1f)
        useUltra = p.getBoolean("ultra", false)
        isFront = p.getBoolean("front", false)
        activeMode = p.getString("mode", "PHOTO") ?: "PHOTO"
    }

    private fun savePrefs() {
        prefs().edit()
            .putInt("flash", flashMode)
            .putInt("timer", timerSec)
            .putBoolean("square", squareMode)
            .putBoolean("highres", highRes)
            .putBoolean("night", nightOn)
            .putFloat("zoom", zoomLevel)
            .putBoolean("ultra", useUltra)
            .putBoolean("front", isFront)
            .putString("mode", activeMode)
            .apply()
    }

    // ------------------------------------------------------------------
    // Crash log and error screen
    // ------------------------------------------------------------------

    private fun logFile(): File {
        val dir = getExternalFilesDir(null) ?: filesDir
        return File(dir, "crash_log.txt")
    }

    private fun installCrashHandler() {
        val file = logFile()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                file.appendText("[CRASH on thread " + t.name + "]\n" + Log.getStackTraceString(e) + "\n\n")
            } catch (ex: Throwable) {
            }
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun readPreviousLog(): String? {
        return try {
            val f = logFile()
            if (f.exists() && f.length() > 0) f.readText() else null
        } catch (ex: Throwable) {
            null
        }
    }

    private fun logMsg(step: String, detail: String) {
        try {
            logFile().appendText("[" + step + "] " + detail + "\n")
        } catch (ex: Throwable) {
        }
        showDebug(step + ": " + detail)
    }

    private fun logError(step: String, e: Throwable) {
        try {
            logFile().appendText("[" + step + "] " + Log.getStackTraceString(e) + "\n")
        } catch (ex: Throwable) {
        }
        showDebug(step + ": " + e.javaClass.simpleName + " " + (e.message ?: ""))
    }

    private fun showDebug(text: String) {
        synchronized(debugLines) {
            debugLines.add(text)
            while (debugLines.size > 5) debugLines.removeAt(0)
        }
        runOnUiThread {
            val tv = debugText
            if (tv != null) {
                tv.text = synchronized(debugLines) { debugLines.joinToString("\n") }
                tv.visibility = View.VISIBLE
            }
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

        val continueBtn = Button(this)
        continueBtn.text = "Clear and continue"
        continueBtn.setOnClickListener {
            try {
                logFile().delete()
            } catch (ex: Throwable) {
            }
            recreate()
        }

        buttons.addView(copyBtn)
        buttons.addView(continueBtn)
        layout.addView(
            buttons,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(Color.WHITE)
        tv.textSize = 11f
        tv.setTextIsSelectable(true)

        val sv = ScrollView(this)
        sv.addView(tv)
        layout.addView(
            sv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(layout)
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private fun previewHeightPx(): Int =
        if (squareMode) screenWpx else screenWpx * 4 / 3

    private fun updatePreviewLayout() {
        val params = FrameLayout.LayoutParams(screenWpx, previewHeightPx())
        params.topMargin = (screenHpx * 0.081f).toInt()
        textureView.layoutParams = params
    }

    private fun buildUi() {
        try {
            window.insetsController?.hide(WindowInsets.Type.statusBars())
        } catch (e: Throwable) {
        }

        val metrics = getSystemService(WindowManager::class.java).currentWindowMetrics
        screenWpx = metrics.bounds.width()
        screenHpx = metrics.bounds.height()

        rootView = FrameLayout(this)
        rootView.setBackgroundColor(Color.BLACK)

        // Preview
        textureView = TextureView(this)
        textureView.surfaceTextureListener = surfaceTextureListener
        val textureParams = FrameLayout.LayoutParams(screenWpx, previewHeightPx())
        textureParams.topMargin = (screenHpx * 0.081f).toInt()
        rootView.addView(textureView, textureParams)

        // Toast and countdown
        toastText = TextView(this).apply {
            text = "Saved"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT
            textSize = 16f
            alpha = 0f
            gravity = Gravity.CENTER
            setPadding(32, 16, 32, 16)
            setBackgroundColor(Color.parseColor("#88000000"))
        }
        val toastParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = (screenHpx * 0.12f).toInt()
        }
        rootView.addView(toastText, toastParams)

        countdownText = TextView(this).apply {
            text = ""
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 64f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val countParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = (screenHpx * 0.4f).toInt()
        }
        rootView.addView(countdownText, countParams)

        // Top icon row: flash, timer, ratio, resolution, motion, effects, face
        val iconSize = (screenWpx * 0.075f).toInt()
        val fractions = listOf(0.1f, 0.233f, 0.367f, 0.5f, 0.633f, 0.767f, 0.9f)
        iconFlash = IconView(this, "flash")
        iconTimer = IconView(this, "timer")
        iconRatio = IconView(this, "ratio")
        iconRes = IconView(this, "res")
        val iconMotion = IconView(this, "motion")
        val iconEffects = IconView(this, "effects")
        val iconFace = IconView(this, "face")
        val topIcons = listOf(iconFlash, iconTimer, iconRatio, iconRes, iconMotion, iconEffects, iconFace)
        for (i in topIcons.indices) {
            val params = FrameLayout.LayoutParams(iconSize, iconSize)
            params.leftMargin = (screenWpx * fractions[i] - iconSize / 2f).toInt()
            params.topMargin = (screenHpx * 0.03f).toInt()
            rootView.addView(topIcons[i], params)
        }
        iconFlash.setOnClickListener { cycleFlash() }
        iconTimer.setOnClickListener { cycleTimer() }
        iconRatio.setOnClickListener { toggleRatio() }
        iconRes.setOnClickListener { toggleResolution() }
        iconMotion.setOnClickListener { showToast("Coming in next step") }
        iconEffects.setOnClickListener { showToast("Coming in next step") }
        iconFace.setOnClickListener { showToast("Coming in next step") }

        // Night (moon) button at the right of the preview
        moonView = IconView(this, "moon")
        moonView.background = oval(Color.parseColor("#66333333"))
        val moonSize = (screenWpx * 0.11f).toInt()
        val moonParams = FrameLayout.LayoutParams(moonSize, moonSize)
        moonParams.leftMargin = (screenWpx * 0.9f - moonSize / 2f).toInt()
        moonParams.topMargin = (screenHpx * 0.66f - moonSize / 2f).toInt()
        moonView.setOnClickListener { toggleNight() }
        rootView.addView(moonView, moonParams)

        // Zoom pill: .5 | 1x | 2
        val zoomPill = buildZoomPill()
        val zoomParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = (screenHpx * 0.704f).toInt()
        }
        rootView.addView(zoomPill, zoomParams)

        // 4-dots button
        val dotsView = IconView(this, "dots")
        dotsView.background = oval(Color.parseColor("#66333333"))
        val dotsSize = (screenWpx * 0.1f).toInt()
        val dotsParams = FrameLayout.LayoutParams(dotsSize, dotsSize)
        dotsParams.leftMargin = (screenWpx * 0.9f - dotsSize / 2f).toInt()
        dotsParams.topMargin = (screenHpx * 0.734f - dotsSize / 2f).toInt()
        dotsView.setOnClickListener { showToast("Coming in next step") }
        rootView.addView(dotsView, dotsParams)

        // Gallery (left), shutter (centre), flip (right)
        val shutterSize = (screenWpx * 0.178f).toInt()
        val shutterView = CircleView(this, Color.parseColor("#FAFAF7"))
        val shutterParams = FrameLayout.LayoutParams(shutterSize, shutterSize)
        shutterParams.leftMargin = (screenWpx * 0.5f - shutterSize / 2f).toInt()
        shutterParams.topMargin = (screenHpx * 0.829f - shutterSize / 2f).toInt()
        shutterView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(180)
                        .setInterpolator(OvershootInterpolator()).start()
                    onShutter()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(180)
                        .setInterpolator(OvershootInterpolator()).start()
                }
            }
            true
        }
        rootView.addView(shutterView, shutterParams)

        val gallerySize = (screenWpx * 0.13f).toInt()
        val galleryView = CircleView(this, Color.DKGRAY)
        val galleryParams = FrameLayout.LayoutParams(gallerySize, gallerySize)
        galleryParams.leftMargin = (screenWpx * 0.16f - gallerySize / 2f).toInt()
        galleryParams.topMargin = (screenHpx * 0.829f - gallerySize / 2f).toInt()
        galleryView.setOnClickListener { openGallery() }
        rootView.addView(galleryView, galleryParams)

        val flipSize = (screenWpx * 0.133f).toInt()
        val flipView = CircleView(this, Color.parseColor("#2B2B2B"))
        val flipParams = FrameLayout.LayoutParams(flipSize, flipSize)
        flipParams.leftMargin = (screenWpx * 0.84f - flipSize / 2f).toInt()
        flipParams.topMargin = (screenHpx * 0.829f - flipSize / 2f).toInt()
        flipView.setOnClickListener {
            flipView.animate().rotationBy(180f).setDuration(250)
                .setInterpolator(OvershootInterpolator()).start()
            switchCamera()
        }
        rootView.addView(flipView, flipParams)

        // Mode strip: FUN | PORTRAIT | PHOTO | VIDEO | MORE
        val modeStrip = buildModeStrip()
        val modeParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (screenHpx * 0.905f).toInt()
        }
        rootView.addView(modeStrip, modeParams)

        // Debug overlay
        val dbg = TextView(this).apply {
            setTextColor(Color.parseColor("#FF9090"))
            textSize = 11f
            setPadding(16, 8, 16, 8)
            setBackgroundColor(Color.parseColor("#88000000"))
            visibility = View.GONE
        }
        debugText = dbg
        val dbgParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        dbgParams.topMargin = (screenHpx * 0.085f).toInt()
        rootView.addView(dbg, dbgParams)

        setContentView(rootView)
    }

    private fun roundRect(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }

    private fun oval(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun buildZoomPill(): LinearLayout {
        val pill = LinearLayout(this)
        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER
        pill.background = roundRect(Color.parseColor("#66333333"), 400f)
        val cell = (screenWpx * 0.11f).toInt()
        val labels = listOf(".5", "1x", "2")
        val values = listOf(0.5f, 1f, 2f)
        for (i in labels.indices) {
            val tv = TextView(this)
            tv.text = labels[i]
            tv.textSize = 15f
            tv.setTextColor(Color.WHITE)
            tv.typeface = Typeface.DEFAULT_BOLD
            tv.gravity = Gravity.CENTER
            tv.layoutParams = LinearLayout.LayoutParams(cell, cell)
            val z = values[i]
            tv.setOnClickListener { setZoom(z) }
            zoomViews.add(tv)
            pill.addView(tv)
        }
        return pill
    }

    private fun buildModeStrip(): LinearLayout {
        val strip = LinearLayout(this)
        strip.orientation = LinearLayout.HORIZONTAL
        strip.gravity = Gravity.CENTER
        val labels = listOf("FUN", "PORTRAIT", "PHOTO", "VIDEO", "MORE")
        for (label in labels) {
            val tv = TextView(this)
            tv.text = label
            tv.gravity = Gravity.CENTER
            tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            tv.setOnClickListener { onModeClicked(label) }
            modeViews.add(tv)
            strip.addView(tv)
        }
        return strip
    }

    private fun updateAllIcons() {
        if (!uiReady) return
        iconFlash.state = flashMode
        iconFlash.invalidate()
        iconTimer.label = if (timerSec == 0) "" else timerSec.toString()
        iconTimer.invalidate()
        iconRatio.label = if (squareMode) "1:1" else "3:4"
        iconRatio.invalidate()
        iconRes.label = if (highRes) "12M" else "3M"
        iconRes.invalidate()
        moonView.state = if (nightOn) 1 else 0
        moonView.invalidate()
        updateZoomPill()
        updateModeStrip()
    }

    private fun updateZoomPill() {
        val sel = if (useUltra) 0 else if (zoomLevel > 1.5f) 2 else 1
        for (i in zoomViews.indices) {
            val tv = zoomViews[i]
            tv.background = if (i == sel) oval(Color.parseColor("#AA777777")) else null
            tv.alpha = if (i == sel) 1f else 0.75f
        }
    }

    private fun updateModeStrip() {
        for (tv in modeViews) {
            val selected = tv.text.toString() == activeMode
            tv.setTextColor(if (selected) Color.WHITE else Color.parseColor("#888888"))
            tv.textSize = if (selected) 15f else 13f
            tv.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private fun onModeClicked(label: String) {
        if (label == "PHOTO") {
            activeMode = label
            savePrefs()
        } else {
            showToast("Coming in next step")
        }
        updateModeStrip()
    }

    private fun showToast(message: String) {
        runOnUiThread {
            toastText.animate().cancel()
            toastText.text = message
            toastText.alpha = 0f
            toastText.animate().alpha(1f).setDuration(200).withEndAction {
                toastText.postDelayed({
                    toastText.animate().alpha(0f).setDuration(200).start()
                }, 2000)
            }.start()
        }
    }

    // ------------------------------------------------------------------
    // Icon actions (all working)
    // ------------------------------------------------------------------

    private fun cycleFlash() {
        flashMode = (flashMode + 1) % 3
        savePrefs()
        updateAllIcons()
        applyPreviewSettings()
    }

    private fun cycleTimer() {
        timerSec = when (timerSec) {
            0 -> 3
            3 -> 10
            else -> 0
        }
        savePrefs()
        updateAllIcons()
    }

    private fun toggleRatio() {
        squareMode = !squareMode
        savePrefs()
        updatePreviewLayout()
        updateAllIcons()
        restartCamera()
    }

    private fun toggleResolution() {
        highRes = !highRes
        savePrefs()
        updateAllIcons()
        restartCamera()
    }

    private fun toggleNight() {
        nightOn = !nightOn
        savePrefs()
        updateAllIcons()
        applyPreviewSettings()
        showToast(if (nightOn) "Night on" else "Night off")
    }

    private fun setZoom(z: Float) {
        pickCameraIds()
        if (z < 1f) {
            if (isFront || ultraId == null) {
                showToast("Ultra wide not available")
                return
            }
            useUltra = true
            zoomLevel = 1f
            savePrefs()
            updateAllIcons()
            restartCamera()
            return
        }
        if (useUltra) {
            useUltra = false
            zoomLevel = z
            savePrefs()
            updateAllIcons()
            restartCamera()
        } else {
            zoomLevel = z
            savePrefs()
            updateAllIcons()
            applyPreviewSettings()
        }
    }

    private fun switchCamera() {
        if (!uiReady) return
        pickCameraIds()
        if (frontId == null) {
            showToast("Front camera not found")
            return
        }
        isFront = !isFront
        useUltra = false
        zoomLevel = 1f
        savePrefs()
        updateAllIcons()
        restartCamera()
    }

    private fun openGallery() {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.type = "image/*"
            startActivity(intent)
        } catch (e: Throwable) {
            showToast("No gallery app found")
        }
    }

    // ------------------------------------------------------------------
    // Shutter with timer
    // ------------------------------------------------------------------

    private fun onShutter() {
        if (!uiReady) return
        if (timerRunning.get()) return
        if (timerSec == 0) {
            captureBurst()
        } else {
            startCountdown(timerSec)
        }
    }

    private fun startCountdown(seconds: Int) {
        timerRunning.set(true)
        var left = seconds
        val tick = object : Runnable {
            override fun run() {
                if (left <= 0) {
                    countdownText.visibility = View.GONE
                    timerRunning.set(false)
                    captureBurst()
                } else {
                    countdownText.text = left.toString()
                    countdownText.visibility = View.VISIBLE
                    left--
                    uiHandler.postDelayed(this, 1000L)
                }
            }
        }
        uiHandler.post(tick)
    }

    // ------------------------------------------------------------------
    // Background threads
    // ------------------------------------------------------------------

    private fun startBackgroundThread() {
        if (backgroundThread != null) return
        val thread = HandlerThread("CameraBackground")
        thread.start()
        backgroundThread = thread
        backgroundHandler = Handler(thread.looper)
        backgroundExecutor = Executors.newSingleThreadExecutor()
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
        } catch (e: InterruptedException) {
        }
        backgroundThread = null
        backgroundHandler = null
        backgroundExecutor?.shutdown()
        backgroundExecutor = null
    }

    // ------------------------------------------------------------------
    // Camera selection
    // ------------------------------------------------------------------

    // Finds rear (ID "0"), front, and ultrawide (smallest focal back camera).
    private fun pickCameraIds() {
        if (rearId != null) return
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var rearF = 99f
        var ultraF = 99f
        var ultraCandidate: String? = null
        try {
            for (id in manager.cameraIdList) {
                val c = manager.getCameraCharacteristics(id)
                val facing = c.get(CameraCharacteristics.LENS_FACING) ?: continue
                val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 99f
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    if (id == "0") {
                        rearId = id
                        rearF = f
                    } else if (f < ultraF) {
                        ultraF = f
                        ultraCandidate = id
                    }
                } else if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    if (frontId == null) frontId = id
                }
            }
        } catch (e: Throwable) {
            logError("pickCameraIds", e)
        }
        if (rearId == null) rearId = "0"
        if (ultraCandidate != null && ultraF < rearF * 0.9f) ultraId = ultraCandidate
    }

    // ------------------------------------------------------------------
    // Camera
    // ------------------------------------------------------------------

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            if (cameraDevice != null) {
                createCameraPreviewSession()
            } else {
                openCamera()
            }
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private fun chooseSize(sizes: Array<Size>?, maxW: Int, aw: Int, ah: Int, fallback: Size): Size {
        if (sizes == null || sizes.isEmpty()) return fallback
        var best: Size? = null
        for (s in sizes) {
            if (s.width * ah != s.height * aw) continue
            if (s.width > maxW) continue
            val current = best
            if (current == null || s.width * s.height > current.width * current.height) best = s
        }
        return best ?: fallback
    }

    private fun restartCamera() {
        closeCamera()
        openCamera()
    }

    private fun openCamera() {
        if (!uiReady) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (cameraDevice != null || cameraOpening) return
        if (backgroundHandler == null) startBackgroundThread()
        pickCameraIds()

        val fId = frontId
        val uId = ultraId
        val rId = rearId ?: "0"
        val id = if (isFront && fId != null) fId else if (useUltra && uId != null) uId else rId

        cameraOpening = true
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val chars = manager.getCameraCharacteristics(id)
            currentChars = chars
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            val aw = if (squareMode) 1 else 3
            val ah = if (squareMode) 1 else 4

            previewSize = chooseSize(
                map?.getOutputSizes(SurfaceTexture::class.java),
                1440,
                aw,
                ah,
                if (squareMode) Size(1080, 1080) else Size(1440, 1080)
            )

            val format = if (useJpeg) ImageFormat.JPEG else ImageFormat.YUV_420_888
            val maxW = if (highRes) 100000 else 2048
            val fallback = if (highRes) {
                Size(4080, if (squareMode) 4080 else 3060)
            } else {
                Size(2048, if (squareMode) 2048 else 1536)
            }
            val captureSize = chooseSize(map?.getOutputSizes(format), maxW, aw, ah, fallback)

            imageReader?.close()
            imageReader = ImageReader.newInstance(captureSize.width, captureSize.height, format, 12).apply {
                setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
            }
            manager.openCamera(id, stateCallback, backgroundHandler)
        } catch (e: Throwable) {
            cameraOpening = false
            logError("openCamera", e)
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraOpening = false
            if (!uiReady || backgroundThread == null) {
                camera.close()
                return
            }
            cameraDevice = camera
            createCameraPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraOpening = false
            camera.close()
            cameraDevice = null
            logMsg("camera", "disconnected")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraOpening = false
            camera.close()
            cameraDevice = null
            logMsg("camera", "open error code " + error)
        }
    }

    // Applies flash, torch, zoom crop and night exposure to any request builder.
    private fun applyCommon(b: CaptureRequest.Builder) {
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        val torch = flashMode == 2 && !isFront
        b.set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, nightEv())
        val chars = currentChars
        if (chars != null) applyCrop(b, chars)
    }

    private fun applyCrop(b: CaptureRequest.Builder, chars: CameraCharacteristics) {
        if (zoomLevel <= 1.01f || useUltra) return
        val arr = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val cw = (arr.width() / zoomLevel).toInt()
        val ch = (arr.height() / zoomLevel).toInt()
        val l = arr.left + (arr.width() - cw) / 2
        val t = arr.top + (arr.height() - ch) / 2
        b.set(CaptureRequest.SCALER_CROP_REGION, Rect(l, t, l + cw, t + ch))
    }

    private fun nightEv(): Int {
        if (!nightOn) return 0
        val range: Range<Int>? = currentChars?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        if (range == null) return 0
        return min(6, range.upper)
    }

    private fun createCameraPreviewSession() {
        try {
            val device = cameraDevice ?: return
            val reader = imageReader ?: return
            val texture = textureView.surfaceTexture
            if (texture == null) {
                showDebug("Preview surface not ready yet")
                return
            }
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewSurface?.release()
            val surface = Surface(texture)
            previewSurface = surface

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            applyCommon(builder)
            previewBuilder = builder

            val surfaces = listOf(surface, reader.surface)
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) {
                        session.close()
                        return
                    }
                    captureSession = session
                    try {
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                    } catch (e: Throwable) {
                        logError("setRepeatingRequest", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    logMsg("session", "configure failed (useJpeg=" + useJpeg + ")")
                    if (!useJpeg) {
                        useJpeg = true
                        backgroundHandler?.post {
                            closeCamera()
                            openCamera()
                        }
                    }
                }
            }, backgroundHandler)
        } catch (e: Throwable) {
            logError("createPreviewSession", e)
        }
    }

    // Re-sends the live preview request so flash, torch, zoom and night apply at once.
    private fun applyPreviewSettings() {
        val b = previewBuilder ?: return
        val s = captureSession ?: return
        try {
            applyCommon(b)
            s.setRepeatingRequest(b.build(), null, backgroundHandler)
        } catch (e: Throwable) {
            logError("applySettings", e)
        }
    }

    private val burstCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            logMsg("burst", "capture failed, reason " + failure.reason)
        }
    }

    private fun captureBurst() {
        if (!burstInProgress.compareAndSet(false, true)) return
        val id = burstId.incrementAndGet()
        try {
            val device = cameraDevice
            val session = captureSession
            val reader = imageReader
            if (device == null || session == null || reader == null) {
                burstInProgress.set(false)
                showDebug("Camera not ready")
                return
            }

            val total = framesPerBurst
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(reader.surface)
            applyCommon(builder)
            if (flashMode == 1 && !isFront) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
            }
            if (useJpeg) {
                builder.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                builder.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
            }

            val requests = ArrayList<CaptureRequest>()
            for (i in 0 until total) requests.add(builder.build())

            val timestamp = System.currentTimeMillis()
            val dir = File(getExternalFilesDir(null) ?: filesDir, "burst/" + timestamp)
            dir.mkdirs()
            currentBurstDir = dir
            currentBurstTotal = total
            burstFrameCount.set(0)

            session.captureBurst(requests, burstCallback, backgroundHandler)

            backgroundHandler?.postDelayed({
                if (burstId.get() == id) burstInProgress.set(false)
            }, 20000)
        } catch (e: Throwable) {
            burstInProgress.set(false)
            logError("captureBurst", e)
        }
    }

    // ------------------------------------------------------------------
    // Saving frames and processing
    // ------------------------------------------------------------------

    private val onImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image: Image? = try {
            reader.acquireNextImage()
        } catch (e: Exception) {
            null
        }
        if (image == null) return@OnImageAvailableListener

        val dir = currentBurstDir
        val total = currentBurstTotal
        val count = burstFrameCount.incrementAndGet()
        val executor = backgroundExecutor
        if (dir == null || count > total || executor == null) {
            image.close()
            return@OnImageAvailableListener
        }

        try {
            executor.execute {
                try {
                    saveFrame(image, count, dir)
                    if (count == total) {
                        processBurst(dir, total)
                    }
                } catch (e: Throwable) {
                    logError("saveFrame " + count, e)
                } finally {
                    try {
                        image.close()
                    } catch (e: Throwable) {
                    }
                    if (count == total) burstInProgress.set(false)
                }
            }
        } catch (e: Throwable) {
            image.close()
            burstInProgress.set(false)
            logError("executor", e)
        }
    }

    private fun saveFrame(image: Image, index: Int, dir: File) {
        val file = File(dir, String.format(Locale.US, "frame_%02d.jpg", index))
        if (image.format == ImageFormat.JPEG) {
            val buf = image.planes[0].buffer
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            FileOutputStream(file).use { it.write(bytes) }
        } else {
            val jpegBytes = yuvToJpeg(image)
            FileOutputStream(file).use { it.write(jpegBytes) }
        }
        applyExifOrientation(file)
    }

    private fun processBurst(dir: File, total: Int) {
        try {
            val frames = (1..total)
                .map { File(dir, String.format(Locale.US, "frame_%02d.jpg", it)) }
                .filter { it.exists() }
            if (frames.isEmpty()) {
                logMsg("process", "no frames found")
                return
            }
            showToast("Processing...")

            val enhDir = File(getExternalFilesDir(null) ?: filesDir, "enhanced")
            enhDir.mkdirs()
            val outName = "GC_" + System.currentTimeMillis() + "_enhanced.jpg"
            val outFile = File(enhDir, outName)

            val processor = imageProcessor ?: ImageProcessor(processorLog).also { imageProcessor = it }
            val result = processor.process(frames, outFile)

            applyExifOrientation(result.file)
            saveToGallery(result.file.readBytes(), outName)

            if (result.usedFallback) {
                showToast("Saved sharpest frame (enhancement failed)")
            } else {
                showToast("Enhanced photo saved")
            }
        } catch (e: Throwable) {
            logError("processBurst", e)
            showToast("Processing failed, see log")
        }
    }

    private fun yuvToJpeg(image: Image): ByteArray {
        val w = image.width
        val h = image.height
        val nv21Size = w * h * 3 / 2
        val nv21: ByteArray = nv21Buffer?.takeIf { it.size == nv21Size }
            ?: ByteArray(nv21Size).also { nv21Buffer = it }
        var pos = 0

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yLine = ByteArray(yRowStride)
        for (row in 0 until h) {
            yBuffer.position(row * yRowStride)
            yBuffer.get(yLine, 0, min(yRowStride, yBuffer.remaining()))
            System.arraycopy(yLine, 0, nv21, pos, w)
            pos += w
        }

        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        val uLine = ByteArray(uRowStride)
        val vLine = ByteArray(vRowStride)
        for (row in 0 until h / 2) {
            vBuffer.position(row * vRowStride)
            vBuffer.get(vLine, 0, min(vRowStride, vBuffer.remaining()))
            uBuffer.position(row * uRowStride)
            uBuffer.get(uLine, 0, min(uRowStride, uBuffer.remaining()))

            for (col in 0 until w / 2) {
                nv21[pos++] = vLine[col * vPixelStride]
                nv21[pos++] = uLine[col * uPixelStride]
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, w, h, null)
        val baos = ByteArrayOutputStream(w * h / 2)
        yuvImage.compressToJpeg(Rect(0, 0, w, h), 100, baos)
        return baos.toByteArray()
    }

    private fun applyExifOrientation(file: File) {
        try {
            val value = when (sensorOrientation) {
                90 -> ExifInterface.ORIENTATION_ROTATE_90
                180 -> ExifInterface.ORIENTATION_ROTATE_180
                270 -> ExifInterface.ORIENTATION_ROTATE_270
                else -> ExifInterface.ORIENTATION_NORMAL
            }
            val exif = ExifInterface(file.absolutePath)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, value.toString())
            exif.saveAttributes()
        } catch (e: Throwable) {
            logError("exif", e)
        }
    }

    private fun saveToGallery(jpegBytes: ByteArray, fileName: String) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GalaxyCamera")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(jpegBytes)
                }
            }
        } catch (e: Throwable) {
            logError("gallery", e)
        }
    }

    private fun closeCamera() {
        try {
            captureSession?.close()
        } catch (e: Throwable) {
        }
        captureSession = null
        previewBuilder = null
        try {
            cameraDevice?.close()
        } catch (e: Throwable) {
        }
        cameraDevice = null
        try {
            imageReader?.close()
        } catch (e: Throwable) {
        }
        imageReader = null
        try {
            previewSurface?.release()
        } catch (e: Throwable) {
        }
        previewSurface = null
        cameraOpening = false
        burstInProgress.set(false)
    }

    // ------------------------------------------------------------------
    // Drawn views
    // ------------------------------------------------------------------

    private class CircleView(context: Context, private val circleColor: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = circleColor
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = width / 2f
            canvas.drawCircle(radius, radius, radius, paint)
        }
    }

    // Line icons drawn with Canvas. state and label change how they look.
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
                "flash" -> drawBolt(canvas, cx, cy, r)
                "timer" -> {
                    canvas.drawCircle(cx, cy, r, p)
                    if (label.isNotEmpty()) {
                        drawText(canvas, label, cx, cy, r * 1.1f)
                    } else {
                        canvas.drawLine(cx, cy, cx, cy - r * 0.6f, p)
                        canvas.drawLine(cx, cy, cx + r * 0.45f, cy, p)
                    }
                }
                "ratio", "res" -> drawText(canvas, label, cx, cy, h * 0.36f)
                "motion" -> {
                    canvas.drawCircle(cx, cy, r, p)
                    p.style = Paint.Style.FILL
                    canvas.drawCircle(cx, cy, r * 0.22f, p)
                    p.style = Paint.Style.STROKE
                }
                "effects" -> {
                    canvas.drawCircle(cx - r * 0.3f, cy - r * 0.15f, r * 0.7f, p)
                    canvas.drawCircle(cx + r * 0.3f, cy + r * 0.15f, r * 0.7f, p)
                }
                "face" -> {
                    canvas.drawCircle(cx, cy, r, p)
                    p.style = Paint.Style.FILL
                    canvas.drawCircle(cx - r * 0.35f, cy - r * 0.2f, r * 0.1f, p)
                    canvas.drawCircle(cx + r * 0.35f, cy - r * 0.2f, r * 0.1f, p)
                    p.style = Paint.Style.STROKE
                    canvas.drawArc(
                        RectF(cx - r * 0.55f, cy - r * 0.2f, cx + r * 0.55f, cy + r * 0.6f),
                        20f, 140f, false, p
                    )
                }
                "moon" -> {
                    canvas.drawCircle(cx, cy, r, p)
                    if (state == 0) {
                        canvas.drawLine(cx - r, cy - r, cx + r, cy + r, p)
                    } else {
                        p.style = Paint.Style.FILL
                        canvas.drawCircle(cx, cy, r * 0.5f, p)
                        p.style = Paint.Style.STROKE
                    }
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

        private fun drawBolt(canvas: Canvas, cx: Float, cy: Float, r: Float) {
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

        private fun drawText(canvas: Canvas, text: String, x: Float, y: Float, size: Float) {
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
}
