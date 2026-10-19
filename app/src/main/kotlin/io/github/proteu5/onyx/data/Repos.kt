// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.data

import io.github.proteu5.onyx.core.ByteReader
import io.github.proteu5.onyx.core.ByteWriter
import io.github.proteu5.onyx.core.Bytes
import io.github.proteu5.onyx.core.ForgeRank
import io.github.proteu5.onyx.core.OnionAddress
import io.github.proteu5.onyx.core.Transport

/** A paired person. Name is a LOCAL label only; ONYX has no profiles and never sends names. */
data class Contact(
    val id: String,
    val name: String,
    val onion: OnionAddress,
    val identityKey: ByteArray,
    val linkKey: ByteArray,
    val mailbox: ByteArray?,
    val badge: ForgeRank.Badge?,
    val verified: Boolean,
    val disappearSeconds: Int,
    val smsAllowed: Boolean,
    val smsNumber: String?,
) {
    /** libsignal address: the onion label is our stable identifier. Single device per identity. */
    val signalName: String get() = onion.label

    fun encode(): ByteArray = ByteWriter(512)
        .u8(1).str16(id).str16(name).raw(onion.publicKey).bytes16(identityKey).raw(linkKey)
        .u8(if (mailbox != null) 1 else 0).apply { mailbox?.let { raw(it) } }
        .u8(if (badge != null) 1 else 0).apply { badge?.let { bytes16(it.encode()) } }
        .u8(if (verified) 1 else 0).u32(disappearSeconds.toLong())
        .u8(if (smsAllowed) 1 else 0).str16(smsNumber ?: "")
        .toByteArray()

    companion object {
        fun decode(b: ByteArray): Contact {
            val r = ByteReader(b)
            require(r.u8() == 1)
            val id = r.str16(); val name = r.str16()
            val onion = OnionAddress.fromPublicKey(r.raw(32))
            val ik = r.bytes16(); val lk = r.raw(32)
            val mb = if (r.u8() == 1) r.raw(32) else null
            val badge = if (r.u8() == 1) ForgeRank.Badge.decode(r.bytes16()) else null
            val verified = r.u8() == 1; val dis = r.u32().toInt()
            val sms = r.u8() == 1; val num = r.str16().ifEmpty { null }
            return Contact(id, name, onion, ik, lk, mb, badge, verified, dis, sms, num)
        }
        fun newId(): String = Bytes.hex(Bytes.random(16))
    }
}

/**
 * Per-contact list state for the contact list (v1.4.0). Kept apart from [Contact] so Contact's stored
 * format is unchanged. Local only, inside the encrypted store; never sent.
 *  - lastOrd: store order counter of the latest message (sorting by "recent" without timestamps)
 *  - unread: incoming messages not yet seen in the chat screen
 *  - archived: hidden from the main list until a new message arrives
 *  - blocked: incoming messages are dropped silently, sending is refused
 */
data class ContactMeta(
    val lastOrd: Long = 0,
    val unread: Int = 0,
    val archived: Boolean = false,
    val blocked: Boolean = false,
) {
    fun encode(): ByteArray = ByteWriter(32).u8(1).u64(lastOrd).u32(unread.toLong())
        .u8(if (archived) 1 else 0).u8(if (blocked) 1 else 0).toByteArray()

    companion object {
        fun decode(b: ByteArray): ContactMeta {
            val r = ByteReader(b); require(r.u8() == 1)
            return ContactMeta(r.u64(), r.u32().toInt(), r.u8() == 1, r.u8() == 1)
        }
    }
}

class ContactMetaRepo(private val store: SecureStore) {
    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    private fun changed() = synchronized(listeners) { listeners.toList() }.forEach { it() }

    fun get(id: String): ContactMeta =
        store.get(NS, id)?.let { runCatching { ContactMeta.decode(it) }.getOrNull() } ?: ContactMeta()

    @Synchronized
    fun update(id: String, f: (ContactMeta) -> ContactMeta) {
        val old = get(id); val new = f(old)
        if (new != old) { store.upsertKeepOrder(NS, id, new.encode()); changed() }
    }

    /** A new message: bump recency; incoming ones count as unread and bring an archived chat back. */
    fun onMessage(contactId: String, ord: Long, incoming: Boolean) = update(contactId) {
        it.copy(lastOrd = ord, unread = if (incoming) it.unread + 1 else it.unread, archived = if (incoming) false else it.archived)
    }

    fun markRead(id: String) = update(id) { it.copy(unread = 0) }
    fun setArchived(id: String, v: Boolean) = update(id) { it.copy(archived = v) }
    fun setBlocked(id: String, v: Boolean) = update(id) { it.copy(blocked = v) }
    fun isBlocked(id: String): Boolean = get(id).blocked
    fun delete(id: String) { store.delete(NS, id); changed() }

    companion object { private const val NS = "contact-meta" }
}

class ContactRepo(private val store: SecureStore, private val meta: ContactMetaRepo? = null) {
    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    private fun changed() = synchronized(listeners) { listeners.toList() }.forEach { it() }

    fun all(): List<Contact> = store.list(NS).map { Contact.decode(it.second) }
    fun get(id: String): Contact? = store.get(NS, id)?.let { Contact.decode(it) }
    fun byOnion(onion: OnionAddress): Contact? = all().firstOrNull { it.onion == onion }
    fun save(c: Contact) { store.upsertKeepOrder(NS, c.id, c.encode()); changed() }
    fun delete(id: String) {
        store.delete(NS, id); store.deleteNamespace(MessageRepo.ns(id)); store.deleteNamespace(MessageRepo.imgNs(id)); meta?.delete(id); changed()
    }

    companion object { private const val NS = "contacts" }
}

enum class MsgState { QUEUED, SENT, FAILED, RECEIVED }

data class Message(
    val id: String,
    val contactId: String,
    val outgoing: Boolean,
    val text: String,
    val sentAtMinute: Long,
    val state: MsgState,
    val transport: Transport?,
    val expiresAtMs: Long,     // 0 = never
    /** Group chats only (v1.4.1): the contact id of the member who wrote an incoming message. */
    val senderId: String? = null,
    /** v1.4.4: an image is attached (bytes kept separately, see [MessageRepo.image]); [text] is its caption. */
    val hasImage: Boolean = false,
) {
    fun encode(): ByteArray = ByteWriter(text.length * 3 + 96)
        .u8(3).str16(id).str16(contactId).u8(if (outgoing) 1 else 0)
        .bytes32(text.toByteArray(Charsets.UTF_8)).u64(sentAtMinute).u8(state.ordinal)
        .u8(transport?.ordinal ?: 255).u64(expiresAtMs).str16(senderId ?: "").u8(if (hasImage) 1 else 0).toByteArray()

    companion object {
        fun decode(b: ByteArray): Message {
            val r = ByteReader(b)
            val v = r.u8(); require(v in 1..3)
            return Message(
                r.str16(), r.str16(), r.u8() == 1, String(r.bytes32(1 shl 20), Charsets.UTF_8), r.u64(),
                MsgState.values()[r.u8()], r.u8().let { if (it == 255) null else Transport.values()[it] }, r.u64(),
                if (v >= 2) r.str16().ifEmpty { null } else null,
                if (v >= 3) r.u8() == 1 else false,
            )
        }
    }
}

class MessageRepo(private val store: SecureStore, private val meta: ContactMetaRepo? = null) {
    private val listeners = mutableListOf<(String) -> Unit>()
    fun addListener(l: (String) -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: (String) -> Unit) = synchronized(listeners) { listeners -= l }
    private fun changed(contactId: String) = synchronized(listeners) { listeners.toList() }.forEach { it(contactId) }

    fun forContact(contactId: String): List<Message> = store.list(ns(contactId)).map { Message.decode(it.second) }

    fun add(m: Message) {
        val ord = store.nextOrd()
        store.put(ns(m.contactId), m.id, m.encode(), ord)
        meta?.onMessage(m.contactId, ord, incoming = !m.outgoing)
        changed(m.contactId)
    }

    fun update(m: Message) { store.upsertKeepOrder(ns(m.contactId), m.id, m.encode()); changed(m.contactId) }

    fun delete(contactId: String, id: String) { store.delete(ns(contactId), id); store.delete(imgNs(contactId), id); changed(contactId) }

    /** Image bytes (already re-encoded, no metadata), stored apart so listing a chat stays light. */
    fun putImage(chatId: String, messageId: String, jpeg: ByteArray) = store.upsertKeepOrder(imgNs(chatId), messageId, jpeg)
    fun image(chatId: String, messageId: String): ByteArray? = store.get(imgNs(chatId), messageId)

    /** Removes expired disappearing messages. Returns number removed. */
    fun sweepExpired(contacts: List<Contact>, nowMs: Long): Int {
        var n = 0
        for (c in contacts) for (m in forContact(c.id)) {
            if (m.expiresAtMs in 1..nowMs) { store.delete(ns(c.id), m.id); store.delete(imgNs(c.id), m.id); n++ }
        }
        return n
    }

    companion object {
        fun ns(contactId: String) = "msg:$contactId"
        fun imgNs(chatId: String) = "img:$chatId"
    }
}

/** Ciphertexts waiting for delivery. Encrypted ONCE; retries resend identical bytes. */
data class OutboxItem(
    val messageId: String,
    val contactId: String,
    val signalType: Int,
    val ciphertext: ByteArray,
    val attempts: Int,
    val nextAttemptMs: Long,
) {
    fun encode(): ByteArray = ByteWriter(ciphertext.size + 96).u8(1).str16(messageId).str16(contactId)
        .u8(signalType).bytes32(ciphertext).u32(attempts.toLong()).u64(nextAttemptMs).toByteArray()

    companion object {
        fun decode(b: ByteArray): OutboxItem {
            val r = ByteReader(b); require(r.u8() == 1)
            return OutboxItem(r.str16(), r.str16(), r.u8(), r.bytes32(1 shl 20), r.u32().toInt(), r.u64())
        }
    }
}

class OutboxRepo(private val store: SecureStore) {
    fun all(): List<OutboxItem> = store.list(NS).map { OutboxItem.decode(it.second) }
    fun put(i: OutboxItem) = store.upsertKeepOrder(NS, i.messageId, i.encode())
    fun remove(messageId: String) = store.delete(NS, messageId)
    fun removeForContact(contactId: String) = all().filter { it.contactId == contactId }.forEach { remove(it.messageId) }
    companion object { private const val NS = "outbox" }
}

/**
 * Remote invite links outlive the app process (24 h), so their secret + bundle are persisted
 * in the encrypted store. In-person codes stay memory-only.
 */
class InviteRepo(private val store: SecureStore) {
    /** A stored invite. [expectOnion]/[expectIdentity]: set for group introductions, which only that member may use. */
    class Stored(val codeText: String, val bundle: ByteArray, val expectOnion: ByteArray?, val expectIdentity: ByteArray?)

    fun put(pairingIdHex: String, codeText: String, bundle: ByteArray, expectOnion: ByteArray? = null, expectIdentity: ByteArray? = null) {
        val w = ByteWriter(bundle.size + 360).u8(2).str16(codeText).bytes32(bundle)
        if (expectOnion != null && expectIdentity != null) w.u8(1).fixed(expectOnion, 32).bytes16(expectIdentity) else w.u8(0)
        store.put(NS, pairingIdHex, w.toByteArray(), store.nextOrd())
    }

    /** Single use: returns and deletes. */
    fun take(pairingIdHex: String): Stored? {
        val raw = store.get(NS, pairingIdHex) ?: return null
        store.delete(NS, pairingIdHex)
        val r = ByteReader(raw); val v = r.u8()
        val text = r.str16(); val bundle = r.bytes32(1 shl 16)
        return if (v >= 2 && r.u8() == 1) Stored(text, bundle, r.raw(32), r.bytes16(64)) else Stored(text, bundle, null, null)
    }

    fun count(): Int = store.list(NS).size
    fun revokeAll() = store.deleteNamespace(NS)
    companion object { private const val NS = "invites" }
}
