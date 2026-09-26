// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import io.github.proteu5.onyx.R
import io.github.proteu5.onyx.core.ForgeRank
import io.github.proteu5.onyx.data.Contact
import io.github.proteu5.onyx.service.OnyxService
import io.github.proteu5.onyx.tor.TorController

class MainActivity : OnyxActivity() {

    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private val torListener: (TorController.State) -> Unit = { runOnUiThread { renderStatus() } }
    private val contactsListener: () -> Unit = { runOnUiThread { renderContacts() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        OnyxService.start(this)

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
        list = vbox()
        col.add(list, 8)

        col.add(button("Settings · Forge rank · Security", primary = false) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, 24)

        setContentView(android.widget.ScrollView(this).apply { setBackgroundColor(Forge.BG); addView(col) })
    }

    override fun onStart() {
        super.onStart()
        app.tor.addListener(torListener); app.contacts.addListener(contactsListener)
        renderStatus(); renderContacts()
    }

    override fun onStop() {
        app.tor.removeListener(torListener); app.contacts.removeListener(contactsListener)
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
        if (all.isEmpty()) {
            list.add(text("No contacts yet. Meet in person, then one of you shows a code and the other scans it. " +
                "No phone numbers, no accounts, no servers.", 14f, Forge.MUTED))
            return
        }
        all.forEach { list.add(row(it), 8) }
    }

    private fun row(c: Contact): LinearLayout {
        val r = card().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val cover = c.badge?.let { Forge.coverDrawable(it.cover) }
        if (cover != null) r.addView(image(cover, 40), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        val texts = vbox()
        texts.add(text(c.name, 17f, Forge.TEXT))
        val tier = c.badge?.tier?.takeIf { it != ForgeRank.Tier.NONE }?.let { "◆ ${it.title.uppercase()}  " } ?: ""
        texts.add(text(tier + (if (c.verified) "verified in person" else "unverified"), 11f, Forge.CYAN_DIM, mono = true))
        r.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        r.setOnClickListener { startActivity(Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_ID, c.id)) }
        return r
    }
}
