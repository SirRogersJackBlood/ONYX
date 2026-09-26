// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import io.github.proteu5.onyx.core.ForgeRank
import io.github.proteu5.onyx.service.OnyxService
import io.github.proteu5.onyx.tor.TorController
import kotlin.concurrent.thread

class SettingsActivity : OnyxActivity() {

    override fun onResume() { super.onResume(); build() }

    private fun switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label; setTextColor(Forge.TEXT); textSize = 15f; isChecked = checked
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun build() {
        val sf = app.snowflake
        val s = sf.settings()
        val stats = sf.stats()
        val tier = ForgeRank.tierFor(stats)

        screen("SETTINGS", "/FORGE") {
            // ---------------- Forge rank ----------------
            add(text("FORGE RANK · HELP OTHERS CONNECT", 11f, Forge.MUTED, mono = true).apply { letterSpacing = 0.2f }, 20)
            val rank = card()
            val head = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            Forge.coverDrawable(s.cover)?.let { head.addView(image(it, 56), LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(14) }) }
            val ht = vbox()
            ht.add(text(tier.title.uppercase(), 24f, Forge.CYAN).apply { letterSpacing = 0.2f })
            ht.add(text("%.1f h relayed · %d people helped · %d MB today".format(
                stats.secondsProxied / 3600.0, stats.peopleHelped, sf.bytesToday() / 1_000_000), 11f, Forge.MUTED, mono = true))
            head.addView(ht)
            rank.add(head)
            rank.add(ProgressBar(this@SettingsActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000; progress = (ForgeRank.progress(stats) * 1000).toInt()
                progressTintList = android.content.res.ColorStateList.valueOf(Forge.CYAN)
            }, 10)
            rank.add(text("Tiers: Ember → Spark (10 h / 25) → Flame (50 h / 250) → Forge (200 h / 1,000) → Crucible (1,000 h / 5,000)",
                11f, Forge.MUTED), 6)

            rank.add(switch("Run Snowflake proxy", s.enabled) { v ->
                if (v) confirmSnowflake { sf.saveSettings(s.copy(enabled = true)); build() } else { sf.saveSettings(s.copy(enabled = false)); build() }
            }, 14)
            rank.add(text("Status: " + (if (sf.running) "relaying for censored users" else sf.lastBlockReason ?: "idle"), 12f, Forge.CYAN_DIM, mono = true), 4)
            rank.add(text("Runs only on unmetered Wi-Fi while charging.", 12f, Forge.MUTED), 4)

            val caps = intArrayOf(100, 500, 2000)
            val capRow = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.HORIZONTAL }
            caps.forEach { mb ->
                capRow.addView(button(if (mb >= 1000) "${mb / 1000} GB/day" else "$mb MB/day", primary = s.dailyCapMb == mb) {
                    sf.saveSettings(s.copy(dailyCapMb = mb)); build()
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
            }
            rank.add(capRow, 10)

            rank.add(switch("Show my badge to people I chat with", s.shareBadge) { v ->
                sf.saveSettings(s.copy(shareBadge = v))
                sf.badge()?.let { b -> thread { runCatching { app.messenger.shareBadgeWithAll(b) } } }
            }, 14)
            rank.add(text("Shared: your tier + cover icon only, inside the encrypted chat. Never hours, counts, or anything public. " +
                "No leaderboard exists.", 11f, Forge.MUTED), 4)

            rank.add(text("Cover icon", 12f, Forge.SILVER), 12)
            val covers = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(ForgeRank.Cover.EMBLEM, ForgeRank.Cover.SIGIL, ForgeRank.Cover.RING).forEach { c ->
                val iv = image(Forge.coverDrawable(c)!!, 64).apply {
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat(); setStroke(dp(2), if (c == s.cover) Forge.CYAN else Forge.LINE)
                    }
                    setOnClickListener {
                        sf.saveSettings(s.copy(cover = c))
                        sf.badge()?.let { b -> thread { runCatching { app.messenger.shareBadgeWithAll(b) } } }
                        build()
                    }
                }
                covers.addView(iv, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(10) })
            }
            rank.add(covers, 6)
            add(rank, 8)

            // ---------------- Share ONYX ----------------
            add(text("SHARE ONYX", 11f, Forge.MUTED, mono = true).apply { letterSpacing = 0.2f }, 24)
            val share = card()
            share.add(text("Spread the word, not your data", 17f, Forge.TEXT))
            if (Links.SITE_URL.isBlank()) {
                share.add(text("The ONYX website address hasn't been set in this build yet.", 13f, Forge.MUTED), 6)
            } else {
                share.add(text("Let a friend scan this to get ONYX. It's only the website address: nothing about you or your contacts.",
                    13f, Forge.MUTED), 6)
                val qrHolder = LinearLayout(this@SettingsActivity).apply { gravity = Gravity.CENTER }
                qrHolder.addView(android.widget.ImageView(this@SettingsActivity).apply {
                    adjustViewBounds = true; setImageBitmap(qrBitmap(Links.SITE_URL, 600))
                }, LinearLayout.LayoutParams(dp(200), dp(200)))
                share.add(qrHolder, 12)
                share.add(text(Links.SITE_URL, 12f, Forge.CYAN, mono = true).apply { gravity = Gravity.CENTER }, 8)
                share.add(button("Share website link", primary = false) {
                    shareText("ONYX", "ONYX: private messaging with no phone number, no account and no server.\n${Links.SITE_URL}")
                }, 10)
            }
            share.add(button("Invite someone to chat", primary = false) {
                startActivity(Intent(this@SettingsActivity, PairActivity::class.java))
            }, 8)
            add(share, 8)

            // ---------------- Network ----------------
            add(text("NETWORK", 11f, Forge.MUTED, mono = true).apply { letterSpacing = 0.2f }, 24)
            val net = card()
            net.add(text("Tor: " + app.tor.state.name.lowercase() + (app.tor.lastError?.let { " ($it)" } ?: ""), 14f, Forge.TEXT))
            app.tor.onion?.let { net.add(text("Your onion (share only via QR):\n" + it.label.chunked(14).joinToString(" "), 11f, Forge.CYAN_DIM, mono = true), 6) }
            net.add(text("Transport order: Tor direct → Tor mailbox → nearby → SMS (off; opt-in per contact, carrier sees metadata).", 12f, Forge.MUTED), 8)
            net.add(button("Stop ONYX (go offline)", primary = false) {
                startService(Intent(this@SettingsActivity, OnyxService::class.java).setAction(OnyxService.ACTION_STOP))
                finishAffinity()
            }, 10)
            add(net, 8)

            // ---------------- Security ----------------
            add(text("SECURITY", 11f, Forge.MUTED, mono = true).apply { letterSpacing = 0.2f }, 24)
            val sec = card()
            sec.add(text("Key storage: " + app.vault.backend(), 14f, Forge.TEXT))
            sec.add(text("Protocol: PQXDH (X25519 + ML-KEM-1024) · Triple Ratchet (Double Ratchet + SPQR/ML-KEM-768) · libsignal", 11f, Forge.MUTED, mono = true), 6)
            sec.add(switch("App lock (biometric / device PIN)", app.appLockEnabled) { v -> app.appLockEnabled = v; app.unlocked = true }, 10)
            sec.add(button("Panic wipe", primary = false) { confirmWipe() }, 12)
            add(sec, 8)

            add(button("About ONYX", primary = false) { startActivity(Intent(this@SettingsActivity, AboutActivity::class.java)) }, 24)
        }
    }

    private fun confirmSnowflake(onYes: () -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Help others reach Tor?")
            .setMessage(
                "Snowflake lets people in censored countries connect to Tor through your phone.\n\n" +
                "• It is NOT an exit node: nothing leaves the Tor network from your IP.\n" +
                "• Your public IP is visible to the Tor Project's Snowflake broker and to the users you help (that's how WebRTC works).\n" +
                "• It never touches your ONYX messages or contacts.\n" +
                "• Only on unmetered Wi-Fi while charging, within your daily cap."
            )
            .setPositiveButton("Turn on") { _, _ -> onYes() }
            .setNegativeButton("Cancel") { _, _ -> build() }
            .show()
    }

    private fun confirmWipe() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Panic wipe")
            .setMessage("Destroys the hardware-backed master key, then deletes every contact, message, session and your onion identity. This cannot be undone.")
            .setPositiveButton("Wipe everything") { _, _ ->
                app.panicWipe()
                finishAffinity()
                android.os.Process.killProcess(android.os.Process.myPid())
            }
            .setNegativeButton("Cancel", null).show()
    }
}
