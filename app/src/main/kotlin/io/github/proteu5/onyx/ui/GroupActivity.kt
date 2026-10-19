// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import io.github.proteu5.onyx.core.Bytes
import io.github.proteu5.onyx.core.Groups
import io.github.proteu5.onyx.core.MessagePolicy
import io.github.proteu5.onyx.data.Contact
import io.github.proteu5.onyx.data.GroupRepo
import io.github.proteu5.onyx.data.Message
import io.github.proteu5.onyx.data.MsgState
import io.github.proteu5.onyx.net.Messenger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** A group chat (v1.4.1): up to 8 people, no admin, every message encrypted separately to each member. */
class GroupActivity : OnyxActivity() {

    private lateinit var groupId: String
    private lateinit var chatId: String
    private lateinit var header: LinearLayout
    private lateinit var threadView: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private val msgListener: (String) -> Unit = { id -> if (id == chatId) runOnUiThread { render() } }
    private val changeListener: () -> Unit = { runOnUiThread { renderHeader(); render() } }

    private fun group(): Groups.State? = app.groups.get(groupId)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        groupId = intent.getStringExtra(EXTRA_ID) ?: return finish()
        chatId = GroupRepo.chatId(groupId)

        val root = vbox().apply { setBackgroundColor(Forge.BG) }
        header = vbox().apply { setPadding(dp(16), dp(16), dp(16), dp(8)) }
        root.add(header)

        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), 0, dp(12), dp(8)) }
        val lp = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) } }
        tools.addView(button("Members", false) { showMembers() }, lp())
        tools.addView(button("Invite", false) { chooseInvitee() }, lp())
        tools.addView(button("Leave", false) { confirmLeave() }, lp())
        root.add(tools)

        threadView = vbox().apply { setPadding(dp(12), dp(8), dp(12), dp(8)) }
        scroll = ScrollView(this).apply { addView(threadView) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(8), dp(12), dp(12)); gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "Message the group · ${MessagePolicy.MAX_CHARS} max"; setHintTextColor(Forge.MUTED); setTextColor(Forge.TEXT)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(android.text.InputFilter.LengthFilter(MessagePolicy.MAX_CHARS))
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            maxLines = 6
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Forge.SURFACE); setStroke(dp(1), Forge.LINE) }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        bar.addView(button("Photo", primary = false) { MessageViews.pickImage(this) }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        bar.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        bar.addView(button("Send") { send() })
        root.add(bar)
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        app.messages.addListener(msgListener); app.groups.addListener(changeListener); app.contacts.addListener(changeListener)
        renderHeader(); render()
    }

    override fun onStop() {
        app.messages.removeListener(msgListener); app.groups.removeListener(changeListener); app.contacts.removeListener(changeListener)
        super.onStop()
    }

    private fun renderHeader() {
        val g = group() ?: return finish()
        header.removeAllViews()
        header.add(text(g.title, 22f, Forge.SILVER))
        header.add(text("GROUP · ${g.active().size}/${Groups.MAX_MEMBERS} · TOR P2P · PQ E2EE" +
            (if (g.verifiedOnly) " · VERIFIED ONLY" else ""), 10f, Forge.CYAN, mono = true).apply { letterSpacing = 0.1f })
    }

    private fun render() {
        val g = group() ?: return
        runCatching { app.contactMeta.markRead(chatId) }
        threadView.removeAllViews()
        val others = g.active().filter { e -> app.tor.onion?.let { !Bytes.ctEquals(it.publicKey, e.onionPk) } ?: true }
        val reachable = app.messenger.reachable(g).size
        val waiting = others.size - reachable
        val unverified = others.mapNotNull { app.messenger.contactFor(it) }.count { !it.verified }
        if (waiting > 0 || unverified > 0) {
            threadView.add(card().apply {
                if (waiting > 0) add(text("◌ CONNECTING TO $waiting MEMBER${if (waiting > 1) "S" else ""}", 11f, Forge.CYAN, mono = true).apply { letterSpacing = 0.15f })
                if (g.verifiedOnly && waiting > 0) add(text("Verified-only group: you only reach members you've paired with in person and verified.", 12f, Forge.MUTED), 4)
                else if (waiting > 0) add(text("Members you haven't met are introduced through the group. It finishes when both phones are online.", 12f, Forge.MUTED), 4)
                if (unverified > 0) add(text("⚠ $unverified member${if (unverified > 1) "s are" else " is"} UNVERIFIED. Open Members and compare safety numbers.", 12f, Forge.WARN), 6)
                setOnClickListener { showMembers() }
            }, 0)
        }
        val msgs = app.messages.forContact(chatId)
        if (msgs.isEmpty()) threadView.add(text("Each message is encrypted separately to every member. Say hello.", 13f, Forge.MUTED), 6)
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
        if (!m.outgoing) {
            val sender = m.senderId?.let { app.contacts.get(it) }
            b.add(text((sender?.name ?: "Former member") + if (sender != null && !sender.verified) "  ⚠" else "",
                11f, if (sender?.verified == true) Forge.CYAN else Forge.WARN, mono = true))
        }
        val body = vbox()
        MessageViews.fill(this, body, m) { app.messages.image(chatId, m.id) }
        b.add(body, if (m.outgoing) 0 else 2)
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.sentAtMinute * 60_000))
        val state = when (m.state) { MsgState.QUEUED -> " · queued"; MsgState.SENT -> " · delivered"; MsgState.FAILED -> " · no one reachable yet"; MsgState.RECEIVED -> "" }
        b.add(text(time + state, 10f, Forge.MUTED, mono = true), 2)
        val wrap = LinearLayout(this).apply { gravity = if (m.outgoing) Gravity.END else Gravity.START }
        wrap.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            if (m.outgoing) marginStart = dp(48) else marginEnd = dp(48)
        })
        return wrap
    }

    private fun send() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        if (MessagePolicy.sanitize(t).isEmpty()) { toast("Only letters, numbers, emoji and . , ! ? ' \" - : ( ) can be sent."); return }
        input.setText("")
        thread {
            runCatching { app.messenger.sendGroupText(groupId, t) }
                .onSuccess { sent -> if (sent != t) runOnUiThread { toast("Code-like symbols were removed before sending.") } }
                .onFailure { e -> runOnUiThread { input.setText(t); toast(e.message ?: "Could not queue message") } }
        }
    }

    @Deprecated("platform API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != MessageViews.REQ_IMAGE || resultCode != RESULT_OK) return
        MessageViews.confirmAndSend(this, uri, input.text.toString().trim()) { jpeg, cap ->
            val r = runCatching { app.messenger.sendGroupImage(groupId, jpeg, cap) }
            runOnUiThread { if (r.isSuccess) input.setText("") else toast(r.exceptionOrNull()?.message ?: "Could not send image") }
        }
    }

    private fun showMembers() {
        val g = group() ?: return
        val me = app.tor.onion?.publicKey
        val bad = app.messenger.disputed(g)
        val rows = g.active().map { e ->
            if (me != null && Bytes.ctEquals(e.onionPk, me)) "You" to null
            else if (e.key in bad) "⚠ Member ${io.github.proteu5.onyx.core.OnionAddress.fromPublicKey(e.onionPk).label.take(4).uppercase()} · TWO DIFFERENT KEYS CLAIM THIS MEMBER: check in person" to null
            else {
                val c = app.messenger.contactFor(e)
                when {
                    c == null -> "Member ${io.github.proteu5.onyx.core.OnionAddress.fromPublicKey(e.onionPk).label.take(4).uppercase()} · connecting…" to null
                    c.verified -> "${c.name} · verified" to c
                    else -> "${c.name} · ⚠ UNVERIFIED" to c
                }
            }
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Members (${rows.size}/${Groups.MAX_MEMBERS})")
            .setItems(rows.map { it.first }.toTypedArray()) { _, i ->
                rows[i].second?.let { c -> startActivity(Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_ID, c.id)) }
            }
            .setPositiveButton("Close", null).show()
    }

    private fun chooseInvitee() {
        val g = group() ?: return
        if (g.isFull()) return toast("This group is full (${Groups.MAX_MEMBERS} people).")
        val mine = app.messenger.myMailboxId()
        val candidates: List<Contact> = app.contacts.all().filter { c ->
            c.id != mine && !g.isActive(c.onion.publicKey) && !app.contactMeta.isBlocked(c.id) &&
                app.crypto.hasSession(c) && (!g.verifiedOnly || c.verified)
        }.sortedBy { it.name.lowercase() }
        if (candidates.isEmpty()) return toast(if (g.verifiedOnly) "No verified contacts left to invite." else "No contacts left to invite.")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Invite one of your contacts")
            .setItems(candidates.map { it.name + if (it.verified) "" else "  ⚠" }.toTypedArray()) { _, i ->
                val c = candidates[i]
                thread {
                    val r = runCatching { app.messenger.inviteToGroup(groupId, c) }
                    runOnUiThread { toast(r.exceptionOrNull()?.message ?: "Invite sent. ${c.name} decides whether to join.") }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirmLeave() {
        val g = group() ?: return
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Leave ${g.title}?")
            .setMessage("Members are told you left (signed by your phone, nobody else can do this for you). " +
                "The group and its messages are deleted from this phone. Contacts you met through the group stay; delete them separately if you want.")
            .setPositiveButton("Leave") { _, _ -> thread {
                val r = runCatching { app.messenger.leaveGroup(groupId) }
                runOnUiThread { r.exceptionOrNull()?.let { toast(it.message ?: "Couldn't leave") } ?: finish() }
            } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object { const val EXTRA_ID = "onyx.group" }
}
