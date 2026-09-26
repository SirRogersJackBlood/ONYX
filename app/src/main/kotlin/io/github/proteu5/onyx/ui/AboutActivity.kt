// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.ui

import android.os.Bundle
import android.view.Gravity
import io.github.proteu5.onyx.R

class AboutActivity : OnyxActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen("ONYX", "CRUCIBLE ENGINE") {
            add(image(R.drawable.raven_credit, 200), 16)
            add(text("DESIGN BY", 11f, Forge.MUTED, mono = true).apply { gravity = Gravity.CENTER; letterSpacing = 0.3f }, 8)
            add(text("SirRogersJackBlood", 16f, Forge.CYAN, mono = true).apply { gravity = Gravity.CENTER; letterSpacing = 0.12f }, 2)
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
            add(image(R.drawable.forge_sigil, 160), 20)
            add(text(
                "Free software under the GNU AGPL-3.0.\nSource: github.com/SirRogersJackBlood/ONYX\nWebsite: 0nyx.up.railway.app\n\n" +
                "Built on: libsignal (Signal Messenger, AGPL-3.0) · Tor via tor-android and jtorctl (The Tor Project, Guardian Project, BSD-3) · " +
                "Snowflake via IPtProxy (Tor Project, Guardian Project) · ZXing (Apache-2.0).\n\n" +
                "No analytics. No crash reporting. No Google services.", 12f, Forge.MUTED), 16)
        }
    }
}
