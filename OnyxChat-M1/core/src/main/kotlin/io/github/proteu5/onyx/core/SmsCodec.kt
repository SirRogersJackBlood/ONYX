// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.core

import java.util.Base64

/**
 * Last-resort transport: carries an existing libsignal ciphertext over ordinary text SMS.
 * The content stays end-to-end encrypted, but the CARRIER SEES sender, recipient, time and
 * part count. The UI must label this path accordingly and it is off unless the user opts in
 * per contact.
 *
 * Every message is sent as a FIXED number of parts ([PARTS_PER_MESSAGE]) so the part count
 * does not leak length either. Part format (all GSM-7 safe characters):
 *   "ONX1" + msgId(6 base32) + index(1 char) + total(1 char) + base64url chunk
 */
object SmsCodec {
    private const val PREFIX = "ONX1"
    private const val HEADER = 4 + 6 + 1 + 1
    const val MAX_CHARS = 153           // one concatenated-SMS segment in GSM-7
    const val PARTS_PER_MESSAGE = 4
    private const val DIGITS = "0123456789abcdefghijklmnopqrstuv"

    /** Maximum ciphertext bytes that fit in the fixed part budget. */
    val MAX_PAYLOAD: Int = run {
        val chars = (MAX_CHARS - HEADER) * PARTS_PER_MESSAGE
        chars * 3 / 4 - 5 // minus framing (type + u32 len)
    }

    fun encode(signalType: Int, ciphertext: ByteArray): List<String> {
        require(ciphertext.size <= MAX_PAYLOAD) { "ciphertext too large for SMS" }
        val framed = ByteWriter().u8(signalType).bytes32(ciphertext).toByteArray()
        // Fill the whole budget with random bytes after the framed payload so every message is the same size.
        val capacity = (MAX_CHARS - HEADER) * PARTS_PER_MESSAGE * 3 / 4
        val filled = framed + Bytes.random(capacity - framed.size)
        val text = Base64.getUrlEncoder().withoutPadding().encodeToString(filled)
        val chunk = MAX_CHARS - HEADER
        val id = Base32.encode(Bytes.random(4)).substring(0, 6)
        return (0 until PARTS_PER_MESSAGE).map { i ->
            val part = text.substring(minOf(i * chunk, text.length), minOf((i + 1) * chunk, text.length))
            PREFIX + id + DIGITS[i] + DIGITS[PARTS_PER_MESSAGE] + part
        }
    }

    fun isOnyx(body: String) = body.startsWith(PREFIX) && body.length > HEADER

    /** Collects parts (possibly out of order) and yields (signalType, ciphertext) when complete. */
    class Reassembler(private val maxPending: Int = 64) {
        private val pending = LinkedHashMap<String, Array<String?>>()

        fun offer(sender: String, body: String): Pair<Int, ByteArray>? {
            if (!isOnyx(body)) return null
            val id = body.substring(4, 10)
            val idx = DIGITS.indexOf(body[10]); val total = DIGITS.indexOf(body[11])
            if (total != PARTS_PER_MESSAGE || idx !in 0 until total) throw MalformedException("sms header")
            val key = "$sender/$id"
            val slots = pending.getOrPut(key) { arrayOfNulls(total) }
            slots[idx] = body.substring(HEADER)
            while (pending.size > maxPending) pending.remove(pending.keys.first())
            if (slots.any { it == null }) return null
            pending.remove(key)
            val raw = try { Base64.getUrlDecoder().decode(slots.joinToString("")) } catch (e: IllegalArgumentException) { throw MalformedException("sms base64") }
            val r = ByteReader(raw)
            val type = r.u8()
            val ct = r.bytes32(MAX_PAYLOAD)
            return type to ct
        }
    }
}
