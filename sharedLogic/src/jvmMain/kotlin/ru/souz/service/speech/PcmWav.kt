package ru.souz.service.speech

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun pcm16MonoToWav(
    rawPcm: ByteArray,
    sampleRateHz: Int,
    channels: Int,
    bitsPerSample: Int,
): ByteArray {
    val byteRate = sampleRateHz * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    return ByteArrayOutputStream(WAV_HEADER_SIZE + rawPcm.size).apply {
        writeAscii("RIFF")
        writeLeInt(36 + rawPcm.size)
        writeAscii("WAVE")
        writeAscii("fmt ")
        writeLeInt(16)
        writeLeShort(1)
        writeLeShort(channels)
        writeLeInt(sampleRateHz)
        writeLeInt(byteRate)
        writeLeShort(blockAlign)
        writeLeShort(bitsPerSample)
        writeAscii("data")
        writeLeInt(rawPcm.size)
        write(rawPcm)
    }.toByteArray()
}

private const val WAV_HEADER_SIZE = 44

private fun ByteArrayOutputStream.writeAscii(value: String) {
    write(value.toByteArray(Charsets.US_ASCII))
}

private fun ByteArrayOutputStream.writeLeShort(value: Int) {
    write(
        ByteBuffer.allocate(2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(value.toShort())
            .array()
    )
}

private fun ByteArrayOutputStream.writeLeInt(value: Int) {
    write(
        ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(value)
            .array()
    )
}
