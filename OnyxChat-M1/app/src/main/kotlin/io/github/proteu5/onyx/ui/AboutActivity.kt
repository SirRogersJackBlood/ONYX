// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.ui

import android.os.Bundle
import android.view.Gravity
import io.github.proteu5.onyx.R

class AboutActivity : OnyxActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen("ONYX", "CRUCIBLE ENGINE") {
            add(image(R.drawable.author_proteu5, 180), 16)
            add(text("BY: Proteu5", 16f, Forge.CYAN, mono = true).apply { gravity = Gravity.CENTER; letterSpacing = 0.2f }, 8)
            add(text(
                "Private messaging with no phone numbers, no accounts and no servers. " +
                "Messages travel onion-to-onion over Tor and are end-to-end encrypted with the Signal Protocol " +
                "(post-quantum PQXDH and Triple Ratchet).", 14f, Forge.MUTED), 16)
            add(image(R.drawable.forge_sigil, 160), 20)
            add(text(
                "Free software under the GNU AGPL-3.0.\n\n" +
                "Built on: libsignal (Signal Messenger, AGPL-3.0) · Tor via tor-android and jtorctl (The Tor Project, Guardian Project, BSD-3) · " +
                "Snowflake via IPtProxy (Tor Project, Guardian Project) · ZXing (Apache-2.0).\n\n" +
                "No analytics. No crash reporting. No Google services.", 12f, Forge.MUTED), 16)
        }
    }
}
