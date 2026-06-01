package ru.souz.runtime.sandbox

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class SandboxCommandOutputCaptureTest {
    @Test
    fun `stores prefix and records truncated bytes`() {
        val capture = SandboxCommandOutputCapture(limitBytes = 5)
        capture.append("abc".toByteArray(StandardCharsets.UTF_8))
        capture.append("defg".toByteArray(StandardCharsets.UTF_8))

        assertEquals("abcde${SANDBOX_COMMAND_OUTPUT_TRUNCATION_PREFIX}2 bytes]", capture.text())
    }

    @Test
    fun `keeps utf8 prefix and truncation marker`() {
        val keptText = "ЖЖ"
        val capture = SandboxCommandOutputCapture(limitBytes = keptText.toByteArray(StandardCharsets.UTF_8).size)
        capture.append("$keptText!".toByteArray(StandardCharsets.UTF_8))

        assertEquals("$keptText${SANDBOX_COMMAND_OUTPUT_TRUNCATION_PREFIX}1 bytes]", capture.text())
        assertContains(capture.text(), SANDBOX_COMMAND_OUTPUT_TRUNCATION_PREFIX)
    }

    private fun SandboxCommandOutputCapture.append(bytes: ByteArray) {
        append(bytes, 0, bytes.size)
    }
}
