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
        TIMER(5);

        companion object { fun of(c: Int) = values().firstOrNull { it.code == c } ?: throw MalformedException("kind") }
    }

    fun encodePadded(): ByteArray = Padding.pad(
        ByteWriter(body.size + 32).u8(VERSION).u8(kind.code).fixed(id, 16).u32(sentAtMinute).bytes32(body).toByteArray()
    )

    companion object {
        const val VERSION = 1
        const val MAX_BODY = 60_000

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
