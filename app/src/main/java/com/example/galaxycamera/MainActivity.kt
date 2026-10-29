package com.example.galaxycamera

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.YuvImage
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
import android.provider.MediaStore
import android.util.Log
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
    private var debugText: TextView? = null
    private var uiReady = false

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var cameraOpening = false
    private var useJpeg = false
    private var sensorOrientation = 90
    private var previewSize = Size(1440, 1080)

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var backgroundExecutor: ExecutorService? = null

    private var currentBurstDir: File? = null
    private val burstFrameCount = AtomicInteger(0)
    private val burstInProgress = AtomicBoolean(false)
    private val burstId = AtomicInteger(0)

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

        try {
            buildUi()
            uiReady = true
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
                captureBurst()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
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

    private fun buildUi() {
        try {
            window.insetsController?.hide(WindowInsets.Type.statusBars())
        } catch (e: Throwable) {
        }

        val metrics = getSystemService(WindowManager::class.java).currentWindowMetrics
        val screenW = metrics.bounds.width()
        val screenH = metrics.bounds.height()

        rootView = FrameLayout(this)
        rootView.setBackgroundColor(Color.BLACK)

        textureView = TextureView(this)
        textureView.surfaceTextureListener = surfaceTextureListener
        val previewH = screenW * 4 / 3
        val textureParams = FrameLayout.LayoutParams(screenW, previewH)
        textureParams.topMargin = (screenH * 0.081f).toInt()
        rootView.addView(textureView, textureParams)

        toastText = TextView(this).apply {
            text = "Saved 10 frames"
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
            topMargin = (screenH * 0.12f).toInt()
        }
        rootView.addView(toastText, toastParams)

        val shutterSize = (screenW * 0.178f).toInt()
        val shutterView = CircleView(this, Color.parseColor("#FAFAF7"))
        val shutterParams = FrameLayout.LayoutParams(shutterSize, shutterSize)
        shutterParams.leftMargin = (screenW * 0.5f - shutterSize / 2f).toInt()
        shutterParams.topMargin = (screenH * 0.829f - shutterSize / 2f).toInt()

        shutterView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(180)
                        .setInterpolator(OvershootInterpolator()).start()
                    captureBurst()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(180)
                        .setInterpolator(OvershootInterpolator()).start()
                }
            }
            true
        }
        rootView.addView(shutterView, shutterParams)

        val gallerySize = (screenW * 0.13f).toInt()
        val galleryView = CircleView(this, Color.DKGRAY)
        val galleryParams = FrameLayout.LayoutParams(gallerySize, gallerySize)
        galleryParams.leftMargin = (screenW * 0.16f - gallerySize / 2f).toInt()
        galleryParams.topMargin = (screenH * 0.829f - gallerySize / 2f).toInt()
        rootView.addView(galleryView, galleryParams)

        val flipSize = (screenW * 0.133f).toInt()
        val flipView = CircleView(this, Color.parseColor("#2B2B2B"))
        val flipParams = FrameLayout.LayoutParams(flipSize, flipSize)
        flipParams.leftMargin = (screenW * 0.84f - flipSize / 2f).toInt()
        flipParams.topMargin = (screenH * 0.829f - flipSize / 2f).toInt()
        rootView.addView(flipView, flipParams)

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
        dbgParams.topMargin = (screenH * 0.085f).toInt()
        rootView.addView(dbg, dbgParams)

        setContentView(rootView)
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

    private fun chooseSize(sizes: Array<Size>?, maxW: Int, fallback: Size): Size {
        if (sizes == null || sizes.isEmpty()) return fallback
        var best: Size? = null
        for (s in sizes) {
            if (s.width * 3 != s.height * 4) continue
            if (s.width > maxW) continue
            val current = best
            if (current == null || s.width * s.height > current.width * current.height) best = s
        }
        return best ?: fallback
    }

    private fun openCamera() {
        if (!uiReady) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (cameraDevice != null || cameraOpening) return
        if (backgroundHandler == null) startBackgroundThread()

        cameraOpening = true
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val chars = manager.getCameraCharacteristics("0")
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            previewSize = chooseSize(
                map?.getOutputSizes(SurfaceTexture::class.java), 1440, Size(1440, 1080)
            )
            val format = if (useJpeg) ImageFormat.JPEG else ImageFormat.YUV_420_888
            val captureSize = chooseSize(map?.getOutputSizes(format), 100000, Size(4080, 3060))

            imageReader?.close()
            imageReader = ImageReader.newInstance(captureSize.width, captureSize.height, format, 12).apply {
                setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
            }
            manager.openCamera("0", stateCallback, backgroundHandler)
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
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

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

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(reader.surface)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            if (useJpeg) {
                builder.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                builder.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
            }

            val requests = ArrayList<CaptureRequest>()
            for (i in 0 until 10) requests.add(builder.build())

            val timestamp = System.currentTimeMillis()
            val dir = File(getExternalFilesDir(null) ?: filesDir, "burst/" + timestamp)
            dir.mkdirs()
            currentBurstDir = dir
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
    // Saving frames and Stage 2 processing
    // ------------------------------------------------------------------

    private val onImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image: Image? = try {
            reader.acquireNextImage()
        } catch (e: Exception) {
            null
        }
        if (image == null) return@OnImageAvailableListener

        val dir = currentBurstDir
        val count = burstFrameCount.incrementAndGet()
        val executor = backgroundExecutor
        if (dir == null || count > 10 || executor == null) {
            image.close()
            return@OnImageAvailableListener
        }

        try {
            executor.execute {
                try {
                    saveFrame(image, count, dir)
                    if (count == 10) {
                        processBurst(dir)
                    }
                } catch (e: Throwable) {
                    logError("saveFrame " + count, e)
                } finally {
                    try {
                        image.close()
                    } catch (e: Throwable) {
                    }
                    if (count == 10) burstInProgress.set(false)
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

    private fun processBurst(dir: File) {
        try {
            val frames = (1..10)
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
}
