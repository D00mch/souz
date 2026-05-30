package ru.souz.runtime.files

/**
 * Best-effort binary file check for bytes that should be editable UTF-8 text.
 *
 * A NUL byte is treated as binary immediately. Otherwise, only the first 1 KiB is sampled:
 * if more than 20% of the sample is non-whitespace ASCII control bytes, the content is
 * considered binary-like.
 */
internal fun ByteArray.isLikelyBinary(): Boolean {
    val bytes = this
    return bytes.any { it == 0.toByte() } ||
            bytes.take(BINARY_SAMPLE_SIZE).let { sample ->
                sample.count { byte ->
                    val value = byte.toInt() and 0xFF
                    value < 0x20 && !isTextWhitespaceControl(value)
                } * 5 > sample.size
            }
}

private fun isTextWhitespaceControl(value: Int): Boolean =
    value == 0x09 || value == 0x0A || value == 0x0C || value == 0x0D

private const val BINARY_SAMPLE_SIZE = 1024
