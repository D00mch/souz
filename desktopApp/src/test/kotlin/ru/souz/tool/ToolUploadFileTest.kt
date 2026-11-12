package ru.souz.tool

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.exceedsContextBudget
import ru.souz.llms.giga.toGiga
import ru.souz.tool.desktop.ToolUploadFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ToolUploadFileTest {
    @Test
    fun `three document uploads retain distinct IDs and fit the default desktop window`() = runTest {
        val api = mockk<LLMChatAPI> {
            coEvery { uploadFile(any()) } answers {
                val name = firstArg<java.io.File>().name
                LLMResponse.UploadFile(12, 0, name, "id-$name", "file", "general", "private")
            }
        }
        val tool = ToolUploadFile(api).toGiga()
        val messages = listOf(LLMRequest.Message(LLMMessageRole.user, "Analyze these documents")) +
            listOf("report.csv", "notes.txt", "document.pdf").map { name ->
                tool.invoke(LLMResponse.FunctionCall("UploadFile", mapOf("filePath" to "/synthetic/$name")))
            }
        assertEquals(listOf("id-report.csv", "id-notes.txt", "id-document.pdf"), messages.flatMap { it.attachments.orEmpty() })
        assertFalse(LLMRequest.Chat(messages = messages).exceedsContextBudget(16_000))
    }
}
