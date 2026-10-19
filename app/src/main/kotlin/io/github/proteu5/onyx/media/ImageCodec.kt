// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import io.github.proteu5.onyx.core.Media
import java.io.ByteArrayOutputStream

/**
 * Images with NOTHING but pixels (v1.4.4):
 *  - Untrusted bytes (a picked photo, a received image) are decoded ONLY inside a fresh isolated process per
 *    image ([ImageSandbox]), which hands back raw pixels of a checked size: the `decode*` functions below.
 *  - The app turns those pixels into its own baseline JPEG ([encode]): no EXIF, GPS, XMP, maker notes,
 *    thumbnails or trailing data ([Bitmap.compress] writes none of them). That JPEG is the only one ONYX
 *    stores, sends or decodes for display.
 *  - The camera's rotation is applied to the pixels first (the rotation tag itself is dropped); the longest
 *    side is at most [Media.MAX_SIDE]; quality is lowered until it fits [Media.MAX_JPEG].
 */
object ImageCodec {

    class TooLarge : Exception("That image is too large or not a supported picture.")

    /** Largest picked file read into the sandbox (camera photos are usually 2–12 MB). */
    const val MAX_SOURCE_BYTES = 40 * 1024 * 1024

    /** SANDBOX ONLY. Sender side: picked file bytes → pixels, rotated, longest side at most [Media.MAX_SIDE]. */
    fun decodePicked(src: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(src, 0, src.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw TooLarge()
        if (bounds.outWidth.toLong() * bounds.outHeight > 120_000_000L) throw TooLarge()   // > 120 MP: refuse
        val rotation = runCatching {
            when (ExifInterface(src.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, Media.MAX_SIDE)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(src, 0, src.size, opts) ?: throw TooLarge()
        return fit(decoded, rotation)
    }

    /** SANDBOX ONLY. Receiver side: checks the claimed size BEFORE decoding pixels. */
    fun decodeReceived(jpeg: ByteArray): Bitmap? = runCatching {
        if (!Media.looksLikeJpeg(jpeg) || jpeg.size > Media.MAX_JPEG) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth > Media.MAX_DECODE_SIDE || bounds.outHeight > Media.MAX_DECODE_SIDE) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, Media.MAX_SIDE); inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        fit(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return null, 0f)
    }.getOrNull()

    /** APP SIDE, display only: decodes a JPEG that [encode] produced (never anything received as-is). */
    fun decodeForDisplay(jpeg: ByteArray, maxPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx) }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
    }.getOrNull()

    private fun sampleSize(w: Int, h: Int, target: Int): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= target) s *= 2
        return s
    }

    /** Longest side at most [Media.MAX_SIDE], rotated in the same step. */
    private fun fit(src: Bitmap, rotation: Float): Bitmap {
        val scale = minOf(1f, Media.MAX_SIDE.toFloat() / maxOf(src.width, src.height))
        if (scale >= 1f && rotation == 0f) return src
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** APP SIDE. Checked pixels → ONYX's own baseline JPEG, no metadata, at most [Media.MAX_JPEG]. */
    fun encode(bmp: Bitmap): ByteArray {
        // Flatten transparency onto white (JPEG has no alpha) into a fresh bitmap: no carried-over state.
        val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
        var current = flat
        while (true) {
            for (q in intArrayOf(82, 72, 62, 52, 42)) {
                val out = ByteArrayOutputStream()
                current.compress(Bitmap.CompressFormat.JPEG, q, out)
                if (out.size() <= Media.MAX_JPEG) return out.toByteArray()
            }
            if (maxOf(current.width, current.height) < 160) throw TooLarge()
            current = Bitmap.createScaledBitmap(current, (current.width * 0.8f).toInt(), (current.height * 0.8f).toInt(), true)
        }
    }
}
