// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.signal

import io.github.proteu5.onyx.core.ByteReader
import io.github.proteu5.onyx.core.ByteWriter
import io.github.proteu5.onyx.core.Bytes
import io.github.proteu5.onyx.core.Envelope
import io.github.proteu5.onyx.core.OnionAddress
import io.github.proteu5.onyx.core.PreKeyBundleWire
import io.github.proteu5.onyx.data.Contact
import io.github.proteu5.onyx.data.SecureStore
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import java.security.SecureRandom

/**
 * libsignal persistence, every record encrypted by [SecureStore].
 *
 * Trust policy is STRICT: an identity is trusted only if it equals the key pinned at QR pairing.
 * A changed identity key blocks the conversation until the user re-pairs in person.
 */
class OnyxSignalStore(private val store: SecureStore) :
    IdentityKeyStore, PreKeyStore, SignedPreKeyStore, KyberPreKeyStore, SessionStore {

    fun hasIdentity() = store.get(NS_META, "identity") != null

    fun createIdentity() {
        check(!hasIdentity())
        val pair = IdentityKeyPair.generate()
        val reg = 1 + SecureRandom().nextInt(16380)
        store.put(NS_META, "identity", pair.serialize())
        store.put(NS_META, "registration", ByteWriter().u32(reg.toLong()).toByteArray())
    }

    // ---- IdentityKeyStore ----
    override fun getIdentityKeyPair(): IdentityKeyPair = IdentityKeyPair(store.get(NS_META, "identity")!!)
    override fun getLocalRegistrationId(): Int = ByteReader(store.get(NS_META, "registration")!!).u32().toInt()

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val prev = getIdentity(address)
        store.put(NS_IDENT, address.name, identityKey.serialize())
        return if (prev == null || prev == identityKey) IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        else IdentityKeyStore.IdentityChange.REPLACED_EXISTING
    }

    override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction): Boolean {
        val pinned = getIdentity(address) ?: return false
        return pinned == identityKey
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        store.get(NS_IDENT, address.name)?.let { IdentityKey(it) }

    /** Pin an identity at pairing time (before any session exists). */
    fun pin(name: String, identityKey: ByteArray) = store.put(NS_IDENT, name, IdentityKey(identityKey).serialize())

    // ---- PreKeyStore (one-time EC prekeys) ----
    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        store.get(NS_PRE, preKeyId.toString())?.let { PreKeyRecord(it) } ?: throw InvalidKeyIdException("no prekey")
    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) = store.put(NS_PRE, preKeyId.toString(), record.serialize())
    override fun containsPreKey(preKeyId: Int) = store.get(NS_PRE, preKeyId.toString()) != null
    override fun removePreKey(preKeyId: Int) = store.delete(NS_PRE, preKeyId.toString())

    // ---- SignedPreKeyStore ----
    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        store.get(NS_SPK, signedPreKeyId.toString())?.let { SignedPreKeyRecord(it) } ?: throw InvalidKeyIdException("no signed prekey")
    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> = store.list(NS_SPK).map { SignedPreKeyRecord(it.second) }
    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) =
        store.put(NS_SPK, signedPreKeyId.toString(), record.serialize())
    override fun containsSignedPreKey(signedPreKeyId: Int) = store.get(NS_SPK, signedPreKeyId.toString()) != null
    override fun removeSignedPreKey(signedPreKeyId: Int) = store.delete(NS_SPK, signedPreKeyId.toString())

    // ---- KyberPreKeyStore (ONE-TIME Kyber prekeys: deleted on use) ----
    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        store.get(NS_KPK, kyberPreKeyId.toString())?.let { KyberPreKeyRecord(it) } ?: throw InvalidKeyIdException("no kyber prekey")
    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> = store.list(NS_KPK).map { KyberPreKeyRecord(it.second) }
    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) =
        store.put(NS_KPK, kyberPreKeyId.toString(), record.serialize())
    override fun containsKyberPreKey(kyberPreKeyId: Int) = store.get(NS_KPK, kyberPreKeyId.toString()) != null
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) =
        store.delete(NS_KPK, kyberPreKeyId.toString())

    // ---- SessionStore ----
    private fun sk(a: SignalProtocolAddress) = "${a.name}.${a.deviceId}"
    override fun loadSession(address: SignalProtocolAddress): SessionRecord =
        store.get(NS_SESS, sk(address))?.let { SessionRecord(it) } ?: SessionRecord()
    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.map { a -> store.get(NS_SESS, sk(a))?.let { SessionRecord(it) } ?: throw NoSessionException("no session") }
    override fun getSubDeviceSessions(name: String): List<Int> = emptyList()
    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) = store.put(NS_SESS, sk(address), record.serialize())
    override fun containsSession(address: SignalProtocolAddress) = store.get(NS_SESS, sk(address)) != null
    override fun deleteSession(address: SignalProtocolAddress) = store.delete(NS_SESS, sk(address))
    override fun deleteAllSessions(name: String) = store.delete(NS_SESS, "$name.1")

    fun forget(name: String) {
        deleteAllSessions(name)
        store.delete(NS_IDENT, name)
    }

    companion object {
        private const val NS_META = "sig-meta"
        private const val NS_IDENT = "sig-ident"
        private const val NS_PRE = "sig-pre"
        private const val NS_SPK = "sig-spk"
        private const val NS_KPK = "sig-kpk"
        private const val NS_SESS = "sig-sess"
    }
}

/**
 * All Signal Protocol operations. Serialized on one lock: libsignal session state must never be
 * advanced concurrently for the same peer.
 *
 * Key agreement: PQXDH (X25519 + ML-KEM/Kyber-1024 one-time prekey).
 * Messaging:     Double Ratchet + SPQR (ML-KEM-768 braid) = Signal's "Triple Ratchet",
 *                negotiated automatically by libsignal ≥ 0.85.
 */
class CryptoEngine(private val sig: OnyxSignalStore) {
    private val lock = Any()
    private val rng = SecureRandom()

    fun ensureIdentity() = synchronized(lock) { if (!sig.hasIdentity()) sig.createIdentity() }

    fun identityKeyBytes(): ByteArray = sig.identityKeyPair.publicKey.serialize()

    private fun address(c: Contact) = SignalProtocolAddress(c.signalName, DEVICE_ID)
    private fun address(name: String) = SignalProtocolAddress(name, DEVICE_ID)

    /**
     * Fresh bundle for ONE pairing: one-time EC prekey, fresh signed prekey, one-time Kyber prekey.
     * Nothing is ever published; the bundle goes only to the person who scanned our QR.
     */
    fun newPairingBundle(): PreKeyBundleWire = synchronized(lock) {
        val identity = sig.identityKeyPair
        val now = System.currentTimeMillis()

        val preId = 1 + rng.nextInt(0xFFFFFE)
        val pre = PreKeyRecord(preId, ECKeyPair.generate())
        sig.storePreKey(preId, pre)

        val spkId = 1 + rng.nextInt(0xFFFFFE)
        val spkPair = ECKeyPair.generate()
        val spkSig = identity.privateKey.calculateSignature(spkPair.publicKey.serialize())
        sig.storeSignedPreKey(spkId, SignedPreKeyRecord(spkId, now, spkPair, spkSig))

        val kId = 1 + rng.nextInt(0xFFFFFE)
        val kPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kSig = identity.privateKey.calculateSignature(kPair.publicKey.serialize())
        sig.storeKyberPreKey(kId, KyberPreKeyRecord(kId, now, kPair, kSig))

        PreKeyBundleWire(
            sig.localRegistrationId, DEVICE_ID,
            preId, pre.keyPair.publicKey.serialize(),
            spkId, spkPair.publicKey.serialize(), spkSig,
            identity.publicKey.serialize(),
            kId, kPair.publicKey.serialize(), kSig,
        )
    }

    /** Scanner side: pin the peer's identity and build an outgoing PQXDH session from their bundle. */
    fun startSession(name: String, w: PreKeyBundleWire) = synchronized(lock) {
        sig.pin(name, w.identityKey)
        val bundle = PreKeyBundle(
            w.registrationId, w.deviceId,
            w.preKeyId, w.preKey?.let { ECPublicKey(it) },
            w.signedPreKeyId, ECPublicKey(w.signedPreKey), w.signedPreKeySignature,
            IdentityKey(w.identityKey),
            w.kyberPreKeyId, KEMPublicKey(w.kyberPreKey), w.kyberPreKeySignature,
        )
        SessionBuilder(sig, sig, sig, sig, address(name)).process(bundle)
    }

    /** Displayed side: pin the scanner's identity; their first PreKey message completes the session. */
    fun pinPeer(name: String, identityKey: ByteArray) = synchronized(lock) { sig.pin(name, identityKey) }

    fun hasSession(c: Contact) = synchronized(lock) { sig.containsSession(address(c)) }

    /** Returns (signalType, ciphertext). Envelope is bucket-padded before encryption. */
    fun encrypt(c: Contact, e: Envelope): Pair<Int, ByteArray> = synchronized(lock) {
        val padded = e.encodePadded()
        try {
            val msg: CiphertextMessage = SessionCipher(sig, sig, sig, sig, sig, address(c)).encrypt(padded)
            msg.type to msg.serialize()
        } finally { Bytes.wipe(padded) }
    }

    fun decrypt(c: Contact, signalType: Int, ciphertext: ByteArray): Envelope = synchronized(lock) {
        val cipher = SessionCipher(sig, sig, sig, sig, sig, address(c))
        val padded = if (signalType == CiphertextMessage.PREKEY_TYPE) cipher.decrypt(PreKeySignalMessage(ciphertext))
                     else cipher.decrypt(SignalMessage(ciphertext))
        try { Envelope.decodePadded(padded) } finally { Bytes.wipe(padded) }
    }

    /** 60-digit safety number. Stable identifiers are the two onion public keys. */
    fun safetyNumber(me: OnionAddress, c: Contact): String = synchronized(lock) {
        NumericFingerprintGenerator(5200).createFor(
            2, me.publicKey, sig.identityKeyPair.publicKey, c.onion.publicKey, IdentityKey(c.identityKey)
        ).displayableFingerprint.displayText
    }

    fun forget(c: Contact) = synchronized(lock) { sig.forget(c.signalName) }

    companion object { const val DEVICE_ID = 1 }
}
