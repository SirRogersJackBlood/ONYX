// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.core

/**
 * Size-hiding padding. Every plaintext and every wire frame is rounded up to one of a small
 * set of bucket sizes, so a short "ok" and a 900-character message are indistinguishable.
 *
 * Scheme: ISO/IEC 7816-4 — append 0x80 then 0x00 until the bucket boundary.
 * Unpadding scans from the end and fails closed on anything malformed.
 */
object Padding {
    /** Plaintext buckets (before libsignal encryption). Signal pads to multiples of 160 bytes; we use coarser buckets. */
    val MESSAGE_BUCKETS = intArrayOf(256, 1024, 4096, 16384, 65536)

    /** Wire frame buckets on the Tor link. */
    val FRAME_BUCKETS = intArrayOf(1024, 4096, 16384, 65536, 262144)

    fun bucketFor(len: Int, buckets: IntArray): Int =
        buckets.firstOrNull { it >= len + 1 } ?: throw IllegalArgumentException("payload too large ($len)")

    fun pad(data: ByteArray, buckets: IntArray = MESSAGE_BUCKETS): ByteArray {
        val target = bucketFor(data.size, buckets)
        val out = ByteArray(target)
        System.arraycopy(data, 0, out, 0, data.size)
        out[data.size] = 0x80.toByte()
        return out
    }

    fun unpad(padded: ByteArray, buckets: IntArray = MESSAGE_BUCKETS): ByteArray {
        if (padded.size !in buckets) throw MalformedException("not a bucket size")
        var i = padded.size - 1
        while (i >= 0 && padded[i] == 0.toByte()) i--
        if (i < 0 || padded[i] != 0x80.toByte()) throw MalformedException("bad padding")
        return padded.copyOfRange(0, i)
    }
}
