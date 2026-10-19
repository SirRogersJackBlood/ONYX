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
    val MESSAGE_BUCKETS = intArrayOf(256, 1024, 4096, 16384, 61440, 65536, 131072)

    /**
     * Wire frame buckets on the Tor link (what this version SENDS). Measured Oct 2026: because plaintext and
     * wire buckets are the same sizes, ~1 KB of encryption overhead pushes every frame into the next bucket, 4×
     * bigger (a text costs 16 KiB on the wire). [FRAME_BUCKETS_NEXT] fixes that; see [FRAME_BUCKETS_ACCEPTED].
     */
    val FRAME_BUCKETS = intArrayOf(1024, 4096, 16384, 65536, 262144)

    /** v1.5 wire buckets: each plaintext bucket plus 2 KiB of room for encryption overhead. */
    val FRAME_BUCKETS_NEXT = intArrayOf(1024, 3072, 6144, 18432, 63488, 67584, 133120, 262144)

    /**
     * What this version ACCEPTS: both sets, so phones on v1.4.4 already understand v1.5's leaner frames and
     * v1.5 can switch sending without breaking anyone.
     */
    val FRAME_BUCKETS_ACCEPTED = (FRAME_BUCKETS + FRAME_BUCKETS_NEXT).distinct().sorted().toIntArray()

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
