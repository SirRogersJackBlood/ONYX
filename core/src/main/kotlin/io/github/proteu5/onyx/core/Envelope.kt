// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.core

/**
 * The plaintext that goes INTO libsignal. Padded to a bucket before encryption so the
 * ciphertext length leaks only the bucket, never the message length.
 *
 * Timestamps are coarsened to the minute: the recipient does not need sub-minute precision,
 * and a device seized later reveals less about when messages were typed.
 */
class Envelope(
    val kind: Kind,
    val id: ByteArray,
    val sentAtMinute: Long,
    val body: ByteArray,
) {
    init { require(id.size == 16) }

    enum class Kind(val code: Int) {
        TEXT(1),
        /** Contributor badge, shared only with people you chat with. Body = [ForgeRank.Badge]. */
        BADGE(2),
        /** Delivery/read receipt. Body = referenced message id (16). */
        RECEIPT(3),
        /** First message after QR pairing; establishes the libsignal session. */
        PAIRED(4),
        /** Disappearing-message timer. Body = u32 seconds (0 = off). */
        TIMER(5),
        /** Sender's mailbox onion (v1.4.0). Body = 32-byte onion public key, or empty when removed. */
        MAILBOX(6),
        /** Group invite (v1.4.1). Body = [Groups.State]; joining is the receiver's choice. */
        GROUP_INVITE(7),
        /** Group roster, gossiped between members on change. Body = [Groups.State]. */
        GROUP_STATE(8),
        /** Group message, sent separately to each member. Body = [Groups.Text]. */
        GROUP_TEXT(9),
        /** Introduction between two members who aren't paired yet. Body = [Groups.Intro]. */
        GROUP_INTRO(10),
        /** Image (v1.4.4), already re-encoded without metadata. Body = [Media.Image]. Padded to one fixed size. */
        IMAGE(11);

        companion object { fun of(c: Int) = values().firstOrNull { it.code == c } ?: throw MalformedException("kind") }
    }

    /**
     * Text pads to at least 4 KiB, so any message up to 500 characters is the same size; every image pads to
     * exactly 60 KiB. Control messages use the smallest bucket that fits.
     */
    fun encodePadded(): ByteArray {
        val min = when (kind) { Kind.TEXT, Kind.GROUP_TEXT -> TEXT_BUCKET; Kind.IMAGE -> IMAGE_BUCKET; else -> 0 }
        return Padding.pad(
            ByteWriter(body.size + 32).u8(VERSION).u8(kind.code).fixed(id, 16).u32(sentAtMinute).bytes32(body).toByteArray(),
            Padding.MESSAGE_BUCKETS.filter { it >= min }.toIntArray())
    }

    companion object {
        const val VERSION = 1
        const val MAX_BODY = 130_000
        const val TEXT_BUCKET = 4096
        /**
         * 60 KiB: with Signal's overhead an image still fits a 64 KiB wire frame (measured: ~0.8 s on Wi-Fi vs
         * 1.2 s for the 256 KiB frames images used before, and 2–3× faster on a busy link).
         */
        const val IMAGE_BUCKET = 61_440

        fun text(s: String, nowMs: Long) = Envelope(Kind.TEXT, Bytes.random(16), nowMs / 60_000, s.toByteArray(Charsets.UTF_8))

        fun decodePadded(padded: ByteArray): Envelope {
            val r = ByteReader(Padding.unpad(padded))
            if (r.u8() != VERSION) throw MalformedException("envelope version")
            val e = Envelope(Kind.of(r.u8()), r.raw(16), r.u32(), r.bytes32(MAX_BODY))
            r.end()
            return e
        }
    }
}
