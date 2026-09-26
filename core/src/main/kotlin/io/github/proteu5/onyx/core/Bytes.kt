// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.core

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom

/** Byte helpers shared by every ONYX codec. No logging, no toString() of secrets. */
object Bytes {
    private val rng = SecureRandom()

    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    /** Constant-time comparison (MessageDigest.isEqual is constant-time since JDK 6u17 / all Android). */
    fun ctEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    /** Best-effort wipe of secret material held in a mutable array. */
    fun wipe(vararg arrays: ByteArray?) { arrays.forEach { it?.fill(0) } }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}

/** RFC 4648 base32, lowercase alphabet, no padding (the form Tor uses for .onion). */
object Base32 {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

    fun encode(data: ByteArray): String {
        val out = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    fun decode(s: String): ByteArray {
        val out = ByteArrayOutputStream(s.length * 5 / 8)
        var buffer = 0
        var bits = 0
        for (c in s.lowercase()) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "invalid base32" }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        // Reject non-canonical trailing bits.
        require(buffer and ((1 shl bits) - 1) == 0) { "non-canonical base32" }
        return out.toByteArray()
    }
}

/** Minimal length-checked binary writer. Big-endian. */
class ByteWriter(initial: Int = 256) {
    private val out = ByteArrayOutputStream(initial)
    fun u8(v: Int) = apply { require(v in 0..0xff); out.write(v) }
    fun u16(v: Int) = apply { require(v in 0..0xffff); out.write(v ushr 8); out.write(v and 0xff) }
    fun u32(v: Long) = apply {
        require(v in 0..0xffffffffL)
        for (s in intArrayOf(24, 16, 8, 0)) out.write(((v ushr s) and 0xff).toInt())
    }
    fun u64(v: Long) = apply { for (s in 56 downTo 0 step 8) out.write(((v ushr s) and 0xff).toInt()) }
    fun raw(b: ByteArray) = apply { out.write(b) }
    fun fixed(b: ByteArray, len: Int) = apply { require(b.size == len) { "expected $len bytes" }; out.write(b) }
    /** u16 length-prefixed field. */
    fun bytes16(b: ByteArray) = apply { u16(b.size); out.write(b) }
    /** u32 length-prefixed field. */
    fun bytes32(b: ByteArray) = apply { u32(b.size.toLong()); out.write(b) }
    fun str16(s: String) = bytes16(s.toByteArray(Charsets.UTF_8))
    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Strict reader: every read is bounds-checked; [end] asserts no trailing bytes. */
class ByteReader(private val b: ByteArray) {
    var pos = 0
        private set
    val remaining get() = b.size - pos

    private fun need(n: Int) { if (n < 0 || n > remaining) throw MalformedException("truncated") }
    fun u8(): Int { need(1); return b[pos++].toInt() and 0xff }
    fun u16(): Int = (u8() shl 8) or u8()
    fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()
    fun u64(): Long { var v = 0L; repeat(8) { v = (v shl 8) or u8().toLong() }; return v }
    fun raw(n: Int): ByteArray { need(n); return b.copyOfRange(pos, pos + n).also { pos += n } }
    fun bytes16(max: Int = 0xffff): ByteArray { val n = u16(); if (n > max) throw MalformedException("field too large"); return raw(n) }
    fun bytes32(max: Int): ByteArray { val n = u32(); if (n > max) throw MalformedException("field too large"); return raw(n.toInt()) }
    fun str16(max: Int = 4096): String = String(bytes16(max), Charsets.UTF_8)
    fun rest(): ByteArray = raw(remaining)
    fun end() { if (remaining != 0) throw MalformedException("trailing bytes") }
}

class MalformedException(msg: String) : Exception(msg)
