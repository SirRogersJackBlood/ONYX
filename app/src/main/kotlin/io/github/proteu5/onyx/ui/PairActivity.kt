// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.proteu5.onyx.core.Pairing
import io.github.proteu5.onyx.tor.TorController
import kotlin.concurrent.thread

/**
 * Two ways to add someone:
 *  - In person: a one-time, 10-minute QR code → contact is VERIFIED.
 *  - Remote: a 24 h single-use invite link shared through another app → contact is UNVERIFIED
 *    until both people compare safety numbers.
 */
class PairActivity : OnyxActivity() {

    private var code: Pairing.Code? = null
    private lateinit var qr: ImageView
    private lateinit var countdown: TextView
    private lateinit var inviteStatus: TextView
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen("PAIR", "ONE-TIME CODE · IN PERSON") {
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

            // ---------------- remote invite ----------------
            add(text("NOT IN THE SAME ROOM?", 11f, Forge.MUTED, mono = true).apply { letterSpacing = 0.2f }, 28)
            val remote = card()
            remote.add(text("Share an invite link", 17f, Forge.TEXT))
            remote.add(text("Send a one-time link through any app. They paste it into ONYX and you connect over Tor. " +
                "The contact shows as UNVERIFIED until you compare safety numbers by voice or in person.", 13f, Forge.MUTED), 6)
            remote.add(button("Create & share invite link") { confirmInvite() }, 12)
            inviteStatus = text("", 11f, Forge.CYAN_DIM, mono = true)
            remote.add(inviteStatus, 8)
            remote.add(button("Revoke unused invite links", primary = false) {
                thread { runCatching { app.messenger.revokeAllInvites() }; runOnUiThread { refreshInvites(); toast("All unused invite links revoked") } }
            }, 8)
            add(remote, 8)
        }
        app.messenger.onPaired = { c ->
            runOnUiThread {
                startActivity(Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_ID, c.id))
                finish()
            }
        }
        generate()
        refreshInvites()
    }

    private fun refreshInvites() {
        val n = app.messenger.pendingInviteCount()
        inviteStatus.text = if (n == 0) "No unused invite links" else "$n unused invite link${if (n > 1) "s" else ""} (valid up to 24 h)"
    }

    private fun confirmInvite() {
        if (app.tor.state != TorController.State.ONLINE) { toast("Wait for Tor to come online first"); return }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Share an invite link?")
            .setMessage(
                "• Anyone who gets this link before your contact uses it can connect as them. Send it only to the person you mean.\n" +
                "• The app you send it through (SMS, email, a messenger) can see the link.\n" +
                "• It works once and expires in 24 hours. You can revoke it here.\n" +
                "• The contact starts UNVERIFIED. Compare safety numbers to verify."
            )
            .setPositiveButton("Create link") { _, _ ->
                thread {
                    val link = runCatching { app.messenger.createInviteLink() }
                    runOnUiThread {
                        link.onSuccess { l ->
                            refreshInvites()
                            val site = if (Links.SITE_URL.isNotBlank()) "Get ONYX: ${Links.SITE_URL}\n\n" else ""
                            shareText("ONYX invite", "Connect with me on ONYX (private, no phone number).\n\n" +
                                "${site}In ONYX: Scan code → paste this invite. It works once and expires in 24 h:\n\n$l")
                        }.onFailure { toast("Could not create invite: ${it.message}") }
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
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
        qr.setImageBitmap(qrBitmap(c.encode(), 720))
        tick()
    }

    private fun tick() {
        val c = code ?: return
        val left = c.expiresAtEpochSec - System.currentTimeMillis() / 1000
        if (left <= 0) { countdown.text = "Expired — tap New code"; qr.setImageBitmap(null); return }
        countdown.text = "EXPIRES IN %d:%02d".format(left / 60, left % 60)
        ui.postDelayed({ tick() }, 1000)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        code?.let { app.messenger.cancelPairingCode(it) }   // the in-person code never outlives this screen
        app.messenger.onPaired = null
        super.onDestroy()
    }
}
