// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.ui

import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import io.github.proteu5.onyx.core.ForgeRank
import io.github.proteu5.onyx.core.Padding
import io.github.proteu5.onyx.core.WireAnatomy
import io.github.proteu5.onyx.core.WireDirection
import io.github.proteu5.onyx.net.WireTap
import io.github.proteu5.onyx.data.Contact
import io.github.proteu5.onyx.data.Message
import io.github.proteu5.onyx.data.MsgState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class ChatActivity : OnyxActivity() {

    private lateinit var contactId: String
    private lateinit var header: LinearLayout
    private lateinit var threadView: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var rawScroll: ScrollView
    private lateinit var rawText: TextView
    private lateinit var draftLine: TextView
    private var rawOn = false
    private val tapListener: (String) -> Unit = { id -> if (id == contactId && rawOn) runOnUiThread { renderRaw() } }
    private val msgListener: (String) -> Unit = { id -> if (id == contactId) runOnUiThread { render() } }
    private val contactListener: () -> Unit = { runOnUiThread { renderHeader() } }

    private fun contact(): Contact? = app.contacts.get(contactId)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        contactId = intent.getStringExtra(EXTRA_ID) ?: return finish()

        val root = vbox().apply { setBackgroundColor(Forge.BG) }
        header = vbox().apply { setPadding(dp(16), dp(16), dp(16), dp(8)) }
        root.add(header)

        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), 0, dp(12), dp(8)) }
        val lp = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) } }
        tools.addView(button("Safety #", false) { showSafetyNumber() }, lp())
        tools.addView(button("Timer", false) { chooseTimer() }, lp())
        tools.addView(button("Rename", false) { rename() }, lp())
        tools.addView(button("Delete", false) { deleteContact() }, lp())
        tools.addView(button("RAW", false) { toggleRaw() }, lp())
        root.add(tools)

        threadView = vbox().apply { setPadding(dp(12), dp(8), dp(12), dp(8)) }
        scroll = ScrollView(this).apply { addView(threadView) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // RAW wire view: split screen under the conversation, hidden until toggled.
        val rawBox = vbox().apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply { setColor(android.graphics.Color.parseColor("#020608")); setStroke(dp(1), Forge.LINE) }
        }
        rawBox.add(text("RAW · WHAT AN INTERCEPTOR WOULD CAPTURE", 10f, Forge.CYAN, mono = true).apply { letterSpacing = 0.12f })
        draftLine = text("", 10f, Forge.WARN, mono = true)
        rawBox.add(draftLine, 4)
        rawText = text("", 9f, Forge.CYAN_DIM, mono = true)
        rawBox.add(rawText, 6)
        rawScroll = ScrollView(this).apply { addView(rawBox); visibility = View.GONE }
        root.addView(rawScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(8), dp(12), dp(12)); gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "Message · 80 max"; setHintTextColor(Forge.MUTED); setTextColor(Forge.TEXT)
            // One line, 80 characters: ONYX is for short human messages, not payloads or scripts.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(android.text.InputFilter.LengthFilter(io.github.proteu5.onyx.core.MessagePolicy.MAX_CHARS))
            // Ask the keyboard not to learn from what is typed here (Gboard incognito etc.).
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            maxLines = 3
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Forge.SURFACE); setStroke(dp(1), Forge.LINE) }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) { if (rawOn) updateDraftLine() }
        })
        bar.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        bar.addView(button("Send") { send() })
        root.add(bar)
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        app.messages.addListener(msgListener); app.contacts.addListener(contactListener); WireTap.addListener(tapListener)
        renderHeader(); render(); if (rawOn) renderRaw()
    }

    override fun onStop() {
        app.messages.removeListener(msgListener); app.contacts.removeListener(contactListener); WireTap.removeListener(tapListener)
        super.onStop()
    }

    private fun toggleRaw() {
        rawOn = !rawOn
        rawScroll.visibility = if (rawOn) View.VISIBLE else View.GONE
        if (rawOn) { updateDraftLine(); renderRaw() }
    }

    /**
     * Live, while typing: how big the message will be ON THE WIRE. Nothing is encrypted here
     * (encrypting a draft would advance the ratchet); it's just the padding arithmetic.
     */
    private fun updateDraftLine() {
        val n = input.text.toString().toByteArray(Charsets.UTF_8).size
        if (n == 0) { draftLine.text = "type a message: its size on the wire updates live"; return }
        val envelope = n + 26                                   // version, kind, id, minute, length
        val plainBucket = runCatching { Padding.bucketFor(envelope, Padding.MESSAGE_BUCKETS) }.getOrNull()
        if (plainBucket == null) { draftLine.text = "draft $n B · too large"; return }
        val frame = runCatching { Padding.bucketFor(plainBucket + 160, Padding.FRAME_BUCKETS) }.getOrDefault(-1)
        draftLine.text = "draft $n B → padded to $plainBucket B → encrypted → ≈ ${frame + 4} B frame. " +
            "Any message up to 80 characters looks identical on the wire."
    }

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private fun renderRaw() {
        val entries = WireTap.entries(contactId)
        val sb = StringBuilder()
        if (entries.isEmpty()) {
            sb.append("No frames captured yet this session.\nSend or receive a message over Tor and the raw bytes appear here.\n\n")
        }
        sb.append("Inside Tor, frames look like this. Your carrier sees even less: TLS to a Tor relay, in fixed 514-byte cells.\n\n")
        for (e in entries) {
            val arrow = if (e.dir == WireDirection.OUT) "▲ OUT" else "▼ IN "
            sb.append("$arrow ${timeFmt.format(Date(e.atMs))}  ${e.typeName}  ${e.wireSize} B\n")
            sb.append("      ${e.anatomy}\n")
            sb.append(WireAnatomy.hexDump(e.preview, ascii = false))
            if (e.wireSize > e.preview.size) sb.append("… ${e.wireSize - e.preview.size} more bytes (ciphertext + zero padding)\n")
            sb.append('\n')
        }
        rawText.text = sb
        rawScroll.post { rawScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun renderHeader() {
        val c = contact() ?: return finish()
        header.removeAllViews()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        c.badge?.let { b -> Forge.coverDrawable(b.cover)?.let { row.addView(image(it, 44), LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) }) } }
        val t = vbox()
        t.add(text(c.name, 22f, Forge.SILVER))
        val tier = c.badge?.tier?.takeIf { it != ForgeRank.Tier.NONE }?.let { "◆ ${it.title.uppercase()} · helps the Tor network  " } ?: ""
        val timer = if (c.disappearSeconds > 0) "⏱ ${fmtTimer(c.disappearSeconds)}  " else ""
        t.add(text(tier + timer + "TOR P2P · PQ E2EE", 10f, Forge.CYAN, mono = true).apply { letterSpacing = 0.1f })
        if (!c.verified) t.add(text("UNVERIFIED", 10f, Forge.WARN, mono = true).apply { letterSpacing = 0.15f }, 2)
        row.addView(t)
        header.add(row)
    }

    private fun render() {
        val c = contact() ?: return
        threadView.removeAllViews()
        val msgs = app.messages.forContact(c.id)
        if (!c.verified) {
            threadView.add(card().apply {
                add(text("⚠ UNVERIFIED CONTACT", 11f, Forge.WARN, mono = true).apply { letterSpacing = 0.15f })
                add(text("Connected with an invite link. Tap Safety # and compare the numbers by voice or in person.", 12f, Forge.MUTED), 4)
                setOnClickListener { showSafetyNumber() }
            }, 0)
        }
        if (msgs.isEmpty()) {
            val waiting = !app.crypto.hasSession(c)
            threadView.add(text(
                if (waiting) "Waiting for their device to finish the secure handshake over Tor…"
                else "Session established (PQXDH + Triple Ratchet). Say hello.", 13f, Forge.MUTED))
        }
        msgs.forEach { threadView.add(bubble(it), 6) }
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun bubble(m: Message): LinearLayout {
        val b = vbox().apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (m.outgoing) Forge.CYAN_DIM else Forge.SURFACE)
                if (!m.outgoing) setStroke(dp(1), Forge.LINE)
            }
        }
        b.add(text(m.text, 15f, Forge.TEXT))
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.sentAtMinute * 60_000))
        val state = when (m.state) { MsgState.QUEUED -> " · queued"; MsgState.SENT -> " · delivered"; MsgState.FAILED -> " · failed"; MsgState.RECEIVED -> "" }
        val disappear = if (m.expiresAtMs > 0) " · ⏱" else ""
        b.add(text(time + state + disappear, 10f, Forge.MUTED, mono = true), 2)
        val wrap = LinearLayout(this).apply { gravity = if (m.outgoing) Gravity.END else Gravity.START }
        wrap.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            if (m.outgoing) marginStart = dp(48) else marginEnd = dp(48)
        })
        return wrap
    }

    private fun send() {
        val c = contact() ?: return
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        if (!app.crypto.hasSession(c)) { toast("Secure session not ready yet — their phone must come online once."); return }
        val clean = io.github.proteu5.onyx.core.MessagePolicy.sanitize(t)
        if (clean.isEmpty()) { toast("Only letters, numbers, emoji and . , ! ? ' \" - : ( ) can be sent."); return }
        input.setText("")
        thread {
            runCatching { app.messenger.sendText(c, t) }
                .onSuccess { sent -> if (sent != t) runOnUiThread { toast("Code-like symbols were removed before sending.") } }
                .onFailure { e -> runOnUiThread {
                    if (e is io.github.proteu5.onyx.net.Messenger.RateLimited) { input.setText(t); toast(e.message ?: "Slow down") }
                    else toast("Could not queue message")
                } }
        }
    }

    private fun showSafetyNumber() {
        val c = contact() ?: return
        val me = app.tor.onion ?: return toast("Tor is not online yet")
        val digits = app.crypto.safetyNumber(me, c).chunked(5).chunked(4).joinToString("\n") { it.joinToString("  ") }
        val b = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (c.verified) "Safety number · verified" else "Safety number · UNVERIFIED")
        if (c.verified) {
            b.setMessage("$digits\n\nThis contact is verified. Compare again any time to re-check.")
                .setPositiveButton("OK", null)
        } else {
            b.setMessage("$digits\n\nYou connected with an invite link, so this contact is not verified yet. " +
                "Read these 60 digits to each other on a voice call or in person. If every digit matches, mark as verified. " +
                "If anything differs, delete the contact: someone may be in the middle.")
                .setPositiveButton("They match · verify") { _, _ -> app.contacts.save(c.copy(verified = true)) }
                .setNegativeButton("Not now", null)
        }
        b.show()
    }

    private fun chooseTimer() {
        val c = contact() ?: return
        val options = intArrayOf(0, 300, 3600, 86_400, 604_800)
        val labels = options.map { if (it == 0) "Off" else fmtTimer(it) }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Disappearing messages")
            .setItems(labels) { _, i -> thread { runCatching { app.messenger.setDisappearing(c, options[i]) } } }
            .show()
    }

    private fun rename() {
        val c = contact() ?: return
        val field = EditText(this).apply { setText(c.name); imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING; setTextColor(Forge.TEXT) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Local name (never sent)")
            .setView(field)
            .setPositiveButton("Save") { _, _ -> field.text.toString().trim().takeIf { it.isNotEmpty() }?.let { app.contacts.save(c.copy(name = it.take(40))) } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun deleteContact() {
        val c = contact() ?: return
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Delete ${c.name}?")
            .setMessage("Deletes the conversation, session keys and pinned identity from this phone. You would need to pair in person again.")
            .setPositiveButton("Delete") { _, _ ->
                app.outbox.removeForContact(c.id); app.crypto.forget(c); app.contacts.delete(c.id); finish()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun fmtTimer(s: Int) = when {
        s >= 604_800 -> "${s / 604_800}w"; s >= 86_400 -> "${s / 86_400}d"; s >= 3600 -> "${s / 3600}h"; else -> "${s / 60}m"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object { const val EXTRA_ID = "contact" }
}
