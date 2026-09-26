// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.core

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA256 (RFC 5869) and HMAC-SHA256 on platform JCA only.
 * These are used for pairing/link authentication; message confidentiality is libsignal's job.
 */
object Kdf {
    private const val HMAC = "HmacSHA256"

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(key, HMAC))
        parts.forEach { mac.update(it) }
        return mac.doFinal()
    }

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray =
        hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var off = 0
        var counter = 1
        while (off < length) {
            t = hmac(prk, t, info, byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - off)
            System.arraycopy(t, 0, out, off, n)
            off += n
            counter++
        }
        return out
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int = 32): ByteArray {
        val prk = extract(salt, ikm)
        try {
            return expand(prk, info.toByteArray(Charsets.UTF_8), length)
        } finally {
            Bytes.wipe(prk)
        }
    }

    /** Length-prefixed transcript so field boundaries can never be shifted between messages. */
    fun transcript(vararg fields: ByteArray): ByteArray {
        val w = ByteWriter()
        fields.forEach { w.bytes32(it) }
        return w.toByteArray()
    }
}
