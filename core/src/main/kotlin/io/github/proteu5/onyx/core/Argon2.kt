// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.core

/**
 * BLAKE2b (RFC 7693) and Argon2 (RFC 9106), used to slow down guessing of handles (v1.5).
 *
 * Standard algorithms, not ONYX inventions: verified in the unit tests against the RFC 9106 test vectors for
 * Argon2d, Argon2i and Argon2id, against the reference implementation's published vector at the parameters ONYX
 * uses (64 MiB, p = 1), and BLAKE2b against independent output. Android ships neither algorithm, and this
 * keeps the core free of dependencies (as with the SHA3 code for onion addresses).
 */
class Blake2b(private val outLen: Int, key: ByteArray = ByteArray(0)) {
    private val h = IV.copyOf()
    private val buf = ByteArray(128)
    private var bufLen = 0
    private var t0 = 0L; private var t1 = 0L

    init {
        require(outLen in 1..64 && key.size <= 64)
        h[0] = h[0] xor (0x01010000L or (key.size.toLong() shl 8) or outLen.toLong())
        if (key.isNotEmpty()) { update(key); bufLen = 128 }
    }

    fun update(data: ByteArray, off: Int = 0, len: Int = data.size): Blake2b {
        var o = off; var n = len
        while (n > 0) {
            if (bufLen == 128) { inc(128); compress(false); bufLen = 0 }
            val take = minOf(n, 128 - bufLen)
            System.arraycopy(data, o, buf, bufLen, take)
            bufLen += take; o += take; n -= take
        }
        return this
    }

    fun digest(): ByteArray {
        inc(bufLen.toLong())
        java.util.Arrays.fill(buf, bufLen, 128, 0)
        compress(true)
        val out = ByteArray(outLen)
        for (i in 0 until outLen) out[i] = (h[i / 8] ushr (8 * (i % 8))).toByte()
        return out
    }

    private fun inc(n: Long) { t0 += n; if (java.lang.Long.compareUnsigned(t0, n) < 0) t1++ }

    private fun compress(last: Boolean) {
        val m = LongArray(16) { i -> le64(buf, i * 8) }
        val v = LongArray(16)
        for (i in 0 until 8) { v[i] = h[i]; v[i + 8] = IV[i] }
        v[12] = v[12] xor t0; v[13] = v[13] xor t1
        if (last) v[14] = v[14].inv()
        for (r in 0 until 12) {
            val s = SIGMA[r % 10]
            g(v, 0, 4, 8, 12, m[s[0]], m[s[1]]); g(v, 1, 5, 9, 13, m[s[2]], m[s[3]])
            g(v, 2, 6, 10, 14, m[s[4]], m[s[5]]); g(v, 3, 7, 11, 15, m[s[6]], m[s[7]])
            g(v, 0, 5, 10, 15, m[s[8]], m[s[9]]); g(v, 1, 6, 11, 12, m[s[10]], m[s[11]])
            g(v, 2, 7, 8, 13, m[s[12]], m[s[13]]); g(v, 3, 4, 9, 14, m[s[14]], m[s[15]])
        }
        for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
    }

    private fun g(v: LongArray, a: Int, b: Int, c: Int, d: Int, x: Long, y: Long) {
        v[a] = v[a] + v[b] + x; v[d] = java.lang.Long.rotateRight(v[d] xor v[a], 32)
        v[c] = v[c] + v[d]; v[b] = java.lang.Long.rotateRight(v[b] xor v[c], 24)
        v[a] = v[a] + v[b] + y; v[d] = java.lang.Long.rotateRight(v[d] xor v[a], 16)
        v[c] = v[c] + v[d]; v[b] = java.lang.Long.rotateRight(v[b] xor v[c], 63)
    }

    companion object {
        private val IV = longArrayOf(
            0x6a09e667f3bcc908L, -0x4498517a7b3558c5L, 0x3c6ef372fe94f82bL, -0x5ab00ac5a0e2c90fL,
            0x510e527fade682d1L, -0x64fa9773d4c193e1L, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L)
        private val SIGMA = arrayOf(
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
            intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
            intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
            intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
            intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
            intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
            intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
            intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
            intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
            intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0))

        fun le64(b: ByteArray, o: Int): Long {
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xff)
            return v
        }

        fun hash(outLen: Int, vararg parts: ByteArray): ByteArray {
            val b = Blake2b(outLen); parts.forEach { b.update(it) }; return b.digest()
        }
    }
}

object Argon2 {
    enum class Type(val y: Int) { D(0), I(1), ID(2) }
    private const val VERSION = 0x13
    private const val QWORDS = 128          // 1 KiB block = 128 × 64-bit words

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    /** H' (RFC 9106 §3.3): variable-length hash. */
    private fun hPrime(outLen: Int, input: ByteArray): ByteArray {
        if (outLen <= 64) return Blake2b.hash(outLen, le32(outLen), input)
        val out = ByteArray(outLen)
        val r = (outLen + 31) / 32 - 2
        var v = Blake2b.hash(64, le32(outLen), input)
        System.arraycopy(v, 0, out, 0, 32)
        var pos = 32
        for (i in 2..r) { v = Blake2b.hash(64, v); System.arraycopy(v, 0, out, pos, 32); pos += 32 }
        v = Blake2b.hash(outLen - 32 * r, v)
        System.arraycopy(v, 0, out, pos, v.size)
        return out
    }

    /**
     * Argon2 per RFC 9106. [memoryKiB] blocks of 1 KiB, [iterations] passes, [lanes] parallelism (computed
     * sequentially here), [secret] and [ad] optional.
     */
    fun hash(type: Type, password: ByteArray, salt: ByteArray, iterations: Int, memoryKiB: Int, lanes: Int, outLen: Int,
             secret: ByteArray = ByteArray(0), ad: ByteArray = ByteArray(0)): ByteArray {
        require(lanes in 1..0xFFFFFF && outLen >= 4 && iterations >= 1 && memoryKiB >= 8 * lanes && salt.size >= 8)
        val h0 = Blake2b(64).update(le32(lanes)).update(le32(outLen)).update(le32(memoryKiB)).update(le32(iterations))
            .update(le32(VERSION)).update(le32(type.y))
            .update(le32(password.size)).update(password).update(le32(salt.size)).update(salt)
            .update(le32(secret.size)).update(secret).update(le32(ad.size)).update(ad).digest()

        val segLen = memoryKiB / (4 * lanes)
        val laneLen = segLen * 4
        val mem = Array(lanes * laneLen) { LongArray(QWORDS) }
        fun blk(l: Int, j: Int) = mem[l * laneLen + j]

        for (l in 0 until lanes) for (j in 0..1) {
            val b = hPrime(1024, h0 + le32(j) + le32(l))
            val dst = blk(l, j)
            for (k in 0 until QWORDS) dst[k] = Blake2b.le64(b, k * 8)
        }

        val zero = LongArray(QWORDS)
        val tmp = LongArray(QWORDS)
        val addr = LongArray(QWORDS); val input = LongArray(QWORDS)

        for (pass in 0 until iterations) for (slice in 0 until 4) for (lane in 0 until lanes) {
            val dataIndependent = type == Type.I || (type == Type.ID && pass == 0 && slice < 2)
            if (dataIndependent) {
                java.util.Arrays.fill(input, 0)
                input[0] = pass.toLong(); input[1] = lane.toLong(); input[2] = slice.toLong()
                input[3] = (lanes * laneLen).toLong(); input[4] = iterations.toLong(); input[5] = type.y.toLong()
            }
            val start = if (pass == 0 && slice == 0) 2 else 0
            if (dataIndependent && start == 2) nextAddresses(input, addr, zero, tmp)
            for (idx in start until segLen) {
                val cur = slice * segLen + idx
                val prev = if (cur == 0) laneLen - 1 else cur - 1
                val rand: Long = if (dataIndependent) {
                    if (idx % QWORDS == 0) nextAddresses(input, addr, zero, tmp)
                    addr[idx % QWORDS]
                } else blk(lane, prev)[0]
                val j1 = rand and 0xFFFFFFFFL
                val j2 = rand ushr 32
                val refLane = if (pass == 0 && slice == 0) lane else (j2 % lanes).toInt()
                val sameLane = refLane == lane
                val area: Long = if (pass == 0) {
                    if (slice == 0) (idx - 1).toLong()
                    else if (sameLane) (slice * segLen + idx - 1).toLong()
                    else (slice * segLen + if (idx == 0) -1 else 0).toLong()
                } else {
                    if (sameLane) (laneLen - segLen + idx - 1).toLong()
                    else (laneLen - segLen + if (idx == 0) -1 else 0).toLong()
                }
                var rel = (j1 * j1) ushr 32
                rel = area - 1 - ((area * rel) ushr 32)
                val startPos = if (pass == 0 || slice == 3) 0L else ((slice + 1) * segLen).toLong()
                val refIdx = ((startPos + rel) % laneLen).toInt()
                val out = blk(lane, cur)
                compressG(blk(lane, prev), blk(refLane, refIdx), out, xorInto = pass > 0, tmp = tmp)
            }
        }

        val c = blk(0, laneLen - 1).copyOf()
        for (l in 1 until lanes) { val b = blk(l, laneLen - 1); for (k in 0 until QWORDS) c[k] = c[k] xor b[k] }
        val cb = ByteArray(1024)
        for (k in 0 until QWORDS) for (i in 0 until 8) cb[k * 8 + i] = (c[k] ushr (8 * i)).toByte()
        val tag = hPrime(outLen, cb)
        mem.forEach { java.util.Arrays.fill(it, 0) }
        return tag
    }

    private fun nextAddresses(input: LongArray, addr: LongArray, zero: LongArray, tmp: LongArray) {
        input[6]++
        compressG(zero, input, addr, false, tmp)
        compressG(zero, addr.copyOf(), addr, false, tmp)
    }

    /** G(X, Y) (RFC 9106 §3.5); with [xorInto] the result is XORed into [out] (passes after the first). */
    private fun compressG(x: LongArray, y: LongArray, out: LongArray, xorInto: Boolean, tmp: LongArray) {
        val r = LongArray(QWORDS) { x[it] xor y[it] }
        System.arraycopy(r, 0, tmp, 0, QWORDS)
        for (i in 0 until 8) {        // rows: 16 consecutive words
            val b = i * 16
            p(tmp, b, b + 1, b + 2, b + 3, b + 4, b + 5, b + 6, b + 7, b + 8, b + 9, b + 10, b + 11, b + 12, b + 13, b + 14, b + 15)
        }
        for (i in 0 until 8) {        // columns: word pairs 2i, 2i+1 of each row
            val b = 2 * i
            p(tmp, b, b + 1, b + 16, b + 17, b + 32, b + 33, b + 48, b + 49, b + 64, b + 65, b + 80, b + 81, b + 96, b + 97, b + 112, b + 113)
        }
        if (xorInto) for (k in 0 until QWORDS) out[k] = out[k] xor tmp[k] xor r[k]
        else for (k in 0 until QWORDS) out[k] = tmp[k] xor r[k]
    }

    private fun p(v: LongArray, a0: Int, a1: Int, a2: Int, a3: Int, a4: Int, a5: Int, a6: Int, a7: Int,
                  a8: Int, a9: Int, a10: Int, a11: Int, a12: Int, a13: Int, a14: Int, a15: Int) {
        gb(v, a0, a4, a8, a12); gb(v, a1, a5, a9, a13); gb(v, a2, a6, a10, a14); gb(v, a3, a7, a11, a15)
        gb(v, a0, a5, a10, a15); gb(v, a1, a6, a11, a12); gb(v, a2, a7, a8, a13); gb(v, a3, a4, a9, a14)
    }

    private fun fBlaMka(x: Long, y: Long): Long = x + y + 2 * ((x and 0xFFFFFFFFL) * (y and 0xFFFFFFFFL))

    private fun gb(v: LongArray, a: Int, b: Int, c: Int, d: Int) {
        v[a] = fBlaMka(v[a], v[b]); v[d] = java.lang.Long.rotateRight(v[d] xor v[a], 32)
        v[c] = fBlaMka(v[c], v[d]); v[b] = java.lang.Long.rotateRight(v[b] xor v[c], 24)
        v[a] = fBlaMka(v[a], v[b]); v[d] = java.lang.Long.rotateRight(v[d] xor v[a], 16)
        v[c] = fBlaMka(v[c], v[d]); v[b] = java.lang.Long.rotateRight(v[b] xor v[c], 63)
    }
}
