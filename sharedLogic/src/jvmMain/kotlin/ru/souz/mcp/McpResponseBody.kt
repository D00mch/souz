package ru.souz.mcp

import java.io.IOException
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/** Bounds JSON and both SSE response paths before the SDK or Ktor can accumulate a frame. */
internal class McpResponseBody(private val delegate: ResponseBody) : ResponseBody() {
    private val guarded = object : ForwardingSource(delegate.source()) {
        private var total = 0L
        private val json = McpJsonDepthGuard()
        private val sse = this@McpResponseBody.delegate.contentType()?.let {
            it.type == "text" && it.subtype == "event-stream"
        } == true
        private var prefix = 0
        private var dataLine = false
        private val data = Buffer()
        private var lineEmpty = true
        private var previousCr = false
        private var startBytes = 0

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (byteCount == 0L) return 0
            try {
                val chunk = Buffer()
                val read = super.read(chunk, minOf(byteCount, 8_192, McpLimits.RESPONSE_BYTES - total + 1))
                if (read <= 0) {
                    if (read == -1L && sse) finishDataLine()
                    return read
                }
                total += read
                if (total > McpLimits.RESPONSE_BYTES) throw IOException("MCP response exceeds the byte limit.")
                val bytes = chunk.readByteArray()
                bytes.forEach { byte -> if (sse) acceptSse(byte.toInt() and 255) else json.accept(byte.toInt() and 255) }
                sink.write(bytes)
                return read
            } catch (failure: IOException) {
                super.close()
                throw failure
            }
        }

        private fun acceptSse(c: Int) {
            if (startBytes < 3 && c == listOf(0xef, 0xbb, 0xbf)[startBytes]) {
                startBytes++
                return
            }
            startBytes = 3
            if (c == 10 && previousCr) {
                previousCr = false
                return
            }
            previousCr = c == 13
            if (c == 10 || c == 13) {
                finishDataLine()
                if (lineEmpty) json.reset()
                prefix = 0
                dataLine = false
                lineEmpty = true
            } else {
                lineEmpty = false
                if (dataLine) data.writeByte(c)
                else if (prefix >= 0) {
                    if (c == "data:"[prefix].code) {
                        prefix++
                        if (prefix == 5) dataLine = true
                    } else prefix = -1
                }
            }
        }

        private fun finishDataLine() {
            // The SDK trims each inline SSE data line before concatenation. The response byte cap
            // bounds this buffer, including unterminated lines, before any frame can be decoded.
            data.readUtf8().trim().forEach { json.accept(it.code) }
        }
    }.buffer()

    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()
    override fun source(): BufferedSource = guarded
}

/** UTF-8 continuation bytes cannot alias JSON punctuation; no unbounded strings or nesting stack. */
private class McpJsonDepthGuard {
    private var depth = 0
    private var quoted = false
    private var escaped = false

    fun reset() {
        depth = 0
        quoted = false
        escaped = false
    }

    fun accept(c: Int) {
        if (quoted) {
            when {
                escaped -> escaped = false
                c == 92 -> escaped = true
                c == 34 -> quoted = false
            }
        } else when (c) {
            34 -> quoted = true
            123, 91 -> {
                depth++
                if (depth > McpLimits.JSON_DEPTH) throw IOException("MCP JSON exceeds the nesting limit.")
            }
            125, 93 -> depth--
        }
    }
}
