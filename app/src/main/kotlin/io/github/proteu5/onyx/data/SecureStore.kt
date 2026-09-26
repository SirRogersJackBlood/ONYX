// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.proteu5.onyx.core.ByteReader
import io.github.proteu5.onyx.core.ByteWriter
import io.github.proteu5.onyx.core.Bytes
import io.github.proteu5.onyx.core.Kdf
import io.github.proteu5.onyx.vault.KeyVault
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted record store on the platform SQLite (no SQLCipher dependency, no AndroidX).
 *
 * Schema: rec(ns BLOB, k BLOB, ord INTEGER, v BLOB)
 *  - ns / k are HMAC(indexKey, …) truncated to 16 bytes: table names, contact ids, message ids
 *    never appear in the file.
 *  - ord is a local monotonic counter (NOT a timestamp) used only for ordering.
 *  - v  is AES-256-GCM(dataKey, iv, aad = ns‖k) over (originalKey ‖ value).
 *  - PRAGMA secure_delete overwrites freed pages, so deleted/disappeared messages don't linger.
 */
class SecureStore(context: Context, private val vault: KeyVault) :
    SQLiteOpenHelper(context, "onyx.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA secure_delete = ON", null).close()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE rec (ns BLOB NOT NULL, k BLOB NOT NULL, ord INTEGER NOT NULL, v BLOB NOT NULL, PRIMARY KEY (ns, k))")
        db.execSQL("CREATE INDEX rec_ns_ord ON rec (ns, ord)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private fun nsHash(ns: String) = Kdf.hmac(vault.indexKey(), "ns".toByteArray(), ns.toByteArray()).copyOf(16)
    private fun keyHash(ns: String, key: String) =
        Kdf.hmac(vault.indexKey(), "k".toByteArray(), Kdf.transcript(ns.toByteArray(), key.toByteArray())).copyOf(16)

    private fun seal(nsH: ByteArray, kH: ByteArray, key: String, value: ByteArray): ByteArray {
        val iv = Bytes.random(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, vault.dataKey(), GCMParameterSpec(128, iv))
        c.updateAAD(nsH); c.updateAAD(kH)
        val pt = ByteWriter(value.size + 64).str16(key).bytes32(value).toByteArray()
        try { return iv + c.doFinal(pt) } finally { Bytes.wipe(pt) }
    }

    private fun open(nsH: ByteArray, kH: ByteArray, blob: ByteArray): Pair<String, ByteArray> {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, vault.dataKey(), GCMParameterSpec(128, blob, 0, 12))
        c.updateAAD(nsH); c.updateAAD(kH)
        val pt = c.doFinal(blob, 12, blob.size - 12)
        val r = ByteReader(pt)
        return r.str16() to r.bytes32(Int.MAX_VALUE).also { Bytes.wipe(pt) }
    }

    @Synchronized
    fun nextOrd(): Long {
        val cur = get("meta", "ord")?.let { ByteReader(it).u64() } ?: 0L
        val next = cur + 1
        put("meta", "ord", ByteWriter().u64(next).toByteArray(), 0)
        return next
    }

    @Synchronized
    fun put(ns: String, key: String, value: ByteArray, ord: Long = 0) {
        val nsH = nsHash(ns); val kH = keyHash(ns, key)
        val cv = ContentValues().apply {
            put("ns", nsH); put("k", kH); put("ord", ord); put("v", seal(nsH, kH, key, value))
        }
        writableDatabase.insertWithOnConflict("rec", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Replace the value of an existing row, keeping its order. Inserts (with a new ord) if absent. */
    @Synchronized
    fun upsertKeepOrder(ns: String, key: String, value: ByteArray) {
        val nsH = nsHash(ns); val kH = keyHash(ns, key)
        val st = writableDatabase.compileStatement("UPDATE rec SET v = ? WHERE ns = ? AND k = ?")
        st.bindBlob(1, seal(nsH, kH, key, value)); st.bindBlob(2, nsH); st.bindBlob(3, kH)
        val n = st.executeUpdateDelete(); st.close()
        if (n == 0) put(ns, key, value, nextOrd())
    }

    @Synchronized
    fun get(ns: String, key: String): ByteArray? {
        val nsH = nsHash(ns); val kH = keyHash(ns, key)
        // Hashes are hex-encoded by us (only [0-9a-f]), so literal blob syntax is injection-safe.
        readableDatabase.rawQuery(
            "SELECT v FROM rec WHERE ns = x'${Bytes.hex(nsH)}' AND k = x'${Bytes.hex(kH)}'", null
        ).use { c ->
            if (!c.moveToFirst()) return null
            return open(nsH, kH, c.getBlob(0)).second
        }
    }

    @Synchronized
    fun delete(ns: String, key: String) {
        val st = writableDatabase.compileStatement("DELETE FROM rec WHERE ns = ? AND k = ?")
        st.bindBlob(1, nsHash(ns)); st.bindBlob(2, keyHash(ns, key))
        st.executeUpdateDelete(); st.close()
    }

    @Synchronized
    fun deleteNamespace(ns: String) {
        val st = writableDatabase.compileStatement("DELETE FROM rec WHERE ns = ?")
        st.bindBlob(1, nsHash(ns)); st.executeUpdateDelete(); st.close()
    }

    /** All (key, value) pairs in [ns], ordered by [ord]. */
    @Synchronized
    fun list(ns: String): List<Pair<String, ByteArray>> {
        val nsH = nsHash(ns)
        val hex = Bytes.hex(nsH)
        val out = ArrayList<Pair<String, ByteArray>>()
        readableDatabase.rawQuery("SELECT k, v FROM rec WHERE ns = x'$hex' ORDER BY ord ASC", null).use { c ->
            while (c.moveToNext()) out += open(nsH, c.getBlob(0), c.getBlob(1))
        }
        return out
    }

    /** Wipes the database file contents and the file itself. */
    fun wipeAll(context: Context) {
        close()
        context.deleteDatabase("onyx.db")
    }
}
