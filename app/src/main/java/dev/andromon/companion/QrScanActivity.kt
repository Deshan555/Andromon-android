// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.math.abs

/**
 * Send › Scan code: shows the back camera and reads the QR code Andromon displays on the
 * computer (Wi‑Fi transfer › Show code), so the address doesn't have to be typed. The frames are
 * decoded on the phone with ZXing and never saved or sent anywhere; the camera closes as soon as a
 * code is read or the screen is left. The text of the code is handed over in [scanned].
 */
class QrScanActivity : Activity() {

    companion object {
        /** The last code read; the Send screen takes it (and clears it) when it comes back. */
        @Volatile var scanned: String? = null
    }

    private lateinit var preview: TextureView
    private lateinit var hint: TextView
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private val qr = QRCodeReader()
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)
    @Volatile private var done = false
    private var asked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val dp = resources.displayMetrics.density
        preview = TextureView(this)
        hint = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding((24 * dp).toInt(), (48 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt())
            setBackgroundColor(0x99000000.toInt()) // readable over a bright picture
            text = "Point the camera at the code Andromon shows on your computer"
        }
        val close = TextView(this).apply {
            text = "Cancel"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding((28 * dp).toInt(), (14 * dp).toInt(), (28 * dp).toInt(), (14 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = 28 * dp }
            setOnClickListener { finish() }
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(preview, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            addView(Frame(this@QrScanActivity), FrameLayout.LayoutParams(-1, -1))
            addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(close, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (56 * dp).toInt() })
        })
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!asked) { asked = true; requestPermissions(arrayOf(Manifest.permission.CAMERA), 1) }
            else hint.text = "Scanning needs the camera. Allow it in Android's settings for this app, or type the address instead."
            return
        }
        thread = HandlerThread("qr-scan").also { it.start(); handler = Handler(it.looper) }
        if (preview.isAvailable) open() else preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = open()
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
    }

    override fun onPause() {
        runCatching { session?.close() }; session = null
        runCatching { camera?.close() }; camera = null
        runCatching { reader?.close() }; reader = null
        thread?.quitSafely(); thread = null; handler = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        // onResume runs again after the permission dialog and opens the camera, or explains.
    }

    private fun closest(sizes: Array<Size>?, w: Int, h: Int): Size =
        sizes?.minByOrNull { abs(it.width - w) + abs(it.height - h) + if (it.width * h == it.height * w) 0 else 400 } ?: Size(w, h)

    private fun open() {
        if (camera != null || isFinishing) return
        val h = handler ?: return
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull() ?: return fail("This phone has no camera the app can use.")
            val map = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val view = closest(map?.getOutputSizes(SurfaceTexture::class.java), 1280, 720)
            val frame = closest(map?.getOutputSizes(ImageFormat.YUV_420_888), 1280, 720)
            // The sensor is landscape; on the portrait screen the picture is view.height wide.
            preview.layoutParams = FrameLayout.LayoutParams(preview.width, preview.width * view.width / view.height, Gravity.CENTER)
            val texture = preview.surfaceTexture ?: return
            texture.setDefaultBufferSize(view.width, view.height)
            val shown = Surface(texture)
            val r = ImageReader.newInstance(frame.width, frame.height, ImageFormat.YUV_420_888, 2)
            reader = r
            r.setOnImageAvailableListener({ decode(it) }, h)
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    try {
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(shown); addTarget(r.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }
                        @Suppress("DEPRECATION")
                        device.createCaptureSession(listOf(shown, r.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                runCatching { s.setRepeatingRequest(request.build(), null, h) }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) = fail("The camera couldn't start.")
                        }, h)
                    } catch (_: Exception) { fail("The camera couldn't start.") }
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); camera = null }
                override fun onError(device: CameraDevice, error: Int) { device.close(); camera = null; fail("The camera couldn't start.") }
            }, h)
        } catch (_: SecurityException) {
            fail("Scanning needs the camera.")
        } catch (_: Exception) {
            fail("The camera couldn't start.")
        }
    }

    private fun fail(why: String) = runOnUiThread { hint.text = "$why Type the address instead." }

    /** Looks for a QR code in the brightness plane of one camera frame (on the scan thread). */
    private fun decode(r: ImageReader) {
        val image = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return
        try {
            if (done) return
            val w = image.width; val hgt = image.height
            val plane = image.planes[0]
            val buf = plane.buffer
            val stride = plane.rowStride
            val luma = ByteArray(w * hgt)
            if (stride == w) buf.get(luma, 0, minOf(luma.size, buf.remaining()))
            else for (y in 0 until hgt) { buf.position(y * stride); buf.get(luma, y * w, minOf(w, buf.remaining())) }
            val source = PlanarYUVLuminanceSource(luma, w, hgt, 0, 0, w, hgt, false)
            val text = try { qr.decode(BinaryBitmap(HybridBinarizer(source)), hints).text } catch (_: Exception) { null } finally { qr.reset() }
            if (!text.isNullOrBlank()) {
                done = true
                scanned = text
                runOnUiThread { finish() }
            }
        } finally {
            image.close()
        }
    }

    /** A rounded square in the middle, with the rest slightly dimmed: where to hold the code. */
    private class Frame(ctx: Context) : View(ctx) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 3 * resources.displayMetrics.density }
        override fun onDraw(c: Canvas) {
            val side = minOf(width, height) * 0.68f
            val box = RectF((width - side) / 2, (height - side) / 2, (width + side) / 2, (height + side) / 2)
            val r = 24 * resources.displayMetrics.density
            c.drawRoundRect(box, r, r, line)
        }
    }
}
