// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.core

/**
 * Images (v1.4.4). The phone re-encodes every image before it's sent and again when it arrives
 * (Android side), so what travels is a plain baseline JPEG with no EXIF, GPS, camera model, thumbnails
 * or anything else a camera writes. Every image is padded to the same size on the wire.
 */
object Media {
    /** Longest side after re-encoding, in pixels. */
    const val MAX_SIDE = 800
    /** Largest JPEG accepted: leaves room for a 500-character caption inside the fixed 60 KiB padded envelope. */
    const val MAX_JPEG = 58_000
    /** Largest dimension a received image may claim before it is even decoded. */
    const val MAX_DECODE_SIDE = 4096

    /** IMAGE body: optional group id (for group chats), a caption (same text rules) and the JPEG. */
    class Image(val groupId: ByteArray?, val caption: String, val jpeg: ByteArray) {
        init { require(groupId == null || groupId.size == Groups.ID_LEN) }
        fun encode(): ByteArray = ByteWriter(jpeg.size + caption.length * 3 + 64)
            .u8(1).u8(if (groupId != null) 1 else 0).apply { groupId?.let { fixed(it, Groups.ID_LEN) } }
            .str16(caption).bytes32(jpeg).toByteArray()

        companion object {
            fun decode(b: ByteArray): Image {
                val r = ByteReader(b)
                if (r.u8() != 1) throw MalformedException("image version")
                val gid = if (r.u8() == 1) r.raw(Groups.ID_LEN) else null
                val img = Image(gid, r.str16(4096), r.bytes32(MAX_JPEG))
                r.end()
                if (!looksLikeJpeg(img.jpeg)) throw MalformedException("not a JPEG")
                return img
            }
        }
    }

    fun looksLikeJpeg(b: ByteArray) = b.size > 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()

    /** Images per minute, on top of the general message limit. */
    const val IMAGES_PER_MINUTE = 5
}
