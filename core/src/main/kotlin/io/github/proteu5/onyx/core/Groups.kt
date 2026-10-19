// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.core

/**
 * Small private groups (v1.4.1). No server, no admin.
 *
 *  - Up to [MAX_MEMBERS] people. Every message is encrypted separately to each member over the
 *    existing 1:1 Signal sessions (fan-out), so groups add no new cryptography. The cap keeps that
 *    fan-out cheap over Tor and keeps a group a group of people who know each other. See docs/GROUPS.md.
 *  - Any member may INVITE one of their own contacts, by signing a voucher for that contact's onion + key.
 *    Joining is the invitee's own decision. Every member entry needs a voucher from someone already in the
 *    roster (except the creator's), so a contact who merely learns the group id can't add themselves.
 *  - Membership is self-sovereign: each member's entry (join or leave) is signed by that member's own
 *    identity key. Nobody can join for you, remove you, or fake that you left.
 *  - The group id commits to the creator, name and verified-only setting, so those can't be altered.
 *  - Every phone keeps the roster and merges what it hears: per member, the highest signed version wins
 *    (a leave beats a join at the same version). Merging is order-independent, so phones converge.
 *
 * Signature checking needs libsignal, so it is passed in as `verify` and lives in the app.
 */
object Groups {
    const val MAX_MEMBERS = 8
    /** Joined + left entries kept per group (left ones stay so an old "joined" can't resurrect them). */
    const val MAX_ENTRIES = 32
    const val TITLE_MAX = 40
    const val ID_LEN = 16
    const val SIG_LEN = 64
    /** Hops an introduction may be relayed through members before it is dropped. */
    const val MAX_HOPS = 6
    private val DOMAIN = "onyx-group-member-v2".toByteArray()
    private val VOUCHER_DOMAIN = "onyx-group-voucher-v1".toByteArray()
    private val ID_DOMAIN = "onyx-group-id-v1".toByteArray()

    /** Highest accepted version: leaves room so a member can always sign one more change (leave). */
    const val MAX_VERSION = 0x7fffffffL

    class Entry(
        val onionPk: ByteArray,
        val identityKey: ByteArray,
        val version: Long,
        val present: Boolean,
        val joinedAtMinute: Long,
        /** Who invited this member (their onion) and their signature over this member's onion + key. Null only for the creator. */
        val voucherOnionPk: ByteArray?,
        val voucherSig: ByteArray?,
        val sig: ByteArray,
    ) {
        init {
            require(onionPk.size == 32 && identityKey.size in 1..64 && sig.size == SIG_LEN && version in 1..0xffffffffL)
            require((voucherOnionPk == null) == (voucherSig == null) && (voucherOnionPk?.size ?: 32) == 32 && (voucherSig?.size ?: SIG_LEN) == SIG_LEN)
        }
        val key: String get() = Bytes.hex(onionPk)

        fun write(w: ByteWriter) = w.fixed(onionPk, 32).bytes16(identityKey).u32(version)
            .u8(if (present) 1 else 0).u32(joinedAtMinute)
            .u8(if (voucherOnionPk != null) 1 else 0).apply { if (voucherOnionPk != null) { fixed(voucherOnionPk, 32); fixed(voucherSig!!, SIG_LEN) } }
            .fixed(sig, SIG_LEN)

        companion object {
            fun read(r: ByteReader): Entry {
                val onion = r.raw(32); val ik = r.bytes16(64); val v = r.u32(); val present = r.u8() == 1; val joined = r.u32()
                val hasVoucher = r.u8() == 1
                val vo = if (hasVoucher) r.raw(32) else null; val vs = if (hasVoucher) r.raw(SIG_LEN) else null
                return Entry(onion, ik, v, present, joined, vo, vs, r.raw(SIG_LEN))
            }
        }
    }

    /** The bytes a member signs for its own entry (including who vouched for it). */
    fun signedBytes(groupId: ByteArray, onionPk: ByteArray, identityKey: ByteArray, version: Long, present: Boolean,
                    joinedAtMinute: Long, voucherOnionPk: ByteArray?): ByteArray =
        ByteWriter(200).raw(DOMAIN).fixed(groupId, ID_LEN).fixed(onionPk, 32).bytes16(identityKey)
            .u32(version).u8(if (present) 1 else 0).u32(joinedAtMinute)
            .apply { if (voucherOnionPk != null) u8(1).fixed(voucherOnionPk, 32) else u8(0) }.toByteArray()

    fun signedBytes(groupId: ByteArray, e: Entry) =
        signedBytes(groupId, e.onionPk, e.identityKey, e.version, e.present, e.joinedAtMinute, e.voucherOnionPk)

    /** What an inviter signs: "this onion with this identity key may join this group". */
    fun voucherBytes(groupId: ByteArray, memberOnionPk: ByteArray, memberIdentityKey: ByteArray): ByteArray =
        ByteWriter(128).raw(VOUCHER_DOMAIN).fixed(groupId, ID_LEN).fixed(memberOnionPk, 32).bytes16(memberIdentityKey).toByteArray()

    /**
     * The group id commits to the creator, name and verified-only setting, so nobody can change those
     * in an invite or a roster without changing the id itself.
     */
    fun deriveId(creatorOnionPk: ByteArray, creatorIdentityKey: ByteArray, title: String, verifiedOnly: Boolean, nonce: ByteArray): ByteArray =
        Bytes.sha256(ByteWriter(200).raw(ID_DOMAIN).fixed(creatorOnionPk, 32).bytes16(creatorIdentityKey).str16(title)
            .u8(if (verifiedOnly) 1 else 0).fixed(nonce, 16).toByteArray()).copyOf(ID_LEN)

    class State(
        val groupId: ByteArray, val title: String, val verifiedOnly: Boolean,
        val creatorOnionPk: ByteArray, val creatorIdentityKey: ByteArray, val nonce: ByteArray,
        val entries: List<Entry>,
    ) {
        init { require(groupId.size == ID_LEN && entries.size <= MAX_ENTRIES && creatorOnionPk.size == 32 && nonce.size == 16) }
        val idHex: String get() = Bytes.hex(groupId)

        /** True when the id really commits to this creator, name and setting. */
        fun genesisValid(): Boolean =
            Bytes.ctEquals(groupId, deriveId(creatorOnionPk, creatorIdentityKey, title, verifiedOnly, nonce))

        fun withEntries(list: List<Entry>) = State(groupId, title, verifiedOnly, creatorOnionPk, creatorIdentityKey, nonce, list)

        fun entry(onionPk: ByteArray): Entry? = entries.firstOrNull { Bytes.ctEquals(it.onionPk, onionPk) }

        /**
         * Members in the group now: present, oldest join first, at most [MAX_MEMBERS] (same answer on every phone).
         * A member can't claim to have joined before the person who invited them, so invitees can't jump the queue.
         */
        fun active(): List<Entry> = entries.filter { it.present }
            .sortedWith(compareBy<Entry>({ it.joinedAtMinute }, { it.key })).take(MAX_MEMBERS)

        fun isActive(onionPk: ByteArray) = active().any { Bytes.ctEquals(it.onionPk, onionPk) }
        fun isFull() = active().size >= MAX_MEMBERS

        fun encode(): ByteArray {
            val w = ByteWriter(128 + entries.size * 260).u8(2).fixed(groupId, ID_LEN).str16(title).u8(if (verifiedOnly) 1 else 0)
                .fixed(creatorOnionPk, 32).bytes16(creatorIdentityKey).fixed(nonce, 16).u8(entries.size)
            entries.forEach { it.write(w) }
            return w.toByteArray()
        }

        companion object {
            /** Rejects anything whose id doesn't match its creator, name and setting. */
            fun decode(b: ByteArray): State {
                val r = ByteReader(b)
                if (r.u8() != 2) throw MalformedException("group version")
                val id = r.raw(ID_LEN); val title = r.str16(TITLE_MAX * 4); val vo = r.u8() == 1
                val co = r.raw(32); val cik = r.bytes16(64); val nonce = r.raw(16)
                val n = r.u8(); if (n > MAX_ENTRIES) throw MalformedException("too many members")
                val entries = List(n) { Entry.read(r) }
                r.end()
                if (entries.map { it.key }.toSet().size != entries.size) throw MalformedException("duplicate member")
                val s = State(id, title, vo, co, cik, nonce, entries)
                if (!s.genesisValid()) throw MalformedException("group id doesn't match")
                return s
            }
        }
    }

    /** GROUP_INVITE body: the roster plus the inviter's voucher for the invitee. */
    class Invite(val state: State, val voucherOnionPk: ByteArray, val voucherSig: ByteArray) {
        init { require(voucherOnionPk.size == 32 && voucherSig.size == SIG_LEN) }
        fun encode(): ByteArray = ByteWriter(4096).u8(1).bytes32(state.encode()).fixed(voucherOnionPk, 32).fixed(voucherSig, SIG_LEN).toByteArray()
        companion object {
            fun decode(b: ByteArray): Invite {
                val r = ByteReader(b); if (r.u8() != 1) throw MalformedException("invite version")
                val i = Invite(State.decode(r.bytes32(1 shl 16)), r.raw(32), r.raw(SIG_LEN)); r.end(); return i
            }
        }
    }

    /**
     * Merges [remote] into [local]. An entry is accepted only if:
     *  - its own signature checks out with the identity key it carries, and its version is at most [MAX_VERSION];
     *  - it's the creator's entry with the creator's key from the group id, or it carries a valid voucher from a
     *    member already in the roster and doesn't claim to have joined before that member;
     *  - its identity key matches what we already know for that onion (a paired contact's key first, then the
     *    roster). A different key for a known onion is reported through [onConflict] and never accepted.
     * New members aren't added beyond [MAX_ENTRIES] (nothing is ever evicted, so an old "joined" can't come back).
     * Ties at the same version resolve the same way everywhere: a leave beats a join, then the smaller signature.
     * Title and verified-only come from the group id and never change.
     */
    fun merge(local: State, remote: State, verify: (identityKey: ByteArray, message: ByteArray, sig: ByteArray) -> Boolean,
              pinned: (ByteArray) -> ByteArray? = { null }, onConflict: (ByteArray) -> Unit = {}): State {
        require(Bytes.ctEquals(local.groupId, remote.groupId))
        val byKey = LinkedHashMap<String, Entry>()
        local.entries.forEach { byKey[it.key] = it }
        var pending = remote.entries.filter { e ->
            val cur = byKey[e.key]
            cur == null || e.version > cur.version || (e.version == cur.version && beats(e, cur))
        }
        while (pending.isNotEmpty()) {
            val next = mutableListOf<Entry>()
            var progress = false
            for (e in pending) {
                when (val v = check(local, e, byKey, verify, pinned)) {
                    Verdict.OK -> {
                        if (byKey[e.key] == null && byKey.size >= MAX_ENTRIES) continue
                        byKey[e.key] = e; progress = true
                    }
                    Verdict.WAIT -> next += e            // its voucher may arrive later in this same merge
                    Verdict.CONFLICT -> onConflict(e.onionPk)
                    Verdict.BAD -> Unit
                }
            }
            if (!progress) break
            pending = next
        }
        return local.withEntries(byKey.values.toList())
    }

    private enum class Verdict { OK, WAIT, CONFLICT, BAD }

    private fun beats(e: Entry, cur: Entry): Boolean =
        (cur.present && !e.present) || (cur.present == e.present && compareBytes(e.sig, cur.sig) < 0)

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) { val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff); if (d != 0) return d }
        return a.size - b.size
    }

    private fun check(g: State, e: Entry, byKey: Map<String, Entry>, verify: (ByteArray, ByteArray, ByteArray) -> Boolean,
                      pinned: (ByteArray) -> ByteArray?): Verdict {
        if (e.version > MAX_VERSION) return Verdict.BAD
        val known = pinned(e.onionPk) ?: byKey[e.key]?.identityKey
        if (known != null && !Bytes.ctEquals(known, e.identityKey)) return Verdict.CONFLICT
        if (!verify(e.identityKey, signedBytes(g.groupId, e), e.sig)) return Verdict.BAD
        if (Bytes.ctEquals(e.onionPk, g.creatorOnionPk)) {
            return if (Bytes.ctEquals(e.identityKey, g.creatorIdentityKey) && e.voucherOnionPk == null) Verdict.OK else Verdict.CONFLICT
        }
        val vo = e.voucherOnionPk ?: return Verdict.BAD
        if (Bytes.ctEquals(vo, e.onionPk)) return Verdict.BAD
        val voucher = byKey[Bytes.hex(vo)] ?: return Verdict.WAIT
        if (!verify(voucher.identityKey, voucherBytes(g.groupId, e.onionPk, e.identityKey), e.voucherSig!!)) return Verdict.BAD
        if (e.joinedAtMinute < voucher.joinedAtMinute) return Verdict.BAD
        return Verdict.OK
    }

    /** True when [b] holds something [a] doesn't. */
    fun differs(a: State, b: State): Boolean =
        a.entries.size != b.entries.size || a.entries.any { e -> b.entry(e.onionPk)?.let { it.version != e.version || it.present != e.present } ?: true }

    fun cleanTitle(raw: String): String = MessagePolicy.sanitize(raw).take(TITLE_MAX).ifEmpty { "Group" }

    /** GROUP_TEXT body. */
    class Text(val groupId: ByteArray, val text: String) {
        fun encode(): ByteArray = ByteWriter(text.length * 3 + 32).fixed(groupId, ID_LEN).str16(text).toByteArray()
        companion object {
            fun decode(b: ByteArray): Text { val r = ByteReader(b); val t = Text(r.raw(ID_LEN), r.str16(4096)); r.end(); return t }
        }
    }

    /**
     * GROUP_INTRO body: a one-time invite code from member [fromOnionPk] for member [toOnionPk], relayed
     * through members who are already paired, so two members who never met can pair. The receiver checks the
     * code's onion and identity against the signed roster before using it; the contact starts UNVERIFIED.
     */
    class Intro(val introId: ByteArray, val groupId: ByteArray, val fromOnionPk: ByteArray, val toOnionPk: ByteArray, val code: String, val hops: Int) {
        init { require(introId.size == 16 && groupId.size == ID_LEN && fromOnionPk.size == 32 && toOnionPk.size == 32 && hops in 0..MAX_HOPS) }
        fun encode(): ByteArray = ByteWriter(256).fixed(introId, 16).fixed(groupId, ID_LEN).fixed(fromOnionPk, 32)
            .fixed(toOnionPk, 32).str16(code).u8(hops).toByteArray()
        fun relayed() = Intro(introId, groupId, fromOnionPk, toOnionPk, code, hops - 1)
        companion object {
            fun decode(b: ByteArray): Intro {
                val r = ByteReader(b)
                val i = Intro(r.raw(16), r.raw(ID_LEN), r.raw(32), r.raw(32), r.str16(300), r.u8().coerceAtMost(MAX_HOPS))
                r.end(); return i
            }
        }
    }

    /** Which of two members creates the invite code: the one whose onion key sorts lower. The other waits. */
    fun iIntroduce(myOnionPk: ByteArray, theirOnionPk: ByteArray): Boolean = Bytes.hex(myOnionPk) < Bytes.hex(theirOnionPk)
}
