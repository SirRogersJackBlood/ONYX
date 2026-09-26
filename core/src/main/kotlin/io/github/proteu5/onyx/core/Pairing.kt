// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.core

import java.util.Base64

/**
 * In-person QR pairing. No phone numbers, no directory server, no discovery.
 *
 * The QR code (≈140 chars, easy to scan) carries:
 *   version | onion pubkey (32) | identity commitment SHA-256(IdentityKey) (32) | one-time secret (32) | expiry (u32)
 *
 * Flow (A scans B's code):
 *   A → B.onion : PAIR_REQUEST  { pairingId, A.identityKey, A.onionPk, A.mailbox?, mac }
 *   B → A       : PAIR_RESPONSE { B.preKeyBundle, mac }
 *   A checks SHA-256(bundle.identityKey) == QR commitment, verifies mac, builds a libsignal session
 *   and immediately sends the first (PreKey) message. Both sides derive the same linkKey.
 *
 * Security properties:
 *  - Only someone who saw the QR (knows the secret) can pair: MAC over a length-prefixed transcript.
 *  - B's identity is pinned by the QR commitment, so a malicious onion/relay cannot substitute keys.
 *  - Secret is single-use and time-limited (default 10 minutes).
 *  - Scanning in person = verified identity; safety numbers remain available for later re-checks.
 */
object Pairing {
    const val VERSION = 1
    /** Version byte for REMOTE invite links: same payload, but both sides mark the contact UNVERIFIED. */
    const val VERSION_REMOTE = 2
    const val SCHEME = "onyx1:"
    const val DEFAULT_TTL_SECONDS = 600L
    /** Remote invites live longer (they travel through other apps) but are still single-use. */
    const val REMOTE_TTL_SECONDS = 24 * 3600L
    private val SALT = "ONYX-pairing-v1".toByteArray()

    class Code(
        val onion: OnionAddress,
        val identityCommit: ByteArray,
        val secret: ByteArray,
        val expiresAtEpochSec: Long,
        /** true = shared as a link through another channel; identity is NOT verified in person. */
        val remote: Boolean = false,
    ) {
        init { require(identityCommit.size == 32 && secret.size == 32) }

        fun encode(): String {
            val raw = ByteWriter(101)
                .u8(if (remote) VERSION_REMOTE else VERSION)
                .fixed(onion.publicKey, 32)
                .fixed(identityCommit, 32)
                .fixed(secret, 32)
                .u32(expiresAtEpochSec)
                .toByteArray()
            return SCHEME + Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        }

        fun isExpired(nowEpochSec: Long) = nowEpochSec > expiresAtEpochSec

        val keys: Keys get() = Keys.derive(secret)

        companion object {
            fun create(onion: OnionAddress, identityKeyBytes: ByteArray, nowEpochSec: Long, ttl: Long = DEFAULT_TTL_SECONDS) =
                Code(onion, Bytes.sha256(identityKeyBytes), Bytes.random(32), nowEpochSec + ttl)

            /** Remote invite link: 24 h, single use, contact starts UNVERIFIED on both sides. */
            fun createRemote(onion: OnionAddress, identityKeyBytes: ByteArray, nowEpochSec: Long) =
                Code(onion, Bytes.sha256(identityKeyBytes), Bytes.random(32), nowEpochSec + REMOTE_TTL_SECONDS, remote = true)

            /** Finds an ONYX code inside arbitrary shared text (e.g. a message with a greeting around it). */
            fun extract(text: String): String? = Regex("onyx1:[A-Za-z0-9_-]{100,200}").find(text)?.value

            fun decode(text: String): Code {
                val t = text.trim()
                if (!t.startsWith(SCHEME)) throw MalformedException("not an ONYX code")
                val raw = try {
                    Base64.getUrlDecoder().decode(t.substring(SCHEME.length))
                } catch (e: IllegalArgumentException) { throw MalformedException("base64") }
                val r = ByteReader(raw)
                val version = r.u8()
                if (version != VERSION && version != VERSION_REMOTE) throw MalformedException("unsupported pairing version")
                val onion = try { OnionAddress.fromPublicKey(r.raw(32)) } catch (e: IllegalArgumentException) { throw MalformedException("onion") }
                val c = Code(onion, r.raw(32), r.raw(32), r.u32(), remote = version == VERSION_REMOTE)
                r.end()
                return c
            }
        }
    }

    /** Keys derived from the one-time secret. */
    class Keys private constructor(val pairingId: ByteArray, val macKey: ByteArray, val linkKey: ByteArray) {
        fun wipe() = Bytes.wipe(pairingId, macKey, linkKey)
        companion object {
            fun derive(secret: ByteArray) = Keys(
                Kdf.hkdf(secret, SALT, "pairing-id", 16),
                Kdf.hkdf(secret, SALT, "pairing-mac", 32),
                Kdf.hkdf(secret, SALT, "link-key", 32),
            )
        }
    }

    /** A → B. [mailbox] optional onion of A's always-on mailbox device. */
    class Request(
        val pairingId: ByteArray,
        val identityKey: ByteArray,
        val onionPk: ByteArray,
        val mailboxPk: ByteArray?,
        val mac: ByteArray,
    ) {
        fun encode(): ByteArray = ByteWriter()
            .fixed(pairingId, 16).bytes16(identityKey).fixed(onionPk, 32)
            .u8(if (mailboxPk != null) 1 else 0).apply { mailboxPk?.let { fixed(it, 32) } }
            .fixed(mac, 32).toByteArray()

        fun verify(keys: Keys): Boolean =
            Bytes.ctEquals(pairingId, keys.pairingId) &&
                Bytes.ctEquals(mac, macOf(keys, pairingId, identityKey, onionPk, mailboxPk))

        companion object {
            private fun macOf(k: Keys, id: ByteArray, ik: ByteArray, pk: ByteArray, mb: ByteArray?) =
                Kdf.hmac(k.macKey, "req".toByteArray(), Kdf.transcript(id, ik, pk, mb ?: ByteArray(0)))

            fun create(keys: Keys, identityKey: ByteArray, onionPk: ByteArray, mailboxPk: ByteArray?) =
                Request(keys.pairingId, identityKey, onionPk, mailboxPk, macOf(keys, keys.pairingId, identityKey, onionPk, mailboxPk))

            fun decode(b: ByteArray): Request {
                val r = ByteReader(b)
                val id = r.raw(16)
                val ik = r.bytes16(256)
                val pk = r.raw(32)
                val mb = when (r.u8()) { 0 -> null; 1 -> r.raw(32); else -> throw MalformedException("flag") }
                val mac = r.raw(32)
                r.end()
                return Request(id, ik, pk, mb, mac)
            }
        }
    }

    /** B → A. [bundle] is a serialized [PreKeyBundleWire]. */
    class Response(val bundle: ByteArray, val mailboxPk: ByteArray?, val mac: ByteArray) {
        fun encode(): ByteArray = ByteWriter()
            .bytes32(bundle).u8(if (mailboxPk != null) 1 else 0).apply { mailboxPk?.let { fixed(it, 32) } }
            .fixed(mac, 32).toByteArray()

        /** [request] binds the response to the exact request that was sent. */
        fun verify(keys: Keys, request: Request): Boolean =
            Bytes.ctEquals(mac, macOf(keys, request, bundle, mailboxPk))

        companion object {
            private fun macOf(k: Keys, req: Request, bundle: ByteArray, mb: ByteArray?) =
                Kdf.hmac(k.macKey, "resp".toByteArray(), Kdf.transcript(req.encode(), bundle, mb ?: ByteArray(0)))

            fun create(keys: Keys, request: Request, bundle: ByteArray, mailboxPk: ByteArray?) =
                Response(bundle, mailboxPk, macOf(keys, request, bundle, mailboxPk))

            fun decode(b: ByteArray): Response {
                val r = ByteReader(b)
                val bundle = r.bytes32(16_384)
                val mb = when (r.u8()) { 0 -> null; 1 -> r.raw(32); else -> throw MalformedException("flag") }
                val mac = r.raw(32)
                r.end()
                return Response(bundle, mb, mac)
            }
        }
    }
}

/**
 * Library-neutral wire form of a libsignal PreKeyBundle (PQXDH: EC + Kyber prekeys).
 * The Android module maps this to org.signal.libsignal.protocol.state.PreKeyBundle.
 */
class PreKeyBundleWire(
    val registrationId: Int,
    val deviceId: Int,
    val preKeyId: Int,          // -1 when no one-time EC prekey is included
    val preKey: ByteArray?,
    val signedPreKeyId: Int,
    val signedPreKey: ByteArray,
    val signedPreKeySignature: ByteArray,
    val identityKey: ByteArray,
    val kyberPreKeyId: Int,
    val kyberPreKey: ByteArray,
    val kyberPreKeySignature: ByteArray,
) {
    fun encode(): ByteArray = ByteWriter(2048)
        .u8(1)
        .u32(registrationId.toLong() and 0xffffffffL).u32(deviceId.toLong() and 0xffffffffL)
        .u32(preKeyId.toLong() and 0xffffffffL).bytes16(preKey ?: ByteArray(0))
        .u32(signedPreKeyId.toLong() and 0xffffffffL).bytes16(signedPreKey).bytes16(signedPreKeySignature)
        .bytes16(identityKey)
        .u32(kyberPreKeyId.toLong() and 0xffffffffL).bytes16(kyberPreKey).bytes16(kyberPreKeySignature)
        .toByteArray()

    companion object {
        fun decode(b: ByteArray): PreKeyBundleWire {
            val r = ByteReader(b)
            if (r.u8() != 1) throw MalformedException("bundle version")
            val reg = r.u32().toInt(); val dev = r.u32().toInt()
            val pkId = r.u32().toInt(); val pk = r.bytes16(64)
            val spkId = r.u32().toInt(); val spk = r.bytes16(64); val spkSig = r.bytes16(128)
            val ik = r.bytes16(64)
            val kId = r.u32().toInt(); val k = r.bytes16(2048); val kSig = r.bytes16(128)
            r.end()
            return PreKeyBundleWire(reg, dev, pkId, pk.takeIf { it.isNotEmpty() }, spkId, spk, spkSig, ik, kId, k, kSig)
        }
    }
}
