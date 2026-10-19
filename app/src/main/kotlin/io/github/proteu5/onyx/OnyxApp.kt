// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import io.github.proteu5.onyx.core.ByteReader
import io.github.proteu5.onyx.core.ByteWriter
import io.github.proteu5.onyx.data.ContactMetaRepo
import io.github.proteu5.onyx.data.ContactRepo
import io.github.proteu5.onyx.data.GroupRepo
import io.github.proteu5.onyx.data.InviteRepo
import io.github.proteu5.onyx.data.MailboxStore
import io.github.proteu5.onyx.data.MessageRepo
import io.github.proteu5.onyx.data.OutboxRepo
import io.github.proteu5.onyx.data.SecureStore
import io.github.proteu5.onyx.core.Frame
import io.github.proteu5.onyx.data.Contact
import io.github.proteu5.onyx.net.MailboxHost
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
    val contactMeta by lazy { ContactMetaRepo(store) }
    val contacts by lazy { ContactRepo(store, contactMeta) }
    val messages by lazy { MessageRepo(store, contactMeta) }
    val outbox by lazy { OutboxRepo(store) }
    val groups by lazy { GroupRepo(store, contactMeta) }
    val crypto by lazy { CryptoEngine(OnyxSignalStore(store)) }
    val tor by lazy { TorController(this, store) }
    val snowflake by lazy { SnowflakeController(this, store) }
    val mailboxStore by lazy { MailboxStore(store) }

    /** This phone's side of mailbox mode. Only active when [mailboxMode] is on. */
    private val mailboxHost = object : MailboxHost {
        override val enabled: Boolean get() = mailboxMode
        // A mailbox pairs with exactly one phone, its owner, and refuses every pairing after that.
        override fun isOwner(c: Contact) = contacts.all().singleOrNull()?.id == c.id
        override fun depositors() = mailboxStore.depositors()
        override fun setDepositors(entries: List<Pair<ByteArray, ByteArray>>) = mailboxStore.setDepositors(entries)
        override fun deposit(tag: ByteArray, m: Frame.Message) = mailboxStore.deposit(tag, m)
        override fun keys() = mailboxStore.keys()
        override fun item(digestHex: String) = mailboxStore.item(digestHex)?.let { Frame.MailboxItem(it.tag, it.message) }
        override fun remove(digestHex: String) { mailboxStore.remove(digestHex) }
        override fun sweep() { mailboxStore.sweep() }
    }

    val messenger by lazy {
        Messenger(crypto, tor, contacts, messages, outbox, InviteRepo(store),
            badgeProvider = { snowflake.badge() },
            notifyIncoming = { OnyxService.notifyNewMessage(this) },
            mailbox = mailboxHost, groups = groups).apply {
            isBlocked = { id -> contactMeta.isBlocked(id) }
            imageSanitizer = { jpeg -> io.github.proteu5.onyx.media.ImageSandbox.sanitize(this@OnyxApp, jpeg) }
            myMailboxId = { store.get("prefs", "my-mailbox")?.let { String(it, Charsets.UTF_8) }?.takeIf { it.isNotEmpty() } }
            saveMyMailboxId = { id -> store.put("prefs", "my-mailbox", (id ?: "").toByteArray(Charsets.UTF_8)) }
        }
    }

    /**
     * Mailbox mode (v1.4.0): this phone only holds encrypted messages for its owner. Can be turned on
     * only while there are no contacts; turning it off wipes everything it holds.
     */
    var mailboxMode: Boolean
        get() = store.get("prefs", "mailbox-mode")?.let { ByteReader(it).u8() == 1 } ?: false
        set(v) {
            if (v) check(contacts.all().isEmpty()) { "Mailbox mode needs a phone with no contacts." }
            else mailboxStore.wipe()
            store.put("prefs", "mailbox-mode", ByteWriter().u8(if (v) 1 else 0).toByteArray())
        }

    // ---- app lock ----
    @Volatile var unlocked = false
    private var started = 0
    private var backgroundedAt = 0L

    var appLockEnabled: Boolean
        get() = store.get("prefs", "applock")?.let { ByteReader(it).u8() == 1 } ?: false
        set(v) = store.put("prefs", "applock", ByteWriter().u8(if (v) 1 else 0).toByteArray())

    /** Fingerprint of the accessibility services the user said they trust (warning hidden until that set changes). */
    var a11yAcknowledged: String
        get() = store.get("prefs", "a11y-ack")?.let { String(it, Charsets.UTF_8) } ?: ""
        set(v) = store.put("prefs", "a11y-ack", v.toByteArray(Charsets.UTF_8))

    override fun onCreate() {
        super.onCreate()
        // The image sandbox runs in an isolated process that also creates this Application. It must do NOTHING
        // there: no vault, no store, no Tor. (It couldn't reach them anyway; this keeps it from trying.)
        if (android.os.Process.isIsolated()) return
        instance = this
        vault = KeystoreVault(this)
        if (vault.isLost()) resetAfterKeyLoss()
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
        io.github.proteu5.onyx.net.WireTap.clearAll()
        vault.destroy()
        store.wipeAll(this)
        filesDir.deleteRecursively()
        cacheDir.deleteRecursively()
        noBackupFilesDir.deleteRecursively()
    }

    /**
     * Android erased the vault's master key (Android 12-14 do this to lock-bound keys when the screen
     * lock is removed). Nothing stored can ever be decrypted again, so start clean instead of crashing,
     * and tell the user once.
     */
    private fun resetAfterKeyLoss() {
        vault.destroy()
        deleteDatabase("onyx.db")
        filesDir.deleteRecursively()
        cacheDir.deleteRecursively()
        noBackupFilesDir.deleteRecursively()
        noBackupFilesDir.mkdirs()
        java.io.File(noBackupFilesDir, RESET_NOTICE).writeText("1")
    }

    /** True once after an automatic reset, so the UI can explain it. */
    fun consumeResetNotice(): Boolean {
        val f = java.io.File(noBackupFilesDir, RESET_NOTICE)
        return f.exists().also { if (it) f.delete() }
    }

    companion object {
        lateinit var instance: OnyxApp; private set
        private const val RESET_NOTICE = "reset-notice"
        const val CH_SERVICE = "onyx.service"
        const val CH_MESSAGES = "onyx.messages"
        private const val LOCK_AFTER_MS = 30_000L
    }
}
