// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.core

/**
 * Transport stack, strongest privacy first. The selector never silently downgrades:
 * SMS is used only when the user enabled it for that contact AND nothing better is available,
 * and the UI shows [Transport.exposure] on every message sent that way.
 */
enum class Transport(val rank: Int, val exposure: String) {
    TOR_DIRECT(0, "Onion-to-onion. Nobody sees IPs, numbers or who talks to whom."),
    TOR_MAILBOX(1, "Stored on the recipient's own mailbox onion until they come online."),
    NEARBY(2, "Local Bluetooth/Wi-Fi Direct. Visible only to radios in range."),
    SMS(3, "Content encrypted, but the carrier sees both numbers, time and size.");
}

data class TransportContext(
    val torReady: Boolean,
    val peerReachable: Boolean?,     // null = unknown (not yet tried)
    val peerHasMailbox: Boolean,
    val nearbyPeerVisible: Boolean,
    val smsAllowedForContact: Boolean,
    val hasCellular: Boolean,
)

object TransportSelector {
    /** Ordered list of transports worth attempting right now. Empty = queue and retry later. */
    fun plan(c: TransportContext): List<Transport> = buildList {
        if (c.torReady && c.peerReachable != false) add(Transport.TOR_DIRECT)
        if (c.torReady && c.peerHasMailbox) add(Transport.TOR_MAILBOX)
        if (c.nearbyPeerVisible) add(Transport.NEARBY)
        if (c.smsAllowedForContact && c.hasCellular && !c.torReady) add(Transport.SMS)
    }

    /** Exponential backoff for the outbox, capped, with jitter so retries don't form a timing fingerprint. */
    fun retryDelayMs(attempt: Int, jitter: Double): Long {
        val base = 15_000L shl minOf(attempt, 7) // 15s … 32m
        val capped = minOf(base, 30 * 60_000L)
        return (capped * (0.75 + 0.5 * jitter.coerceIn(0.0, 1.0))).toLong()
    }
}
