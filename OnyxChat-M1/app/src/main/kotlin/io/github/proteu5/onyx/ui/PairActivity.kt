// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.proteu5.onyx.core.Pairing
import io.github.proteu5.onyx.tor.TorController

/** Shows a one-time, 10-minute QR code. Only show it to the person standing in front of you. */
class PairActivity : OnyxActivity() {

    private var code: Pairing.Code? = null
    private lateinit var qr: ImageView
    private lateinit var countdown: TextView
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen("PAIR", "ONE-TIME CODE · IN PERSON ONLY") {
            add(text("Let the other person scan this with ONYX. It contains your onion address, a commitment to " +
                "your identity key and a one-time secret. It works once and expires in 10 minutes.", 14f, Forge.MUTED), 12)
            val holder = card().apply { gravity = Gravity.CENTER }
            qr = ImageView(this@PairActivity).apply { adjustViewBounds = true }
            holder.addView(qr, LinearLayout.LayoutParams(dp(280), dp(280)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            add(holder, 16)
            countdown = text("", 12f, Forge.CYAN, mono = true).apply { gravity = Gravity.CENTER }
            add(countdown, 8)
            add(button("New code", primary = false) { generate() }, 16)
            add(button("Scan their code instead", primary = false) { startActivity(Intent(this@PairActivity, ScanActivity::class.java)); finish() }, 8)
        }
        app.messenger.onPaired = { c ->
            runOnUiThread {
                startActivity(Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_ID, c.id))
                finish()
            }
        }
        generate()
    }

    private fun generate() {
        code?.let { app.messenger.cancelPairingCode(it) }
        if (app.tor.state != TorController.State.ONLINE) {
            countdown.text = "Waiting for Tor… (the code needs your onion address)"
            ui.postDelayed({ generate() }, 2000)
            return
        }
        val c = app.messenger.createPairingCode()
        code = c
        qr.setImageBitmap(render(c.encode(), 720))
        tick()
    }

    private fun tick() {
        val c = code ?: return
        val left = c.expiresAtEpochSec - System.currentTimeMillis() / 1000
        if (left <= 0) { countdown.text = "Expired — tap New code"; qr.setImageBitmap(null); return }
        countdown.text = "EXPIRES IN %d:%02d".format(left / 60, left % 60)
        ui.postDelayed({ tick() }, 1000)
    }

    private fun render(text: String, size: Int): Bitmap {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) Color.BLACK else Color.parseColor("#E8F6FA") }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        code?.let { app.messenger.cancelPairingCode(it) }   // a code never outlives this screen
        app.messenger.onPaired = null
        super.onDestroy()
    }
}
