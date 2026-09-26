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

class ContactRepo(private val store: SecureStore) {
    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    private fun changed() = synchronized(listeners) { listeners.toList() }.forEach { it() }

    fun all(): List<Contact> = store.list(NS).map { Contact.decode(it.second) }
    fun get(id: String): Contact? = store.get(NS, id)?.let { Contact.decode(it) }
    fun byOnion(onion: OnionAddress): Contact? = all().firstOrNull { it.onion == onion }
    fun save(c: Contact) { store.upsertKeepOrder(NS, c.id, c.encode()); changed() }
    fun delete(id: String) { store.delete(NS, id); store.deleteNamespace(MessageRepo.ns(id)); changed() }

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
) {
    fun encode(): ByteArray = ByteWriter(text.length * 3 + 64)
        .u8(1).str16(id).str16(contactId).u8(if (outgoing) 1 else 0)
        .bytes32(text.toByteArray(Charsets.UTF_8)).u64(sentAtMinute).u8(state.ordinal)
        .u8(transport?.ordinal ?: 255).u64(expiresAtMs).toByteArray()

    companion object {
        fun decode(b: ByteArray): Message {
            val r = ByteReader(b)
            require(r.u8() == 1)
            return Message(
                r.str16(), r.str16(), r.u8() == 1, String(r.bytes32(1 shl 20), Charsets.UTF_8), r.u64(),
                MsgState.values()[r.u8()], r.u8().let { if (it == 255) null else Transport.values()[it] }, r.u64()
            )
        }
    }
}

class MessageRepo(private val store: SecureStore) {
    private val listeners = mutableListOf<(String) -> Unit>()
    fun addListener(l: (String) -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: (String) -> Unit) = synchronized(listeners) { listeners -= l }
    private fun changed(contactId: String) = synchronized(listeners) { listeners.toList() }.forEach { it(contactId) }

    fun forContact(contactId: String): List<Message> = store.list(ns(contactId)).map { Message.decode(it.second) }

    fun add(m: Message) { store.put(ns(m.contactId), m.id, m.encode(), store.nextOrd()); changed(m.contactId) }

    fun update(m: Message) { store.upsertKeepOrder(ns(m.contactId), m.id, m.encode()); changed(m.contactId) }

    fun delete(contactId: String, id: String) { store.delete(ns(contactId), id); changed(contactId) }

    /** Removes expired disappearing messages. Returns number removed. */
    fun sweepExpired(contacts: List<Contact>, nowMs: Long): Int {
        var n = 0
        for (c in contacts) for (m in forContact(c.id)) {
            if (m.expiresAtMs in 1..nowMs) { store.delete(ns(c.id), m.id); n++ }
        }
        return n
    }

    companion object { fun ns(contactId: String) = "msg:$contactId" }
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
    fun put(pairingIdHex: String, codeText: String, bundle: ByteArray) =
        store.put(NS, pairingIdHex, ByteWriter(bundle.size + 256).u8(1).str16(codeText).bytes32(bundle).toByteArray(), store.nextOrd())

    /** Single use: returns and deletes. */
    fun take(pairingIdHex: String): Pair<String, ByteArray>? {
        val raw = store.get(NS, pairingIdHex) ?: return null
        store.delete(NS, pairingIdHex)
        val r = ByteReader(raw); r.u8()
        return r.str16() to r.bytes32(1 shl 16)
    }

    fun count(): Int = store.list(NS).size
    fun revokeAll() = store.deleteNamespace(NS)
    companion object { private const val NS = "invites" }
}
