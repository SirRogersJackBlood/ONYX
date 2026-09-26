// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * ONYX peer link protocol, carried inside a Tor onion-service stream.
 *
 * Every frame on the wire is:  u32 length | padded(type | body)
 * where the padded block is always one of [Padding.FRAME_BUCKETS]. Tor already encrypts the
 * stream end-to-end; the padding hides message sizes from anyone doing traffic analysis on
 * cell counts, and libsignal protects the message content itself.
 *
 * Session setup (mutual, replay-proof):
 *   server → CHALLENGE(nonceS)
 *   client → HELLO(nonceC, tag = HMAC(linkKey, "hello" | nonceS | nonceC))
 *   server → WELCOME(tag = HMAC(linkKey, "welcome" | nonceS | nonceC))
 * A connection that cannot prove a pairing-derived linkKey gets nothing but a closed socket,
 * so a leaked .onion address does not even confirm that its owner runs ONYX.
 *
 * Pairing (first contact, authenticated by the one-time QR secret):
 *   client → PAIR_REQUEST, server → PAIR_RESPONSE (see [Pairing]).
 */
sealed class Frame(val type: Int) {
    abstract fun body(): ByteArray

    class Challenge(val nonce: ByteArray) : Frame(T_CHALLENGE) {
        init { require(nonce.size == 32) }
        override fun body() = nonce.copyOf()
    }
    class Hello(val nonce: ByteArray, val tag: ByteArray) : Frame(T_HELLO) {
        init { require(nonce.size == 32 && tag.size == 32) }
        override fun body() = nonce + tag
    }
    class Welcome(val tag: ByteArray) : Frame(T_WELCOME) {
        init { require(tag.size == 32) }
        override fun body() = tag.copyOf()
    }
    class PairRequest(val payload: ByteArray) : Frame(T_PAIR_REQ) { override fun body() = payload }
    class PairResponse(val payload: ByteArray) : Frame(T_PAIR_RESP) { override fun body() = payload }

    /** A libsignal ciphertext. [signalType] is CiphertextMessage.getType() (2 = whisper, 3 = prekey). */
    class Message(val signalType: Int, val ciphertext: ByteArray) : Frame(T_MESSAGE) {
        override fun body() = ByteWriter(ciphertext.size + 8).u8(signalType).bytes32(ciphertext).toByteArray()
        /** Stable id used for acknowledgements without revealing plaintext. */
        fun digest(): ByteArray = Bytes.sha256(byteArrayOf(signalType.toByte()), ciphertext).copyOfRange(0, 16)
    }
    class Ack(val digest: ByteArray) : Frame(T_ACK) {
        init { require(digest.size == 16) }
        override fun body() = digest.copyOf()
    }
    /** Generic refusal. Carries no reason on purpose. */
    object Reject : Frame(T_REJECT) { override fun body() = ByteArray(0) }
    object Bye : Frame(T_BYE) { override fun body() = ByteArray(0) }

    companion object {
        const val T_CHALLENGE = 0x01
        const val T_HELLO = 0x02
        const val T_WELCOME = 0x03
        const val T_PAIR_REQ = 0x04
        const val T_PAIR_RESP = 0x05
        const val T_MESSAGE = 0x10
        const val T_ACK = 0x11
        const val T_REJECT = 0x7e
        const val T_BYE = 0x7f

        const val MAX_CIPHERTEXT = 200_000

        fun decode(type: Int, body: ByteArray): Frame {
            val r = ByteReader(body)
            val f = when (type) {
                T_CHALLENGE -> Challenge(r.raw(32))
                T_HELLO -> Hello(r.raw(32), r.raw(32))
                T_WELCOME -> Welcome(r.raw(32))
                T_PAIR_REQ -> PairRequest(r.rest())
                T_PAIR_RESP -> PairResponse(r.rest())
                T_MESSAGE -> Message(r.u8(), r.bytes32(MAX_CIPHERTEXT))
                T_ACK -> Ack(r.raw(16))
                T_REJECT -> Reject
                T_BYE -> Bye
                else -> throw MalformedException("unknown frame type")
            }
            r.end()
            return f
        }
    }
}

/** Direction of a frame relative to this device. */
enum class WireDirection { OUT, IN }

/**
 * Reads/writes padded frames on a stream. Not thread-safe; one reader and one writer per link.
 *
 * [tap], when set, receives the EXACT bytes as they cross the link (u32 length + padded block):
 * what someone who broke Tor's encryption would see. Used by the in-app RAW view; the bytes are
 * already end-to-end ciphertext + padding, never plaintext.
 */
class FrameCodec(input: InputStream, output: OutputStream) {
    private val din = DataInputStream(input)
    private val dout = DataOutputStream(output)
    @Volatile var tap: ((WireDirection, Int, ByteArray) -> Unit)? = null

    fun write(frame: Frame) {
        val inner = byteArrayOf(frame.type.toByte()) + frame.body()
        val padded = Padding.pad(inner, Padding.FRAME_BUCKETS)
        dout.writeInt(padded.size)
        dout.write(padded)
        dout.flush()
        tap?.let { t -> runCatching { t(WireDirection.OUT, frame.type, wireBytes(padded)) } }
    }

    private fun wireBytes(padded: ByteArray): ByteArray =
        ByteWriter(padded.size + 4).u32(padded.size.toLong()).raw(padded).toByteArray()

    fun read(): Frame {
        val len = din.readInt()
        if (len !in Padding.FRAME_BUCKETS) throw MalformedException("frame length")
        val padded = ByteArray(len)
        din.readFully(padded)
        val inner = Padding.unpad(padded, Padding.FRAME_BUCKETS)
        if (inner.isEmpty()) throw MalformedException("empty frame")
        val type = inner[0].toInt() and 0xff
        tap?.let { t -> runCatching { t(WireDirection.IN, type, wireBytes(padded)) } }
        return Frame.decode(type, inner.copyOfRange(1, inner.size))
    }

    inline fun <reified T : Frame> expect(): T {
        val f = read()
        return f as? T ?: throw MalformedException("unexpected frame ${f.type}")
    }
}

/** Link-authentication tags for the CHALLENGE/HELLO/WELCOME exchange. */
object LinkAuth {
    fun helloTag(linkKey: ByteArray, nonceS: ByteArray, nonceC: ByteArray) =
        Kdf.hmac(linkKey, "onyx-hello-v1".toByteArray(), nonceS, nonceC)

    fun welcomeTag(linkKey: ByteArray, nonceS: ByteArray, nonceC: ByteArray) =
        Kdf.hmac(linkKey, "onyx-welcome-v1".toByteArray(), nonceS, nonceC)

    /**
     * Server side: find which paired contact produced [hello]. Every candidate is checked
     * (no early exit) so timing does not reveal the position of the match.
     */
    fun <C> identify(candidates: List<Pair<C, ByteArray>>, nonceS: ByteArray, hello: Frame.Hello): C? {
        var match: C? = null
        for ((contact, key) in candidates) {
            if (Bytes.ctEquals(helloTag(key, nonceS, hello.nonce), hello.tag) && match == null) match = contact
        }
        return match
    }
}

/** Human-readable anatomy of one wire frame, for the RAW view. Parses only public framing. */
object WireAnatomy {
    fun typeName(t: Int) = when (t) {
        Frame.T_CHALLENGE -> "CHALLENGE"; Frame.T_HELLO -> "HELLO"; Frame.T_WELCOME -> "WELCOME"
        Frame.T_PAIR_REQ -> "PAIR_REQUEST"; Frame.T_PAIR_RESP -> "PAIR_RESPONSE"
        Frame.T_MESSAGE -> "MESSAGE"; Frame.T_ACK -> "ACK"; Frame.T_REJECT -> "REJECT"; Frame.T_BYE -> "BYE"
        else -> "0x%02x".format(t)
    }

    /** e.g. "bucket 1024 B · payload 187 B · padding 836 B" (+ signal type for MESSAGE). */
    fun describe(type: Int, wire: ByteArray): String {
        val bucket = wire.size - 4
        var end = wire.size - 1
        while (end >= 4 && wire[end] == 0.toByte()) end--
        val payload = (end - 4).coerceAtLeast(0)          // bytes before the 0x80 marker
        val base = "bucket $bucket B · payload $payload B · padding ${bucket - payload} B"
        if (type == Frame.T_MESSAGE && wire.size > 10) {
            val sig = wire[5].toInt() and 0xff
            val kind = if (sig == 3) "PreKey (PQXDH)" else if (sig == 2) "Triple Ratchet" else "type $sig"
            return "$base · libsignal $kind"
        }
        return base
    }

    /** Classic hex dump: offset, 16 bytes hex, ASCII column. */
    fun hexDump(b: ByteArray, maxBytes: Int = b.size, ascii: Boolean = true): String {
        val n = minOf(maxBytes, b.size)
        val sb = StringBuilder(n * 4 + 64)
        var off = 0
        while (off < n) {
            sb.append("%08x  ".format(off))
            for (i in 0 until 16) {
                if (off + i < n) sb.append("%02x ".format(b[off + i].toInt() and 0xff)) else sb.append("   ")
                if (i == 7) sb.append(' ')
            }
            if (ascii) {
                sb.append(" |")
                for (i in 0 until 16) if (off + i < n) {
                    val c = b[off + i].toInt() and 0xff
                    sb.append(if (c in 0x20..0x7e) c.toChar() else '.')
                }
                sb.append('|')
            }
            sb.append('\n')
            off += 16
        }
        if (n < b.size) sb.append("… ${b.size - n} more bytes\n")
        return sb.toString()
    }
}
