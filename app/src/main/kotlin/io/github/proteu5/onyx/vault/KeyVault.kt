// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.vault

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import io.github.proteu5.onyx.core.Bytes
import io.github.proteu5.onyx.core.Kdf
import java.io.File
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
}

class KeystoreVault(context: Context) : KeyVault {
    private val dir = File(context.noBackupFilesDir, "vault").apply { mkdirs() }
    private val wrappedFile = File(dir, "dek.bin")
    private val ks: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    @Volatile private var cachedDek: SecretKey? = null
    @Volatile private var cachedIndex: ByteArray? = null

    private fun masterKey(): SecretKey {
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return try {
            generateMaster(strongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            generateMaster(strongBox = false)
        }
    }

    private fun generateMaster(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            // Unusable until the user has unlocked the phone at least once since boot.
            .setUnlockedDeviceRequired(true)
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
        val c = Cipher.getInstance(GCM)
        c.init(Cipher.ENCRYPT_MODE, masterKey())
        val ct = c.doFinal(raw)
        val tmp = File(dir, "dek.tmp")
        tmp.writeBytes(byteArrayOf(c.iv.size.toByte()) + c.iv + ct)
        if (!tmp.renameTo(wrappedFile)) throw IllegalStateException("vault write failed")
        return raw
    }

    private fun unwrap(): ByteArray {
        val blob = wrappedFile.readBytes()
        val ivLen = blob[0].toInt()
        val c = Cipher.getInstance(GCM)
        c.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, blob, 1, ivLen))
        return c.doFinal(blob, 1 + ivLen, blob.size - 1 - ivLen)
    }

    @Synchronized
    override fun lock() {
        cachedDek = null
        cachedIndex?.let { Bytes.wipe(it) }
        cachedIndex = null
    }

    override fun backend(): String = try {
        val key = masterKey()
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
        if (wrappedFile.exists()) {
            runCatching { wrappedFile.writeBytes(Bytes.random(wrappedFile.length().toInt())) }
            wrappedFile.delete()
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "onyx_master_v1"
        private const val GCM = "AES/GCM/NoPadding"
    }
}
