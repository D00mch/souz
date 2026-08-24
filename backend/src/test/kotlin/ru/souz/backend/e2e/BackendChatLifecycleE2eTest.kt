package ru.souz.backend.e2e

import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import ru.souz.backend.http.BackendHttpRoutes

class BackendChatLifecycleE2eTest {
    @Test
    fun `chat creation CRUD ownership archive and pagination use production persistence`() =
        backendE2eTest("e2e_chat_lifecycle") {
            val userId = UUID.randomUUID().toString()
            val foreignUserId = UUID.randomUUID().toString()
            val created = client.post(BackendHttpRoutes.CHATS) {
                jsonBody(
                    """{"userId":"$userId","requestId":"create-1","clientType":"backend","title":" Original "}"""
                )
            }
            val chatId = created.jsonBody()["chat"]["id"].asText()
            val duplicate = client.post(BackendHttpRoutes.CHATS) {
                jsonBody(
                    """{"userId":"$userId","requestId":"create-1","clientType":"backend","title":" Original "}"""
                )
            }
            val conflict = client.post(BackendHttpRoutes.CHATS) {
                jsonBody(
                    """{"userId":"$userId","requestId":"create-1","clientType":"backend","title":"Different"}"""
                )
            }
            val strict = client.post(BackendHttpRoutes.CHATS) {
                jsonBody(
                    """{"userId":"$userId","requestId":"create-2","clientType":"backend","unknown":true}"""
                )
            }
            val foreignChat = client.post(BackendHttpRoutes.CHATS) {
                jsonBody(
                    """{"userId":"$foreignUserId","requestId":"foreign-1","clientType":"backend","title":"Foreign"}"""
                )
            }.jsonBody()["chat"]["id"].asText()

            assertEquals(HttpStatusCode.Created, created.status)
            assertFalse(created.jsonBody()["duplicate"].asBoolean())
            assertEquals(HttpStatusCode.OK, duplicate.status)
            assertTrue(duplicate.jsonBody()["duplicate"].asBoolean())
            assertEquals(chatId, duplicate.jsonBody()["chat"]["id"].asText())
            assertEquals(HttpStatusCode.Conflict, conflict.status)
            assertEquals("idempotency_conflict", conflict.jsonBody()["error"]["code"].asText())
            assertEquals(HttpStatusCode.BadRequest, strict.status)

            val titled = client.patch(BackendHttpRoutes.chatTitle(chatId)) {
                trusted(userId)
                jsonBody("""{"title":" Renamed "}""")
            }
            val foreignTitle = client.patch(BackendHttpRoutes.chatTitle(chatId)) {
                trusted(foreignUserId)
                jsonBody("""{"title":"Stolen"}""")
            }
            assertEquals(HttpStatusCode.OK, titled.status)
            assertEquals("Renamed", titled.jsonBody()["title"].asText())
            assertEquals(HttpStatusCode.NotFound, foreignTitle.status)

            repeat(3) { index ->
                val response = client.post(BackendHttpRoutes.chatMessages(chatId)) {
                    trusted(userId)
                    jsonBody(
                        """{"content":"message-${index + 1}","clientMessageId":"client-${index + 1}","options":{"model":"${E2E_LOCAL_MODEL.alias}"}}"""
                    )
                }
                assertEquals(HttpStatusCode.OK, response.status)
                val events = eventually("turn ${index + 1} terminal event") {
                    client.get(BackendHttpRoutes.chatEvents(chatId)) {
                        trusted(userId)
                    }.jsonBody()["items"].takeIf { items ->
                        items.count {
                            it["type"].asText() in setOf(
                                "execution.finished",
                                "execution.failed",
                                "execution.cancelled",
                            )
                        } == index + 1
                    }
                }
                assertEquals("execution.finished", events.last()["type"].asText())
                val messages = client.get(BackendHttpRoutes.chatMessages(chatId)) {
                    trusted(userId)
                }.jsonBody()["items"]
                assertEquals((index + 1) * 2, messages.size())
            }

            val firstPage = client.get("${BackendHttpRoutes.chatMessages(chatId)}?limit=2") {
                trusted(userId)
            }.jsonBody()
            val secondPage = client.get("${BackendHttpRoutes.chatMessages(chatId)}?afterSeq=2&limit=2") {
                trusted(userId)
            }.jsonBody()
            val foreignMessages = client.get(BackendHttpRoutes.chatMessages(chatId)) {
                trusted(foreignUserId)
            }
            val finalMessageContent = client.get(BackendHttpRoutes.chatMessages(chatId)) {
                trusted(userId)
            }.jsonBody()["items"].last()["content"].asText()
            assertEquals(listOf(1L, 2L), firstPage["items"].map { it["seq"].asLong() })
            assertEquals(listOf(3L, 4L), secondPage["items"].map { it["seq"].asLong() })
            assertEquals(HttpStatusCode.NotFound, foreignMessages.status)

            val archived = client.post(BackendHttpRoutes.archiveChat(chatId)) {
                trusted(userId)
            }
            val activeList = client.get(BackendHttpRoutes.CHATS) {
                trusted(userId)
            }.jsonBody()["items"]
            val archivedList = client.get("${BackendHttpRoutes.CHATS}?includeArchived=true") {
                trusted(userId)
            }.jsonBody()["items"]
            val foreignList = client.get("${BackendHttpRoutes.CHATS}?includeArchived=true") {
                trusted(foreignUserId)
            }.jsonBody()["items"]

            assertTrue(archived.jsonBody()["archived"].asBoolean())
            assertTrue(activeList.none { it["id"].asText() == chatId })
            assertEquals(chatId, archivedList.single()["id"].asText())
            assertEquals(finalMessageContent, archivedList.single()["lastMessagePreview"].asText())
            assertEquals(foreignChat, foreignList.single()["id"].asText())

            val unarchived = client.post(BackendHttpRoutes.unarchiveChat(chatId)) {
                trusted(userId)
            }
            assertFalse(unarchived.jsonBody()["archived"].asBoolean())
        }

    @Test
    fun `different chats execute independently while each chat keeps one active turn`() =
        backendE2eTest("e2e_chat_concurrency", llm = E2eLlmApi().apply { pauseUntilReleased() }) {
            val userA = UUID.randomUUID().toString()
            val userB = UUID.randomUUID().toString()
            val chatA = createChat(userA, "create-a")
            val chatB = createChat(userB, "create-b")

            coroutineScope {
                val first = async { send(chatA, userA, "A1") }
                val second = async { send(chatB, userB, "B1") }
                llm.awaitPrompt("A1")
                llm.awaitPrompt("B1")

                val sameChatConflict = client.post(BackendHttpRoutes.chatMessages(chatA)) {
                    trusted(userA)
                    jsonBody("""{"content":"A2"}""")
                }
                assertEquals(HttpStatusCode.Conflict, sameChatConflict.status)
                assertEquals(2, llm.requests.size)

                llm.release()
                assertEquals(HttpStatusCode.OK, first.await().status)
                assertEquals(HttpStatusCode.OK, second.await().status)
            }
        }

    private suspend fun BackendE2eScope.createChat(userId: String, requestId: String): String =
        client.post(BackendHttpRoutes.CHATS) {
            jsonBody("""{"userId":"$userId","requestId":"$requestId","clientType":"backend"}""")
        }.jsonBody()["chat"]["id"].asText()

    private suspend fun BackendE2eScope.send(chatId: String, userId: String, content: String) =
        client.post(BackendHttpRoutes.chatMessages(chatId)) {
            trusted(userId)
            jsonBody("""{"content":"$content","options":{"model":"${E2E_LOCAL_MODEL.alias}"}}""")
        }
}
