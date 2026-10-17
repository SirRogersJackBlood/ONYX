// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.vault

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import io.github.proteu5.onyx.core.Bytes
import io.github.proteu5.onyx.core.Kdf
import java.io.File
import java.security.ProviderException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Where ONYX keeps the root of all local secrets.
 *
 * This interface is the seam for the hypervisor work: today [KeystoreVault] (Android Keystore,
 * StrongBox/Knox Vault when present); later a Microdroid/pVM-backed implementation can replace it
 * without touching the rest of the app.
 */
interface KeyVault {
    /** 256-bit data-encryption key for the local store. Throws if the device has never been unlocked since boot. */
    fun dataKey(): SecretKey
    /** Separate key for hashing index columns so row keys never appear in plaintext. */
    fun indexKey(): ByteArray
    /** Drop cached key material from memory (panic lock / app lock). */
    fun lock()
    /** Human-readable backend, e.g. "StrongBox", "TEE". */
    fun backend(): String
    /** Irreversibly destroy the master key: every local byte becomes unreadable. */
    fun destroy()
    /** True when the phone has no screen lock (PIN, pattern or password). */
    fun lockMissing(): Boolean = false
    /** True when the master key is tied to the screen lock (unusable until first unlock after boot). */
    fun lockBound(): Boolean = false
    /** True when the vault file exists but Android has erased its master key: the data can never be decrypted. */
    fun isLost(): Boolean = false
}

class KeystoreVault(context: Context) : KeyVault {
    private val dir = File(context.noBackupFilesDir, "vault").apply { mkdirs() }
    private val wrappedFile = File(dir, "dek.bin")
    /** "bound" or "unbound". Absent on v1.2.x installs, whose master key is always lock-bound. */
    private val stateFile = File(dir, "master.state")
    private val keyguard: KeyguardManager? = context.getSystemService(KeyguardManager::class.java)
    private val ks: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    @Volatile private var cachedDek: SecretKey? = null
    @Volatile private var cachedIndex: ByteArray? = null

    private class Blob(val alias: String, val iv: ByteArray, val ct: ByteArray)

    private fun deviceSecure(): Boolean = keyguard?.isDeviceSecure == true

    /**
     * Tie the master key to the screen lock only where that is safe. Android 12-14 (API 31-34)
     * permanently erase lock-bound keys when the screen lock is removed, which would destroy every
     * message, so there the key stays in StrongBox/TEE but is not lock-bound.
     */
    private fun shouldBind(): Boolean = deviceSecure() && Build.VERSION.SDK_INT !in 31..34

    private fun storedBound(): Boolean = !stateFile.exists() || stateFile.readText().trim() == "bound"
    private fun setBound(bound: Boolean) { dir.mkdirs(); stateFile.writeText(if (bound) "bound" else "unbound") }

    /** Master key: StrongBox when present, else TEE. */
    private fun newMaster(alias: String, lockBound: Boolean): SecretKey {
        runCatching { ks.deleteEntry(alias) }
        return try {
            generate(alias, strongBox = true, lockBound = lockBound)
        } catch (e: ProviderException) {   // includes StrongBoxUnavailableException
            generate(alias, strongBox = false, lockBound = lockBound)
        }
    }

    private fun generate(alias: String, strongBox: Boolean, lockBound: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUnlockedDeviceRequired(lockBound)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec); generateKey()
        }
    }

    @Synchronized
    override fun dataKey(): SecretKey {
        cachedDek?.let { return it }
        val raw = if (wrappedFile.exists()) unwrap() else createAndWrap()
        try {
            rebindIfNeeded(raw)
            val dek = SecretKeySpec(raw, "AES")
            cachedIndex = Kdf.hkdf(raw, "ONYX-vault-v1".toByteArray(), "index", 32)
            cachedDek = dek
            return dek
        } finally {
            Bytes.wipe(raw)
        }
    }

    override fun indexKey(): ByteArray {
        if (cachedIndex == null) dataKey()
        return cachedIndex!!
    }

    private fun createAndWrap(): ByteArray {
        val raw = Bytes.random(32)
        var bound = shouldBind()
        val key = try {
            newMaster(ALIAS, bound)
        } catch (e: ProviderException) {
            if (!bound) throw e
            bound = false                 // keystore refused a lock-bound key: keep working unbound
            newMaster(ALIAS, false)
        }
        setBound(bound)
        writeBlob(ALIAS, key, raw)
        return raw
    }

    /**
     * Screen lock added or removed, or a v1.2.x lock-bound key on Android 12-14: re-wrap the data key
     * under a new master with the right binding. Atomic: the blob names its own master key, and the
     * old key is deleted only after the new blob is in place.
     */
    private fun rebindIfNeeded(raw: ByteArray) {
        val want = shouldBind()
        if (want == storedBound()) return
        val old = readBlob().alias
        val next = if (old == ALIAS) ALIAS_B else ALIAS
        val key = runCatching { newMaster(next, want) }.getOrNull() ?: return
        writeBlob(next, key, raw)
        setBound(want)
        runCatching { ks.deleteEntry(old) }
    }

    /** dek.bin = "OX" 0x02 | aliasLen | alias | ivLen | iv | AES-GCM(dek). Legacy files are ivLen | iv | ct under [ALIAS]. */
    private fun writeBlob(alias: String, key: SecretKey, raw: ByteArray) {
        val c = Cipher.getInstance(GCM)
        c.init(Cipher.ENCRYPT_MODE, key)
        val ct = c.doFinal(raw)
        val a = alias.toByteArray(Charsets.US_ASCII)
        dir.mkdirs()
        val tmp = File(dir, "dek.tmp")
        tmp.writeBytes(byteArrayOf('O'.code.toByte(), 'X'.code.toByte(), 2, a.size.toByte()) + a +
            byteArrayOf(c.iv.size.toByte()) + c.iv + ct)
        if (!tmp.renameTo(wrappedFile)) throw IllegalStateException("vault write failed")
    }

    private fun readBlob(): Blob {
        val b = wrappedFile.readBytes()
        var p = 0
        val alias = if (b.size > 4 && b[0] == 'O'.code.toByte() && b[1] == 'X'.code.toByte() && b[2].toInt() == 2) {
            val n = b[3].toInt()
            p = 4 + n
            String(b, 4, n, Charsets.US_ASCII)
        } else ALIAS
        val ivLen = b[p].toInt()
        return Blob(alias, b.copyOfRange(p + 1, p + 1 + ivLen), b.copyOfRange(p + 1 + ivLen, b.size))
    }

    private fun unwrap(): ByteArray {
        val blob = readBlob()
        val key = ks.getKey(blob.alias, null) as? SecretKey
            ?: throw IllegalStateException("vault master key missing")
        val c = Cipher.getInstance(GCM)
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.iv))
        return c.doFinal(blob.ct)
    }

    @Synchronized
    override fun lock() {
        cachedDek = null
        cachedIndex?.let { Bytes.wipe(it) }
        cachedIndex = null
    }

    override fun lockMissing(): Boolean = !deviceSecure()

    override fun lockBound(): Boolean = wrappedFile.exists() && storedBound()

    override fun isLost(): Boolean = runCatching {
        wrappedFile.exists() && !ks.containsAlias(readBlob().alias)
    }.getOrDefault(false)

    override fun backend(): String {
        val key = try {
            if (wrappedFile.exists()) ks.getKey(readBlob().alias, null) as? SecretKey else null
        } catch (e: Exception) { null } ?: return "Not created yet"
        return describe(key)
    }

    private fun describe(key: SecretKey): String = try {
        val info = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo
        if (Build.VERSION.SDK_INT >= 31) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox (secure element)"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE (hardware)"
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "Software"
                else -> "Hardware"
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) "Hardware" else "Software"
        }
    } catch (e: Exception) { "Unavailable until unlock" }

    @Synchronized
    override fun destroy() {
        lock()
        runCatching { ks.deleteEntry(ALIAS) }
        runCatching { ks.deleteEntry(ALIAS_B) }
        if (wrappedFile.exists()) {
            runCatching { wrappedFile.writeBytes(Bytes.random(wrappedFile.length().toInt())) }
            wrappedFile.delete()
        }
        stateFile.delete()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "onyx_master_v1"
        private const val ALIAS_B = "onyx_master_v1b"
        private const val GCM = "AES/GCM/NoPadding"
    }
}
