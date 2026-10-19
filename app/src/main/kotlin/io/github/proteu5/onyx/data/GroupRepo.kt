// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.data

import io.github.proteu5.onyx.core.ByteReader
import io.github.proteu5.onyx.core.ByteWriter
import io.github.proteu5.onyx.core.Groups

/**
 * Groups this phone has joined, pending invites, and when each introduction was last sent (v1.4.1).
 * Everything lives in the encrypted store. A group's messages use the chat id "g:<group id>" in
 * [MessageRepo] and [ContactMetaRepo], so unread counts, recency and archive work like a contact's.
 */
class GroupRepo(private val store: SecureStore, private val meta: ContactMetaRepo? = null) {
    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    private fun changed() = synchronized(listeners) { listeners.toList() }.forEach { it() }

    fun all(): List<Groups.State> = store.list(NS).mapNotNull { runCatching { Groups.State.decode(it.second) }.getOrNull() }
    fun get(idHex: String): Groups.State? = store.get(NS, idHex)?.let { runCatching { Groups.State.decode(it) }.getOrNull() }
    fun save(s: Groups.State) { store.upsertKeepOrder(NS, s.idHex, s.encode()); changed() }

    /** Leaves no trace: roster, messages, list state and introduction times. */
    fun delete(idHex: String) {
        store.delete(NS, idHex)
        store.deleteNamespace(MessageRepo.ns(chatId(idHex)))
        store.deleteNamespace(MessageRepo.imgNs(chatId(idHex)))
        meta?.delete(chatId(idHex))
        store.list(NS_INTRO).map { it.first }.filter { it.startsWith(idHex) }.forEach { store.delete(NS_INTRO, it) }
        store.delete(NS_CONFLICT, idHex)
        changed()
    }

    // ---- invites waiting for my answer ----
    class Invite(val fromContactId: String, val invite: Groups.Invite) { val state: Groups.State get() = invite.state }

    fun invites(): List<Invite> = store.list(NS_INV).mapNotNull { (_, raw) ->
        runCatching { val r = ByteReader(raw); require(r.u8() == 2); Invite(r.str16(), Groups.Invite.decode(r.bytes32(1 shl 16))) }.getOrNull()
    }

    /** False when too many invites are already waiting (anti-spam). A newer invite to the same group replaces the old one. */
    fun putInvite(fromContactId: String, inv: Groups.Invite): Boolean {
        val idHex = inv.state.idHex
        if (store.get(NS_INV, idHex) == null && store.list(NS_INV).size >= MAX_PENDING_INVITES) return false
        store.upsertKeepOrder(NS_INV, idHex, ByteWriter(4096).u8(2).str16(fromContactId).bytes32(inv.encode()).toByteArray())
        changed(); return true
    }

    fun takeInvite(idHex: String): Invite? = invites().firstOrNull { it.state.idHex == idHex }?.also { removeInvite(idHex) }
    fun removeInvite(idHex: String) { store.delete(NS_INV, idHex); changed() }
    /** Invites from someone who was blocked or deleted go away. */
    fun removeInvitesFrom(contactId: String) = invites().filter { it.fromContactId == contactId }.forEach { removeInvite(it.state.idHex) }

    // ---- disputed members: an onion claimed with two different identity keys ----
    fun conflicts(idHex: String): Set<String> =
        store.get(NS_CONFLICT, idHex)?.let { String(it, Charsets.UTF_8).split(',').filter { s -> s.length == 64 }.toSet() } ?: emptySet()

    fun addConflict(idHex: String, onionHex: String) {
        val now = conflicts(idHex)
        if (onionHex in now || now.size >= Groups.MAX_ENTRIES) return
        store.upsertKeepOrder(NS_CONFLICT, idHex, (now + onionHex).joinToString(",").toByteArray(Charsets.UTF_8)); changed()
    }

    // ---- introduction throttle ----
    fun introSentAt(idHex: String, memberKey: String): Long = store.get(NS_INTRO, idHex + memberKey)?.let { ByteReader(it).u64() } ?: 0L
    fun setIntroSent(idHex: String, memberKey: String, atMs: Long) = store.upsertKeepOrder(NS_INTRO, idHex + memberKey, ByteWriter(8).u64(atMs).toByteArray())

    companion object {
        private const val NS = "groups"
        private const val NS_INV = "group-invites"
        private const val NS_INTRO = "group-intros"
        private const val NS_CONFLICT = "group-conflicts"
        const val MAX_PENDING_INVITES = 20
        fun chatId(idHex: String) = "g:$idHex"
        fun isGroupChat(chatId: String) = chatId.startsWith("g:")
        fun idOf(chatId: String) = chatId.removePrefix("g:")
    }
}
