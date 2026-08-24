package ru.souz.backend.e2e

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.BackendConversationTurnOutcome
import ru.souz.backend.agent.runtime.BackendConversationTurnRunner
import ru.souz.backend.agent.session.AgentConversationSession
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.options.model.OptionKind
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse

class BackendOptionsE2eTest {
    @Test
    fun `option request persists and answering continues through HTTP and Postgres`() =
        backendE2eTest(
            schemaPrefix = "e2e_options",
            featureFlags = BackendFeatureFlags(wsEvents = true, options = true),
            turnRunnerOverride = ScriptedOptionTurnRunner(),
        ) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val sent = client.post(BackendHttpRoutes.chatMessages(chatId)) {
                trusted(userId)
                jsonBody("""{"content":"need option"}""")
            }
            assertEquals(HttpStatusCode.OK, sent.status)

            val optionEvent = eventually("option requested event") {
                client.get(BackendHttpRoutes.chatEvents(chatId)) {
                    trusted(userId)
                }.jsonBody()["items"].firstOrNull { it["type"].asText() == "option.requested" }
            }
            val optionId = optionEvent["payload"]["optionId"].asText()
            assertEquals("Select variant", optionEvent["payload"]["title"].asText())
            assertEquals(2, optionEvent["payload"]["options"].size())

            val answer = client.post(BackendHttpRoutes.optionAnswer(optionId)) {
                trusted(userId)
                jsonBody("""{"selectedOptionIds":["a"],"freeText":"Alpha","metadata":{"source":"e2e"}}""")
            }
            assertEquals(HttpStatusCode.OK, answer.status)
            assertEquals("answered", answer.jsonBody()["option"]["status"].asText())
            eventually("continued assistant message") {
                client.get(BackendHttpRoutes.chatMessages(chatId)) {
                    trusted(userId)
                }.jsonBody()["items"].takeIf { messages ->
                    messages.any { it["role"].asText() == "assistant" }
                }
            }.let { messages ->
                assertTrue(messages.any { it["content"].asText() == "continued after choosing Alpha" })
            }
        }

    private suspend fun BackendE2eScope.createPublicChat(userId: String): String {
        val created = client.post(BackendHttpRoutes.CHATS) {
            jsonBody("""{"userId":"$userId","requestId":"create-1","clientType":"backend"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        return created.jsonBody()["chat"]["id"].asText()
    }
}

private class ScriptedOptionTurnRunner : BackendConversationTurnRunner {
    private val waitingConversations = LinkedHashSet<AgentConversationKey>()

    override suspend fun run(
        conversationKey: AgentConversationKey,
        request: BackendConversationTurnRequest,
        eventSink: AgentRuntimeEventSink,
        initialUsage: LLMResponse.Usage,
    ): BackendConversationTurnOutcome =
        if (waitingConversations.add(conversationKey)) {
            eventSink.emit(
                AgentRuntimeEvent.ChoiceRequested(
                    choiceId = UUID.randomUUID().toString(),
                    kind = OptionKind.GENERIC_SELECTION.value,
                    title = "Select variant",
                    selectionMode = "single",
                    options = listOf(
                        AgentRuntimeEvent.ChoiceRequested.ChoiceOption("a", "Alpha", "alpha"),
                        AgentRuntimeEvent.ChoiceRequested.ChoiceOption("b", "Beta", "beta"),
                    ),
                )
            )
            BackendConversationTurnOutcome.WaitingOption(
                usage = LLMResponse.Usage(3, 2, 5, 0),
                session = sessionFor(request.prompt, "waiting for option"),
            )
        } else {
            waitingConversations.remove(conversationKey)
            BackendConversationTurnOutcome.Completed(
                output = "continued after choosing Alpha",
                usage = LLMResponse.Usage(5, 7, 12, 0),
                session = sessionFor(request.prompt, "continued after choosing Alpha"),
            )
        }

    private fun sessionFor(prompt: String, assistant: String): AgentConversationSession =
        AgentConversationSession(
            history = listOf(
                LLMRequest.Message(role = LLMMessageRole.user, content = prompt),
                LLMRequest.Message(role = LLMMessageRole.assistant, content = assistant),
            ),
            temperature = 0.6f,
            locale = "ru-RU",
            timeZone = "Europe/Moscow",
        )
}
