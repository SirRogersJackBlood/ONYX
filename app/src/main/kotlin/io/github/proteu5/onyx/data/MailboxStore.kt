// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.data

import io.github.proteu5.onyx.core.ByteReader
import io.github.proteu5.onyx.core.ByteWriter
import io.github.proteu5.onyx.core.Bytes
import io.github.proteu5.onyx.core.Frame
import io.github.proteu5.onyx.core.Mailbox

/**
 * Storage on a MAILBOX device (v1.4.0). Holds ciphertexts that the owner's contacts deposited while
 * the owner was offline. Everything here is already end-to-end encrypted to the owner: this device
 * can't read it. It only knows an opaque per-contact tag, the size and when it arrived.
 * Kept inside the encrypted store like everything else.
 */
class MailboxStore(private val store: SecureStore) {

    class Item(val digestHex: String, val tag: ByteArray, val message: Frame.Message, val receivedAtMs: Long)

    // ---- who may deposit: (tag, depositKey) pairs sent by the owner ----

    fun depositors(): List<Pair<ByteArray, ByteArray>> = store.get(NS_KEYS, "all")?.let { raw ->
        runCatching {
            val r = ByteReader(raw); require(r.u8() == 1)
            List(r.u16()) { r.raw(Mailbox.TAG_LEN) to r.raw(32) }
        }.getOrNull()
    } ?: emptyList()

    fun setDepositors(entries: List<Pair<ByteArray, ByteArray>>) {
        val w = ByteWriter(3 + entries.size * (Mailbox.TAG_LEN + 32)).u8(1).u16(entries.size)
        entries.forEach { (t, k) -> w.fixed(t, Mailbox.TAG_LEN).fixed(k, 32) }
        store.upsertKeepOrder(NS_KEYS, "all", w.toByteArray())
    }

    // ---- stored items ----
    // Each item is stored once (NS_ITEMS) plus a small index row (NS_INDEX: tag, size, arrival time), so limits,
    // sweeping and listing never load the ciphertexts themselves. Images make items up to ~135 KB each.

    private class Index(val tag: ByteArray, val size: Int, val receivedAtMs: Long)

    private fun index(): List<Pair<String, Index>> = store.list(NS_INDEX).mapNotNull { (key, raw) ->
        runCatching { val r = ByteReader(raw); require(r.u8() == 1); key to Index(r.raw(Mailbox.TAG_LEN), r.u32().toInt(), r.u64()) }.getOrNull()
    }

    /** Digests of waiting items, oldest first. */
    fun keys(): List<String> = index().sortedBy { it.second.receivedAtMs }.map { it.first }

    fun item(digestHex: String): Item? = store.get(NS_ITEMS, digestHex)?.let { raw ->
        runCatching {
            val r = ByteReader(raw); require(r.u8() == 1)
            val tag = r.raw(Mailbox.TAG_LEN); val type = r.u8(); val ct = r.bytes32(Frame.MAX_CIPHERTEXT); val at = r.u64()
            Item(digestHex, tag, Frame.Message(type, ct), at)
        }.getOrNull()
    }

    fun count(): Int = store.list(NS_INDEX).size

    /** Stores one deposit. Duplicates are accepted (already held). False when a limit is reached. */
    @Synchronized
    fun deposit(tag: ByteArray, m: Frame.Message, nowMs: Long = System.currentTimeMillis()): Boolean {
        val key = Bytes.hex(m.digest())
        if (store.get(NS_INDEX, key) != null) return true
        val idx = index()
        val size = m.ciphertext.size
        if (idx.size >= MAX_TOTAL || idx.sumOf { it.second.size.toLong() } + size > MAX_TOTAL_BYTES) return false
        val mine = idx.filter { Bytes.ctEquals(it.second.tag, tag) }
        if (mine.size >= MAX_PER_SENDER || mine.sumOf { it.second.size.toLong() } + size > MAX_SENDER_BYTES) return false
        val v = ByteWriter(size + 64).u8(1).fixed(tag, Mailbox.TAG_LEN).u8(m.signalType)
            .bytes32(m.ciphertext).u64(nowMs).toByteArray()
        store.put(NS_ITEMS, key, v, store.nextOrd())
        store.put(NS_INDEX, key, ByteWriter(40).u8(1).fixed(tag, Mailbox.TAG_LEN).u32(size.toLong()).u64(nowMs).toByteArray(), store.nextOrd())
        return true
    }

    fun remove(digestHex: String) { store.delete(NS_ITEMS, digestHex); store.delete(NS_INDEX, digestHex) }

    /** Deletes items older than the retention window. Returns how many were removed. */
    fun sweep(nowMs: Long = System.currentTimeMillis()): Int {
        val old = index().filter { nowMs - it.second.receivedAtMs > RETENTION_MS }
        old.forEach { remove(it.first) }
        return old.size
    }

    fun wipe() { store.deleteNamespace(NS_ITEMS); store.deleteNamespace(NS_INDEX); store.deleteNamespace(NS_KEYS) }

    companion object {
        private const val NS_ITEMS = "mbox-items"
        private const val NS_KEYS = "mbox-keys"
        private const val NS_INDEX = "mbox-index"
        const val MAX_PER_SENDER = 500
        const val MAX_TOTAL = 5000
        /** Byte caps (v1.4.4, images): 300 MB in total, 30 MB per sender. */
        const val MAX_TOTAL_BYTES = 300L * 1024 * 1024
        const val MAX_SENDER_BYTES = 30L * 1024 * 1024
        const val RETENTION_MS = 14L * 24 * 3600 * 1000
    }
}
