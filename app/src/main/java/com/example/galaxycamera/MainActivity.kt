package com.example.galaxycamera

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

class MainActivity : Activity() {

    private lateinit var rootView: FrameLayout
    private lateinit var textureView: TextureView
    private lateinit var toastText: TextView

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var backgroundExecutor: ExecutorService? = null

    private var currentBurstDir: File? = null
    private val burstFrameCount = AtomicInteger(0)
    
    private var nv21Buffer: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        window.insetsController?.hide(WindowInsets.Type.statusBars())
        
        val metrics = getSystemService(WindowManager::class.java).currentWindowMetrics
        val screenW = metrics.bounds.width()
        val screenH = metrics.bounds.height()

        rootView = FrameLayout(this)
        rootView.setBackgroundColor(Color.BLACK)

        textureView = TextureView(this)
        textureView.surfaceTextureListener = surfaceTextureListener
        val previewW = screenW
        val previewH = (screenW * 4 / 3)
        val textureParams = FrameLayout.LayoutParams(previewW, previewH)
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

        setContentView(rootView)

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 100)
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
        backgroundExecutor = Executors.newSingleThreadExecutor()
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        backgroundExecutor?.shutdown()
        backgroundExecutor = null
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
            openCamera()
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, 1440f, 1080f)
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        matrix.postRotate(90f, centerX, centerY)
        textureView.setTransform(matrix)
    }

    private fun openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            imageReader = ImageReader.newInstance(4080, 3060, ImageFormat.YUV_420_888, 12).apply {
                setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
            }
            manager.openCamera("0", stateCallback, backgroundHandler)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCameraPreviewSession()
        }
        override fun onDisconnected(camera: CameraDevice) {
            cameraDevice?.close()
            cameraDevice = null
        }
        override fun onError(camera: CameraDevice, error: Int) {
            cameraDevice?.close()
            cameraDevice = null
        }
    }

    private fun createCameraPreviewSession() {
        try {
            val texture = textureView.surfaceTexture
            texture?.setDefaultBufferSize(1440, 1080)
            val surface = Surface(texture)

            val previewRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder?.addTarget(surface)

            val surfaces = listOf(surface, imageReader!!.surface)

            cameraDevice?.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    try {
                        previewRequestBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        previewRequestBuilder?.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        captureSession?.setRepeatingRequest(previewRequestBuilder!!.build(), null, backgroundHandler)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun captureBurst() {
        try {
            val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE) ?: return
            builder.addTarget(imageReader!!.surface)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

            val burstRequests = (0 until 10).map { builder.build() }
            
            val timestamp = System.currentTimeMillis()
            currentBurstDir = File(getExternalFilesDir(null), "burst/$timestamp").apply { mkdirs() }
            burstFrameCount.set(0)

            captureSession?.captureBurst(burstRequests, null, backgroundHandler)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val onImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = try {
            reader.acquireNextImage()
        } catch (e: Exception) {
            null
        } ?: return@OnImageAvailableListener

        val count = burstFrameCount.incrementAndGet()
        if (count > 10) {
            image.close()
            return@OnImageAvailableListener
        }

        val dir = currentBurstDir ?: return@OnImageAvailableListener

        backgroundExecutor?.execute {
            try {
                processAndSaveImage(image, count, dir)
                if (count == 10) {
                    runOnUiThread {
                        toastText.animate().alpha(1f).setDuration(200).withEndAction {
                            toastText.postDelayed({
                                toastText.animate().alpha(0f).setDuration(200).start()
                            }, 2000)
                        }.start()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                image.close()
            }
        }
    }

    private fun processAndSaveImage(image: Image, index: Int, burstDir: File) {
        val w = image.width
        val h = image.height
        val nv21Size = w * h * 3 / 2

        if (nv21Buffer == null || nv21Buffer!!.size != nv21Size) {
            nv21Buffer = ByteArray(nv21Size)
        }
        val nv21 = nv21Buffer!!
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
        val fileName = String.format("frame_%02d.jpg", index)
        val file = File(burstDir, fileName)

        val baos = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, w, h), 100, baos)
        val jpegBytes = baos.toByteArray()

        FileOutputStream(file).use { it.write(jpegBytes) }

        if (index == 1) {
            saveToGallery(jpegBytes, fileName)
        }
    }

    private fun saveToGallery(jpegBytes: ByteArray, fileName: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GalaxyCamera")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { os ->
                os.write(jpegBytes)
            }
        }
    }

    private fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private class CircleView(context: Context, private val color: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = this@CircleView.color
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = width / 2f
            canvas.drawCircle(radius, radius, radius, paint)
        }
    }
}
