package ru.souz.backend.attachments

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.test.runTest
import org.slf4j.LoggerFactory
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageGenerationTest {
    @Test
    fun `generation failures expose safe codes and correlation without provider details`() = runTest {
        val logger = LoggerFactory.getLogger(ToolGenerateAttachmentImage::class.java) as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        try {
            for ((failure, code) in listOf(
                AttachmentException("attachment_not_found") to "attachment_not_found",
                AttachmentException("attachment_expired") to "attachment_expired",
                IllegalStateException("secret-provider-body") to "image_generation_failed",
            )) {
                val tool = ToolGenerateAttachmentImage { _, _, _ -> throw failure }
                val result = tool.invoke(
                    LLMResponse.FunctionCall(tool.fn.name, mapOf("prompt" to "private-prompt")),
                    ToolInvocationMeta("owner", "chat", "execution"),
                )
                assertTrue(code in result.content)
                val log = logs.list.last()
                assertTrue("code=$code" in log.formattedMessage)
                assertTrue("userId=owner, executionId=execution" in log.formattedMessage)
                assertFalse("secret-provider-body" in log.formattedMessage)
                assertFalse("private-prompt" in log.formattedMessage)
                assertNull(log.throwableProxy)
            }
            assertEquals(3, logs.list.size)
        } finally {
            logger.detachAppender(logs)
            logs.stop()
        }
    }
}
