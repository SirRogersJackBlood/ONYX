// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.ui

import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import io.github.proteu5.onyx.core.ForgeRank
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
        root.add(tools)

        threadView = vbox().apply { setPadding(dp(12), dp(8), dp(12), dp(8)) }
        scroll = ScrollView(this).apply { addView(threadView) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(8), dp(12), dp(12)); gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "Message"; setHintTextColor(Forge.MUTED); setTextColor(Forge.TEXT)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            // Ask the keyboard not to learn from what is typed here (Gboard incognito etc.).
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            maxLines = 5
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Forge.SURFACE); setStroke(dp(1), Forge.LINE) }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        bar.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        bar.addView(button("Send") { send() })
        root.add(bar)
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        app.messages.addListener(msgListener); app.contacts.addListener(contactListener)
        renderHeader(); render()
    }

    override fun onStop() {
        app.messages.removeListener(msgListener); app.contacts.removeListener(contactListener)
        super.onStop()
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
        row.addView(t)
        header.add(row)
    }

    private fun render() {
        val c = contact() ?: return
        threadView.removeAllViews()
        val msgs = app.messages.forContact(c.id)
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
        input.setText("")
        thread { runCatching { app.messenger.sendText(c, t) }.onFailure { runOnUiThread { toast("Could not queue message") } } }
    }

    private fun showSafetyNumber() {
        val c = contact() ?: return
        val me = app.tor.onion ?: return toast("Tor is not online yet")
        val digits = app.crypto.safetyNumber(me, c).chunked(5).chunked(4).joinToString("\n") { it.joinToString("  ") }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Safety number")
            .setMessage("$digits\n\nCompare in person. You already verified by scanning a QR face to face; this lets you re-check at any time.")
            .setPositiveButton("OK", null).show()
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
