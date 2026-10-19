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

            // ---------------- Mailbox ----------------
            add(text("MAILBOX", 11f, Forge.MUTED, mono = true).apply { letterSpacing = 0.2f }, 24)
            val mbox = card()
            val myBox = app.messenger.myMailbox()
            when {
                app.mailboxMode -> {
                    mbox.add(text("This phone is a mailbox", 17f, Forge.TEXT))
                    mbox.add(text("It holds encrypted messages for its owner and nothing else. ${app.mailboxStore.count()} waiting.", 13f, Forge.MUTED), 6)
                    mbox.add(button("Stop being a mailbox", primary = false) { confirmMailboxOff() }, 10)
                }
                myBox != null -> {
                    mbox.add(text("Your mailbox: ${myBox.name}", 17f, Forge.TEXT))
                    mbox.add(text("When your phone is offline, contacts leave encrypted messages there and you collect them " +
                        "every minute once you're back. It can't read them.", 13f, Forge.MUTED), 6)
                    mbox.add(button("Remove my mailbox", primary = false) {
                        app.messenger.removeMyMailbox(); android.widget.Toast.makeText(this@SettingsActivity, "Mailbox removed", android.widget.Toast.LENGTH_SHORT).show(); build()
                    }, 10)
                }
                else -> {
                    mbox.add(text("Get messages while you're offline", 17f, Forge.TEXT))
                    mbox.add(text("Turn a spare phone into your own mailbox: it stays online and holds encrypted messages " +
                        "until you collect them. No server, nobody else's phone. See docs/MAILBOX.md.", 13f, Forge.MUTED), 6)
                    mbox.add(button("Pair my mailbox", primary = false) {
                        startActivity(Intent(this@SettingsActivity, ScanActivity::class.java).putExtra(ScanActivity.EXTRA_MAILBOX, true))
                    }, 10)
                    if (app.contacts.all().isEmpty()) {
                        mbox.add(button("Use THIS phone as a mailbox", primary = false) { confirmMailboxOn() }, 8)
                        mbox.add(text("Only on a phone with no contacts. It will pair with one owner and refuse everyone else.", 11f, Forge.MUTED), 4)
                    }
                }
            }
            add(mbox, 8)

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
            if (app.vault.lockMissing()) {
                sec.add(text("NO SCREEN LOCK", 11f, Forge.DANGER, mono = true).apply { letterSpacing = 0.2f }, 10)
                sec.add(text("This phone has no PIN, pattern or password. Anyone who picks it up can open ONYX, " +
                    "and its keys are not tied to a lock. Set a screen lock to protect your messages.", 13f, Forge.DANGER), 4)
                sec.add(button("Set a screen lock", primary = true) {
                    startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
                }, 8)
            } else if (!app.vault.lockBound()) {
                sec.add(text("Key not tied to the screen lock: Android 12-14 would erase it if the lock were ever " +
                    "removed. It stays in the phone's secure hardware.", 11f, Forge.MUTED), 6)
            }
            val a11y = AccessibilityCheck.enabled(this@SettingsActivity)
            if (a11y.isNotEmpty()) {
                sec.add(text("ACCESSIBILITY APPS ON: " + a11y.joinToString(", ") { it.label }, 11f, Forge.WARN, mono = true), 10)
                sec.add(text(if (AccessibilityCheck.sensitiveScreensSupported) "ONYX screens are hidden from those that aren't accessibility tools."
                    else "They can read ONYX's screen on this Android version.", 11f, Forge.MUTED), 4)
                sec.add(button("Review accessibility apps", primary = false) {
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                }, 6)
            } else sec.add(text("No accessibility apps are on.", 11f, Forge.MUTED), 10)
            sec.add(text("Protocol: PQXDH (X25519 + ML-KEM-1024) · Triple Ratchet (Double Ratchet + SPQR/ML-KEM-768) · libsignal", 11f, Forge.MUTED, mono = true), 6)
            sec.add(switch("App lock (biometric / device PIN)", app.appLockEnabled) { v -> app.appLockEnabled = v; app.unlocked = true }, 10)
            sec.add(button("Panic wipe", primary = false) { confirmWipe() }, 12)
            add(sec, 8)

            // ---------------- Developer ----------------
            add(text("DEVELOPER", 11f, Forge.MUTED, mono = true).apply { letterSpacing = 0.2f }, 24)
            val dev = card()
            dev.add(text("Transit benchmark: measures real delivery time over Tor with padding-only test frames. " +
                "Nothing is stored or shown to the other person; only paired contacts can take part.", 12f, Forge.MUTED))
            dev.add(switch("Accept benchmark from contacts (turns itself off after 1 hour)", app.messenger.server.acceptBench) { v ->
                app.messenger.server.acceptBench = v
            }, 8)
            dev.add(button("Run transit benchmark", primary = false) { chooseBenchContact() }, 8)
            add(dev, 8)

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

    private fun confirmMailboxOn() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Make this phone a mailbox?")
            .setMessage("This phone will only hold encrypted messages for one owner: the first phone that pairs with it.\n\n" +
                "• It can't read anything it holds.\n• It learns an opaque tag per sender and when messages arrive, never names.\n" +
                "• Messages are deleted when collected, or after 14 days.\n• It can't chat or pair with anyone else.")
            .setPositiveButton("Make mailbox") { _, _ ->
                runCatching { app.mailboxMode = true }.onFailure {
                    android.widget.Toast.makeText(this, it.message ?: "Not possible", android.widget.Toast.LENGTH_LONG).show()
                }
                build()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirmMailboxOff() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Stop being a mailbox?")
            .setMessage("Deletes every message waiting here (${app.mailboxStore.count()}) and forgets the owner. The owner should remove this mailbox on their phone too.")
            .setPositiveButton("Stop and delete") { _, _ ->
                app.contacts.all().forEach { c -> app.outbox.removeForContact(c.id); app.crypto.forget(c); app.contacts.delete(c.id) }
                app.mailboxMode = false
                build()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun chooseBenchContact() {
        val list = app.contacts.all().filter { app.crypto.hasSession(it) }
        if (list.isEmpty()) { android.widget.Toast.makeText(this, "Pair with a second device first.", android.widget.Toast.LENGTH_LONG).show(); return }
        val rounds = intArrayOf(10, 20, 50)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Benchmark which contact?")
            .setItems(list.map { it.name }.toTypedArray()) { _, i ->
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Rounds per test")
                    .setItems(rounds.map { "$it (about ${it * 30 / 60 + 1} min)" }.toTypedArray()) { _, r -> runBench(list[i], rounds[r]) }
                    .show()
            }.show()
    }

    /** Runs the benchmark with real battery (charge counter) and data (this app's bytes) readings around it. */
    private fun runBench(c: io.github.proteu5.onyx.data.Contact, rounds: Int) {
        val status = text("Starting…", 12f, Forge.CYAN, mono = true).apply { setPadding(dp(20), dp(12), dp(20), dp(12)) }
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val dlg = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Transit benchmark").setView(status).setCancelable(false)
            .setNegativeButton("Stop", null).show()
        // Keep the dialog open after Stop: it closes by itself with the partial report.
        dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            stop.set(true); status.text = "Stopping after the current send (can take up to a minute over Tor)…"
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        thread {
            val bm = getSystemService(android.os.BatteryManager::class.java)
            val uid = android.os.Process.myUid()
            val charge0 = bm.getLongProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            val rx0 = android.net.TrafficStats.getUidRxBytes(uid); val tx0 = android.net.TrafficStats.getUidTxBytes(uid)
            val t0 = System.currentTimeMillis()
            val report = runCatching { app.messenger.benchmark(c, rounds, { stop.get() }) { p -> runOnUiThread { if (!stop.get()) status.text = p } } }
                .getOrElse { "Benchmark failed: ${it.message}" }
            val mins = (System.currentTimeMillis() - t0) / 60_000.0
            val charge1 = bm.getLongProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            val rx = android.net.TrafficStats.getUidRxBytes(uid) - rx0; val tx = android.net.TrafficStats.getUidTxBytes(uid) - tx0
            val charging = bm.isCharging
            val full = report + "\nduration_min|%.1f\n".format(java.util.Locale.US, mins) +
                "data_sent_MB|%.2f\ndata_received_MB|%.2f\n".format(java.util.Locale.US, tx / 1e6, rx / 1e6) +
                (if (charge0 > 0 && charge1 > 0) "battery_used_mAh|%.1f%s\n".format(java.util.Locale.US, (charge0 - charge1) / 1000.0,
                    if (charging) " (CHARGING: unplug for a battery reading)" else "") else "battery_used_mAh|not reported by this phone\n") +
                "device|${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}\n"
            runOnUiThread {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                dlg.dismiss()
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Benchmark result")
                    .setMessage(full)
                    .setPositiveButton("Copy") { _, _ ->
                        getSystemService(android.content.ClipboardManager::class.java)
                            ?.setPrimaryClip(android.content.ClipData.newPlainText("onyx benchmark", full))
                    }
                    .setNegativeButton("Close", null).show()
            }
        }
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
