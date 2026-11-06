package dev.ziqeno.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout

class QrCameraActivity : Activity() {
    private lateinit var preview: TextureView
    private var decoderThread: HandlerThread? = null
    private var reader: ImageReader? = null
    private var output: Surface? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var generation = 0
    private var opening = false
    private var active = false
    private var requested = false

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requested = savedInstanceState?.getBoolean("requested") ?: false
        val mode = getSharedPreferences("display", MODE_PRIVATE).getString("theme", "system")
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val ui = PixelUi(this, mode == "dark" || mode == "system" && night)
        val root = ui.column().apply {
            setBackgroundColor(ui.paper)
            setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16))
        }
        root.addView(ui.text(getString(R.string.source_scan_qr), 28))
        root.addView(ui.text(getString(R.string.source_qr_point_camera), 17),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8); bottomMargin = ui.dp(14) })
        preview = TextureView(this)
        val viewport = FrameLayout(this)
        viewport.addView(preview, FrameLayout.LayoutParams(-1, -1))
        viewport.addView(object : View(this) {
            private val paint = Paint().apply { color = ui.accent; strokeWidth = ui.dp(2).toFloat() }
            override fun onDraw(canvas: Canvas) {
                val side = minOf(width, height) * 0.64f
                val left = (width - side) / 2
                val top = (height - side) / 2
                paint.style = Paint.Style.STROKE
                canvas.drawRect(left, top, left + side, top + side, paint)
            }
        }, FrameLayout.LayoutParams(-1, -1))
        root.addView(viewport, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(ui.button(getString(R.string.source_cancel)) { finish() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            val bars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (ui.dark) 0 else bars, bars)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val edges = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(edges.left + ui.dp(20), edges.top + ui.dp(16),
                    edges.right + ui.dp(20), edges.bottom + ui.dp(16))
                insets
            }
        } else {
            window.statusBarColor = ui.paper
            window.navigationBarColor = ui.paper
        }
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = tryStart()
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                closeCamera()
                return true
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("requested", requested)
    }

    override fun onResume() {
        super.onResume()
        active = true
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) tryStart()
        else if (!requested) {
            requested = true
            requestPermissions(arrayOf(Manifest.permission.CAMERA), requestPermission)
        }
    }

    override fun onPause() {
        active = false
        closeCamera()
        super.onPause()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code != requestPermission) return
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) tryStart()
        else fail(getString(R.string.source_qr_camera_denied))
    }

    private fun tryStart() {
        if (!active || isFinishing || opening || camera != null || !preview.isAvailable) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        runCatching { openCamera(checkNotNull(preview.surfaceTexture)) }.onFailure {
            fail(getString(R.string.source_qr_camera_unavailable))
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(texture: SurfaceTexture) {
        val manager = getSystemService(CameraManager::class.java)
        val ids = manager.cameraIdList
        val id = ids.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_BACK } ?: ids.first()
        val characteristics = manager.getCameraCharacteristics(id)
        val streams = checkNotNull(characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
        val frames = preferred(streams.getOutputSizes(ImageFormat.YUV_420_888))
        val previewSize = preferred(streams.getOutputSizes(SurfaceTexture::class.java))
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val autofocus = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true
        val outputSurface = Surface(texture)
        output = outputSurface
        val worker = HandlerThread("ziqeno-qr").apply { start() }
        decoderThread = worker
        val handler = Handler(worker.looper)
        val token = ++generation
        opening = true
        var lastScan = -300L
        val frameReader = ImageReader.newInstance(frames.width, frames.height, ImageFormat.YUV_420_888, 2)
        reader = frameReader
        frameReader.setOnImageAvailableListener({ images ->
            val result = runCatching {
                images.acquireLatestImage()?.use { frame ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastScan < 180) null else {
                        lastScan = now
                        QrReader.frame(frame)
                    }
                }
            }.getOrNull()
            if (result != null) runOnUiThread {
                if (token == generation && active && !isFinishing) {
                    setResult(RESULT_OK, Intent().putExtra(resultText, result))
                    finish()
                }
            }
        }, handler)
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (token != generation || !active) {
                    device.close()
                    return
                }
                opening = false
                camera = device
                runCatching { beginPreview(device, outputSurface, frameReader.surface, token, autofocus) }.onFailure {
                    fail(getString(R.string.source_qr_camera_start_failed))
                }
            }

            override fun onDisconnected(device: CameraDevice) = end(device)
            override fun onError(device: CameraDevice, error: Int) = end(device)
            private fun end(device: CameraDevice) {
                device.close()
                if (token == generation) fail(getString(R.string.source_qr_camera_unavailable))
            }
        }, Handler(mainLooper))
    }

    @Suppress("DEPRECATION")
    private fun beginPreview(device: CameraDevice, output: Surface, frames: Surface, token: Int, autofocus: Boolean) {
        device.createCaptureSession(listOf(output, frames), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                if (token != generation || !active) {
                    configured.close()
                    return
                }
                session = configured
                runCatching {
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(output)
                        addTarget(frames)
                        if (autofocus) set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    }.build()
                    configured.setRepeatingRequest(request, null, null)
                }.onFailure { fail(getString(R.string.source_qr_camera_start_failed)) }
            }

            override fun onConfigureFailed(configured: CameraCaptureSession) {
                configured.close()
                if (token == generation) fail(getString(R.string.source_qr_camera_mode_failed))
            }
        }, Handler(mainLooper))
    }

    private fun closeCamera() {
        generation++
        opening = false
        session?.close()
        session = null
        camera?.close()
        camera = null
        reader?.close()
        reader = null
        output?.release()
        output = null
        decoderThread?.quitSafely()
        decoderThread = null
    }

    private fun fail(message: String) {
        if (isFinishing) return
        setResult(RESULT_CANCELED, Intent().putExtra(errorText, message))
        finish()
    }

    private fun preferred(sizes: Array<Size>): Size =
        sizes.minBy { kotlin.math.abs(it.width * it.height - 640 * 480) }

    companion object {
        const val resultText = "qr-result"
        const val errorText = "qr-error"
        private const val requestPermission = 43
    }
}
