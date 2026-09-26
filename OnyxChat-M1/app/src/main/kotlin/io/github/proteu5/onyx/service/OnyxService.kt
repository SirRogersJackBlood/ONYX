// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.proteu5.onyx.OnyxApp
import io.github.proteu5.onyx.R
import io.github.proteu5.onyx.ui.MainActivity

/**
 * Keeps Tor + our onion service alive so contacts can reach us directly.
 * Runs only after the user opens the app (no boot autostart by default).
 */
class OnyxService : Service() {

    private val app get() = application as OnyxApp
    @Volatile private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown(); stopSelf(); return START_NOT_STICKY
        }
        val n = statusNotification("Connecting to Tor…")
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID_STATUS, n)

        if (worker == null) {
            worker = Thread({ bringUp() }, "onyx-bringup").apply { isDaemon = true; start() }
        }
        return START_STICKY
    }

    private fun bringUp() {
        var attempt = 0
        while (true) {
            try {
                app.crypto.ensureIdentity()
                val port = app.messenger.server.start()
                app.tor.start(port)
                app.messenger.start()
                app.snowflake.onTierChanged = { badge -> app.messenger.shareBadgeWithAll(badge) }
                app.snowflake.start()
                update("Online · end-to-end encrypted over Tor")
                return
            } catch (e: Exception) {
                attempt++
                update("Tor unavailable — retrying")
                runCatching { app.tor.stop() }
                Thread.sleep(minOf(300_000L, 5_000L shl minOf(attempt, 6)))
            }
        }
    }

    private fun update(text: String) =
        getSystemService(NotificationManager::class.java).notify(ID_STATUS, statusNotification(text))

    private fun statusNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, OnyxApp.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ONYX")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .build()
    }

    private fun shutdown() {
        runCatching { app.snowflake.shutdown() }
        runCatching { app.messenger.stop() }
        runCatching { app.tor.stop() }
    }

    override fun onDestroy() { shutdown(); super.onDestroy() }

    companion object {
        private const val ID_STATUS = 1
        private const val ID_MESSAGE = 2
        const val ACTION_STOP = "io.github.proteu5.onyx.STOP"

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, OnyxService::class.java))

        /** Content-free on purpose: no sender, no text, not shown on the lock screen. */
        fun notifyNewMessage(ctx: Context) {
            val open = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(ctx, OnyxApp.CH_MESSAGES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("ONYX")
                .setContentText("New message")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .build()
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID_MESSAGE, n) }
        }
    }
}
