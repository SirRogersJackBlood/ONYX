// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.net

import io.github.proteu5.onyx.core.ByteReader
import io.github.proteu5.onyx.core.ByteWriter
import io.github.proteu5.onyx.core.Bytes
import io.github.proteu5.onyx.core.Envelope
import io.github.proteu5.onyx.core.ForgeRank
import io.github.proteu5.onyx.core.Frame
import io.github.proteu5.onyx.core.Groups
import io.github.proteu5.onyx.core.Media
import io.github.proteu5.onyx.core.Mailbox
import io.github.proteu5.onyx.core.MessagePolicy
import io.github.proteu5.onyx.core.OnionAddress
import io.github.proteu5.onyx.core.Pairing
import io.github.proteu5.onyx.core.PreKeyBundleWire
import io.github.proteu5.onyx.core.Transport
import io.github.proteu5.onyx.core.TransportContext
import io.github.proteu5.onyx.core.TransportSelector
import io.github.proteu5.onyx.data.Contact
import io.github.proteu5.onyx.data.ContactRepo
import io.github.proteu5.onyx.data.GroupRepo
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
    private val mailbox: MailboxHost? = null,
    private val groups: GroupRepo? = null,
) {
    private val client = PeerClient(tor)
    private val exec = Executors.newSingleThreadScheduledExecutor { Thread(it, "onyx-messenger").apply { isDaemon = true } }
    private val rng = SecureRandom()

    // Live pairing codes: memory only, single use, expire in 10 minutes.
    private class Pending(val code: Pairing.Code, val bundle: ByteArray, val expectOnion: ByteArray? = null, val expectIdentity: ByteArray? = null)
    private val pending = ConcurrentHashMap<String, Pending>()
    @Volatile var onPaired: ((Contact) -> Unit)? = null

    val server = PeerServer(
        contacts = { contacts.all() },
        onPair = { handlePairRequest(it) },
        onMessage = { c, f -> handleIncoming(c, f) },
        mailbox = mailbox,
    )

    // ---- mailbox (v1.4.0) ----
    /** Set by OnyxApp: the contact id of MY mailbox device (owner side), stored in prefs. */
    @Volatile var myMailboxId: () -> String? = { null }
    @Volatile var saveMyMailboxId: (String?) -> Unit = {}

    fun myMailbox(): Contact? = myMailboxId()?.let { contacts.get(it) }
    private fun myMailboxPk(): ByteArray? = myMailbox()?.onion?.publicKey

    /** Owner side: [contact] (already paired) becomes my mailbox; every other contact is told where to deposit. */
    fun setMyMailbox(contact: Contact) {
        check(mailbox?.enabled != true) { "A mailbox can't have its own mailbox." }
        saveMyMailboxId(contact.id)
        announceMailbox(contact.onion.publicKey)
        exec.execute { runCatching { fetchMailbox() } }
    }

    /** Owner side: stop using the mailbox. Contacts are told to stop depositing. The mailbox contact is kept until deleted. */
    fun removeMyMailbox() {
        val box = myMailboxId() ?: return
        exec.execute {
            runCatching { fetchMailbox() }          // collect anything still waiting first
            saveMyMailboxId(null)
            announceMailbox(null, skip = box)
        }
    }

    private fun announceMailbox(pk: ByteArray?, skip: String? = myMailboxId()) {
        contacts.all().filter { it.id != skip }.forEach {
            queueEnvelope(it, Envelope(Envelope.Kind.MAILBOX, Bytes.random(16), System.currentTimeMillis() / 60_000, pk ?: ByteArray(0)), null)
        }
        kick()
    }

    /**
     * Owner side, every minute: tell the mailbox who may deposit (an opaque tag and a deposit key per contact,
     * never names or onions), then collect and decrypt what it holds. Each item is ACKed only once stored here.
     */
    @Synchronized
    private fun fetchMailbox() {
        if (tor.state != TorController.State.ONLINE) return
        val box = myMailbox() ?: return
        val others = contacts.all().filter { it.id != box.id && !isBlocked(it.id) }.take(Mailbox.MAX_DEPOSITORS)
        val byTag = others.associateBy { Bytes.hex(Mailbox.tag(it.linkKey)) }
        client.open(box).use { link ->
            link.codec.write(Frame.MailboxKeys(others.map { Mailbox.tag(it.linkKey) to Mailbox.depositKey(it.linkKey) }))
            link.codec.write(Frame.Fetch)
            lastSeen[box.id] = System.currentTimeMillis()
            while (true) {
                when (val f = link.codec.read()) {
                    is Frame.MailboxItem -> {
                        val from = byTag[Bytes.hex(f.tag)]
                        // Unknown tag (contact deleted): nothing can read it, drop it. Failed decrypt: keep it (NACK).
                        val ok = from == null || handleIncoming(from, f.message, Transport.TOR_MAILBOX)
                        link.codec.write(Frame.Ack(if (ok) f.message.digest() else ByteArray(16)))
                    }
                    is Frame.MailboxDone -> return
                    else -> return
                }
            }
        }
    }

    fun start() {
        exec.scheduleWithFixedDelay({ runCatching { flushOutbox() } }, 2, 20, TimeUnit.SECONDS)
        exec.scheduleWithFixedDelay({ runCatching { messages.sweepExpired(contacts.all(), System.currentTimeMillis()) } }, 5, 30, TimeUnit.SECONDS)
        exec.scheduleWithFixedDelay({ runCatching { fetchMailbox() } }, 15, 60, TimeUnit.SECONDS)
        exec.scheduleWithFixedDelay({ runCatching { closeIdleLinks() } }, 10, 10, TimeUnit.SECONDS)
        exec.scheduleWithFixedDelay({ runCatching { groups?.all()?.forEach { introduceMembers(it) } } }, 2, 30, TimeUnit.MINUTES)
        exec.scheduleWithFixedDelay({ runCatching { mailbox?.takeIf { it.enabled }?.sweep() } }, 1, 60, TimeUnit.MINUTES)
    }

    fun stop() { closeIdleLinks(all = true); server.stop() }

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
        // A mailbox serves one owner. Once paired, it refuses everyone else.
        if (mailbox?.enabled == true && contacts.all().isNotEmpty()) return null
        val req = Pairing.Request.decode(payload)
        val idHex = Bytes.hex(req.pairingId)
        val p = pending.remove(idHex)                                        // single use
            ?: invites.take(idHex)?.let { Pending(Pairing.Code.decode(it.codeText), it.bundle, it.expectOnion, it.expectIdentity) }
            ?: return null
        val keys = p.code.keys
        if (p.code.isExpired(System.currentTimeMillis() / 1000) || !req.verify(keys)) return null
        // A group introduction code works only for the member it was made for (a relaying member can't use it).
        if (p.expectOnion != null && (!Bytes.ctEquals(req.onionPk, p.expectOnion) || !Bytes.ctEquals(req.identityKey, p.expectIdentity!!))) return null
        val onion = OnionAddress.fromPublicKey(req.onionPk)
        contacts.byOnion(onion)?.let { crypto.forget(it); contacts.delete(it.id) }   // re-pair replaces
        val contact = Contact(
            id = Contact.newId(), name = memberLabel(onion) ?: ("Contact " + onion.label.take(4).uppercase()), onion = onion,
            identityKey = req.identityKey, linkKey = keys.linkKey.copyOf(), mailbox = req.mailboxPk,
            badge = null, verified = !p.code.remote, disappearSeconds = 0, smsAllowed = false, smsNumber = null,
        )
        crypto.pinPeer(contact.signalName, req.identityKey)
        contacts.save(contact)
        val resp = Pairing.Response.create(keys, req, p.bundle, myMailboxPk()).encode()
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
        require(mailbox?.enabled != true) { "This phone is a mailbox. It pairs only with its owner, by showing its code." }
        val req = Pairing.Request.create(keys, crypto.identityKeyBytes(), me.publicKey, myMailboxPk())
        val resp = Pairing.Response.decode(client.pair(code.onion, req.encode()))
        require(resp.verify(keys, req)) { "Pairing failed: authentication mismatch." }
        val bundle = PreKeyBundleWire.decode(resp.bundle)
        require(Bytes.ctEquals(Bytes.sha256(bundle.identityKey), code.identityCommit)) {
            "Pairing failed: identity key does not match the QR code. Possible interception."
        }
        contacts.byOnion(code.onion)?.let { crypto.forget(it); contacts.delete(it.id) }
        val contact = Contact(
            id = Contact.newId(), name = memberLabel(code.onion) ?: ("Contact " + code.onion.label.take(4).uppercase()), onion = code.onion,
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

    class RateLimited : Exception("Slow down: at most 20 messages a minute.")
    class Blocked : Exception("You blocked this contact. Unblock them to send.")

    /** Set by OnyxApp: contacts the user blocked (incoming dropped silently, sending refused). */
    @Volatile var isBlocked: (String) -> Boolean = { false }

    /** Last time a frame was exchanged with each contact, in memory only (never stored): drives "online now". */
    private val lastSeen = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun lastSeenMs(contactId: String): Long = lastSeen[contactId] ?: 0L
    fun isOnline(contactId: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - lastSeenMs(contactId) < ONLINE_WINDOW_MS

    // Send times per contact, memory only: enough to stop scripted bulk sending.
    private val sendTimes = ConcurrentHashMap<String, MutableList<Long>>()
    private val imageTimes = ConcurrentHashMap<String, MutableList<Long>>()

    /** Set by OnyxApp to the isolated sandbox ([io.github.proteu5.onyx.media.ImageSandbox]). Null = rejected. */
    @Volatile var imageSanitizer: (ByteArray) -> ByteArray? = { null }

    class TooManyImages : Exception("Slow down: at most ${Media.IMAGES_PER_MINUTE} images a minute.")

    /** Images: at most a few a minute per chat, on top of the message limit. */
    private fun checkImageRate(chat: String, now: Long) {
        val times = imageTimes.getOrPut(chat) { mutableListOf() }
        synchronized(times) {
            times.removeAll { now - it >= 60_000 }
            if (times.size >= Media.IMAGES_PER_MINUTE) throw TooManyImages()
            times.add(now)
        }
    }

    private fun checkRate(chat: String, now: Long) {
        val times = sendTimes.getOrPut(chat) { mutableListOf() }
        synchronized(times) {
            if (!MessagePolicy.rateAllowed(times, now)) throw RateLimited()
            times.removeAll { now - it >= 60_000 }; times.add(now)
        }
    }

    /**
     * Sends an image that [io.github.proteu5.onyx.media.ImageSandbox.prepare] already re-encoded (no metadata). [caption] follows the text rules
     * and may be empty. Padded to one fixed size on the wire.
     */
    fun sendImage(contact: Contact, jpeg: ByteArray, caption: String) {
        if (isBlocked(contact.id)) throw Blocked()
        require(Media.looksLikeJpeg(jpeg) && jpeg.size <= Media.MAX_JPEG) { "Image not prepared" }
        val text = MessagePolicy.sanitize(caption)
        val now = System.currentTimeMillis()
        checkImageRate(contact.id, now); checkRate(contact.id, now)
        val env = Envelope(Envelope.Kind.IMAGE, Bytes.random(16), now / 60_000, Media.Image(null, text, jpeg).encode())
        val expires = if (contact.disappearSeconds > 0) now + contact.disappearSeconds * 1000L else 0L
        val msg = Message(Bytes.hex(env.id), contact.id, true, text, env.sentAtMinute, MsgState.QUEUED, null, expires, hasImage = true)
        messages.putImage(contact.id, msg.id, jpeg)
        messages.add(msg)
        queueEnvelope(contact, env, msg.id)
        kick()
    }

    /** Group image: encrypted separately to each member, like group text. */
    fun sendGroupImage(groupIdHex: String, jpeg: ByteArray, caption: String) {
        val s = groups?.get(groupIdHex) ?: throw GroupError("Group not found")
        if (!amIn(s)) throw GroupError("You're not in this group.")
        require(Media.looksLikeJpeg(jpeg) && jpeg.size <= Media.MAX_JPEG) { "Image not prepared" }
        val text = MessagePolicy.sanitize(caption)
        val now = System.currentTimeMillis()
        val chat = GroupRepo.chatId(groupIdHex)
        checkImageRate(chat, now); checkRate(chat, now)
        val env = Envelope(Envelope.Kind.IMAGE, Bytes.random(16), now / 60_000, Media.Image(s.groupId, text, jpeg).encode())
        val targets = reachable(s)
        val msg = Message(Bytes.hex(env.id), chat, true, text, env.sentAtMinute,
            if (targets.isEmpty()) MsgState.FAILED else MsgState.QUEUED, null, 0, hasImage = true)
        messages.putImage(chat, msg.id, jpeg)
        messages.add(msg)
        for (c in targets) {
            val (type, ct) = crypto.encrypt(c, env)
            outbox.put(OutboxItem("g|$groupIdHex|${msg.id}|${c.id}", c.id, type, ct, 0, 0))
        }
        kick()
    }

    // Received images are re-encoded in the sandbox OFF the network thread, one at a time, so a flood of images
    // can't stall delivery. Each sender may send at most 10 a minute; extra ones are dropped (still ACKed).
    private val imageExec = Executors.newSingleThreadExecutor { Thread(it, "onyx-img-in").apply { isDaemon = true } }
    private val imagesIn = ConcurrentHashMap<String, MutableList<Long>>()

    private fun onImage(from: Contact, env: Envelope, via: Transport) {
        val img = Media.Image.decode(env.body)
        // Every check BEFORE any image decoding.
        val gid = img.groupId
        val group = if (gid != null) groupSender(from, gid) ?: return else null
        val now = System.currentTimeMillis()
        val times = imagesIn.getOrPut(from.id) { mutableListOf() }
        synchronized(times) { times.removeAll { now - it >= 60_000 }; if (times.size >= 10) return; times.add(now) }
        val caption = MessagePolicy.sanitize(img.caption)
        val id = Bytes.hex(env.id)
        imageExec.execute {
            runCatching {
                // Re-encode: whatever the sender's app did, only pixels from our own encoder are stored.
                val clean = imageSanitizer(img.jpeg) ?: return@runCatching
                if (group == null) {
                    val expires = if (from.disappearSeconds > 0) System.currentTimeMillis() + from.disappearSeconds * 1000L else 0L
                    messages.putImage(from.id, id, clean)
                    messages.add(Message(id, from.id, false, caption, env.sentAtMinute, MsgState.RECEIVED, via, expires, hasImage = true))
                } else {
                    val chat = GroupRepo.chatId(group.idHex)
                    messages.putImage(chat, id, clean)
                    messages.add(Message(id, chat, false, caption, env.sentAtMinute, MsgState.RECEIVED, via, 0, senderId = from.id, hasImage = true))
                }
                notifyIncoming()
            }
        }
    }

    /**
     * Sends one short human message. The text is sanitized by [MessagePolicy]
     * (500 characters max, one line, no code-shaped symbols, links cleaned) and rate-limited. Returns what was actually sent.
     */
    fun sendText(contact: Contact, raw: String): String {
        if (isBlocked(contact.id)) throw Blocked()
        val text = MessagePolicy.sanitize(raw)
        require(text.isNotEmpty()) { "Nothing left to send after removing unsupported characters." }
        val now = System.currentTimeMillis()
        val times = sendTimes.getOrPut(contact.id) { mutableListOf() }
        synchronized(times) {
            if (!MessagePolicy.rateAllowed(times, now)) throw RateLimited()
            times.removeAll { now - it >= 60_000 }; times.add(now)
        }
        val env = Envelope.text(text, now)
        val expires = if (contact.disappearSeconds > 0) System.currentTimeMillis() + contact.disappearSeconds * 1000L else 0L
        val msg = Message(Bytes.hex(env.id), contact.id, true, text, env.sentAtMinute, MsgState.QUEUED, null, expires)
        messages.add(msg)
        queueEnvelope(contact, env, msg.id)
        kick()
        return text
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

    // Delivery runs per contact in parallel (v1.4.4): an offline contact can take Tor a minute or two to give up
    // on, and must never hold up messages to people who are online. One delivery per contact at a time.
    private val deliveryPool = Executors.newFixedThreadPool(4) { Thread(it, "onyx-deliver").apply { isDaemon = true } }
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun flushOutbox(force: Boolean = false) {
        if (tor.state != TorController.State.ONLINE) return
        val now = System.currentTimeMillis()
        val due = outbox.all().filter { force || it.nextAttemptMs <= now }.map { it.contactId }.toSet()
        for (contactId in due) {
            if (!inFlight.add(contactId)) continue          // already being delivered; picked up when it finishes
            deliveryPool.execute {
                try {
                    var rounds = 0
                    // Keep going while new messages for this contact arrived during the attempt (at most 3 rounds).
                    while (deliverTo(contactId, force) && ++rounds < 3) Unit
                } catch (_: Exception) {
                } finally { inFlight.remove(contactId) }
            }
        }
    }

    // Warm links (v1.4.4): after a delivery the connection stays open for [LINK_IDLE_MS], so a reply in a
    // conversation skips the 2–3 s Tor connection setup (measured: 0.7 s instead of 2.2 s per message).
    // The other side closes idle links after 60 s; we close ours first.
    private class WarmLink(val link: PeerLink, @Volatile var lastUsed: Long)
    private val warmLinks = ConcurrentHashMap<String, WarmLink>()

    /** Runs [block] on an open link to [contact]: a warm one if it still works, otherwise a fresh one. */
    private fun <T> withLink(contact: Contact, block: (PeerLink) -> T): T {
        val now = System.currentTimeMillis()
        warmLinks.remove(contact.id)?.let { w ->
            if (now - w.lastUsed < LINK_IDLE_MS) {
                val r = runCatching { block(w.link) }
                if (r.isSuccess) { w.lastUsed = System.currentTimeMillis(); keepWarm(contact.id, w); return r.getOrThrow() }
            }
            runCatching { w.link.close() }                  // stale or broken: fall through to a fresh one
        }
        val link = client.open(contact)
        try {
            val r = block(link)
            keepWarm(contact.id, WarmLink(link, System.currentTimeMillis()))
            return r
        } catch (e: Exception) { runCatching { link.close() }; throw e }
    }

    private fun keepWarm(id: String, w: WarmLink) { warmLinks.put(id, w)?.takeIf { it !== w }?.let { runCatching { it.link.close() } } }

    private fun closeIdleLinks(all: Boolean = false) {
        val now = System.currentTimeMillis()
        for ((id, w) in warmLinks) if (all || now - w.lastUsed >= LINK_IDLE_MS) {
            if (warmLinks.remove(id, w)) runCatching { w.link.close() }
        }
    }

    /** One delivery attempt to one contact. Returns true if fresh (never tried) messages are waiting for them. */
    private fun deliverTo(contactId: String, force: Boolean): Boolean {
        val now = System.currentTimeMillis()
        val items = outbox.all().filter { it.contactId == contactId && (force || it.nextAttemptMs <= now) }
        if (items.isEmpty()) return false
        run {
            val contact = contacts.get(contactId) ?: run { items.forEach { outbox.remove(it.messageId) }; return false }
            val plan = TransportSelector.plan(TransportContext(
                torReady = true, peerReachable = null, peerHasMailbox = false,
                nearbyPeerVisible = false, smsAllowedForContact = false, hasCellular = false))
            if (Transport.TOR_DIRECT !in plan) return false
            val direct = runCatching {
                withLink(contact) { link ->
                    for (item in items) {
                        if (outbox.all().none { it.messageId == item.messageId }) continue   // sent on an earlier try
                        link.deliver(Frame.Message(item.signalType, item.ciphertext))
                        outbox.remove(item.messageId)
                        lastSeen[contact.id] = System.currentTimeMillis()
                        markSent(contact, item.messageId, Transport.TOR_DIRECT)
                    }
                }
            }
            if (direct.isFailure) {
                // Their phone is offline: leave the rest on their mailbox, if they have one. Still Signal
                // ciphertext; the mailbox can't read it and doesn't learn who we are (opaque tag only).
                contact.mailbox?.let { pk ->
                    runCatching {
                        client.open(contact, OnionAddress.fromPublicKey(pk), Mailbox.depositKey(contact.linkKey), tap = false).use { link ->
                            for (item in items) {
                                if (outbox.all().none { it.messageId == item.messageId }) continue
                                link.deliver(Frame.Message(item.signalType, item.ciphertext))
                                outbox.remove(item.messageId)
                                markSent(contact, item.messageId, Transport.TOR_MAILBOX)
                            }
                        }
                    }
                }
                for (item in items) {
                    if (outbox.all().none { it.messageId == item.messageId }) continue
                    val attempt = item.attempts + 1
                    // Background notices (badge, timer, mailbox) give up after ~2 days; your messages never do.
                    if (item.messageId.startsWith("sys-") && attempt > SYS_MAX_ATTEMPTS) { outbox.remove(item.messageId); continue }
                    outbox.put(item.copy(attempts = attempt, nextAttemptMs = now + TransportSelector.retryDelayMs(attempt, rng.nextDouble())))
                }
            }
        }
        val tried = items.map { it.messageId }.toSet()
        return outbox.all().any { it.contactId == contactId && it.attempts == 0 && it.messageId !in tried }
    }

    private fun markSent(contact: Contact, messageId: String, t: Transport) {
        if (messageId.startsWith("sys-")) return
        if (messageId.startsWith("g|")) {
            // Group fan-out item "g|<group>|<message>|<contact>": the message counts as delivered once anyone has it.
            val parts = messageId.split('|')
            val chat = GroupRepo.chatId(parts[1])
            messages.forContact(chat).firstOrNull { it.id == parts[2] && it.state != MsgState.SENT }?.let {
                messages.update(it.copy(state = MsgState.SENT, transport = t))
            }
            return
        }
        messages.forContact(contact.id).firstOrNull { it.id == messageId }?.let {
            messages.update(it.copy(state = MsgState.SENT, transport = t))
        }
    }

    // ------------------------------------------------------------------ groups (v1.4.1)
    //
    // No admin and no server. Any member may invite their own contacts by signing a voucher for them; only you
    // can sign your own join or leave. The group id commits to its creator, name and verified-only setting.
    // Messages are fanned out over the existing 1:1 sessions. Members who never met are paired through an
    // introduction relayed by members who are already paired (UNVERIFIED until checked). See docs/GROUPS.md.

    class GroupError(msg: String) : Exception(msg)

    // Introductions already seen: bounded, oldest forgotten first.
    private val seenIntros: MutableSet<String> = java.util.Collections.newSetFromMap(
        java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Boolean>(256, 0.75f, false) {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Boolean>?) = size > 4096
        }))
    // Rate limits, memory only: replies to members who are behind, and relayed introductions per sender.
    private val behindReplies = ConcurrentHashMap<String, Long>()
    private val introRelays = ConcurrentHashMap<String, MutableList<Long>>()
    private fun nowMinute() = System.currentTimeMillis() / 60_000

    private fun pinnedKey(onionPk: ByteArray): ByteArray? =
        runCatching { contacts.byOnion(OnionAddress.fromPublicKey(onionPk))?.identityKey }.getOrNull()

    private fun merge(a: Groups.State, b: Groups.State): Groups.State =
        Groups.merge(a, b, crypto::verifySignature, ::pinnedKey) { onion -> groups?.addConflict(a.idHex, Bytes.hex(onion)) }

    private fun myEntry(g: Groups.State, version: Long, present: Boolean, joinedAtMinute: Long,
                        voucherOnion: ByteArray?, voucherSig: ByteArray?): Groups.Entry {
        val me = tor.onion ?: throw GroupError("Tor is not online yet")
        if (version > Groups.MAX_VERSION) throw GroupError("This group can't be changed any more.")
        val ik = crypto.identityKeyBytes()
        val sig = crypto.sign(Groups.signedBytes(g.groupId, me.publicKey, ik, version, present, joinedAtMinute, voucherOnion))
        return Groups.Entry(me.publicKey, ik, version, present, joinedAtMinute, voucherOnion, voucherSig, sig)
    }

    /** The paired contact behind a roster entry, only if the identity key matches what we pinned. */
    fun contactFor(e: Groups.Entry): Contact? =
        runCatching { contacts.byOnion(OnionAddress.fromPublicKey(e.onionPk)) }.getOrNull()?.takeIf { Bytes.ctEquals(it.identityKey, e.identityKey) }

    /** Members whose onion was claimed with two different keys: excluded until resolved in person. */
    fun disputed(s: Groups.State): Set<String> = groups?.conflicts(s.idHex) ?: emptySet()

    /** Members I can send to right now: paired, session ready, not blocked, not disputed, verified when required. */
    fun reachable(s: Groups.State): List<Contact> {
        val me = tor.onion?.publicKey
        val bad = disputed(s)
        return s.active().filter { (me == null || !Bytes.ctEquals(it.onionPk, me)) && it.key !in bad }.mapNotNull { contactFor(it) }
            .filter { !isBlocked(it.id) && (!s.verifiedOnly || it.verified) && crypto.hasSession(it) }
    }

    fun amIn(s: Groups.State): Boolean = tor.onion?.let { s.isActive(it.publicKey) } ?: false

    private fun memberLabel(onion: OnionAddress): String? = runCatching {
        groups?.all()?.firstOrNull { it.isActive(onion.publicKey) }?.let { "${it.title} · ${onion.label.take(4).uppercase()}" }
    }.getOrNull()

    private fun sendControl(c: Contact, kind: Envelope.Kind, body: ByteArray) =
        queueEnvelope(c, Envelope(kind, Bytes.random(16), nowMinute(), body), null)

    private fun broadcastState(s: Groups.State, exceptId: String? = null) {
        reachable(s).filter { it.id != exceptId }.forEach { sendControl(it, Envelope.Kind.GROUP_STATE, s.encode()) }
        kick()
    }

    /** Creates a group and invites [invitees] (each still decides whether to join). Returns the group id. */
    fun createGroup(title: String, verifiedOnly: Boolean, invitees: List<Contact>): String {
        val g = groups ?: throw GroupError("Groups unavailable")
        val me = tor.onion ?: throw GroupError("Tor is not online yet")
        val t = Groups.cleanTitle(title)
        val nonce = Bytes.random(16)
        val ik = crypto.identityKeyBytes()
        val genesis = Groups.State(Groups.deriveId(me.publicKey, ik, t, verifiedOnly, nonce), t, verifiedOnly, me.publicKey, ik, nonce, emptyList())
        val s = genesis.withEntries(listOf(myEntry(genesis, 1, true, nowMinute(), null, null)))
        g.save(s)
        invitees.take(Groups.MAX_MEMBERS - 1).forEach { runCatching { inviteToGroup(s.idHex, it) } }
        return s.idHex
    }

    /** Any member may invite one of their own contacts: the invite carries a voucher signed for that contact alone. */
    fun inviteToGroup(groupIdHex: String, c: Contact) {
        val s = groups?.get(groupIdHex) ?: throw GroupError("Group not found")
        val me = tor.onion ?: throw GroupError("Tor is not online yet")
        if (!amIn(s)) throw GroupError("You're not in this group.")
        if (isBlocked(c.id)) throw Blocked()
        if (s.isActive(c.onion.publicKey)) return
        if (s.isFull()) throw GroupError("This group is full (${Groups.MAX_MEMBERS} people).")
        if (s.verifiedOnly && !c.verified) throw GroupError("Verified-only group: compare safety numbers with ${c.name} first.")
        val voucher = crypto.sign(Groups.voucherBytes(s.groupId, c.onion.publicKey, c.identityKey))
        sendControl(c, Envelope.Kind.GROUP_INVITE, Groups.Invite(s, me.publicKey, voucher).encode())
        kick()
    }

    /** Joins: signs my own entry with the inviter's voucher, tells the members, and starts introductions. */
    fun acceptGroupInvite(groupIdHex: String) {
        val g = groups ?: throw GroupError("Groups unavailable")
        val inv = g.takeInvite(groupIdHex) ?: throw GroupError("This invite is no longer available.")
        val me = tor.onion ?: run { g.putInvite(inv.fromContactId, inv.invite); throw GroupError("Tor is not online yet") }
        val st = inv.invite.state
        var s = merge(st.withEntries(emptyList()), st)
        g.get(groupIdHex)?.let { s = merge(it, s) }
        val voucher = s.entry(inv.invite.voucherOnionPk)?.takeIf { it.present } ?: throw GroupError("The person who invited you is no longer in the group.")
        if (s.isFull() && !s.isActive(me.publicKey)) throw GroupError("This group is already full.")
        val version = (s.entry(me.publicKey)?.version ?: 0L) + 1
        val joined = maxOf(nowMinute(), voucher.joinedAtMinute)
        s = merge(s, s.withEntries(listOf(myEntry(s, version, true, joined, inv.invite.voucherOnionPk, inv.invite.voucherSig))))
        if (!s.isActive(me.publicKey)) throw GroupError("Couldn't join: the invite didn't check out.")
        g.save(s)
        broadcastState(s)
        // The inviter always hears back, even if a verified-only rule would otherwise skip them.
        contacts.get(inv.fromContactId)?.takeIf { c -> crypto.hasSession(c) && reachable(s).none { it.id == c.id } }
            ?.let { sendControl(it, Envelope.Kind.GROUP_STATE, s.encode()); kick() }
        exec.execute { runCatching { introduceMembers(s) } }
    }

    fun declineGroupInvite(groupIdHex: String) { groups?.removeInvite(groupIdHex) }

    /**
     * Leaves for good on this phone: signs a leave, tells the members, deletes the group and its messages.
     * Needs Tor online: leaving without telling anyone would keep your seat taken.
     */
    fun leaveGroup(groupIdHex: String) {
        val g = groups ?: return
        val s = g.get(groupIdHex) ?: return
        val me = tor.onion ?: throw GroupError("Connect first: the members need to hear that you left.")
        val mine = s.entry(me.publicKey)
        if (mine != null && mine.present) {
            val left = merge(s, s.withEntries(listOf(
                myEntry(s, mine.version + 1, false, mine.joinedAtMinute, mine.voucherOnionPk, mine.voucherSig))))
            broadcastState(left)
        }
        g.delete(groupIdHex)
    }

    /** One message, encrypted separately to every member I can reach. Returns the text actually sent. */
    fun sendGroupText(groupIdHex: String, raw: String): String {
        val s = groups?.get(groupIdHex) ?: throw GroupError("Group not found")
        if (!amIn(s)) throw GroupError("You're not in this group.")
        val text = MessagePolicy.sanitize(raw)
        require(text.isNotEmpty()) { "Nothing left to send after removing unsupported characters." }
        val now = System.currentTimeMillis()
        val chat = GroupRepo.chatId(groupIdHex)
        checkRate(chat, now)
        val env = Envelope(Envelope.Kind.GROUP_TEXT, Bytes.random(16), now / 60_000, Groups.Text(s.groupId, text).encode())
        val targets = reachable(s)
        val msg = Message(Bytes.hex(env.id), chat, true, text, env.sentAtMinute,
            if (targets.isEmpty()) MsgState.FAILED else MsgState.QUEUED, null, 0)
        messages.add(msg)
        for (c in targets) {
            val (type, ct) = crypto.encrypt(c, env)
            outbox.put(OutboxItem("g|$groupIdHex|${msg.id}|${c.id}", c.id, type, ct, 0, 0))
        }
        kick()
        return text
    }

    private fun onGroupInvite(from: Contact, body: ByteArray) {
        val g = groups ?: return
        val inv = Groups.Invite.decode(body)            // rejects a tampered name or verified-only setting
        val s = inv.state
        g.get(s.idHex)?.let { if (amIn(it)) { onGroupState(from, s.encode()); return } }
        // The voucher must be from the sender, for me, and the sender must be a signed, present member.
        val me = tor.onion ?: return
        if (!Bytes.ctEquals(inv.voucherOnionPk, from.onion.publicKey)) return
        if (!crypto.verifySignature(from.identityKey, Groups.voucherBytes(s.groupId, me.publicKey, crypto.identityKeyBytes()), inv.voucherSig)) return
        val checked = merge(s.withEntries(emptyList()), s)
        val e = checked.entry(from.onion.publicKey) ?: return
        if (!e.present || !Bytes.ctEquals(e.identityKey, from.identityKey)) return
        if (g.putInvite(from.id, inv)) notifyIncoming()
    }

    private fun onGroupState(from: Contact, body: ByteArray) {
        val g = groups ?: return
        val remote = Groups.State.decode(body)
        val local = g.get(remote.idHex) ?: return
        if (!amIn(local)) return
        val merged = merge(local, remote)
        // Only members (old or just joined) may tell us about the group.
        val fe = merged.entry(from.onion.publicKey) ?: return
        if (!Bytes.ctEquals(fe.identityKey, from.identityKey)) return
        if (Groups.differs(merged, local)) {
            g.save(merged)
            broadcastState(merged, exceptId = from.id)
            exec.execute { runCatching { introduceMembers(merged) } }
        }
        // They're missing something we have: tell them, at most every 10 minutes per member and group,
        // so two phones that disagree on a refused entry can't bounce it back and forth forever.
        val missing = merged.entries.any { e -> remote.entry(e.onionPk)?.let { it.version < e.version } ?: true }
        val k = remote.idHex + from.id
        val now = System.currentTimeMillis()
        if (missing && fe.present && now - (behindReplies[k] ?: 0L) > 10 * 60_000L) {
            behindReplies[k] = now
            sendControl(from, Envelope.Kind.GROUP_STATE, merged.encode()); kick()
        }
    }

    /** Checks that [from] may post in group [gid] (shared by text and images). */
    private fun groupSender(from: Contact, gid: ByteArray): Groups.State? {
        val s = groups?.get(Bytes.hex(gid)) ?: return null
        if (!amIn(s) || !s.isActive(from.onion.publicKey)) return null
        if (Bytes.hex(from.onion.publicKey) in disputed(s)) return null
        if (s.entry(from.onion.publicKey)?.identityKey?.let { Bytes.ctEquals(it, from.identityKey) } != true) return null
        if (s.verifiedOnly && !from.verified) return null
        return s
    }

    private fun onGroupText(from: Contact, env: Envelope, via: Transport) {
        val t = Groups.Text.decode(env.body)
        val s = groupSender(from, t.groupId) ?: return
        val text = MessagePolicy.sanitize(t.text)        // re-applied on receipt, like 1:1 text
        if (text.isEmpty()) return
        messages.add(Message(Bytes.hex(env.id), GroupRepo.chatId(s.idHex), false, text, env.sentAtMinute,
            MsgState.RECEIVED, via, 0, senderId = from.id))
        notifyIncoming()
    }

    /**
     * For every member I'm not paired with, where I'm the one who introduces (lower onion key): make a one-time
     * code that ONLY that member can use (bound to their onion and identity key) and pass it through the members
     * I'm paired with. At most once a day per member while it hasn't worked yet. A contact made from an
     * introduction that never finished its handshake is removed and tried again.
     */
    @Synchronized
    private fun introduceMembers(s: Groups.State) {
        val g = groups ?: return
        if (s.verifiedOnly || !amIn(s) || tor.state != TorController.State.ONLINE) return
        val me = tor.onion ?: return
        val relays = reachable(s)
        if (relays.isEmpty()) return
        val now = System.currentTimeMillis()
        val bad = disputed(s)
        for (e in s.active()) {
            if (Bytes.ctEquals(e.onionPk, me.publicKey) || e.key in bad || !Groups.iIntroduce(me.publicKey, e.onionPk)) continue
            val existing = runCatching { contacts.byOnion(OnionAddress.fromPublicKey(e.onionPk)) }.getOrNull()
            if (existing != null) {
                if (!Bytes.ctEquals(existing.identityKey, e.identityKey)) continue     // conflicting key: never auto-replace
                if (crypto.hasSession(existing)) continue                                // paired and working
                if (now - g.introSentAt(s.idHex, e.key) < INTRO_RETRY_MS) continue
                crypto.forget(existing); contacts.delete(existing.id)                   // half-finished: start over
            }
            if (now - g.introSentAt(s.idHex, e.key) < INTRO_RETRY_MS) continue
            val code = Pairing.Code.createRemote(me, crypto.identityKeyBytes(), now / 1000)
            invites.put(Bytes.hex(code.keys.pairingId), code.encode(), crypto.newPairingBundle().encode(), e.onionPk, e.identityKey)
            val intro = Groups.Intro(Bytes.random(16), s.groupId, me.publicKey, e.onionPk, code.encode(), Groups.MAX_HOPS)
            seenIntros.add(Bytes.hex(intro.introId))
            relays.forEach { sendControl(it, Envelope.Kind.GROUP_INTRO, intro.encode()) }
            g.setIntroSent(s.idHex, e.key, now)
        }
        kick()
    }

    private fun onGroupIntro(from: Contact, body: ByteArray) {
        val g = groups ?: return
        // At most 30 introductions an hour from any one member.
        val times = introRelays.getOrPut(from.id) { mutableListOf() }
        val now = System.currentTimeMillis()
        synchronized(times) { times.removeAll { now - it > 3_600_000L }; if (times.size >= 30) return; times.add(now) }
        val intro = Groups.Intro.decode(body)
        if (!seenIntros.add(Bytes.hex(intro.introId))) return
        val s = g.get(Bytes.hex(intro.groupId)) ?: return
        val me = tor.onion ?: return
        if (s.verifiedOnly || !amIn(s) || !s.isActive(from.onion.publicKey)) return
        val bad = disputed(s)
        val fromEntry = s.entry(intro.fromOnionPk)?.takeIf { it.present && it.key !in bad } ?: return
        val toEntry = s.entry(intro.toOnionPk)?.takeIf { it.present && it.key !in bad } ?: return
        if (Bytes.ctEquals(intro.toOnionPk, me.publicKey)) {
            if (pinnedKey(intro.fromOnionPk) != null) return          // already paired
            val code = runCatching { Pairing.Code.decode(intro.code) }.getOrNull() ?: return
            // The code must belong to the member the signed roster says, with the identity the roster says.
            if (!code.remote || code.isExpired(System.currentTimeMillis() / 1000)) return
            if (!Bytes.ctEquals(code.onion.publicKey, fromEntry.onionPk)) return
            if (!Bytes.ctEquals(code.identityCommit, Bytes.sha256(fromEntry.identityKey))) return
            exec.execute { runCatching { pairWith(intro.code) } }
        } else if (intro.hops > 0) {
            val next = contactFor(toEntry)?.let { listOf(it) } ?: reachable(s).filter { it.id != from.id }
            next.forEach { sendControl(it, Envelope.Kind.GROUP_INTRO, intro.relayed().encode()) }
            kick()
        }
    }

    // ------------------------------------------------------------------ transit benchmark (Settings → Developer)

    /**
     * Measures real delivery time over Tor to [contact] for padding-only frames of each wire size: [rounds] sends
     * on a fresh connection each (what a single message costs today) and [rounds] on one open connection.
     * The other phone must have "Accept benchmark" on. Returns a plain-text report; blocking, run off the UI thread.
     */
    fun benchmark(contact: Contact, rounds: Int, stop: () -> Boolean = { false }, progress: (String) -> Unit): String {
        if (tor.state != TorController.State.ONLINE) throw IllegalStateException("Tor isn't online on this phone yet (status: ${tor.state.name.lowercase()}). Wait for ● ONLINE.")
        val sizes = listOf("16 KiB" to 16_300, "64 KiB" to 65_400, "256 KiB" to 261_000)
        val errors = LinkedHashMap<String, Int>()      // why attempts failed, counted (no addresses, no content)
        fun fail(e: Throwable) { val k = (e.message ?: e.javaClass.simpleName).take(80); errors[k] = (errors[k] ?: 0) + 1 }
        val out = StringBuilder("ONYX transit benchmark · ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}\n")
        out.append("rounds per test: $rounds\nframe|mode|ok|median_s|p95_s|min_s|max_s\n")
        fun pct(xs: List<Double>, p: Double): Double {
            if (xs.isEmpty()) return Double.NaN
            val s = xs.sorted(); val k = (s.size - 1) * p; val f = kotlin.math.floor(k).toInt(); val c = minOf(f + 1, s.size - 1)
            return s[f] + (s[c] - s[f]) * (k - f)
        }
        fun line(name: String, mode: String, xs: List<Double>) =
            out.append("$name|$mode|${xs.size}/$rounds|%.2f|%.2f|%.2f|%.2f\n".format(java.util.Locale.US,
                pct(xs, .5), pct(xs, .95), xs.minOrNull() ?: Double.NaN, xs.maxOrNull() ?: Double.NaN))
        for ((name, len) in sizes) {
            val cold = mutableListOf<Double>()
            for (i in 1..rounds) {
                if (stop()) break
                progress("$name · new connection $i/$rounds")
                runCatching {
                    val t0 = System.nanoTime()
                    client.open(contact, contact.onion, contact.linkKey, tap = false).use { it.bench(Bytes.random(len)) }
                    cold += (System.nanoTime() - t0) / 1e9
                }.onFailure { fail(it) }
            }
            line(name, "new connection", cold)
            val warm = mutableListOf<Double>()
            runCatching {
                client.open(contact, contact.onion, contact.linkKey, tap = false).use { link ->
                    for (i in 1..rounds) {
                        if (stop()) break
                        progress("$name · open connection $i/$rounds")
                        val t0 = System.nanoTime()
                        val r = runCatching { link.bench(Bytes.random(len)); warm += (System.nanoTime() - t0) / 1e9 }
                        r.onFailure { fail(it) }
                        if (r.isFailure) break                  // the link is gone; don't count the same failure 20 times
                    }
                }
            }.onFailure { fail(it) }
            line(name, "open connection", warm)
            if (stop()) { out.append("stopped early by user\n"); break }
        }
        if (errors.isNotEmpty()) {
            out.append("failures:\n")
            errors.forEach { (k, n) -> out.append("  ${n}× $k\n") }
            if (errors.keys.any { it.contains("not accepted") || it.contains("EOF") || it.contains("closed") })
                out.append("hint: is \"Accept benchmark\" on (and not expired) on the other phone?\n")
        }
        return out.toString()
    }

    // ------------------------------------------------------------------ receiving

    /** Returns true when the message is safely stored (→ ACK). */
    private fun handleIncoming(contact: Contact, f: Frame.Message, via: Transport = Transport.TOR_DIRECT): Boolean {
        // Blocked: drop silently, but ACK so the sender stops retrying and learns nothing.
        if (isBlocked(contact.id)) return true
        val env = try {
            crypto.decrypt(contact, f.signalType, f.ciphertext)
        } catch (e: DuplicateMessageException) {
            return true // already stored; ACK so the sender stops retrying
        } catch (e: Exception) {
            return false
        }
        val fresh = contacts.get(contact.id) ?: return false
        if (via == Transport.TOR_DIRECT) lastSeen[fresh.id] = System.currentTimeMillis()
        when (env.kind) {
            Envelope.Kind.TEXT -> {
                val expires = if (fresh.disappearSeconds > 0) System.currentTimeMillis() + fresh.disappearSeconds * 1000L else 0L
                // Re-apply the policy on receipt: an older or modified client could send anything.
                // Oversized bodies are cut before decoding; empty results are dropped (still ACKed).
                val raw = String(env.body, 0, minOf(env.body.size, 4096), Charsets.UTF_8)
                val text = MessagePolicy.sanitize(raw)
                if (text.isNotEmpty()) {
                    messages.add(Message(Bytes.hex(env.id), fresh.id, false, text,
                        env.sentAtMinute, MsgState.RECEIVED, via, expires))
                    notifyIncoming()
                }
            }
            Envelope.Kind.BADGE -> runCatching { contacts.save(fresh.copy(badge = ForgeRank.Badge.decode(env.body))) }
            Envelope.Kind.TIMER -> runCatching {
                val secs = ByteReader(env.body).u32().toInt().coerceIn(0, 4 * 7 * 24 * 3600)
                contacts.save(fresh.copy(disappearSeconds = secs))
            }
            Envelope.Kind.PAIRED -> { badgeProvider()?.let { shareBadge(fresh, it); kick() } }
            Envelope.Kind.MAILBOX -> runCatching {
                // Where this contact wants deposits while they're offline. Empty = they stopped using one.
                val pk = env.body.takeIf { it.size == 32 }?.also { OnionAddress.fromPublicKey(it) }
                if (env.body.isEmpty() || pk != null) contacts.save(fresh.copy(mailbox = pk))
            }
            Envelope.Kind.GROUP_INVITE -> runCatching { onGroupInvite(fresh, env.body) }
            Envelope.Kind.GROUP_STATE -> runCatching { onGroupState(fresh, env.body) }
            Envelope.Kind.GROUP_TEXT -> runCatching { onGroupText(fresh, env, via) }
            Envelope.Kind.GROUP_INTRO -> runCatching { onGroupIntro(fresh, env.body) }
            Envelope.Kind.IMAGE -> runCatching { onImage(fresh, env, via) }
            Envelope.Kind.RECEIPT -> Unit // read receipts are not sent by ONYX (privacy default)
        }
        return true
    }
}

private const val ONLINE_WINDOW_MS = 5 * 60_000L
private const val SYS_MAX_ATTEMPTS = 100
private const val INTRO_RETRY_MS = 20L * 3600 * 1000
/** How long a link stays open after the last delivery (the receiving side times out idle links at 60 s). */
private const val LINK_IDLE_MS = 45_000L
