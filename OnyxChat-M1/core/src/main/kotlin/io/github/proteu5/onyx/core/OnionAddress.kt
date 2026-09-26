// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.core

/**
 * Tor v3 onion address (rend-spec-v3 §6):
 *   onion_address = base32(PUBKEY | CHECKSUM | VERSION) + ".onion"
 *   CHECKSUM      = SHA3-256(".onion checksum" | PUBKEY | VERSION)[:2]
 *   VERSION       = 0x03
 *
 * Validating the checksum means a mistyped / corrupted / tampered QR payload is rejected
 * before we ever ask Tor to connect to it.
 */
class OnionAddress private constructor(val publicKey: ByteArray) {

    /** 56-char label without the ".onion" suffix. */
    val label: String = Base32.encode(publicKey + checksum(publicKey) + byteArrayOf(VERSION))

    val hostname: String get() = "$label.onion"

    override fun equals(other: Any?) = other is OnionAddress && Bytes.ctEquals(publicKey, other.publicKey)
    override fun hashCode() = publicKey.contentHashCode()
    override fun toString() = hostname

    companion object {
        private const val VERSION: Byte = 3
        private val PREFIX = ".onion checksum".toByteArray(Charsets.US_ASCII)

        private fun checksum(pk: ByteArray): ByteArray =
            Sha3.sha3_256(PREFIX + pk + byteArrayOf(VERSION)).copyOfRange(0, 2)

        fun fromPublicKey(pk: ByteArray): OnionAddress {
            require(pk.size == 32) { "ed25519 public key must be 32 bytes" }
            return OnionAddress(pk.copyOf())
        }

        /** Accepts "xxxx.onion" or bare 56-char label. Throws MalformedException on any defect. */
        fun parse(input: String): OnionAddress {
            val label = input.trim().lowercase().removeSuffix(".onion")
            if (label.length != 56) throw MalformedException("onion length")
            val raw = try { Base32.decode(label) } catch (e: IllegalArgumentException) { throw MalformedException("onion base32") }
            if (raw.size != 35) throw MalformedException("onion size")
            val pk = raw.copyOfRange(0, 32)
            val sum = raw.copyOfRange(32, 34)
            if (raw[34] != VERSION) throw MalformedException("onion version")
            if (!Bytes.ctEquals(sum, checksum(pk))) throw MalformedException("onion checksum")
            return OnionAddress(pk)
        }
    }
}

/**
 * Self-contained SHA3-256 (FIPS 202). Android's platform providers do not guarantee SHA3,
 * and we refuse to pull in BouncyCastle for one hash. Verified against the JDK's SHA3-256 in tests.
 */
object Sha3 {
    private val RC: LongArray = arrayOf(
        "0000000000000001",
        "0000000000008082",
        "800000000000808A",
        "8000000080008000",
        "000000000000808B",
        "0000000080000001",
        "8000000080008081",
        "8000000000008009",
        "000000000000008A",
        "0000000000000088",
        "0000000080008009",
        "000000008000000A",
        "000000008000808B",
        "800000000000008B",
        "8000000000008089",
        "8000000000008003",
        "8000000000008002",
        "8000000000000080",
        "000000000000800A",
        "800000008000000A",
        "8000000080008081",
        "8000000000008080",
        "0000000080000001",
        "8000000080008008",
    ).map { java.lang.Long.parseUnsignedLong(it, 16) }.toLongArray()
    private val ROT = intArrayOf(
        0, 1, 62, 28, 27, 36, 44, 6, 55, 20, 3, 10, 43, 25, 39, 41, 45, 15, 21, 8, 18, 2, 61, 56, 14
    )

    private fun keccakF(a: LongArray) {
        val c = LongArray(5)
        val b = LongArray(25)
        for (round in 0 until 24) {
            for (x in 0 until 5) c[x] = a[x] xor a[x + 5] xor a[x + 10] xor a[x + 15] xor a[x + 20]
            for (x in 0 until 5) {
                val d = c[(x + 4) % 5] xor java.lang.Long.rotateLeft(c[(x + 1) % 5], 1)
                for (y in 0 until 25 step 5) a[y + x] = a[y + x] xor d
            }
            for (x in 0 until 5) for (y in 0 until 5) {
                val i = x + 5 * y
                b[y + 5 * ((2 * x + 3 * y) % 5)] = java.lang.Long.rotateLeft(a[i], ROT[i])
            }
            for (y in 0 until 25 step 5) for (x in 0 until 5) {
                a[y + x] = b[y + x] xor (b[y + (x + 1) % 5].inv() and b[y + (x + 2) % 5])
            }
            a[0] = a[0] xor RC[round]
        }
    }

    fun sha3_256(input: ByteArray): ByteArray {
        val rate = 136
        val state = LongArray(25)
        // pad10*1 with SHA3 domain bits 0x06
        val padLen = rate - (input.size % rate)
        val msg = input.copyOf(input.size + padLen)
        msg[input.size] = (msg[input.size].toInt() xor 0x06).toByte()
        msg[msg.size - 1] = (msg[msg.size - 1].toInt() xor 0x80).toByte()
        var off = 0
        while (off < msg.size) {
            for (i in 0 until rate / 8) {
                var lane = 0L
                for (j in 0 until 8) lane = lane or ((msg[off + i * 8 + j].toLong() and 0xff) shl (8 * j))
                state[i] = state[i] xor lane
            }
            keccakF(state)
            off += rate
        }
        val out = ByteArray(32)
        for (i in 0 until 32) out[i] = (state[i / 8] ushr (8 * (i % 8))).toByte()
        return out
    }
}
