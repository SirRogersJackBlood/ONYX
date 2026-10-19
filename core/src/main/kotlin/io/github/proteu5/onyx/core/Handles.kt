// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.core

import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Opt-in handles and the handle pool (v1.5). Design and threat model: docs/HANDLES.md.
 *
 *  - A handle is a name you choose plus a random number the app assigns: `raven.4821`.
 *  - Lookup is exact-match only. Pool hosts store records under a random-looking id derived from the handle
 *    with Argon2id (slow on purpose), encrypted with a key derived the same way: hosts can't read records, list
 *    handles or tell whose record is whose; someone guessing pays ~0.5 s of phone time per guess.
 *  - A record holds a request inbox (a separate onion, never your main one) and your identity key, signed by
 *    your identity key. Only the record's owner can change or delete it (a per-record owner key, not your
 *    identity key, so hosts don't learn identities).
 *  - Records expire 8 months after their last renewal; hosts then hold the id for 30 more days before anyone
 *    else may take it.
 *
 * Signature checks need libsignal (XEdDSA), so they're passed in as `verify` and live in the app.
 */
object Handles {
    const val NAME_MIN = 3
    const val NAME_MAX = 20
    const val SUFFIX_MIN = 10
    const val SUFFIX_MAX = 9999
    const val ARGON_MEMORY_KIB = 64 * 1024
    const val ARGON_PASSES = 3
    const val LIFETIME_DAYS = 243L          // about 8 months
    const val HOLD_DAYS = 30L
    const val RENEW_EVERY_DAYS = 30L
    const val SEALED_SIZE = 12 + 256 + 16   // nonce + one padded bucket + GCM tag: every record is the same size
    const val SIG_LEN = 64
    /** Proof of work for a connection request: about a second of phone time, nothing for a person, costly in bulk. */
    const val POW_BITS = 20
    /** Proof of work for every write to the pool (several seconds on a phone), so hosts can't be filled for free. */
    const val PUT_POW_BITS = 22

    private val SALT = "ONYX-pool-v1".toByteArray()
    private val NAME = Regex("^[a-z0-9_]{$NAME_MIN,$NAME_MAX}$")
    private val HANDLE = Regex("^([a-z0-9_]{$NAME_MIN,$NAME_MAX})\\.([1-9][0-9]{1,3})$")

    fun today(nowMs: Long = System.currentTimeMillis()) = nowMs / 86_400_000L

    /** NFKC + lower case; letters a–z, digits and _ only (no look-alike letters from other scripts). */
    fun normalize(input: String): String? {
        val s = Normalizer.normalize(input.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        return s.takeIf { HANDLE.matches(it) }
    }

    fun validName(name: String): Boolean =
        NAME.matches(Normalizer.normalize(name.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT))

    /** The app picks the number: nobody can choose a look-alike of someone else's handle. */
    fun withRandomSuffix(name: String, rng: SecureRandom = SecureRandom()): String {
        val n = Normalizer.normalize(name.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        require(NAME.matches(n)) { "Use 3–20 letters a–z, digits or _" }
        return "$n.${SUFFIX_MIN + rng.nextInt(SUFFIX_MAX - SUFFIX_MIN + 1)}"
    }

    class Keys(val recordId: ByteArray, val key: ByteArray)

    /** Slow on purpose (Argon2id, 64 MiB, 3 passes). [memoryKiB] is lowered only in tests. */
    fun derive(handle: String, memoryKiB: Int = ARGON_MEMORY_KIB): Keys {
        val h = normalize(handle) ?: throw IllegalArgumentException("not a handle")
        val seed = Argon2.hash(Argon2.Type.ID, h.toByteArray(), SALT, ARGON_PASSES, memoryKiB, 1, 32)
        try {
            return Keys(Kdf.hkdf(seed, SALT, "record-id", 32), Kdf.hkdf(seed, SALT, "record-key", 32))
        } finally { Bytes.wipe(seed) }
    }

    /** What's inside an encrypted record. [identitySig] = the identity key's signature over [signedBytes]. */
    class Card(val handle: String, val inboxOnionPk: ByteArray, val identityKey: ByteArray, val expiresDay: Long, val identitySig: ByteArray) {
        init { require(inboxOnionPk.size == 32 && identityKey.size in 1..64 && identitySig.size == SIG_LEN) }
        fun encode(): ByteArray = ByteWriter(200).u8(1).str16(handle).fixed(inboxOnionPk, 32).bytes16(identityKey)
            .u32(expiresDay).fixed(identitySig, SIG_LEN).toByteArray()
        companion object {
            fun signedBytes(handle: String, inboxOnionPk: ByteArray, identityKey: ByteArray, expiresDay: Long): ByteArray =
                ByteWriter(160).raw("onyx-handle-card-v1".toByteArray()).str16(handle).fixed(inboxOnionPk, 32)
                    .bytes16(identityKey).u32(expiresDay).toByteArray()
            fun decode(b: ByteArray): Card {
                val r = ByteReader(b); if (r.u8() != 1) throw MalformedException("card version")
                val c = Card(r.str16(64), r.raw(32), r.bytes16(64), r.u32(), r.raw(SIG_LEN)); r.end(); return c
            }
        }
    }

    private fun aad(recordId: ByteArray) = "onyx-handle-v1".toByteArray() + recordId

    /** Encrypts a card for the pool. Always [SEALED_SIZE] bytes. */
    fun seal(keys: Keys, card: Card, rng: SecureRandom = SecureRandom()): ByteArray {
        val nonce = ByteArray(12).also { rng.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad(keys.recordId))
        return nonce + c.doFinal(Padding.pad(card.encode(), intArrayOf(256)))
    }

    /** Decrypts a card found under [keys]; null if it doesn't open or names a different handle. */
    fun open(keys: Keys, handle: String, sealed: ByteArray): Card? = runCatching {
        require(sealed.size == SEALED_SIZE)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.key, "AES"), GCMParameterSpec(128, sealed, 0, 12))
        c.updateAAD(aad(keys.recordId))
        val card = Card.decode(Padding.unpad(c.doFinal(sealed, 12, sealed.size - 12), intArrayOf(256)))
        card.takeIf { it.handle == normalize(handle) }
    }.getOrNull()

    /**
     * What a pool host stores and serves. [ownerKey] is a per-record signing key kept on the owner's phone,
     * NOT the identity key. [sealed] empty = a delete request.
     */
    class Entry(val recordId: ByteArray, val ownerKey: ByteArray, val version: Long, val expiresDay: Long, val sealed: ByteArray, val sig: ByteArray,
                /** Proof of work over (recordId, ownerKey, version): see [putChallenge]. */
                val pow: Long = 0) {
        init { require(recordId.size == 32 && ownerKey.size in 1..64 && sig.size == SIG_LEN && (sealed.isEmpty() || sealed.size == SEALED_SIZE)) }
        val isDelete get() = sealed.isEmpty()
        val idHex: String get() = Bytes.hex(recordId)
        fun encode(): ByteArray = ByteWriter(400).u8(1).fixed(recordId, 32).bytes16(ownerKey).u32(version).u32(expiresDay)
            .bytes16(sealed).fixed(sig, SIG_LEN).u64(pow).toByteArray()
        companion object {
            fun signedBytes(recordId: ByteArray, ownerKey: ByteArray, version: Long, expiresDay: Long, sealed: ByteArray): ByteArray =
                ByteWriter(200).raw("onyx-handle-entry-v1".toByteArray()).fixed(recordId, 32).bytes16(ownerKey)
                    .u32(version).u32(expiresDay).raw(Bytes.sha256(sealed)).toByteArray()
            fun decode(b: ByteArray): Entry {
                val r = ByteReader(b); if (r.u8() != 1) throw MalformedException("entry version")
                val e = Entry(r.raw(32), r.bytes16(64), r.u32(), r.u32(), r.bytes16(SEALED_SIZE), r.raw(SIG_LEN), r.u64()); r.end(); return e
            }
        }
        fun signedBytes() = signedBytes(recordId, ownerKey, version, expiresDay, sealed)
    }

    /**
     * A purged or deleted record's id, kept [HOLD_DAYS]: nobody else may take it, and its owner may only come back
     * with a HIGHER version, so an old signed copy can't be replayed to revive it.
     */
    class Hold(val recordId: ByteArray, val ownerKey: ByteArray, val version: Long, val untilDay: Long)

    sealed class Decision {
        object Store : Decision()
        object Delete : Decision()
        class Reject(val reason: String) : Decision()
    }

    /**
     * The one rule every host applies to an incoming entry, so all hosts agree. [verify] checks [Entry.sig] with
     * [Entry.ownerKey].
     */
    fun decide(existing: Entry?, hold: Hold?, incoming: Entry, today: Long, verify: (Entry) -> Boolean): Decision {
        if (!checkPow(putChallenge(incoming.recordId, incoming.ownerKey, incoming.version), incoming.pow, PUT_POW_BITS))
            return Decision.Reject("proof of work")
        if (!verify(incoming)) return Decision.Reject("signature")
        val live = existing?.takeIf { today <= it.expiresDay }
        if (live != null) {
            if (!Bytes.ctEquals(live.ownerKey, incoming.ownerKey)) return Decision.Reject("taken")
            if (incoming.version <= live.version) return Decision.Reject("stale")
            if (incoming.isDelete) return Decision.Delete
        } else {
            if (incoming.isDelete) return Decision.Reject("nothing to delete")
            // An expired entry not yet swept counts as held too: no window between expiry and the sweep.
            val held = (hold ?: existing?.let { Hold(it.recordId, it.ownerKey, it.version, it.expiresDay + HOLD_DAYS) })?.takeIf { today <= it.untilDay }
            if (held != null && !Bytes.ctEquals(held.ownerKey, incoming.ownerKey)) return Decision.Reject("held")
            if (held != null && incoming.version <= held.version) return Decision.Reject("stale")
        }
        if (incoming.expiresDay < today) return Decision.Reject("expired")
        if (incoming.expiresDay > today + LIFETIME_DAYS + 1) return Decision.Reject("lifetime too long")
        return Decision.Store
    }

    /** What a host keeps after a [Decision.Delete]: the id stays held for [HOLD_DAYS] at the delete's version. */
    fun holdAfterDelete(delete: Entry, today: Long) = Hold(delete.recordId, delete.ownerKey, delete.version, today + HOLD_DAYS)

    /** Daily host sweep: expired entries become holds; holds past their 30 days disappear. */
    fun sweep(entries: List<Entry>, holds: List<Hold>, today: Long): Pair<List<Entry>, List<Hold>> {
        val (expired, kept) = entries.partition { today > it.expiresDay }
        val newHolds = expired.map { Hold(it.recordId, it.ownerKey, it.version, it.expiresDay + HOLD_DAYS) }
        return kept to (holds.filter { today <= it.untilDay } + newHolds)
    }

    // ---- proof of work for connection requests ----

    /**
     * Bound to the inbox, the day AND the request itself (its digest, which covers the sender's one-time key), so
     * one solution can't be reused for other requests. The inbox also remembers solutions it has seen that day.
     */
    fun powChallenge(inboxOnionPk: ByteArray, day: Long, requestDigest: ByteArray): ByteArray =
        Bytes.sha256("onyx-request-pow-v2".toByteArray(), inboxOnionPk, ByteWriter(4).u32(day).toByteArray(), requestDigest)

    fun putChallenge(recordId: ByteArray, ownerKey: ByteArray, version: Long): ByteArray =
        Bytes.sha256("onyx-pool-put-pow-v1".toByteArray(), recordId, ownerKey, ByteWriter(4).u32(version).toByteArray())

    private fun leadingZeroBits(h: ByteArray): Int {
        var n = 0
        for (b in h) { val v = b.toInt() and 0xff; if (v == 0) { n += 8; continue }; n += Integer.numberOfLeadingZeros(v) - 24; break }
        return n
    }

    private fun powHash(challenge: ByteArray, nonce: Long) = Bytes.sha256(challenge, ByteWriter(8).u64(nonce).toByteArray())

    fun checkPow(challenge: ByteArray, nonce: Long, bits: Int = POW_BITS): Boolean = leadingZeroBits(powHash(challenge, nonce)) >= bits

    fun solvePow(challenge: ByteArray, bits: Int = POW_BITS, start: Long = 0): Long {
        var n = start
        while (!checkPow(challenge, n, bits)) n++
        return n
    }
}
