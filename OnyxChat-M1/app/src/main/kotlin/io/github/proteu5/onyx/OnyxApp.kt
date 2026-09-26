// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import io.github.proteu5.onyx.core.ByteReader
import io.github.proteu5.onyx.core.ByteWriter
import io.github.proteu5.onyx.data.ContactRepo
import io.github.proteu5.onyx.data.MessageRepo
import io.github.proteu5.onyx.data.OutboxRepo
import io.github.proteu5.onyx.data.SecureStore
import io.github.proteu5.onyx.net.Messenger
import io.github.proteu5.onyx.service.OnyxService
import io.github.proteu5.onyx.signal.CryptoEngine
import io.github.proteu5.onyx.signal.OnyxSignalStore
import io.github.proteu5.onyx.snowflake.SnowflakeController
import io.github.proteu5.onyx.tor.TorController
import io.github.proteu5.onyx.vault.KeyVault
import io.github.proteu5.onyx.vault.KeystoreVault

/** Manual dependency graph. No DI framework, no analytics, no crash reporter. */
class OnyxApp : Application() {

    lateinit var vault: KeyVault; private set
    val store by lazy { SecureStore(this, vault) }
    val contacts by lazy { ContactRepo(store) }
    val messages by lazy { MessageRepo(store) }
    val outbox by lazy { OutboxRepo(store) }
    val crypto by lazy { CryptoEngine(OnyxSignalStore(store)) }
    val tor by lazy { TorController(this, store) }
    val snowflake by lazy { SnowflakeController(this, store) }
    val messenger by lazy {
        Messenger(crypto, tor, contacts, messages, outbox,
            badgeProvider = { snowflake.badge() },
            notifyIncoming = { OnyxService.notifyNewMessage(this) })
    }

    // ---- app lock ----
    @Volatile var unlocked = false
    private var started = 0
    private var backgroundedAt = 0L

    var appLockEnabled: Boolean
        get() = store.get("prefs", "applock")?.let { ByteReader(it).u8() == 1 } ?: false
        set(v) = store.put("prefs", "applock", ByteWriter().u8(if (v) 1 else 0).toByteArray())

    override fun onCreate() {
        super.onCreate()
        instance = this
        vault = KeystoreVault(this)
        getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(NotificationChannel(CH_SERVICE, "Connection status", NotificationManager.IMPORTANCE_MIN).apply {
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET; setShowBadge(false)
            })
            createNotificationChannel(NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            })
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { started++ }
            override fun onActivityStopped(a: Activity) {
                started--
                if (started == 0) backgroundedAt = System.currentTimeMillis()
            }
            override fun onActivityResumed(a: Activity) {
                if (backgroundedAt != 0L && System.currentTimeMillis() - backgroundedAt > LOCK_AFTER_MS) unlocked = false
                backgroundedAt = 0L
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        })
    }

    /**
     * Panic wipe: stop everything, destroy the Keystore master key (making every encrypted
     * byte unrecoverable), then delete the database and Tor state.
     */
    fun panicWipe() {
        runCatching { snowflake.shutdown() }
        runCatching { messenger.stop() }
        runCatching { tor.stop() }
        vault.destroy()
        store.wipeAll(this)
        filesDir.deleteRecursively()
        cacheDir.deleteRecursively()
        noBackupFilesDir.deleteRecursively()
    }

    companion object {
        lateinit var instance: OnyxApp; private set
        const val CH_SERVICE = "onyx.service"
        const val CH_MESSAGES = "onyx.messages"
        private const val LOCK_AFTER_MS = 30_000L
    }
}
