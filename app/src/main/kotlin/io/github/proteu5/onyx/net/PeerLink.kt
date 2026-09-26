// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.net

import io.github.proteu5.onyx.core.Bytes
import io.github.proteu5.onyx.core.Frame
import io.github.proteu5.onyx.core.FrameCodec
import io.github.proteu5.onyx.core.LinkAuth
import io.github.proteu5.onyx.core.OnionAddress
import io.github.proteu5.onyx.core.Socks5
import io.github.proteu5.onyx.data.Contact
import io.github.proteu5.onyx.tor.TorController
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** An authenticated, open link to one contact. */
class PeerLink(val contact: Contact, private val socket: Socket, val codec: FrameCodec) : Closeable {
    /** Sends one message and waits for the matching ACK. */
    fun deliver(msg: Frame.Message) {
        codec.write(msg)
        val ack = codec.read()
        if (ack !is Frame.Ack || !Bytes.ctEquals(ack.digest, msg.digest())) throw IOException("not acknowledged")
    }
    override fun close() {
        runCatching { codec.write(Frame.Bye) }
        runCatching { socket.close() }
    }
}

/** Outgoing connections, always through Tor's SOCKS port. */
class PeerClient(private val tor: TorController) {

    private fun dial(onion: OnionAddress): Pair<Socket, FrameCodec> {
        check(tor.state == TorController.State.ONLINE) { "Tor offline" }
        val s = Socks5.connect(tor.socksPort, onion, TorController.VIRTUAL_PORT)
        s.soTimeout = 90_000
        return s to FrameCodec(s.getInputStream(), s.getOutputStream())
    }

    fun open(contact: Contact): PeerLink {
        val (s, codec) = dial(contact.onion)
        codec.tap = { d, t, b -> WireTap.record(contact.id, d, t, b) }
        try {
            val challenge = codec.expect<Frame.Challenge>()
            val nonceC = Bytes.random(32)
            codec.write(Frame.Hello(nonceC, LinkAuth.helloTag(contact.linkKey, challenge.nonce, nonceC)))
            val welcome = codec.expect<Frame.Welcome>()
            if (!Bytes.ctEquals(welcome.tag, LinkAuth.welcomeTag(contact.linkKey, challenge.nonce, nonceC)))
                throw IOException("peer failed link authentication")
            return PeerLink(contact, s, codec)
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }
    }

    /** One-shot pairing exchange with the owner of a scanned QR code. */
    fun pair(onion: OnionAddress, request: ByteArray): ByteArray {
        val (s, codec) = dial(onion)
        s.use {
            codec.expect<Frame.Challenge>()
            codec.write(Frame.PairRequest(request))
            return codec.expect<Frame.PairResponse>().payload
        }
    }
}

/**
 * Inbound server behind our onion service. Listens on loopback only; every connection must
 * prove a pairing-derived key (or a live pairing secret) before anything else happens.
 */
class PeerServer(
    private val contacts: () -> List<Contact>,
    private val onPair: (ByteArray) -> ByteArray?,
    private val onMessage: (Contact, Frame.Message) -> Boolean,
) {
    private val running = AtomicBoolean(false)
    private val pool = Executors.newFixedThreadPool(MAX_CONCURRENT)
    private val slots = Semaphore(MAX_CONCURRENT)
    private var server: ServerSocket? = null

    val port: Int get() = server?.localPort ?: -1

    fun start(): Int {
        if (running.getAndSet(true)) return port
        val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = ss
        Thread({
            while (running.get()) {
                val s = try { ss.accept() } catch (e: IOException) { break }
                if (!slots.tryAcquire()) { runCatching { s.close() }; continue }
                pool.execute { try { handle(s) } finally { slots.release() } }
            }
        }, "onyx-accept").apply { isDaemon = true }.start()
        return ss.localPort
    }

    private fun handle(s: Socket) {
        s.use {
            try {
                s.soTimeout = 60_000
                val codec = FrameCodec(s.getInputStream(), s.getOutputStream())
                val nonceS = Bytes.random(32)
                codec.write(Frame.Challenge(nonceS))
                when (val first = codec.read()) {
                    is Frame.PairRequest -> {
                        val resp = onPair(first.payload) ?: return
                        codec.write(Frame.PairResponse(resp))
                    }
                    is Frame.Hello -> {
                        val all = contacts()
                        val who = LinkAuth.identify(all.map { it to it.linkKey }, nonceS, first) ?: return
                        codec.tap = { d, t, b -> WireTap.record(who.id, d, t, b) }
                        codec.write(Frame.Welcome(LinkAuth.welcomeTag(who.linkKey, nonceS, first.nonce)))
                        while (true) {
                            when (val f = codec.read()) {
                                is Frame.Message -> {
                                    if (onMessage(who, f)) codec.write(Frame.Ack(f.digest())) else return
                                }
                                is Frame.Bye -> return
                                else -> return
                            }
                        }
                    }
                    else -> return
                }
            } catch (_: Exception) {
                // Silent by design: no logs about who connected or why it failed.
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
    }

    companion object { private const val MAX_CONCURRENT = 8 }
}
