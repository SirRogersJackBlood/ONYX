// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Camera2 + ZXing QR scanner. Frames are decoded in memory and discarded; nothing is saved,
 * and there is no Google Play Services / ML Kit dependency.
 */
class ScanActivity : OnyxActivity() {

    private lateinit var preview: TextureView
    private lateinit var status: TextView
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var bgThread: HandlerThread? = null
    private val handled = AtomicBoolean(false)
    private lateinit var field: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen("SCAN", "POINT AT THEIR ONYX CODE") {
            preview = TextureView(this@ScanActivity)
            add(card().apply { addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(360))) }, 12)
            status = text("Looking for a code…", 12f, Forge.CYAN, mono = true)
            add(status, 8)
            add(text("GOT AN INVITE LINK?", 11f, Forge.MUTED, mono = true).apply { letterSpacing = 0.2f }, 24)
            add(text("Paste the whole message or just the onyx1:… code. Remote invites connect you as UNVERIFIED " +
                "until you compare safety numbers. Clear your clipboard afterwards; other apps can read it.", 12f, Forge.MUTED), 4)
            field = EditText(this@ScanActivity).apply {
                setTextColor(Forge.TEXT); hint = "onyx1:…"; setHintTextColor(Forge.MUTED)
                imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            }
            add(field, 4)
            add(button("Connect with invite") { pair(field.text.toString()) }, 8)
        }
        prefillFrom(intent)
    }

    /** Incoming invite from another app: only PRE-FILLS the field. The user must tap Connect. */
    private fun prefillFrom(i: Intent?) {
        val raw = i?.dataString ?: i?.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val code = io.github.proteu5.onyx.core.Pairing.Code.extract(raw) ?: return
        field.setText(code)
        status.text = "Invite received. Check who sent it, then tap Connect with invite."
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); prefillFrom(intent) }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA); return
        }
        startCamera()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else status.text = "Camera permission denied — paste the code instead."
    }

    override fun onPause() { stopCamera(); super.onPause() }

    private fun startCamera() {
        if (camera != null) return
        if (preview.isAvailable) openCamera()
        else preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = openCamera()
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) = Unit
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) = Unit
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val cm = getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cm.cameraIdList.firstOrNull() ?: run { status.text = "No camera found."; return }
        val map = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val size = map.getOutputSizes(ImageFormat.YUV_420_888)
            .filter { it.width <= 1920 && it.height <= 1080 }
            .maxByOrNull { it.width * it.height } ?: Size(1280, 720)

        val t = HandlerThread("onyx-scan").apply { start() }; bgThread = t
        val handler = Handler(t.looper)
        val ir = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2); reader = ir
        ir.setOnImageAvailableListener({ r -> decode(r) }, handler)

        cm.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                val tex = preview.surfaceTexture ?: return
                tex.setDefaultBufferSize(size.width, size.height)
                val previewSurface = Surface(tex)
                val outputs = listOf(OutputConfiguration(previewSurface), OutputConfiguration(ir.surface))
                device.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, mainExecutor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(previewSurface); addTarget(ir.surface)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            }.build()
                            s.setRepeatingRequest(req, null, handler)
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { status.text = "Camera unavailable." }
                    }))
            }
            override fun onDisconnected(device: CameraDevice) { device.close(); camera = null }
            override fun onError(device: CameraDevice, error: Int) { device.close(); camera = null }
        }, handler)
    }

    private val qrReader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.ALSO_INVERTED to true, DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE))

    private fun decode(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        try {
            if (handled.get()) return
            val plane = img.planes[0]
            val w = img.width; val h = img.height; val stride = plane.rowStride
            val buf = plane.buffer
            val y = ByteArray(w * h)
            for (row in 0 until h) { buf.position(row * stride); buf.get(y, row * w, w) }
            val src = PlanarYUVLuminanceSource(y, w, h, 0, 0, w, h, false)
            val result = runCatching { qrReader.decode(BinaryBitmap(HybridBinarizer(src)), hints) }.getOrNull()
            y.fill(0)
            val text = result?.text ?: return
            if (text.startsWith("onyx1:") && handled.compareAndSet(false, true)) runOnUiThread { pair(text) }
        } finally {
            img.close()
            qrReader.reset()
        }
    }

    private fun pair(code: String) {
        stopCamera()
        status.text = "Code found. Connecting over Tor… (can take up to a minute)"
        thread {
            val result = runCatching { app.messenger.pairWith(code.trim()) }
            runOnUiThread {
                result.onSuccess { c ->
                    startActivity(Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_ID, c.id)); finish()
                }.onFailure { e ->
                    status.text = "Pairing failed: ${e.message ?: "unknown error"}"
                    handled.set(false)
                    startCamera()
                }
            }
        }
    }

    private fun stopCamera() {
        runCatching { session?.close() }; session = null
        runCatching { camera?.close() }; camera = null
        runCatching { reader?.close() }; reader = null
        bgThread?.quitSafely(); bgThread = null
    }

    companion object { private const val REQ_CAMERA = 7 }
}
