package ru.souz.llms.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Check the bitstream dimensions before ImageIO can allocate from an untrusted WebP header. */
internal fun webpDimensions(bytes: ByteArray): Pair<Int, Int>? {
    fun hasTag(offset: Int, tag: String): Boolean =
        tag.indices.all { bytes[offset + it].toInt() == tag[it].code }

    if (bytes.size < 12 || !hasTag(0, "RIFF") || !hasTag(8, "WEBP")) return null
    val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    fun uint24(offset: Int): Int =
        (header.getShort(offset).toInt() and 0xffff) or ((bytes[offset + 2].toInt() and 0xff) shl 16)

    require(header.getInt(4) == bytes.size - 8)
    var canvas: Pair<Int, Int>? = null
    var frame: Pair<Int, Int>? = null
    var offset = 12
    while (offset < bytes.size) {
        require(bytes.size - offset >= 8)
        val length = header.getInt(offset + 4)
        val data = offset + 8
        require(length in 0..(bytes.size - data))
        val end = data + length
        val padding = length and 1
        require(padding == 0 || (end < bytes.size && bytes[end] == 0.toByte()))

        when {
            hasTag(offset, "VP8X") -> {
                require(offset == 12 && length == 10 && bytes[data].toInt() and 0x02 == 0)
                canvas = (uint24(data + 4) + 1) to (uint24(data + 7) + 1)
            }
            hasTag(offset, "VP8 ") -> {
                require(frame == null && (offset == 12 || canvas != null) && length >= 10)
                require(bytes[data].toInt() and 1 == 0 && uint24(data + 3) == 0x2a019d)
                frame = (header.getShort(data + 6).toInt() and 0x3fff) to
                    (header.getShort(data + 8).toInt() and 0x3fff)
            }
            hasTag(offset, "VP8L") -> {
                require(frame == null && (offset == 12 || canvas != null) && length >= 5)
                require(bytes[data].toInt() == 0x2f)
                val packed = header.getInt(data + 1)
                require(packed ushr 29 == 0)
                frame = ((packed and 0x3fff) + 1) to (((packed ushr 14) and 0x3fff) + 1)
            }
            hasTag(offset, "ANIM") || hasTag(offset, "ANMF") -> throw InvalidImageException("image_decode_failed")
        }
        offset = end + padding
    }
    require(frame != null && (canvas == null || canvas == frame))
    return frame
}
