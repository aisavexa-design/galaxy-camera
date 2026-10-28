package com.example.galaxycamera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView
    private lateinit var captureButton: Button
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private lateinit var imageReader: ImageReader
    private val cameraThread = HandlerThread("CameraBackground").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val imageProcessor = ImageProcessor()
    
    private var burstList = mutableListOf<File>()
    private var burstDir: File? = null
    private var currentBurstTimestamp: String = ""
    private var isCapturing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        
        surfaceView = SurfaceView(this)
        layout.addView(surfaceView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        
        statusText = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            setShadowLayer(4f, 0f, 0f, 0xFF000000.toInt())
            gravity = Gravity.CENTER
        }
        layout.addView(statusText, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 100
        })

        captureButton = Button(this).apply {
            text = "CAPTURE"
            setOnClickListener { takeBurst() }
        }
        layout.addView(captureButton, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 100
        })

        setContentView(layout)

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 101)
        } else {
            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) { openCamera() }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {}
            })
        }
    }

    private fun openCamera() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { 
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK 
        } ?: return

        imageReader = ImageReader.newInstance(4080, 3060, ImageFormat.YUV_420_888, 12).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                processYuvImage(image)
            }, cameraHandler)
        }

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreview()
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close() }
            }, cameraHandler)
        } catch (e: SecurityException) { }
    }

    private fun startPreview() {
        val camera = cameraDevice ?: return
        val surface = surfaceView.holder.surface
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
        }
        camera.createCaptureSession(listOf(surface, imageReader.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                session.setRepeatingRequest(builder.build(), null, cameraHandler)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {}
        }, cameraHandler)
    }

    private fun takeBurst() {
        if (isCapturing) return
        isCapturing = true
        runOnUiThread { statusText.text = "Capturing burst..." }
        
        currentBurstTimestamp = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(Date())
        burstDir = File(getExternalFilesDir(null), "burst/$currentBurstTimestamp").apply { mkdirs() }
        burstList.clear()

        val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)?.apply {
            addTarget(imageReader.surface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        } ?: return

        val requests = List(10) { builder.build() }
        captureSession?.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
                isCapturing = false
            }
        }, cameraHandler)
    }

    private fun processYuvImage(image: Image) {
        val w = image.width
        val h = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        
        val ySize = yPlane.buffer.remaining()
        val uSize = uPlane.buffer.remaining()
        val vSize = vPlane.buffer.remaining()
        
        val nv21 = ByteArray(ySize + w * h / 2)
        yPlane.buffer.get(nv21, 0, ySize)
        
        val vBuffer = vPlane.buffer
        val uBuffer = uPlane.buffer
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        
        var pos = ySize
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                val vIdx = row * vRowStride + col * vPixelStride
                nv21[pos++] = vBuffer.get(vIdx)
                nv21[pos++] = uBuffer.get(vIdx)
            }
        }
        image.close()

        val dir = burstDir ?: return
        val ts = currentBurstTimestamp
        
        ioExecutor.execute {
            val idx = burstList.size + 1
            val file = File(dir, String.format(Locale.US, "frame_%02d.jpg", idx))
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, w, h, null)
            FileOutputStream(file).use { out ->
                yuvImage.compressToJpeg(Rect(0, 0, w, h), 100, out)
            }
            burstList.add(file)
            
            if (burstList.size == 10) {
                runOnUiThread { statusText.text = "Processing..." }
                val enqueued = imageProcessor.enqueue(dir, ts, this) { msg ->
                    runOnUiThread { statusText.text = msg }
                }
                if (!enqueued) {
                    runOnUiThread { statusText.text = "Queue full. Try again." }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraThread.quitSafely()
        ioExecutor.shutdown()
        imageProcessor.shutdown()
    }
}
