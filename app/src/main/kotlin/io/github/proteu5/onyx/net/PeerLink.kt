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
    /** Benchmark: sends a padding-only frame and waits for its ACK. */
    fun bench(payload: ByteArray) {
        val f = Frame.Bench(payload)
        codec.write(f)
        val ack = codec.read()
        if (ack !is Frame.Ack || !Bytes.ctEquals(ack.digest, f.digest())) throw IOException("benchmark not accepted")
    }

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

    fun open(contact: Contact): PeerLink = open(contact, contact.onion, contact.linkKey, tap = true)

    /**
     * Opens an authenticated link to [onion] with [key]. Used for contacts (their link key) and for
     * depositing into a contact's mailbox (onion = their mailbox, key = Mailbox.depositKey(linkKey)).
     */
    fun open(contact: Contact, onion: OnionAddress, key: ByteArray, tap: Boolean): PeerLink {
        val (s, codec) = dial(onion)
        if (tap) codec.tap = { d, t, b -> WireTap.record(contact.id, d, t, b) }
        try {
            val challenge = codec.expect<Frame.Challenge>()
            val nonceC = Bytes.random(32)
            codec.write(Frame.Hello(nonceC, LinkAuth.helloTag(key, challenge.nonce, nonceC)))
            val welcome = codec.expect<Frame.Welcome>()
            if (!Bytes.ctEquals(welcome.tag, LinkAuth.welcomeTag(key, challenge.nonce, nonceC)))
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
 * What a phone in mailbox mode offers to the [PeerServer] (v1.4.0). On a normal phone [enabled] is false.
 */
interface MailboxHost {
    val enabled: Boolean
    fun isOwner(c: Contact): Boolean
    fun depositors(): List<Pair<ByteArray, ByteArray>>
    fun setDepositors(entries: List<Pair<ByteArray, ByteArray>>)
    fun deposit(tag: ByteArray, m: Frame.Message): Boolean
    /** Waiting items, oldest first; [item] loads one at a time. */
    fun keys(): List<String>
    fun item(digestHex: String): Frame.MailboxItem?
    fun remove(digestHex: String)
    /** Drops items past the retention window (14 days). */
    fun sweep()
}

/**
 * Inbound server behind our onion service. Listens on loopback only; every connection must
 * prove a pairing-derived key (or a live pairing secret) before anything else happens.
 */
class PeerServer(
    private val contacts: () -> List<Contact>,
    private val onPair: (ByteArray) -> ByteArray?,
    private val onMessage: (Contact, Frame.Message) -> Boolean,
    private val mailbox: MailboxHost? = null,
) {
    /** Settings → Developer: answer benchmark frames from paired contacts. Memory only, off by default, ends after an hour. */
    @Volatile var acceptBenchUntil = 0L
    var acceptBench: Boolean
        get() = System.currentTimeMillis() < acceptBenchUntil
        set(v) { acceptBenchUntil = if (v) System.currentTimeMillis() + 60 * 60_000L else 0L }
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
                        val mb = mailbox?.takeIf { it.enabled }
                        val who = LinkAuth.identify(all.map { it to it.linkKey }, nonceS, first)
                        if (who == null) {
                            // Mailbox mode: one of the owner's contacts depositing a message for the owner.
                            if (mb == null) return
                            val dep = LinkAuth.identify(mb.depositors().map { it to it.second }, nonceS, first) ?: return
                            codec.write(Frame.Welcome(LinkAuth.welcomeTag(dep.second, nonceS, first.nonce)))
                            while (true) {
                                when (val f = codec.read()) {
                                    is Frame.Message -> if (mb.deposit(dep.first, f)) codec.write(Frame.Ack(f.digest())) else return
                                    else -> return
                                }
                            }
                        }
                        codec.tap = { d, t, b -> WireTap.record(who.id, d, t, b) }
                        codec.write(Frame.Welcome(LinkAuth.welcomeTag(who.linkKey, nonceS, first.nonce)))
                        val owner = mb != null && mb.isOwner(who)
                        while (true) {
                            when (val f = codec.read()) {
                                is Frame.Message -> {
                                    if (onMessage(who, f)) codec.write(Frame.Ack(f.digest())) else return
                                }
                                is Frame.MailboxKeys -> if (owner) mb.setDepositors(f.entries) else return
                                is Frame.Bench -> if (acceptBench) codec.write(Frame.Ack(f.digest())) else return
                                is Frame.Fetch -> {
                                    if (!owner) return
                                    for (digestHex in mb.keys()) {
                                        val item = mb.item(digestHex) ?: continue
                                        codec.write(item)
                                        // Matching ACK = stored by the owner, delete here. Any other ACK = keep it for later.
                                        val ack = codec.read() as? Frame.Ack ?: return
                                        if (Bytes.ctEquals(ack.digest, item.message.digest())) mb.remove(digestHex)
                                    }
                                    codec.write(Frame.MailboxDone)
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

    // 16: warm links (v1.4.4) keep a few connections open for up to a minute each.
    companion object { private const val MAX_CONCURRENT = 16 }
}
