// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.snowflake

import IPtProxy.SnowflakeClientEvents
import IPtProxy.SnowflakeProxy
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import io.github.proteu5.onyx.core.ByteReader
import io.github.proteu5.onyx.core.ByteWriter
import io.github.proteu5.onyx.core.ForgeRank
import io.github.proteu5.onyx.data.SecureStore
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * "Help others connect": a Snowflake proxy that relays censored users INTO Tor.
 * It is not a Tor relay and never an exit — no traffic leaves the Tor network from this phone.
 *
 * Guardrails (all required): user enabled, unmetered Wi-Fi, charging, under the daily data cap.
 * Stats are local only; [ForgeRank] decides what (tier + cover) may be shared with chat partners.
 */
class SnowflakeController(private val context: Context, private val store: SecureStore) {

    data class Settings(val enabled: Boolean, val dailyCapMb: Int, val shareBadge: Boolean, val cover: ForgeRank.Cover)

    private data class Counters(
        val seconds: Long, val helped: Long, val everEnabled: Boolean,
        val day: String, val bytesToday: Long,
    )

    private val exec = Executors.newSingleThreadScheduledExecutor { Thread(it, "onyx-snowflake").apply { isDaemon = true } }
    private var proxy: SnowflakeProxy? = null
    @Volatile var running = false; private set
    @Volatile var lastBlockReason: String? = null; private set
    private var lastTick = 0L
    var onTierChanged: ((ForgeRank.Badge) -> Unit)? = null

    fun settings(): Settings = store.get(NS, "settings")?.let {
        val r = ByteReader(it); r.u8()
        Settings(r.u8() == 1, r.u32().toInt(), r.u8() == 1, ForgeRank.Cover.of(r.u8()))
    } ?: Settings(false, 500, true, ForgeRank.Cover.EMBLEM)

    fun saveSettings(s: Settings) {
        val before = badge()
        store.put(NS, "settings", ByteWriter().u8(1).u8(if (s.enabled) 1 else 0).u32(s.dailyCapMb.toLong())
            .u8(if (s.shareBadge) 1 else 0).u8(s.cover.code).toByteArray())
        if (s.enabled) update { it.copy(everEnabled = true) }
        exec.execute { evaluate() }
        val after = badge()
        if (after != null && after != before) onTierChanged?.invoke(after)
    }

    private fun counters(): Counters = store.get(NS, "counters")?.let {
        val r = ByteReader(it); r.u8()
        Counters(r.u64(), r.u64(), r.u8() == 1, r.str16(), r.u64())
    } ?: Counters(0, 0, false, LocalDate.now().toString(), 0)

    @Synchronized
    private fun update(f: (Counters) -> Counters) {
        var c = counters()
        val today = LocalDate.now().toString()
        if (c.day != today) c = c.copy(day = today, bytesToday = 0)
        val n = f(c)
        store.put(NS, "counters", ByteWriter().u8(1).u64(n.seconds).u64(n.helped).u8(if (n.everEnabled) 1 else 0)
            .str16(n.day).u64(n.bytesToday).toByteArray())
    }

    fun stats(): ForgeRank.Stats = counters().let { ForgeRank.Stats(it.seconds, it.helped, it.everEnabled) }
    fun tier(): ForgeRank.Tier = ForgeRank.tierFor(stats())
    fun bytesToday(): Long = counters().let { if (it.day == LocalDate.now().toString()) it.bytesToday else 0 }

    /** The only thing ever shared, and only if the user allows it. */
    fun badge(): ForgeRank.Badge? {
        val s = settings()
        val t = tier()
        return if (s.shareBadge && t != ForgeRank.Tier.NONE) ForgeRank.Badge(t, s.cover) else null
    }

    fun start() {
        exec.scheduleWithFixedDelay({ runCatching { evaluate() } }, 0, 60, TimeUnit.SECONDS)
    }

    private fun guardrails(): ForgeRank.Guardrails {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val wifi = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val s = settings()
        return ForgeRank.Guardrails(s.enabled, wifi, charging, bytesToday(), s.dailyCapMb.toLong() * 1_000_000L)
    }

    @Synchronized
    private fun evaluate() {
        val tierBefore = tier()
        if (running) {
            val now = System.currentTimeMillis()
            update { it.copy(seconds = it.seconds + (now - lastTick) / 1000) }
            lastTick = now
        }
        val g = guardrails()
        lastBlockReason = when {
            !g.userEnabled -> "Off"
            !g.onUnmeteredWifi -> "Waiting for unmetered Wi-Fi"
            !g.charging -> "Waiting for charger"
            g.bytesToday >= g.dailyCapBytes -> "Daily data cap reached"
            else -> null
        }
        if (g.allowed() && !running) startProxy() else if (!g.allowed() && running) stopProxy()
        val tierAfter = tier()
        if (tierAfter != tierBefore) badge()?.let { onTierChanged?.invoke(it) }
    }

    private fun startProxy() {
        val p = SnowflakeProxy().apply {
            capacity = 0L
            // Tor Project defaults, spelled out. STUN servers deliberately exclude Google.
            brokerUrl = "https://snowflake-broker.torproject.net/"
            relayUrl = "wss://snowflake.torproject.net/"
            natProbeUrl = "https://snowflake-broker.torproject.net:8443/probe"
            stunServer = "stun:stun.antisip.com:3478"
            pollInterval = 120
            clientEvents = object : SnowflakeClientEvents {
                override fun connected() { update { it.copy(helped = it.helped + 1) } }
                override fun connectionFailed() = Unit
                override fun disconnected(country: String?) = Unit   // country is never stored
                override fun natTypeUpdated(natType: String?) = Unit
                override fun stats(connectionCount: Long, failedConnectionCount: Long, inboundBytes: Long,
                                   outboundBytes: Long, inboundUnit: String?, outboundUnit: String?, summaryInterval: Long) {
                    val bytes = toBytes(inboundBytes, inboundUnit) + toBytes(outboundBytes, outboundUnit)
                    update { it.copy(bytesToday = it.bytesToday + bytes) }
                }
            }
        }
        try {
            p.start()
            proxy = p
            running = true
            lastTick = System.currentTimeMillis()
        } catch (e: Exception) {
            lastBlockReason = "Snowflake failed to start"
        }
    }

    private fun toBytes(v: Long, unit: String?): Long = when (unit?.uppercase()) {
        "KB" -> v * 1_000; "MB" -> v * 1_000_000; "GB" -> v * 1_000_000_000; else -> v
    }

    private fun stopProxy() {
        runCatching { proxy?.stop() }
        proxy = null
        running = false
    }

    fun shutdown() { exec.execute { stopProxy() } }

    companion object { private const val NS = "snowflake" }
}
