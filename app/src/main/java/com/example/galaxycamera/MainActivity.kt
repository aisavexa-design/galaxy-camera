package com.example.galaxycamera

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.res.Configuration
import android.util.Size
import android.util.SparseIntArray
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity() {

    companion object {
        private const val CAMERA_PERMISSION = 1001
        private const val REQUEST_VIDEO_PERMISSION = 1002
        private const val PREFS = "galaxy_camera"
        private const val TAG = "GalaxyCamera"
    }

    private lateinit var root: FrameLayout
    private lateinit var textureView: TextureView
    private lateinit var cameraManager: CameraManager
    private lateinit var backgroundExecutor: ExecutorService
    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler
    private lateinit var processor: ImageProcessor

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var previewSize = Size(1920, 1080)
    private var cameraId: String = ""
    private var sensorRect: Rect? = null
    private var cameraFacing = CameraCharacteristics.LENS_FACING_BACK
    private var isUltraAvailable = false
    private var isRecording = false
    private var isOpening = false
    private var isNight = false
    private var isPortrait = false
    private var isMoreOpen = false
    private var isMenuOpen = false
    private var isProcessing = false
    private var isVideo4k = false

    private var flash = 0
    private var timer = 0
    private var square = false
    private var highres = false
    private var zoom = 1f
    private var mode = "PHOTO"
    private var volumeShutter = true
    private var debugEnabled = false

    private var recordingFile: File? = null
    private var mediaRecorder: MediaRecorder? = null
    private var recordingStartTime = 0L

    private var shutterView: ShutterView? = null
    private var zoomRulerView: ZoomRulerView? = null
    private var debugView: TextView? = null
    private var countdownView: TextView? = null
    private var moreCard: LinearLayout? = null
    private var menuCard: LinearLayout? = null
    private var currentZoomText: TextView? = null
    private var shutterButton: FrameLayout? = null
    private var modeStrip: LinearLayout? = null
    private var topBar: LinearLayout? = null

    private var countdownHandler: Handler? = null
    private var countdownRunnable: Runnable? = null
    private var zoomHideRunnable: Runnable? = null

    private var touchStartDistance = 0f
    private var touchStartZoom = 1f

    private val orientations = SparseIntArray().apply {
        append(Surface.ROTATION_0, 90)
        append(Surface.ROTATION_90, 0)
        append(Surface.ROTATION_180, 270)
        append(Surface.ROTATION_270, 180)
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            isOpening = false
            createPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
            isOpening = false
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            cameraDevice = null
            isOpening = false
            showToast("Camera error: $error")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility = 0

        backgroundExecutor = Executors.newSingleThreadExecutor()
        cameraThread = HandlerThread("GalaxyCameraThread").also { it.start() }
        cameraHandler = Handler(cameraThread.looper)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        processor = ImageProcessor(this) { tag, message, error ->
            android.util.Log.e(tag, message, error)
        }

        loadSettings()
        installCrashHandler()

        if (hasPreviousCrash()) {
            showCrashScreen()
            return
        }

        createInterface()

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                ),
                CAMERA_PERMISSION
            )
        } else {
            startCameraIfReady()
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        flash = prefs.getInt("flash", 0)
        timer = prefs.getInt("timer", 0)
        square = prefs.getBoolean("square", false)
        highres = prefs.getBoolean("highres", false)
        isNight = prefs.getBoolean("night", false)
        zoom = prefs.getFloat("zoom", 1f).coerceIn(1f, 10f)
        val ultra = prefs.getBoolean("ultra", false)
        cameraFacing = if (prefs.getBoolean("front", false)) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        mode = prefs.getString("mode", "PHOTO") ?: "PHOTO"
        isVideo4k = prefs.getBoolean("video4k", false)
        volumeShutter = prefs.getBoolean("set_volume_shutter", true)
        debugEnabled = prefs.getBoolean("set_debug", false)

        if (ultra) zoom = 1f
    }

    private fun saveSettings() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt("flash", flash)
            .putInt("timer", timer)
            .putBoolean("square", square)
            .putBoolean("highres", highres)
            .putBoolean("night", isNight)
            .putFloat("zoom", zoom)
            .putBoolean("ultra", false)
            .putBoolean("front", cameraFacing == CameraCharacteristics.LENS_FACING_FRONT)
            .putString("mode", mode)
            .putBoolean("video4k", isVideo4k)
            .apply()
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val file = File(getExternalFilesDir(null), "crash_log.txt")
                file.parentFile?.mkdirs()
                file.writeText(
                    "GalaxyCamera Crash\n" +
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            .format(Date()) +
                        "\n\n" +
                        android.util.Log.getStackTraceString(throwable)
                )
            } catch (_: Exception) {
            }

            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun hasPreviousCrash(): Boolean {
        val file = File(getExternalFilesDir(null), "crash_log.txt")
        return file.exists() && file.length() > 0
    }

    private fun showCrashScreen() {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 28, 28, 28)
        }

        val title = TextView(this).apply {
            text = "Galaxy Camera"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val message = TextView(this).apply {
            text = "Previous session crashed.\nCrash log available."
            textSize = 16f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 30)
        }

        val copy = createButton("COPY CRASH LOG") {
            val file = File(getExternalFilesDir(null), "crash_log.txt")
            val text = if (file.exists()) file.readText() else "No crash log"
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("Crash log", text)
            )
            showToast("Crash log copied")
        }

        val clear = createButton("CLEAR AND CONTINUE") {
            File(getExternalFilesDir(null), "crash_log.txt").delete()
            recreate()
        }

        layout.addView(title)
        layout.addView(message)
        layout.addView(copy)
        layout.addView(clear)

        root.addView(
            layout,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(root)
    }

    private fun createInterface() {
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        textureView = TextureView(this)
        root.addView(
            textureView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        textureView.surfaceTextureListener = object :
            TextureView.SurfaceTextureListener {

            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                startCameraIfReady()
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            }
        }

        createTopBar()
        createBottomControls()
        createZoomControls()
        createMoreCard()
        createMenuCard()
        createCountdown()
        createDebugOverlay()

        setContentView(root)
    }

    private fun createTopBar() {
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 12, 12, 12)
        }

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            64.dp()
        ).apply {
            gravity = Gravity.TOP
        }

        root.addView(topBar, params)

        addTopIcon("⚡") {
            flash = (flash + 1) % 3
            saveSettings()
            updatePreviewRepeating()
            updateTopBar()
        }

        addTopIcon("◷") {
            timer = when (timer) {
                0 -> 3
                3 -> 10
                else -> 0
            }
            saveSettings()
            updateTopBar()
        }

        addTopIcon(if (square) "1:1" else "3:4") {
            square = !square
            saveSettings()
            updateTopBar()
        }

        addTopIcon(if (highres) "3M" else "12M") {
            highres = !highres
            saveSettings()
            updateTopBar()
        }

        addTopIcon("⠿") {
            toggleMenu()
        }

        updateTopBar()
    }

    private fun addTopIcon(text: String, action: () -> Unit) {
        val view = IconView(this, text).apply {
            setOnClickListener { action() }
        }

        topBar?.addView(
            view,
            LinearLayout.LayoutParams(0, 46.dp(), 1f)
        )
    }

    private fun updateTopBar() {
        val bar = topBar ?: return
        if (bar.childCount < 5) return

        (bar.getChildAt(0) as? IconView)?.setLabel(
            when (flash) {
                0 -> "⚡"
                1 -> "⚡ ON"
                else -> "⚡ T"
            }
        )

        (bar.getChildAt(1) as? IconView)?.setLabel(
            if (timer == 0) "◷ OFF" else "◷ ${timer}s"
        )

        (bar.getChildAt(2) as? IconView)?.setLabel(
            if (square) "1:1" else "3:4"
        )

        (bar.getChildAt(3) as? IconView)?.setLabel(
            if (highres) "3M" else "12M"
        )
    }

    private fun createBottomControls() {
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(12, 8, 12, 18)
            setBackgroundColor(Color.argb(100, 0, 0, 0))
        }

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        root.addView(bottom, params)

        val galleryRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }

        val gallery = createButton("▣") {
            openGallery()
        }

        galleryRow.addView(
            gallery,
            LinearLayout.LayoutParams(60.dp(), 52.dp())
        )

        val spacer = View(this)
        galleryRow.addView(
            spacer,
            LinearLayout.LayoutParams(0, 1, 1f)
        )

        shutterButton = FrameLayout(this).apply {
            background = circularDrawable(Color.WHITE)
            setOnClickListener { onShutterPressed() }
        }

        shutterView = ShutterView(this)
        shutterButton?.addView(
            shutterView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        galleryRow.addView(
            shutterButton,
            LinearLayout.LayoutParams(76.dp(), 76.dp())
        )

        val spacer2 = View(this)
        galleryRow.addView(
            spacer2,
            LinearLayout.LayoutParams(0, 1, 1f)
        )

        val flip = createButton("↻") {
            flipCamera()
        }

        galleryRow.addView(
            flip,
            LinearLayout.LayoutParams(60.dp(), 52.dp())
        )

        bottom.addView(
            galleryRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                88.dp()
            )
        )

        modeStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 0)
        }

        bottom.addView(
            modeStrip,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                44.dp()
            )
        )

        listOf("FUN", "PORTRAIT", "PHOTO", "VIDEO", "MORE").forEach { item ->
            val text = TextView(this).apply {
                this.text = item
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(if (mode == item) Color.YELLOW else Color.WHITE)
                setPadding(8, 8, 8, 8)
                setOnClickListener {
                    when (item) {
                        "MORE" -> toggleMore()
                        "FUN" -> showToast("Next step")
                        else -> selectMode(item)
                    }
                }
            }

            modeStrip?.addView(
                text,
                LinearLayout.LayoutParams(0, 40.dp(), 1f)
            )
        }

        updateModeStrip()
    }

    private fun updateModeStrip() {
        val strip = modeStrip ?: return
        for (i in 0 until strip.childCount) {
            val view = strip.getChildAt(i) as TextView
            val selected = view.text.toString() == mode
            view.setTextColor(if (selected) Color.YELLOW else Color.WHITE)
        }

        shutterView?.invalidate()
    }

    private fun selectMode(selected: String) {
        if (isRecording) return

        mode = selected
        isPortrait = selected == "PORTRAIT"

        if (selected == "VIDEO") {
            shutterView?.setVideoMode(true)
        } else {
            shutterView?.setVideoMode(false)
        }

        saveSettings()
        updateModeStrip()
        updateTopBar()
    }

    private fun createZoomControls() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            78.dp()
        ).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = 175.dp()
        }

        currentZoomText = TextView(this).apply {
            text = "1.0x"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        zoomRulerView = ZoomRulerView(this).apply {
            alpha = 0f
        }

        container.addView(
            currentZoomText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                30.dp()
            )
        )

        container.addView(
            zoomRulerView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                36.dp()
            )
        )

        root.addView(container, params)

        val quickZoom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        listOf("0.5x", "1x", "2x").forEach { value ->
            val button = TextView(this).apply {
                text = value
                textSize = 13f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(12, 4, 12, 4)
                setOnClickListener {
                    when (value) {
                        "0.5x" -> setUltraZoom()
                        "1x" -> setZoom(1f)
                        "2x" -> setZoom(2f)
                    }
                }
            }

            quickZoom.addView(button)
        }

        val quickParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            38.dp()
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 250.dp()
        }

        root.addView(quickZoom, quickParams)

        textureView.setOnTouchListener { _, event ->
            when (event.pointerCount) {
                2 -> {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_POINTER_DOWN -> {
                            touchStartDistance = distance(event)
                            touchStartZoom = zoom
                        }

                        MotionEvent.ACTION_MOVE -> {
                            if (touchStartDistance > 0f) {
                                val scale = distance(event) / touchStartDistance
                                setZoom(
                                    (touchStartZoom * scale).coerceIn(1f, 10f),
                                    false
                                )
                            }
                        }

                        MotionEvent.ACTION_POINTER_UP -> {
                            touchStartDistance = 0f
                            saveSettings()
                        }
                    }
                    true
                }

                else -> false
            }
        }

        updateZoomUI()
    }

    private fun distance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun setZoom(value: Float, save: Boolean = true) {
        zoom = value.coerceIn(1f, 10f)
        updatePreviewRepeating()
        updateZoomUI()

        if (save) saveSettings()

        zoomRulerView?.animate()
            ?.alpha(1f)
            ?.setDuration(120)
            ?.start()

        zoomHideRunnable?.let { root.removeCallbacks(it) }

        zoomHideRunnable = Runnable {
            zoomRulerView?.animate()
                ?.alpha(0f)
                ?.setDuration(300)
                ?.start()
        }

        root.postDelayed(zoomHideRunnable!!, 1500)
    }

    private fun setUltraZoom() {
        if (!isUltraAvailable) {
            showToast("Ultra-wide camera not available")
            return
        }
        showToast("Ultra-wide camera switching is not available on this device configuration")
    }

    private fun updateZoomUI() {
        currentZoomText?.text = String.format(Locale.US, "%.1fx", zoom)
        zoomRulerView?.setZoom(zoom)
    }

    private fun createMoreCard() {
        moreCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 18, 16, 18)
            background = roundedDrawable(Color.rgb(25, 25, 25), 24f)
            alpha = 0f
            translationY = 500f
            visibility = View.GONE
        }

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            320.dp()
        ).apply {
            gravity = Gravity.BOTTOM
            setMargins(12, 0, 12, 12)
        }

        val title = TextView(this).apply {
            text = "MORE MODES"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 14)
        }

        moreCard?.addView(title)

        val items = listOf(
            "PRO", "PRO VIDEO", "NIGHT",
            "FOOD", "PANORAMA", "MACRO",
            "SUPER SLOW-MO", "SLOW MOTION", "HYPERLAPSE"
        )

        for (chunk in items.chunked(3)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            chunk.forEach { item ->
                val button = TextView(this).apply {
                    text = item
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setPadding(6, 12, 6, 12)
                    background = roundedDrawable(
                        Color.rgb(48, 48, 48),
                        12f
                    )
                    setOnClickListener {
                        if (item == "NIGHT") {
                            isNight = !isNight
                            saveSettings()
                            updatePreviewRepeating()
                            showToast(if (isNight) "Night mode ON" else "Night mode OFF")
                        } else {
                            showToast("Next step")
                        }
                    }
                }

                val lp = LinearLayout.LayoutParams(0, 58.dp(), 1f)
                lp.setMargins(4, 4, 4, 4)
                row.addView(button, lp)
            }

            moreCard?.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    66.dp()
                )
            )
        }

        root.addView(moreCard, params)
    }

    private fun toggleMore() {
        val card = moreCard ?: return

        if (isMoreOpen) {
            card.animate()
                .translationY(500f)
                .alpha(0f)
                .setDuration(250)
                .withEndAction {
                    card.visibility = View.GONE
                }
                .start()
        } else {
            card.visibility = View.VISIBLE
            card.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(300)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }

        isMoreOpen = !isMoreOpen
    }

    private fun createMenuCard() {
        menuCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(20, 16, 20, 16)
            background = roundedDrawable(Color.rgb(25, 25, 25), 18f)
            visibility = View.GONE
            alpha = 0f
        }

        val params = FrameLayout.LayoutParams(
            210.dp(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = 70.dp()
            marginEnd = 12.dp()
        }

        val items = listOf(
            "⚙ Settings",
            "⚡ Flash",
            "◷ Timer",
            "▣ Ratio",
            "▤ Resolution",
            "✕ Close"
        )

        items.forEach { item ->
            val view = TextView(this).apply {
                text = item
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(12, 13, 12, 13)
                setOnClickListener {
                    when (item) {
                        "⚙ Settings" -> {
                            try {
                                startActivity(
                                    Intent(
                                        this@MainActivity,
                                        SettingsActivity::class.java
                                    )
                                )
                            } catch (_: Exception) {
                                showToast("Settings unavailable")
                            }
                            toggleMenu()
                        }

                        "⚡ Flash" -> {
                            flash = (flash + 1) % 3
                            saveSettings()
                            updatePreviewRepeating()
                            updateTopBar()
                        }

                        "◷ Timer" -> {
                            timer = when (timer) {
                                0 -> 3
                                3 -> 10
                                else -> 0
                            }
                            saveSettings()
                            updateTopBar()
                        }

                        "▣ Ratio" -> {
                            square = !square
                            saveSettings()
                            updateTopBar()
                        }

                        "▤ Resolution" -> {
                            highres = !highres
                            saveSettings()
                            updateTopBar()
                        }

                        "✕ Close" -> toggleMenu()
                    }
                }
            }

            menuCard?.addView(
                view,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    45.dp()
                )
            )
        }

        root.addView(menuCard, params)
    }

    private fun toggleMenu() {
        val card = menuCard ?: return

        if (isMenuOpen) {
            card.animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction {
                    card.visibility = View.GONE
                }
                .start()
        } else {
            card.visibility = View.VISIBLE
            card.animate()
                .alpha(1f)
                .setDuration(180)
                .start()
        }

        isMenuOpen = !isMenuOpen
    }

    private fun createCountdown() {
        countdownView = TextView(this).apply {
            textSize = 64f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }

        root.addView(
            countdownView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun createDebugOverlay() {
        debugView = TextView(this).apply {
            textSize = 10f
            setTextColor(Color.RED)
            text = "GALAXY CAMERA DEBUG"
            setPadding(8, 4, 8, 4)
            visibility = if (debugEnabled) View.VISIBLE else View.GONE
        }

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = 66.dp()
        }

        root.addView(debugView, params)
    }

    private fun startCameraIfReady() {
        if (!::textureView.isInitialized) return
        if (!textureView.isAvailable) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (isOpening || cameraDevice != null) return

        openCamera()
    }

    private fun openCamera() {
        try {
            isOpening = true

            val ids = cameraManager.cameraIdList
            var selectedId: String? = null

            for (id in ids) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(
                    CameraCharacteristics.LENS_FACING
                )

                if (facing == cameraFacing) {
                    selectedId = id
                    break
                }
            }

            if (selectedId == null) {
                isOpening = false
                showToast("Camera not found")
                return
            }

            cameraId = selectedId

            val characteristics = cameraManager.getCameraCharacteristics(cameraId)

            sensorRect = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE
            )

            val focalLengths = characteristics.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            )

            isUltraAvailable = focalLengths?.size ?: 0 > 1

            val map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )

            previewSize = choosePreviewSize(map)

            cameraManager.openCamera(
                cameraId,
                cameraStateCallback,
                cameraHandler
            )
        } catch (e: SecurityException) {
            isOpening = false
            showToast("Camera permission required")
        } catch (e: CameraAccessException) {
            isOpening = false
            showToast("Unable to access camera")
        }
    }

    private fun choosePreviewSize(map: StreamConfigurationMap?): Size {
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java)
            ?: return Size(1920, 1080)

        return sizes.firstOrNull {
            it.width == 1920 && it.height == 1080
        } ?: sizes.maxByOrNull {
            it.width.toLong() * it.height
        } ?: Size(1920, 1080)
    }

    private fun createPreviewSession() {
        val camera = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: return

        try {
            texture.setDefaultBufferSize(
                previewSize.width,
                previewSize.height
            )

            val previewSurface = Surface(texture)

            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )

            val jpegSizes = map?.getOutputSizes(android.graphics.ImageFormat.JPEG)
            val imageSize = if (highres) {
                jpegSizes?.maxByOrNull { it.width.toLong() * it.height }
            } else {
                jpegSizes?.firstOrNull {
                    it.width <= 4000 && it.height <= 3000
                }
            } ?: Size(1920, 1080)

            imageReader?.close()
            imageReader = ImageReader.newInstance(
                imageSize.width,
                imageSize.height,
                android.graphics.ImageFormat.JPEG,
                2
            )

            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                backgroundExecutor.execute {
                    saveCapturedImage(image)
                }
            }, cameraHandler)

            val builder = camera.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            )

            builder.addTarget(previewSurface)
            previewRequestBuilder = builder

            val outputs = mutableListOf(previewSurface, imageReader!!.surface)

            camera.createCaptureSession(
                outputs,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return

                        captureSession = session

                        try {
                            applyCommonSettings(builder)
                            session.setRepeatingRequest(
                                builder.build(),
                                null,
                                cameraHandler
                            )
                        } catch (e: CameraAccessException) {
                            showToast("Preview error")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        showToast("Camera configuration failed")
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            showToast("Preview initialization failed")
        }
    }

    private fun applyCommonSettings(builder: CaptureRequest.Builder) {
        builder.set(
            CaptureRequest.CONTROL_MODE,
            CaptureRequest.CONTROL_MODE_AUTO
        )

        builder.set(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        )

        builder.set(
            CaptureRequest.CONTROL_AE_MODE,
            CaptureRequest.CONTROL_AE_MODE_ON
        )

        builder.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
        )

        val characteristics = try {
            cameraManager.getCameraCharacteristics(cameraId)
        } catch (_: Exception) {
            null
        }

        val maxZoom = characteristics?.get(
            CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM
        ) ?: 10f

        val actualZoom = zoom.coerceIn(
            1f,
            min(10f, maxZoom)
        )

        val rect = sensorRect

        if (rect != null) {
            val centerX = rect.centerX()
            val centerY = rect.centerY()

            val cropWidth = (rect.width() / actualZoom).roundToInt()
            val cropHeight = (rect.height() / actualZoom).roundToInt()

            val left = (centerX - cropWidth / 2).coerceAtLeast(rect.left)
            val top = (centerY - cropHeight / 2).coerceAtLeast(rect.top)

            builder.set(
                CaptureRequest.SCALER_CROP_REGION,
                Rect(
                    left,
                    top,
                    min(left + cropWidth, rect.right),
                    min(top + cropHeight, rect.bottom)
                )
            )
        }

        val compensationRange = characteristics?.get(
            CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE
        )

        val maxCompensation = compensationRange?.upper ?: 0

        builder.set(
            CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
            if (isNight) min(6, maxCompensation) else 0
        )

        builder.set(
            CaptureRequest.FLASH_MODE,
            if (flash == 2) {
                CaptureRequest.FLASH_MODE_TORCH
            } else {
                CaptureRequest.FLASH_MODE_OFF
            }
        )
    }

    private fun updatePreviewRepeating() {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return

        try {
            applyCommonSettings(builder)
            session.setRepeatingRequest(
                builder.build(),
                null,
                cameraHandler
            )
        } catch (_: Exception) {
        }
    }

    private fun onShutterPressed() {
        if (isProcessing) return

        if (mode == "VIDEO") {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
            return
        }

        if (mode != "PHOTO" && mode != "PORTRAIT") return

        if (timer > 0) {
            startCountdown(timer)
        } else {
            capturePhoto()
        }
    }

    private fun startCountdown(seconds: Int) {
        countdownHandler?.removeCallbacksAndMessages(null)

        countdownHandler = Handler(Looper.getMainLooper())
        var remaining = seconds

        countdownView?.visibility = View.VISIBLE
        countdownView?.text = remaining.toString()

        countdownRunnable = object : Runnable {
            override fun run() {
                if (remaining > 1) {
                    remaining--
                    countdownView?.text = remaining.toString()
                    countdownHandler?.postDelayed(this, 1000)
                } else {
                    countdownView?.visibility = View.GONE
                    capturePhoto()
                }
            }
        }

        countdownHandler?.postDelayed(countdownRunnable!!, 1000)
    }

    private fun capturePhoto() {
        if (isProcessing || cameraDevice == null) return

        val session = captureSession ?: return
        val reader = imageReader ?: return
        val camera = cameraDevice ?: return

        isProcessing = true

        try {
            val builder = camera.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE
            )

            builder.addTarget(reader.surface)

            builder.set(
                CaptureRequest.CONTROL_MODE,
                CaptureRequest.CONTROL_MODE_AUTO
            )

            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )

            builder.set(
                CaptureRequest.CONTROL_AE_MODE,
                if (flash == 1) {
                    CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                } else {
                    CaptureRequest.CONTROL_AE_MODE_ON
                }
            )

            if (flash == 1) {
                builder.set(
                    CaptureRequest.FLASH_MODE,
                    CaptureRequest.FLASH_MODE_SINGLE
                )
            }

            applyCommonSettings(builder)

            builder.set(
                CaptureRequest.JPEG_ORIENTATION,
                orientations.get(windowManager.defaultDisplay.rotation, 90)
            )

            session.capture(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: android.hardware.camera2.TotalCaptureResult
                    ) {
                        runOnUiThread {
                            shutterButton?.animate()
                                ?.scaleX(0.92f)
                                ?.scaleY(0.92f)
                                ?.setDuration(80)
                                ?.withEndAction {
                                    shutterButton?.animate()
                                        ?.scaleX(1f)
                                        ?.scaleY(1f)
                                        ?.setDuration(100)
                                        ?.start()
                                }
                                ?.start()
                        }
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: android.hardware.camera2.CaptureFailure
                    ) {
                        isProcessing = false
                        runOnUiThread {
                            showToast("Capture failed")
                        }
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            isProcessing = false
            showToast("Unable to capture photo")
        }
    }

    private fun saveCapturedImage(image: Image) {
        try {
            val timestamp = System.currentTimeMillis()
            val burstDir = File(
                getExternalFilesDir(null),
                "burst/$timestamp"
            )

            burstDir.mkdirs()

            val frame = File(burstDir, "frame_01.jpg")

            image.use {
                val buffer = it.planes[0].buffer
                FileOutputStream(frame).use { output ->
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    output.write(bytes)
                    output.flush()
                }
            }

            val output = File(
                burstDir,
                "processed_$timestamp.jpg"
            )

            processor.process(
                listOf(frame),
                output,
                mode == "PORTRAIT"
            )

            val finalFile = if (output.exists() && output.length() > 0) {
                output
            } else {
                frame
            }

            savePhotoToMediaStore(finalFile, timestamp, mode == "PORTRAIT")

            runOnUiThread {
                isProcessing = false
                showToast("Photo saved")
            }
        } catch (e: Exception) {
            runOnUiThread {
                isProcessing = false
                showToast("Photo processing failed: ${e.message}")
            }
        }
    }

    private fun savePhotoToMediaStore(
        file: File,
        timestamp: Long,
        portrait: Boolean
    ) {
        val suffix = if (portrait) "_portrait" else ""
        val filename = "GC_${timestamp}_enhanced$suffix.jpg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/GalaxyCamera"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = contentResolver
        val uri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: throw IOException("Unable to create MediaStore image")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: throw IOException("Unable to open image output")

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun startRecording() {
        if (cameraDevice == null) return

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_VIDEO_PERMISSION
            )
            return
        }

        backgroundExecutor.execute {
            try {
                prepareRecorder()
                runOnUiThread {
                    createRecordingSession()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showToast("Recording setup failed: ${e.message}")
                }
            }
        }
    }

    private fun prepareRecorder() {
        val recorder = if (Build.VERSION.SDK_INT >= 31) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mediaRecorder = recorder

        val timestamp = System.currentTimeMillis()
        val directory = File(
            getExternalFilesDir(null),
            "videos"
        )
        directory.mkdirs()

        recordingFile = File(
            directory,
            "GC_$timestamp.mp4"
        )

        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

        if (isVideo4k) {
            recorder.setVideoSize(3840, 2160)
            recorder.setVideoFrameRate(30)
            recorder.setVideoEncodingBitRate(40_000_000)
        } else {
            recorder.setVideoSize(1920, 1080)
            recorder.setVideoFrameRate(60)
            recorder.setVideoEncodingBitRate(16_000_000)
        }

        recorder.setAudioEncodingBitRate(128000)
        recorder.setAudioSamplingRate(44100)

        recorder.setOrientationHint(
            orientations.get(windowManager.defaultDisplay.rotation, 90)
        )

        recorder.setOutputFile(recordingFile!!.absolutePath)
        recorder.prepare()
    }

    private fun createRecordingSession() {
        val camera = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: return
        val recorder = mediaRecorder ?: return

        try {
            texture.setDefaultBufferSize(
                previewSize.width,
                previewSize.height
            )

            val previewSurface = Surface(texture)
            val recorderSurface = recorder.surface

            val builder = camera.createCaptureRequest(
                CameraDevice.TEMPLATE_RECORD
            )

            builder.addTarget(previewSurface)
            builder.addTarget(recorderSurface)

            builder.set(
                CaptureRequest.CONTROL_MODE,
                CaptureRequest.CONTROL_MODE_AUTO
            )

            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )

            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            )

            applyCommonSettings(builder)

            camera.createCaptureSession(
                listOf(previewSurface, recorderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session

                        try {
                            session.setRepeatingRequest(
                                builder.build(),
                                null,
                                cameraHandler
                            )

                            recorder.start()
                            isRecording = true
                            recordingStartTime = System.currentTimeMillis()

                            runOnUiThread {
                                shutterView?.setRecording(true)
                                showToast("Recording started")
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                showToast("Unable to start recording")
                            }
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        runOnUiThread {
                            showToast("Recording session failed")
                        }
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            showToast("Recording session error")
        }
    }

    private fun stopRecording() {
        if (!isRecording) return

        isRecording = false

        try {
            mediaRecorder?.stop()
        } catch (_: RuntimeException) {
            recordingFile?.delete()
        }

        mediaRecorder?.reset()
        mediaRecorder?.release()
        mediaRecorder = null

        val file = recordingFile
        recordingFile = null

        if (file != null && file.exists() && file.length() > 0) {
            backgroundExecutor.execute {
                try {
                    saveVideoToMediaStore(file)
                    runOnUiThread {
                        showToast("Video saved")
                        shutterView?.setRecording(false)
                        recreatePreviewAfterRecording()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        showToast("Unable to save video")
                    }
                }
            }
        } else {
            shutterView?.setRecording(false)
            recreatePreviewAfterRecording()
        }
    }

    private fun saveVideoToMediaStore(file: File) {
        val values = ContentValues().apply {
            put(
                MediaStore.Video.Media.DISPLAY_NAME,
                "GC_${System.currentTimeMillis()}.mp4"
            )
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MOVIES}/GalaxyCamera"
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val uri = contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: throw IOException("Unable to create video")

        try {
            contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: throw IOException("Unable to write video")

            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun recreatePreviewAfterRecording() {
        captureSession?.close()
        captureSession = null
        createPreviewSession()
    }

    private fun flipCamera() {
        if (isRecording || isProcessing) return

        root.animate()
            .rotationY(180f)
            .scaleX(0.92f)
            .setDuration(220)
            .withEndAction {
                cameraDevice?.close()
                cameraDevice = null
                captureSession?.close()
                captureSession = null

                cameraFacing = if (
                    cameraFacing == CameraCharacteristics.LENS_FACING_BACK
                ) {
                    CameraCharacteristics.LENS_FACING_FRONT
                } else {
                    CameraCharacteristics.LENS_FACING_BACK
                }

                saveSettings()

                root.rotationY = 0f
                root.scaleX = 1f

                startCameraIfReady()
            }
            .start()
    }

    private fun openGallery() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    "image/*"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        type = "image/*"
                    }
                )
            } catch (_: Exception) {
                showToast("Gallery not available")
            }
        }
    }

    private fun createButton(
        text: String,
        action: () -> Unit
    ): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setOnClickListener { action() }
        }
    }

    private fun circularDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun roundedDrawable(
        color: Int,
        radius: Float
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.dp().toFloat()
        }
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(
                this,
                message,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun Int.dp(): Int {
        return (this * resources.displayMetrics.density).roundToInt()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (volumeShutter &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == KeyEvent.KEYCODE_VOLUME_UP)
        ) {
            onShutterPressed()
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                startCameraIfReady()
            } else {
                showToast("Camera permission is required")
            }
        }

        if (requestCode == REQUEST_VIDEO_PERMISSION) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                startRecording()
            } else {
                showToast("Microphone permission required for video")
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (::textureView.isInitialized) {
            if (debugView != null) {
                debugView?.visibility =
                    if (debugEnabled) View.VISIBLE else View.GONE
            }
            startCameraIfReady()
        }
    }

    override fun onPause() {
        super.onPause()

        countdownHandler?.removeCallbacksAndMessages(null)

        if (isRecording) {
            stopRecording()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        countdownHandler?.removeCallbacksAndMessages(null)
        zoomHideRunnable?.let { root.removeCallbacks(it) }

        captureSession?.close()
        captureSession = null

        cameraDevice?.close()
        cameraDevice = null

        imageReader?.close()
        imageReader = null

        mediaRecorder?.release()
        mediaRecorder = null

        if (::cameraThread.isInitialized) {
            cameraThread.quitSafely()
            try {
                cameraThread.join(500)
            } catch (_: InterruptedException) {
            }
        }

        if (::backgroundExecutor.isInitialized) {
            backgroundExecutor.shutdown()
            try {
                backgroundExecutor.awaitTermination(2, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
            }
        }
    }

    private class ShutterView(context: Context) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var videoMode = false
        private var recording = false

        fun setVideoMode(enabled: Boolean) {
            videoMode = enabled
            invalidate()
        }

        fun setRecording(enabled: Boolean) {
            recording = enabled
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val cx = width / 2f
            val cy = height / 2f
            val radius = min(width, height) / 2f - 9f

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            paint.color = if (recording) Color.RED else Color.WHITE

            canvas.drawCircle(cx, cy, radius, paint)

            paint.style = Paint.Style.FILL

            if (recording) {
                paint.color = Color.RED
                val size = radius * 0.75f
                canvas.drawRoundRect(
                    cx - size / 2,
                    cy - size / 2,
                    cx + size / 2,
                    cy + size / 2,
                    5f,
                    5f,
                    paint
                )
            } else if (videoMode) {
                paint.color = Color.RED
                canvas.drawCircle(cx, cy, radius * 0.60f, paint)
            } else {
                paint.color = Color.WHITE
                canvas.drawCircle(cx, cy, radius * 0.72f, paint)
            }
        }
    }

    private class IconView(
        context: Context,
        private var label: String
    ) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun setLabel(value: String) {
            label = value
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            paint.color = Color.WHITE
            paint.textSize = 13f * resources.displayMetrics.density
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.DEFAULT_BOLD

            canvas.drawText(
                label,
                width / 2f,
                height / 2f + paint.textSize / 3f,
                paint
            )
        }
    }

    private class CircleView(context: Context) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        var circleColor: Int = Color.WHITE
            set(value) {
                field = value
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.color = circleColor
            paint.style = Paint.Style.FILL

            canvas.drawCircle(
                width / 2f,
                height / 2f,
                min(width, height) / 2f,
                paint
            )
        }
    }

    private class ZoomRulerView(context: Context) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var currentZoom = 1f

        private val values = floatArrayOf(
            0.5f,
            1f,
            2f,
            5f,
            10f
        )

        fun setZoom(value: Float) {
            currentZoom = value.coerceIn(1f, 10f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val centerY = height * 0.55f
            val left = width * 0.10f
            val right = width * 0.90f
            val usableWidth = right - left

            paint.color = Color.WHITE
            paint.strokeWidth = 2f
            paint.style = Paint.Style.STROKE

            canvas.drawLine(
                left,
                centerY,
                right,
                centerY,
                paint
            )

            for (value in values) {
                val position = (value - 0.5f) / 9.5f
                val x = left + usableWidth * position

                val tickHeight = if (value == currentZoom) 12f else 7f

                canvas.drawLine(
                    x,
                    centerY - tickHeight,
                    x,
                    centerY + tickHeight,
                    paint
                )

                paint.style = Paint.Style.FILL
                paint.textSize = 10f * resources.displayMetrics.density
                paint.textAlign = Paint.Align.CENTER

                canvas.drawText(
                    "${value}x",
                    x,
                    height - 2f,
                    paint
                )

                paint.style = Paint.Style.STROKE
            }

            val dotPosition = (currentZoom - 0.5f) / 9.5f
            val dotX = left + usableWidth * dotPosition

            paint.style = Paint.Style.FILL
            paint.color = Color.YELLOW

            canvas.drawCircle(
                dotX,
                centerY,
                5f,
                paint
            )
        }
    }
}
