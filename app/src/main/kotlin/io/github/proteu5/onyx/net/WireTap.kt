// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.net

import io.github.proteu5.onyx.core.WireAnatomy
import io.github.proteu5.onyx.core.WireDirection
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory record of the raw frames exchanged with each contact, for the chat's RAW view.
 *
 * What it holds is exactly what crossed the link *inside* Tor: fixed-size padded frames that carry
 * libsignal ciphertext. It never holds plaintext. It is deliberately memory-only: never written to
 * disk, capped per contact, and cleared on panic wipe or when the process dies.
 */
object WireTap {
    data class Entry(
        val atMs: Long,
        val dir: WireDirection,
        val type: Int,
        val wireSize: Int,
        val anatomy: String,
        val preview: ByteArray,   // first PREVIEW_BYTES bytes, exactly as on the wire
    ) { val typeName: String get() = WireAnatomy.typeName(type) }

    private const val MAX_PER_CONTACT = 120
    const val PREVIEW_BYTES = 256

    private val logs = ConcurrentHashMap<String, ArrayDeque<Entry>>()
    private val listeners = mutableListOf<(String) -> Unit>()

    fun addListener(l: (String) -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: (String) -> Unit) = synchronized(listeners) { listeners -= l }

    fun record(contactId: String, dir: WireDirection, type: Int, wire: ByteArray) {
        val e = Entry(System.currentTimeMillis(), dir, type, wire.size, WireAnatomy.describe(type, wire),
            wire.copyOf(minOf(PREVIEW_BYTES, wire.size)))
        val q = logs.getOrPut(contactId) { ArrayDeque() }
        synchronized(q) { q.addLast(e); while (q.size > MAX_PER_CONTACT) q.removeFirst() }
        synchronized(listeners) { listeners.toList() }.forEach { runCatching { it(contactId) } }
    }

    fun entries(contactId: String): List<Entry> = logs[contactId]?.let { q -> synchronized(q) { q.toList() } } ?: emptyList()

    fun clear(contactId: String) { logs.remove(contactId) }
    fun clearAll() = logs.clear()
}
