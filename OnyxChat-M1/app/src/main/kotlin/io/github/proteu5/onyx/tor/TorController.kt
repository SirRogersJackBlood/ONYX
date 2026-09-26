// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.tor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.github.proteu5.onyx.core.OnionAddress
import io.github.proteu5.onyx.data.SecureStore
import net.freehaven.tor.control.TorControlConnection
import org.torproject.jni.TorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Embedded Tor, in-process (tor-android). Owns:
 *  - the SOCKS port used for outgoing onion connections, and
 *  - our onion service, created with ADD_ONION as an EPHEMERAL service whose ed25519 key lives
 *    only in ONYX's encrypted store — never in a plaintext HiddenServiceDir on disk.
 */
class TorController(private val context: Context, private val store: SecureStore) {

    enum class State { OFF, STARTING, ONLINE, FAILED }

    @Volatile var state: State = State.OFF; private set
    @Volatile var onion: OnionAddress? = null; private set
    @Volatile var socksPort: Int = -1; private set
    @Volatile var lastError: String? = null; private set

    private var service: TorService? = null
    private var bound = false
    private val listeners = mutableListOf<(State) -> Unit>()

    fun addListener(l: (State) -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: (State) -> Unit) = synchronized(listeners) { listeners -= l }
    private fun set(s: State) { state = s; synchronized(listeners) { listeners.toList() }.forEach { it(s) } }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as TorService.LocalBinder).service
            connected.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            set(State.OFF)
        }
    }
    private var connected = CountDownLatch(1)

    /**
     * Blocking; call from a background thread. Returns our onion address once Tor has built
     * circuits and the onion service has been added.
     */
    @Synchronized
    fun start(localPort: Int): OnionAddress {
        if (state == State.ONLINE) return onion!!
        set(State.STARTING)
        try {
            writeTorrc()
            connected = CountDownLatch(1)
            bound = context.bindService(Intent(context, TorService::class.java), conn, Context.BIND_AUTO_CREATE)
            check(bound) { "cannot bind TorService" }
            check(connected.await(30, TimeUnit.SECONDS)) { "TorService did not connect" }

            val svc = service ?: throw IllegalStateException("TorService unavailable")
            val control = waitForBootstrap(svc, 180_000)
            socksPort = svc.socksPort
            check(socksPort > 0) { "no SOCKS port" }

            onion = publishOnion(control, localPort)
            set(State.ONLINE)
            return onion!!
        } catch (e: Exception) {
            lastError = e.message
            set(State.FAILED)
            throw e
        }
    }

    private fun writeTorrc() {
        // Minimal and conservative: unknown options would stop Tor from starting.
        TorService.getTorrc(context).writeText(
            """
            # Written by ONYX. Client + ephemeral onion service only.
            AvoidDiskWrites 1
            SafeSocks 1
            ClientOnly 1
            """.trimIndent() + "\n"
        )
    }

    private fun waitForBootstrap(s: TorService, timeoutMs: Long): TorControlConnection {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val c = s.torControlConnection
            if (c != null) {
                val established = runCatching { c.getInfo("status/circuit-established") }.getOrNull()
                if (established?.trim() == "1") return c
            }
            Thread.sleep(500)
        }
        throw IllegalStateException("Tor bootstrap timed out")
    }

    private fun publishOnion(control: TorControlConnection, localPort: Int): OnionAddress {
        val stored = store.get(NS, KEY)?.let { String(it, Charsets.US_ASCII) }
        val ports: Map<Int, String> = mapOf(VIRTUAL_PORT to "127.0.0.1:$localPort")
        val reply = control.addOnion(stored ?: "NEW:ED25519-V3", ports)

        // jtorctl versions differ in reply key names; parse by value shape instead of key name.
        val values = reply.values.filterNotNull().map { it.trim() }
        val label = values.firstOrNull { Regex("^[a-z2-7]{56}(\\.onion)?$").matches(it) }
            ?: throw IllegalStateException("ADD_ONION returned no address")
        if (stored == null) {
            val key = values.firstOrNull { it.startsWith("ED25519-V3:") }
                ?: values.firstOrNull { Regex("^[A-Za-z0-9+/]{86}==$").matches(it) }?.let { "ED25519-V3:$it" }
                ?: throw IllegalStateException("ADD_ONION returned no key")
            store.put(NS, KEY, key.toByteArray(Charsets.US_ASCII))
        }
        return OnionAddress.parse(label)
    }

    @Synchronized
    fun stop() {
        runCatching { onion?.let { service?.torControlConnection?.delOnion(it.label) } }
        if (bound) runCatching { context.unbindService(conn) }
        bound = false
        runCatching { context.stopService(Intent(context, TorService::class.java)) }
        service = null
        set(State.OFF)
    }

    /** Destroys our onion identity. A new address will be generated next start. */
    fun forgetOnionKey() = store.delete(NS, KEY)

    companion object {
        const val VIRTUAL_PORT = 9878
        private const val NS = "tor"
        private const val KEY = "onion-key"
    }
}
