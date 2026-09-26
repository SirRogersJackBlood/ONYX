// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.net

import io.github.proteu5.onyx.core.ByteReader
import io.github.proteu5.onyx.core.ByteWriter
import io.github.proteu5.onyx.core.Bytes
import io.github.proteu5.onyx.core.Envelope
import io.github.proteu5.onyx.core.ForgeRank
import io.github.proteu5.onyx.core.Frame
import io.github.proteu5.onyx.core.OnionAddress
import io.github.proteu5.onyx.core.Pairing
import io.github.proteu5.onyx.core.PreKeyBundleWire
import io.github.proteu5.onyx.core.Transport
import io.github.proteu5.onyx.core.TransportContext
import io.github.proteu5.onyx.core.TransportSelector
import io.github.proteu5.onyx.data.Contact
import io.github.proteu5.onyx.data.ContactRepo
import io.github.proteu5.onyx.data.InviteRepo
import io.github.proteu5.onyx.data.Message
import io.github.proteu5.onyx.data.MessageRepo
import io.github.proteu5.onyx.data.MsgState
import io.github.proteu5.onyx.data.OutboxItem
import io.github.proteu5.onyx.data.OutboxRepo
import io.github.proteu5.onyx.signal.CryptoEngine
import io.github.proteu5.onyx.tor.TorController
import org.signal.libsignal.protocol.DuplicateMessageException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Orchestrates pairing, sending (outbox + retry), receiving and badge sharing.
 * Every plaintext is created, encrypted and persisted here; nothing is logged.
 */
class Messenger(
    private val crypto: CryptoEngine,
    private val tor: TorController,
    private val contacts: ContactRepo,
    private val messages: MessageRepo,
    private val outbox: OutboxRepo,
    private val invites: InviteRepo,
    private val badgeProvider: () -> ForgeRank.Badge?,
    private val notifyIncoming: () -> Unit,
) {
    private val client = PeerClient(tor)
    private val exec = Executors.newSingleThreadScheduledExecutor { Thread(it, "onyx-messenger").apply { isDaemon = true } }
    private val rng = SecureRandom()

    // Live pairing codes: memory only, single use, expire in 10 minutes.
    private class Pending(val code: Pairing.Code, val bundle: ByteArray)
    private val pending = ConcurrentHashMap<String, Pending>()
    @Volatile var onPaired: ((Contact) -> Unit)? = null

    val server = PeerServer(
        contacts = { contacts.all() },
        onPair = { handlePairRequest(it) },
        onMessage = { c, f -> handleIncoming(c, f) },
    )

    fun start() {
        exec.scheduleWithFixedDelay({ runCatching { flushOutbox() } }, 2, 20, TimeUnit.SECONDS)
        exec.scheduleWithFixedDelay({ runCatching { messages.sweepExpired(contacts.all(), System.currentTimeMillis()) } }, 5, 30, TimeUnit.SECONDS)
    }

    fun stop() { server.stop() }

    fun kick() { exec.execute { runCatching { flushOutbox(force = true) } } }

    // ------------------------------------------------------------------ pairing

    /**
     * Remote side: a 24 h single-use invite link to share through another app.
     * Both sides will mark the resulting contact UNVERIFIED until safety numbers are compared.
     */
    fun createInviteLink(): String {
        val me = tor.onion ?: throw IllegalStateException("Tor is not online yet")
        val code = Pairing.Code.createRemote(me, crypto.identityKeyBytes(), System.currentTimeMillis() / 1000)
        val text = code.encode()
        invites.put(Bytes.hex(code.keys.pairingId), text, crypto.newPairingBundle().encode())
        return text
    }

    fun pendingInviteCount(): Int = runCatching { invites.count() }.getOrDefault(0)
    fun revokeAllInvites() = invites.revokeAll()

    /** Displayed side: create a one-time QR code. */
    fun createPairingCode(): Pairing.Code {
        val me = tor.onion ?: throw IllegalStateException("Tor is not online yet")
        val now = System.currentTimeMillis() / 1000
        pending.values.removeIf { it.code.isExpired(now) }
        val code = Pairing.Code.create(me, crypto.identityKeyBytes(), now)
        pending[Bytes.hex(code.keys.pairingId)] = Pending(code, crypto.newPairingBundle().encode())
        return code
    }

    fun cancelPairingCode(code: Pairing.Code) { pending.remove(Bytes.hex(code.keys.pairingId)) }

    private fun handlePairRequest(payload: ByteArray): ByteArray? {
        val req = Pairing.Request.decode(payload)
        val idHex = Bytes.hex(req.pairingId)
        val p = pending.remove(idHex)                                        // single use
            ?: invites.take(idHex)?.let { (text, bundle) -> Pending(Pairing.Code.decode(text), bundle) }
            ?: return null
        val keys = p.code.keys
        if (p.code.isExpired(System.currentTimeMillis() / 1000) || !req.verify(keys)) return null
        val onion = OnionAddress.fromPublicKey(req.onionPk)
        contacts.byOnion(onion)?.let { crypto.forget(it); contacts.delete(it.id) }   // re-pair replaces
        val contact = Contact(
            id = Contact.newId(), name = "Contact " + onion.label.take(4).uppercase(), onion = onion,
            identityKey = req.identityKey, linkKey = keys.linkKey.copyOf(), mailbox = req.mailboxPk,
            badge = null, verified = !p.code.remote, disappearSeconds = 0, smsAllowed = false, smsNumber = null,
        )
        crypto.pinPeer(contact.signalName, req.identityKey)
        contacts.save(contact)
        val resp = Pairing.Response.create(keys, req, p.bundle, null).encode()
        onPaired?.invoke(contact)
        return resp
    }

    /** Scanner side. Blocking: run off the UI thread. */
    fun pairWith(codeText: String): Contact {
        val code = Pairing.Code.decode(Pairing.Code.extract(codeText) ?: codeText.trim())
        require(!code.isExpired(System.currentTimeMillis() / 1000)) { "This code has expired. Ask for a new one." }
        val me = tor.onion ?: throw IllegalStateException("Tor is not online yet")
        require(code.onion != me) { "That's your own code." }
        val keys = code.keys
        val req = Pairing.Request.create(keys, crypto.identityKeyBytes(), me.publicKey, null)
        val resp = Pairing.Response.decode(client.pair(code.onion, req.encode()))
        require(resp.verify(keys, req)) { "Pairing failed: authentication mismatch." }
        val bundle = PreKeyBundleWire.decode(resp.bundle)
        require(Bytes.ctEquals(Bytes.sha256(bundle.identityKey), code.identityCommit)) {
            "Pairing failed: identity key does not match the QR code. Possible interception."
        }
        contacts.byOnion(code.onion)?.let { crypto.forget(it); contacts.delete(it.id) }
        val contact = Contact(
            id = Contact.newId(), name = "Contact " + code.onion.label.take(4).uppercase(), onion = code.onion,
            identityKey = bundle.identityKey, linkKey = keys.linkKey.copyOf(), mailbox = resp.mailboxPk,
            badge = null, verified = !code.remote, disappearSeconds = 0, smsAllowed = false, smsNumber = null,
        )
        crypto.startSession(contact.signalName, bundle)
        contacts.save(contact)
        keys.wipe()
        // First PreKey message completes PQXDH on their side.
        queueEnvelope(contact, Envelope(Envelope.Kind.PAIRED, Bytes.random(16), System.currentTimeMillis() / 60_000, ByteArray(0)), null)
        badgeProvider()?.let { shareBadge(contact, it) }
        kick()
        return contact
    }

    // ------------------------------------------------------------------ sending

    fun sendText(contact: Contact, text: String) {
        require(text.isNotBlank() && text.length <= 8_000)
        val env = Envelope.text(text, System.currentTimeMillis())
        val expires = if (contact.disappearSeconds > 0) System.currentTimeMillis() + contact.disappearSeconds * 1000L else 0L
        val msg = Message(Bytes.hex(env.id), contact.id, true, text, env.sentAtMinute, MsgState.QUEUED, null, expires)
        messages.add(msg)
        queueEnvelope(contact, env, msg.id)
        kick()
    }

    fun setDisappearing(contact: Contact, seconds: Int) {
        contacts.save(contact.copy(disappearSeconds = seconds))
        val body = ByteWriter().u32(seconds.toLong()).toByteArray()
        queueEnvelope(contact, Envelope(Envelope.Kind.TIMER, Bytes.random(16), System.currentTimeMillis() / 60_000, body), null)
        kick()
    }

    fun shareBadge(contact: Contact, badge: ForgeRank.Badge) {
        queueEnvelope(contact, Envelope(Envelope.Kind.BADGE, Bytes.random(16), System.currentTimeMillis() / 60_000, badge.encode()), null)
    }

    fun shareBadgeWithAll(badge: ForgeRank.Badge) { contacts.all().forEach { shareBadge(it, badge) }; kick() }

    private fun queueEnvelope(contact: Contact, env: Envelope, uiMessageId: String?) {
        val (type, ct) = crypto.encrypt(contact, env)
        outbox.put(OutboxItem(uiMessageId ?: ("sys-" + Bytes.hex(env.id)), contact.id, type, ct, 0, 0))
    }

    @Synchronized
    private fun flushOutbox(force: Boolean = false) {
        if (tor.state != TorController.State.ONLINE) return
        val now = System.currentTimeMillis()
        val due = outbox.all().filter { force || it.nextAttemptMs <= now }
        for ((contactId, items) in due.groupBy { it.contactId }) {
            val contact = contacts.get(contactId) ?: run { items.forEach { outbox.remove(it.messageId) }; continue }
            val plan = TransportSelector.plan(TransportContext(
                torReady = true, peerReachable = null, peerHasMailbox = false,
                nearbyPeerVisible = false, smsAllowedForContact = false, hasCellular = false))
            if (Transport.TOR_DIRECT !in plan) continue
            try {
                client.open(contact).use { link ->
                    for (item in items) {
                        link.deliver(Frame.Message(item.signalType, item.ciphertext))
                        outbox.remove(item.messageId)
                        markSent(contact, item.messageId, Transport.TOR_DIRECT)
                    }
                }
            } catch (e: Exception) {
                for (item in items) {
                    if (outbox.all().none { it.messageId == item.messageId }) continue
                    val attempt = item.attempts + 1
                    outbox.put(item.copy(attempts = attempt, nextAttemptMs = now + TransportSelector.retryDelayMs(attempt, rng.nextDouble())))
                }
            }
        }
    }

    private fun markSent(contact: Contact, messageId: String, t: Transport) {
        if (messageId.startsWith("sys-")) return
        messages.forContact(contact.id).firstOrNull { it.id == messageId }?.let {
            messages.update(it.copy(state = MsgState.SENT, transport = t))
        }
    }

    // ------------------------------------------------------------------ receiving

    /** Returns true when the message is safely stored (→ ACK). */
    private fun handleIncoming(contact: Contact, f: Frame.Message): Boolean {
        val env = try {
            crypto.decrypt(contact, f.signalType, f.ciphertext)
        } catch (e: DuplicateMessageException) {
            return true // already stored; ACK so the sender stops retrying
        } catch (e: Exception) {
            return false
        }
        val fresh = contacts.get(contact.id) ?: return false
        when (env.kind) {
            Envelope.Kind.TEXT -> {
                val expires = if (fresh.disappearSeconds > 0) System.currentTimeMillis() + fresh.disappearSeconds * 1000L else 0L
                messages.add(Message(Bytes.hex(env.id), fresh.id, false, String(env.body, Charsets.UTF_8),
                    env.sentAtMinute, MsgState.RECEIVED, Transport.TOR_DIRECT, expires))
                notifyIncoming()
            }
            Envelope.Kind.BADGE -> runCatching { contacts.save(fresh.copy(badge = ForgeRank.Badge.decode(env.body))) }
            Envelope.Kind.TIMER -> runCatching {
                val secs = ByteReader(env.body).u32().toInt().coerceIn(0, 4 * 7 * 24 * 3600)
                contacts.save(fresh.copy(disappearSeconds = secs))
            }
            Envelope.Kind.PAIRED -> { badgeProvider()?.let { shareBadge(fresh, it); kick() } }
            Envelope.Kind.RECEIPT -> Unit // read receipts are not sent by ONYX (privacy default)
        }
        return true
    }
}
