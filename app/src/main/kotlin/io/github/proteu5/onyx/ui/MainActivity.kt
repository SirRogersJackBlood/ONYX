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
import io.github.proteu5.onyx.service.OnyxService
import io.github.proteu5.onyx.tor.TorController

class MainActivity : OnyxActivity() {

    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var search: EditText
    private lateinit var sortButton: android.widget.Button
    private lateinit var archiveToggle: android.widget.Button
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

        val col = vbox().apply { setPadding(dp(18), dp(20), dp(18), dp(28)) }
        col.add(image(R.drawable.onyx_emblem, 132))
        status = text("", 12f, Forge.CYAN, mono = true).apply { gravity = Gravity.CENTER; letterSpacing = 0.1f }
        col.add(status, 4)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lp = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) } }
        actions.addView(button("Show my code") { startActivity(Intent(this, PairActivity::class.java)) }, lp())
        actions.addView(button("Scan code", primary = false) { startActivity(Intent(this, ScanActivity::class.java)) }, lp())
        col.add(actions, 18)

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

        list = vbox()
        col.add(list, 8)

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
        renderStatus(); renderContacts()
    }

    override fun onStop() {
        app.tor.removeListener(torListener); app.contacts.removeListener(contactsListener); app.contactMeta.removeListener(contactsListener)
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

    private fun renderContacts() {
        list.removeAllViews()
        val all = runCatching { app.contacts.all() }.getOrDefault(emptyList())
        controls.visibility = if (all.size >= 5) View.VISIBLE else View.GONE
        if (all.isEmpty()) {
            archiveToggle.visibility = View.GONE
            list.add(text("No contacts yet. Meet in person, then one of you shows a code and the other scans it. " +
                "No phone numbers, no accounts, no servers.", 14f, Forge.MUTED))
            return
        }
        val meta = all.associate { it.id to runCatching { app.contactMeta.get(it.id) }.getOrDefault(ContactMeta()) }
        val archivedCount = all.count { meta.getValue(it.id).archived }
        if (archivedCount == 0) showArchived = false
        archiveToggle.visibility = if (archivedCount > 0) View.VISIBLE else View.GONE
        archiveToggle.text = if (showArchived) "Back to conversations" else "Archived ($archivedCount)"
        sortButton.text = if (sortRecent) "Recent" else "A–Z"

        val q = query.lowercase()
        val byName = compareBy<Contact> { it.name.lowercase() }
        val shown = all
            .filter { meta.getValue(it.id).archived == showArchived }
            .filter { q.isEmpty() || it.name.lowercase().contains(q) }
            .sortedWith(if (sortRecent) compareByDescending<Contact> { meta.getValue(it.id).lastOrd }.then(byName) else byName)
        if (shown.isEmpty()) {
            list.add(text(if (q.isNotEmpty()) "No contact named \"$query\"." else "Nothing here.", 14f, Forge.MUTED))
            return
        }
        shown.forEach { list.add(row(it, meta.getValue(it.id)), 8) }
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
            .setPositiveButton("Block") { _, _ -> app.contactMeta.setBlocked(c.id, true) }
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
