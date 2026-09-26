// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import io.github.proteu5.onyx.R
import java.security.MessageDigest

class AboutActivity : OnyxActivity() {

    /** Release history shown in the app. Newest first; keep in sync with CHANGELOG.md. */
    private val history = listOf(
        "1.2.3" to "Credits on this screen: design, direction & QA by SirRogersJackBlood; development by Claude (Anthropic). Final pre-release before beta.",
        "1.2.1" to "People, not programs: 80-character plain messages, code symbols stripped, rate limit, no attachments. Origin story, and version tracking here.",
        "1.1.0" to "RAW wire view: watch the exact encrypted frames live, plus a size meter while typing.",
        "1.0.0-pre1" to "First pre-release: Tor peer-to-peer, PQXDH + Triple Ratchet, QR pairing, invite links, /FORGE rank.",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Read the version from the installed package itself, so it can never drift from the build.
        val pkg = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val versionName = pkg.versionName ?: "?"
        val versionCode = pkg.longVersionCode
        val debug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val channel = if (debug) "pre-release · debug build" else "release"
        val signer = runCatching {
            val cert = pkg.signingInfo!!.apkContentsSigners.first().toByteArray()
            MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02x".format(it) }
        }.getOrNull()

        screen("ONYX", "CRUCIBLE ENGINE") {
            add(image(R.drawable.raven_credit, 200), 16)
            add(text("DESIGN BY", 11f, Forge.MUTED, mono = true).apply { gravity = Gravity.CENTER; letterSpacing = 0.3f }, 8)
            add(text("SirRogersJackBlood", 16f, Forge.CYAN, mono = true).apply { gravity = Gravity.CENTER; letterSpacing = 0.12f }, 2)

            // ---------------- version ----------------
            val v = card().apply { gravity = Gravity.CENTER_HORIZONTAL }
            v.add(text("v$versionName", 26f, Forge.SILVER).apply { gravity = Gravity.CENTER; letterSpacing = 0.1f })
            v.add(text("BUILD $versionCode · ${channel.uppercase()}", 10f, Forge.CYAN, mono = true)
                .apply { gravity = Gravity.CENTER; letterSpacing = 0.15f }, 4)
            if (signer != null) {
                v.add(text("SIGNING CERT SHA-256", 9f, Forge.MUTED, mono = true).apply { gravity = Gravity.CENTER; letterSpacing = 0.2f }, 12)
                v.add(text(signer.chunked(16).joinToString("\n"), 10f, Forge.TEXT, mono = true).apply { gravity = Gravity.CENTER }, 4)
                v.add(text("Compare with the release notes on GitHub.", 11f, Forge.MUTED).apply { gravity = Gravity.CENTER }, 6)
            }
            add(v, 16)

            add(text(
                "Private messaging with no phone numbers, no accounts and no servers. " +
                "Messages travel onion-to-onion over Tor and are end-to-end encrypted with the Signal Protocol " +
                "(post-quantum PQXDH and Triple Ratchet).", 14f, Forge.MUTED), 16)

            add(text("ORIGIN", 11f, Forge.MUTED, mono = true).apply { letterSpacing = 0.3f }, 24)
            add(text(
                "About three and a half years ago, while I was at work, an attacker used my machine as a sandbox " +
                "to unpack a remote-access toolkit built on Intel AMT's Local Manageability Service (LMS). " +
                "I found the file hashes. After further reports that I was under attack, including one to our EDR, " +
                "I followed the trail down the rabbit hole at home, quietly watching the attacker work.\n\n" +
                "While they pursued their malicious goals, I found a better use for the same building blocks: " +
                "a Gunyah hypervisor, strong encryption and low-level communication ports. " +
                "On paper, that was the birth of ONYX, under a different name.", 13f, Forge.TEXT), 6)

            add(text("A NOTE ON MESSAGES", 11f, Forge.MUTED, mono = true).apply { letterSpacing = 0.3f }, 20)
            add(text(
                "ONYX is for people, not programs. Messages are 80 characters on one line: letters, numbers, " +
                "emoji and basic punctuation. Code-like symbols and invisible characters are removed, there are no " +
                "attachments, sending is rate-limited, and no other app can send through ONYX.", 13f, Forge.MUTED), 6)

            // ---------------- history ----------------
            add(text("VERSION HISTORY", 11f, Forge.MUTED, mono = true).apply { letterSpacing = 0.3f }, 24)
            val h = card()
            history.forEachIndexed { i, (ver, notes) ->
                val current = ver == versionName
                h.add(text("v$ver" + if (current) "  ◆ INSTALLED" else "", 12f,
                    if (current) Forge.CYAN else Forge.SILVER, mono = true).apply { letterSpacing = 0.08f }, if (i == 0) 0 else 14)
                h.add(text(notes, 12f, Forge.MUTED), 3)
            }
            add(h, 6)

            add(image(R.drawable.forge_sigil, 160), 24)
            // ---------------- credits ----------------
            add(text("CREDITS", 11f, Forge.MUTED, mono = true).apply { letterSpacing = 0.3f }, 24)
            val c = card()
            c.add(text("DESIGN, DIRECTION & QA", 9f, Forge.MUTED, mono = true).apply { letterSpacing = 0.2f })
            c.add(text("SirRogersJackBlood", 14f, Forge.CYAN, mono = true), 2)
            c.add(text("DEVELOPMENT", 9f, Forge.MUTED, mono = true).apply { letterSpacing = 0.2f }, 12)
            c.add(text("Claude (Anthropic), AI pair-programmer", 14f, Forge.SILVER), 2)
            c.add(text("BUILT ON", 9f, Forge.MUTED, mono = true).apply { letterSpacing = 0.2f }, 12)
            c.add(text(
                "libsignal (Signal Messenger, AGPL-3.0) · Tor via tor-android and jtorctl (The Tor Project, Guardian Project, BSD-3) · " +
                "Snowflake via IPtProxy (The Tor Project, Guardian Project) · ZXing (Apache-2.0)", 12f, Forge.TEXT), 2)
            c.add(text(
                "ONYX's code was written with AI assistance and tested on real devices by its designer. " +
                "Independent security review is welcome: report issues privately via GitHub Security Advisories.", 12f, Forge.MUTED), 12)
            add(c, 6)

            add(text(
                "Free software under the GNU AGPL-3.0.\nSource: github.com/SirRogersJackBlood/ONYX\nWebsite: 0nyx.up.railway.app\n\n" +
                "No analytics. No crash reporting. No Google services.", 12f, Forge.MUTED), 16)
        }
    }
}
