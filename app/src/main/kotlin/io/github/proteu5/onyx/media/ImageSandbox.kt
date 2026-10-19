// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.media

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SharedMemory
import android.system.OsConstants
import io.github.proteu5.onyx.core.Media
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Untrusted images are decoded only here (v1.4.4): a service in an ISOLATED process
 * (android:isolatedProcess="true"), and a FRESH one for every image (bindIsolatedService with a new instance
 * name), torn down right after. That process runs under a throwaway user id with no permissions, no network
 * and no access to ONYX's files, keys or messages, and it never sees a second image.
 *
 * It answers with raw RGBA pixels in shared memory: width, height and buffer size are checked by the app, which
 * then builds its own Bitmap and encodes its own JPEG. Nothing the sandbox writes is ever parsed as an image
 * format by the app. A decoder exploit inside the sandbox can at worst return wrong pixels for that one image.
 */
class ImageSandboxService : Service() {
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != ImageSandbox.PREPARE && code != ImageSandbox.SANITIZE) return super.onTransact(code, data, reply, flags)
            val bmp: Bitmap? = runCatching {
                if (code == ImageSandbox.PREPARE) {
                    val pfd = data.readFileDescriptor() ?: return@runCatching null
                    val src = pfd.use { FileInputStream(it.fileDescriptor).use { inp -> readCapped(inp) } } ?: return@runCatching null
                    ImageCodec.decodePicked(src)
                } else {
                    ImageCodec.decodeReceived(data.createByteArray() ?: return@runCatching null)
                }
            }.getOrNull()
            if (bmp == null || bmp.width !in 1..Media.MAX_SIDE || bmp.height !in 1..Media.MAX_SIDE) { reply?.writeInt(0); return true }
            val argb = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
            val shm = SharedMemory.create("onyx-img", argb.width * argb.height * 4)
            val buf = shm.mapReadWrite()
            argb.copyPixelsToBuffer(buf)
            SharedMemory.unmap(buf)
            shm.setProtect(OsConstants.PROT_READ)
            reply?.writeInt(1); reply?.writeInt(argb.width); reply?.writeInt(argb.height)
            shm.writeToParcel(reply!!, 0)
            return true
        }
    }

    private fun readCapped(inp: FileInputStream): ByteArray? {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val n = inp.read(chunk); if (n < 0) break
            buf.write(chunk, 0, n)
            if (buf.size() > ImageCodec.MAX_SOURCE_BYTES) return null
        }
        return buf.toByteArray()
    }

    override fun onBind(intent: Intent?): IBinder = binder
}

/** App-side client. Blocking calls: never on the UI thread. */
object ImageSandbox {
    const val PREPARE = IBinder.FIRST_CALL_TRANSACTION
    const val SANITIZE = IBinder.FIRST_CALL_TRANSACTION + 1
    private const val TIMEOUT_S = 20L
    private val pool = Executors.newCachedThreadPool { Thread(it, "onyx-img").apply { isDaemon = true } }
    private val instance = AtomicLong()

    /** Picked image → ONYX's own clean JPEG. Throws [ImageCodec.TooLarge] for anything the sandbox rejects. */
    fun prepare(ctx: Context, uri: Uri): ByteArray {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw ImageCodec.TooLarge()
        val bmp = pfd.use { call(ctx, PREPARE) { p -> p.writeFileDescriptor(it.fileDescriptor) } } ?: throw ImageCodec.TooLarge()
        return ImageCodec.encode(bmp)
    }

    /** Received JPEG → ONYX's own clean JPEG, or null if rejected. */
    fun sanitize(ctx: Context, jpeg: ByteArray): ByteArray? = runCatching {
        call(ctx, SANITIZE) { p -> p.writeByteArray(jpeg) }?.let { ImageCodec.encode(it) }
    }.getOrNull()

    private fun call(ctx: Context, code: Int, write: (Parcel) -> Unit): Bitmap? {
        val app = ctx.applicationContext
        val ready = CountDownLatch(1)
        var service: IBinder? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, b: IBinder?) { service = b; ready.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
        }
        // A new instance name = a new isolated process, used for this one image only.
        val bound = app.bindIsolatedService(Intent(app, ImageSandboxService::class.java), Context.BIND_AUTO_CREATE,
            "img" + instance.incrementAndGet(), pool, conn)
        if (!bound) return null
        try {
            if (!ready.await(TIMEOUT_S, TimeUnit.SECONDS)) return null
            val b = service ?: return null
            // A decoder that hangs must not hang ONYX: give up after the timeout (unbinding lets Android kill it).
            return pool.submit<Bitmap?> {
                val data = Parcel.obtain(); val reply = Parcel.obtain()
                try {
                    write(data)
                    if (!b.transact(code, data, reply, 0)) return@submit null
                    if (reply.readInt() != 1) return@submit null
                    val w = reply.readInt(); val h = reply.readInt()
                    if (w !in 1..Media.MAX_SIDE || h !in 1..Media.MAX_SIDE) return@submit null
                    SharedMemory.CREATOR.createFromParcel(reply).use { shm ->
                        if (shm.size != w * h * 4) return@submit null
                        val buf: ByteBuffer = shm.mapReadOnly()
                        try {
                            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(buf) }
                        } finally { SharedMemory.unmap(buf) }
                    }
                } finally { data.recycle(); reply.recycle() }
            }.get(TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: Exception) {
            return null       // crashed (DeadObjectException), timed out or rejected: the image is refused
        } finally {
            runCatching { app.unbindService(conn) }
        }
    }
}
