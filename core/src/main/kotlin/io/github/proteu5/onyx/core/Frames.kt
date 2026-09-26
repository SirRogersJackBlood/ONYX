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

/** Reads/writes padded frames on a stream. Not thread-safe; one reader and one writer per link. */
class FrameCodec(input: InputStream, output: OutputStream) {
    private val din = DataInputStream(input)
    private val dout = DataOutputStream(output)

    fun write(frame: Frame) {
        val inner = byteArrayOf(frame.type.toByte()) + frame.body()
        val padded = Padding.pad(inner, Padding.FRAME_BUCKETS)
        dout.writeInt(padded.size)
        dout.write(padded)
        dout.flush()
    }

    fun read(): Frame {
        val len = din.readInt()
        if (len !in Padding.FRAME_BUCKETS) throw MalformedException("frame length")
        val padded = ByteArray(len)
        din.readFully(padded)
        val inner = Padding.unpad(padded, Padding.FRAME_BUCKETS)
        if (inner.isEmpty()) throw MalformedException("empty frame")
        return Frame.decode(inner[0].toInt() and 0xff, inner.copyOfRange(1, inner.size))
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
