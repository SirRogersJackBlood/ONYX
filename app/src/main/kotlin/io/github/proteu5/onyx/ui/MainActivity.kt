// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.github.proteu5.onyx.R
import io.github.proteu5.onyx.core.ForgeRank
import io.github.proteu5.onyx.data.Contact
import io.github.proteu5.onyx.data.ContactMeta
import io.github.proteu5.onyx.data.GroupRepo
import io.github.proteu5.onyx.core.Groups
import io.github.proteu5.onyx.service.OnyxService
import io.github.proteu5.onyx.tor.TorController

class MainActivity : OnyxActivity() {

    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var search: EditText
    private lateinit var sortButton: android.widget.Button
    private lateinit var archiveToggle: android.widget.Button
    private lateinit var scanButton: android.widget.Button
    private lateinit var newGroupButton: android.widget.Button
    private lateinit var invitesBox: LinearLayout
    private lateinit var a11yBox: LinearLayout
    private val torListener: (TorController.State) -> Unit = { runOnUiThread { renderStatus() } }
    private val contactsListener: () -> Unit = { runOnUiThread { renderContacts() } }

    // Contact list view state (memory only).
    private var query = ""
    private var sortRecent = true
    private var showArchived = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        OnyxService.start(this)
        if (app.consumeResetNotice()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("ONYX was reset")
                .setMessage("Android erased ONYX's protected key. Some Android versions do this when the screen lock " +
                    "is removed. The messages and contacts stored on this phone could not be recovered, so ONYX " +
                    "started fresh. Your contacts need to pair with you again.")
                .setPositiveButton("OK", null)
                .show()
        }

        val col = vbox().apply { setPadding(dp(18), dp(10), dp(18), dp(28)) }
        // Compact header: small emblem beside the connection status, so the conversations start higher up.
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(image(R.drawable.onyx_emblem, 52), LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(12) })
        status = text("", 12f, Forge.CYAN, mono = true).apply { letterSpacing = 0.1f }
        header.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.add(header)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lp = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) } }
        actions.addView(button("Show my code") { startActivity(Intent(this, PairActivity::class.java)) }, lp())
        scanButton = button("Scan code", primary = false) { startActivity(Intent(this, ScanActivity::class.java)) }
        actions.addView(scanButton, lp())
        col.add(actions, 12)

        col.add(text("CONVERSATIONS", 11f, Forge.MUTED, mono = true).apply { letterSpacing = 0.2f }, 24)

        // Search + sort: shown once there are 5 or more contacts. Matching runs on the phone only.
        controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        search = EditText(this).apply {
            hint = "Search contacts"; setSingleLine(true); setTextColor(Forge.TEXT); setHintTextColor(Forge.MUTED)
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(e: Editable?) { query = e?.toString()?.trim().orEmpty(); renderContacts() }
            })
        }
        controls.addView(search, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        sortButton = button("Recent", primary = false) { sortRecent = !sortRecent; renderContacts() }
        controls.addView(sortButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        col.add(controls, 8)

        a11yBox = vbox()
        col.add(a11yBox, 8)

        invitesBox = vbox()
        col.add(invitesBox, 8)

        list = vbox()
        col.add(list, 8)

        newGroupButton = button("+ New group", primary = false) { newGroup() }
        col.add(newGroupButton, 8)

        archiveToggle = button("Archived", primary = false) { showArchived = !showArchived; renderContacts() }
        col.add(archiveToggle, 8)

        col.add(button("Settings · Forge rank · Security", primary = false) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, 24)

        setContentView(android.widget.ScrollView(this).apply { setBackgroundColor(Forge.BG); addView(col) })
    }

    override fun onStart() {
        super.onStart()
        app.tor.addListener(torListener); app.contacts.addListener(contactsListener); app.contactMeta.addListener(contactsListener)
        app.groups.addListener(contactsListener)
        renderStatus(); renderContacts(); renderAccessibility()
    }

    /** Warns while accessibility apps are on: they can read the screen. Hidden once trusted, back if the set changes. */
    private fun renderAccessibility() {
        a11yBox.removeAllViews()
        val services = AccessibilityCheck.enabled(this)
        if (services.isEmpty()) return
        val fp = AccessibilityCheck.fingerprint(services)
        if (runCatching { app.a11yAcknowledged }.getOrDefault("") == fp) return
        val c = card()
        c.add(text("⚠ ACCESSIBILITY APPS ARE ON", 11f, Forge.WARN, mono = true).apply { letterSpacing = 0.15f })
        c.add(text(services.joinToString("\n") { "• " + it.label + if (it.isTool) " (accessibility tool)" else "" }, 13f, Forge.TEXT), 6)
        c.add(text("Accessibility apps can read what's on your screen, including decrypted messages, and can tap and type for you. " +
            "Screen readers need this. Anything you don't recognise or don't use should be turned off.", 12f, Forge.MUTED), 6)
        c.add(text(if (AccessibilityCheck.sensitiveScreensSupported)
                "On this phone ONYX hides its screens from accessibility apps that aren't real accessibility tools. Tools still work."
            else "This Android version can't hide ONYX from accessibility apps. Only keep ones you trust.",
            12f, if (AccessibilityCheck.sensitiveScreensSupported) Forge.CYAN_DIM else Forge.WARN), 6)
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lp = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) } }
        btns.addView(button("Review") { runCatching { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }, lp())
        btns.addView(button("I trust these", primary = false) { app.a11yAcknowledged = fp; renderAccessibility() }, lp())
        c.add(btns, 10)
        a11yBox.add(c)
    }

    override fun onStop() {
        app.tor.removeListener(torListener); app.contacts.removeListener(contactsListener); app.contactMeta.removeListener(contactsListener)
        app.groups.removeListener(contactsListener)
        super.onStop()
    }

    private fun renderStatus() {
        status.text = when (app.tor.state) {
            TorController.State.ONLINE -> "● ONLINE · TOR P2P · NO SERVERS"
            TorController.State.STARTING -> "◌ BUILDING TOR CIRCUITS…"
            TorController.State.FAILED -> "✕ TOR UNAVAILABLE · RETRYING"
            TorController.State.OFF -> "○ OFFLINE"
        }
    }

    /** One row in the conversation list: a contact or a group. */
    private class Row(val id: String, val name: String, val meta: ContactMeta, val contact: Contact?, val group: Groups.State?)

    private fun renderContacts() {
        list.removeAllViews(); invitesBox.removeAllViews()
        if (app.mailboxMode) { newGroupButton.visibility = View.GONE; renderMailbox(); return }
        scanButton.visibility = View.VISIBLE
        // My own mailbox phone is plumbing, not a conversation: it lives in Settings → Mailbox.
        val mine = app.messenger.myMailboxId()
        val all = runCatching { app.contacts.all() }.getOrDefault(emptyList()).filter { it.id != mine }
        val groups = runCatching { app.groups.all() }.getOrDefault(emptyList())
        newGroupButton.visibility = if (all.isNotEmpty()) View.VISIBLE else View.GONE
        renderInvites()

        val rows = all.map { Row(it.id, it.name, metaOf(it.id), it, null) } +
            groups.map { Row(GroupRepo.chatId(it.idHex), it.title, metaOf(GroupRepo.chatId(it.idHex)), null, it) }
        controls.visibility = if (rows.size >= 5) View.VISIBLE else View.GONE
        if (rows.isEmpty()) {
            archiveToggle.visibility = View.GONE
            list.add(text("No contacts yet. Meet in person, then one of you shows a code and the other scans it. " +
                "No phone numbers, no accounts, no servers.", 14f, Forge.MUTED))
            return
        }
        val archivedCount = rows.count { it.meta.archived }
        if (archivedCount == 0) showArchived = false
        archiveToggle.visibility = if (archivedCount > 0) View.VISIBLE else View.GONE
        archiveToggle.text = if (showArchived) "Back to conversations" else "Archived ($archivedCount)"
        sortButton.text = if (sortRecent) "Recent" else "A–Z"

        val q = query.lowercase()
        val byName = compareBy<Row> { it.name.lowercase() }
        val shown = rows
            .filter { it.meta.archived == showArchived }
            .filter { q.isEmpty() || it.name.lowercase().contains(q) }
            .sortedWith(if (sortRecent) compareByDescending<Row> { it.meta.lastOrd }.then(byName) else byName)
        if (shown.isEmpty()) {
            list.add(text(if (q.isNotEmpty()) "No conversation named \"$query\"." else "Nothing here.", 14f, Forge.MUTED))
            return
        }
        shown.forEach { r -> list.add(if (r.contact != null) row(r.contact, r.meta) else groupRow(r.group!!, r.meta), 8) }
    }

    private fun metaOf(id: String) = runCatching { app.contactMeta.get(id) }.getOrDefault(ContactMeta())

    /** Group invites wait here until accepted or declined. Nothing about you is shared before you accept. */
    private fun renderInvites() {
        val invites = runCatching { app.groups.invites() }.getOrDefault(emptyList())
        for (inv in invites) {
            val from = app.contacts.get(inv.fromContactId)
            val c = card()
            c.add(text("GROUP INVITE", 11f, Forge.CYAN, mono = true).apply { letterSpacing = 0.2f })
            c.add(text(inv.state.title, 17f, Forge.TEXT), 4)
            c.add(text("From ${from?.name ?: "a former contact"} · ${inv.state.active().size} member(s)" +
                (if (inv.state.verifiedOnly) " · verified only" else ""), 12f, Forge.MUTED, mono = true), 2)
            c.add(text(if (inv.state.verifiedOnly) "Joining shares your membership with the group. You'll only talk to members you've verified in person."
                else "Joining lets every member reach you directly: members you haven't met are paired with you automatically and marked UNVERIFIED.",
                12f, Forge.MUTED), 6)
            val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val lp = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) } }
            btns.addView(button("Join") {
                Thread {
                    val r = runCatching { app.messenger.acceptGroupInvite(inv.state.idHex) }
                    runOnUiThread {
                        r.onSuccess { startActivity(Intent(this, GroupActivity::class.java).putExtra(GroupActivity.EXTRA_ID, inv.state.idHex)) }
                         .onFailure { android.widget.Toast.makeText(this, it.message ?: "Could not join", android.widget.Toast.LENGTH_LONG).show() }
                    }
                }.start()
            }, lp())
            btns.addView(button("Decline", primary = false) { app.messenger.declineGroupInvite(inv.state.idHex) }, lp())
            c.add(btns, 10)
            invitesBox.add(c, 8)
        }
    }

    private fun groupRow(g: Groups.State, m: ContactMeta): LinearLayout {
        val r = card().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = vbox()
        texts.add(text(g.title, 17f, Forge.TEXT))
        texts.add(text("GROUP · ${g.active().size}/${Groups.MAX_MEMBERS}" + (if (g.verifiedOnly) " · verified only" else ""),
            11f, Forge.CYAN_DIM, mono = true))
        r.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (m.unread > 0) {
            r.addView(text(if (m.unread > 99) "99+" else m.unread.toString(), 13f, Forge.BG, mono = true, bold = true).apply {
                setBackgroundColor(Forge.CYAN); setPadding(dp(8), dp(2), dp(8), dp(2)); gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        }
        r.setOnClickListener { startActivity(Intent(this, GroupActivity::class.java).putExtra(GroupActivity.EXTRA_ID, g.idHex)) }
        r.setOnLongClickListener {
            val chat = GroupRepo.chatId(g.idHex)
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(g.title)
                .setItems(arrayOf(if (m.archived) "Unarchive" else "Archive", "Leave group")) { _, i ->
                    if (i == 0) app.contactMeta.setArchived(chat, !m.archived)
                    else AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Leave ${g.title}?")
                        .setMessage("Members are told you left. The group and its messages are deleted from this phone.")
                        .setPositiveButton("Leave") { _, _ -> Thread {
                            val r = runCatching { app.messenger.leaveGroup(g.idHex) }
                            r.exceptionOrNull()?.let { e -> runOnUiThread { android.widget.Toast.makeText(this, e.message ?: "Couldn't leave", android.widget.Toast.LENGTH_LONG).show() } }
                        }.start() }
                        .setNegativeButton("Cancel", null).show()
                }.show()
            true
        }
        return r
    }

    /** New group: a name, who to invite (each decides for themselves), and whether it's verified-only. */
    private fun newGroup() {
        val mine = app.messenger.myMailboxId()
        val candidates = app.contacts.all().filter { it.id != mine && !app.contactMeta.isBlocked(it.id) && app.crypto.hasSession(it) }
            .sortedBy { it.name.lowercase() }
        if (candidates.isEmpty()) { android.widget.Toast.makeText(this, "Pair with someone first.", android.widget.Toast.LENGTH_LONG).show(); return }
        val box = vbox().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        val title = EditText(this).apply {
            hint = "Group name (only members see it)"; setSingleLine(true); setTextColor(Forge.TEXT); setHintTextColor(Forge.MUTED)
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            filters = arrayOf(android.text.InputFilter.LengthFilter(Groups.TITLE_MAX))
        }
        box.add(title)
        val verifiedOnly = android.widget.Switch(this).apply { text = "Verified members only"; setTextColor(Forge.TEXT) }
        box.add(verifiedOnly, 10)
        box.add(text("Invite (up to ${Groups.MAX_MEMBERS - 1}). Anyone in the group can invite their own contacts later; nobody can remove anyone else.",
            12f, Forge.MUTED), 10)
        val checks = candidates.map { c ->
            android.widget.CheckBox(this).apply { text = c.name + if (c.verified) "" else "  ⚠ unverified"; setTextColor(Forge.TEXT) }.also { box.add(it, 2) }
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("New group")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Create") { _, _ ->
                val picked = candidates.filterIndexed { i, _ -> checks[i].isChecked }
                val vo = verifiedOnly.isChecked
                val tooMany = picked.size > Groups.MAX_MEMBERS - 1
                val skipped = if (vo) picked.count { !it.verified } else 0
                Thread {
                    val r = runCatching { app.messenger.createGroup(title.text.toString(), vo, picked.filter { !vo || it.verified }.take(Groups.MAX_MEMBERS - 1)) }
                    runOnUiThread {
                        r.onSuccess { id ->
                            val note = listOfNotNull(
                                if (tooMany) "Only the first ${Groups.MAX_MEMBERS - 1} were invited." else null,
                                if (skipped > 0) "$skipped unverified contact(s) skipped." else null)
                            if (note.isNotEmpty()) android.widget.Toast.makeText(this, note.joinToString(" "), android.widget.Toast.LENGTH_LONG).show()
                            startActivity(Intent(this, GroupActivity::class.java).putExtra(GroupActivity.EXTRA_ID, id))
                        }.onFailure { android.widget.Toast.makeText(this, it.message ?: "Could not create group", android.widget.Toast.LENGTH_LONG).show() }
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Mailbox mode: no conversations, just what this phone is doing for its owner. */
    private fun renderMailbox() {
        controls.visibility = View.GONE; archiveToggle.visibility = View.GONE; scanButton.visibility = View.GONE
        val owner = runCatching { app.contacts.all().singleOrNull() }.getOrNull()
        val held = runCatching { app.mailboxStore.count() }.getOrDefault(0)
        val box = card()
        box.add(text("MAILBOX MODE", 11f, Forge.CYAN, mono = true).apply { letterSpacing = 0.2f })
        if (owner == null) {
            box.add(text("Not paired yet. On your main phone open Settings → Mailbox → Pair my mailbox, then tap " +
                "Show my code here and let it scan.", 14f, Forge.TEXT), 6)
        } else {
            box.add(text("Holding messages for ${owner.name} while they're offline.", 15f, Forge.TEXT), 6)
            box.add(text("$held waiting · kept up to 14 days · all end-to-end encrypted. This phone can't read them " +
                "and only sees an opaque tag per sender, never names.", 12f, Forge.MUTED), 6)
        }
        box.add(text("Keep this phone charged, on Wi-Fi and with ONYX running.", 12f, Forge.MUTED), 8)
        list.add(box)
    }

    private fun row(c: Contact, m: ContactMeta): LinearLayout {
        val r = card().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val cover = c.badge?.let { Forge.coverDrawable(it.cover) }
        if (cover != null) r.addView(image(cover, 40), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        val texts = vbox()
        texts.add(text(c.name, 17f, if (m.blocked) Forge.MUTED else Forge.TEXT))
        val online = !m.blocked && runCatching { app.messenger.isOnline(c.id) }.getOrDefault(false)
        val tier = c.badge?.tier?.takeIf { it != ForgeRank.Tier.NONE }?.let { "◆ ${it.title.uppercase()}  " } ?: ""
        val state = when {
            m.blocked -> "BLOCKED"
            c.verified -> "verified"
            else -> "⚠ unverified · invite link"
        }
        texts.add(text((if (online) "● online  " else "") + tier + state, 11f,
            when { m.blocked -> Forge.MUTED; c.verified -> Forge.CYAN_DIM; else -> Forge.WARN }, mono = true))
        r.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (m.unread > 0 && !m.blocked) {
            r.addView(text(if (m.unread > 99) "99+" else m.unread.toString(), 13f, Forge.BG, mono = true, bold = true).apply {
                setBackgroundColor(Forge.CYAN); setPadding(dp(8), dp(2), dp(8), dp(2)); gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        }
        r.setOnClickListener { startActivity(Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_ID, c.id)) }
        r.setOnLongClickListener { contactMenu(c, m); true }
        return r
    }

    /** Long-press: rename, archive, block. Everything stays on this phone; the contact is never told. */
    private fun contactMenu(c: Contact, m: ContactMeta) {
        val items = arrayOf("Rename", if (m.archived) "Unarchive" else "Archive", if (m.blocked) "Unblock" else "Block")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(c.name)
            .setItems(items) { _, i ->
                when (i) {
                    0 -> rename(c)
                    1 -> app.contactMeta.setArchived(c.id, !m.archived)
                    2 -> if (m.blocked) app.contactMeta.setBlocked(c.id, false) else confirmBlock(c)
                }
            }.show()
    }

    private fun confirmBlock(c: Contact) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Block ${c.name}?")
            .setMessage("Their messages will be dropped without telling them, and you can't send to them until you unblock. " +
                "Your chat history stays on this phone.")
            .setPositiveButton("Block") { _, _ -> app.contactMeta.setBlocked(c.id, true); app.groups.removeInvitesFrom(c.id) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun rename(c: Contact) {
        val field = EditText(this).apply { setText(c.name); imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING; setTextColor(Forge.TEXT) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Local name (never sent)")
            .setView(field)
            .setPositiveButton("Save") { _, _ -> field.text.toString().trim().takeIf { it.isNotEmpty() }?.let { app.contacts.save(c.copy(name = it.take(40))) } }
            .setNegativeButton("Cancel", null).show()
    }
}
