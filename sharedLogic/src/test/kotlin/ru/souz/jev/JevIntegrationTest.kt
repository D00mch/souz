package ru.souz.jev

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.http.createStandardProviderHttpClient
import ru.souz.llms.runtime.JevClassifier
import ru.souz.tool.ToolCategory
import kotlin.test.Test
import kotlin.test.assertEquals

class JevIntegrationTest {
    @Test
    fun `hosted Jev selects calendar and mail together`() = runBlocking {
        assumeTrue(System.getenv("SOUZ_TEST_JEV") == "1", "Enable with SOUZ_TEST_JEV=1 and JEV_TOKEN")
        createStandardProviderHttpClient().use { http ->
            val classifier = JevClassifier(JevClient(http))
            val result = classifier.classify(
                LLMRequest.Chat(
                    model = "unused-conversational-model",
                    messages = listOf(LLMRequest.Message(
                        LLMMessageRole.user, "Check my calendar for today's meetings and email everyone the agenda",
                    )),
                ),
                mapOf(
                    ToolCategory.CALENDAR to "Read and edit calendar events",
                    ToolCategory.MAIL to "Read and send emails",
                    ToolCategory.FILES to "Read and edit local files",
                ),
            )
            assertEquals(setOf(ToolCategory.CALENDAR, ToolCategory.MAIL), result.categories.toSet())
        }
    }
}
