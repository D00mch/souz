package ru.souz.backend.http

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.llms.LLMModel

class BackendOpenApiTest {
    private val json = jacksonObjectMapper()

    @Test
    fun `swagger UI and OpenAPI document are public and same origin`() = testApplication {
        installBackend(BackendFeatureFlags())

        val uiResponse = client.get(BackendHttpRoutes.DOCS)
        val ui = uiResponse.bodyAsText()
        assertEquals(HttpStatusCode.OK, uiResponse.status)
        assertTrue(uiResponse.contentType()?.match(ContentType.Text.Html) == true)
        assertTrue(ui.contains("url: '/docs/openapi.json'"))
        assertTrue(ui.contains("https://unpkg.com/swagger-ui-dist@"))

        val documentResponse = client.get(BackendHttpRoutes.OPENAPI_DOCUMENT)
        val document = json.readTree(documentResponse.bodyAsText())
        assertEquals(HttpStatusCode.OK, documentResponse.status)
        assertTrue(documentResponse.contentType()?.match(ContentType.Application.Json) == true)
        assertEquals("3.1.1", document["openapi"].asText())
        assertEquals("Souz Backend API", document["info"]["title"].asText())
        assertEquals("1.0.0", document["info"]["version"].asText())

        val rootResponse = client.get(BackendHttpRoutes.ROOT)
        val root = json.readTree(rootResponse.bodyAsText())
        assertEquals(HttpStatusCode.OK, rootResponse.status)
        assertTrue(root["endpoints"].map { it.asText() }.contains("GET /docs"))
    }

    @Test
    fun `registered path and operation inventory is exact for both Telegram configurations`() {
        assertInventory(telegramEnabled = false)
        assertInventory(telegramEnabled = true)
    }

    @Test
    fun `all V1 operations require both proxy schemes and public system operations are unsecured`() =
        testApplication {
            installBackend(BackendFeatureFlags(telegramBot = true))
            val document = openApiDocument()
            val schemes = document["components"]["securitySchemes"]

            assertEquals(
                setOf("souzProxyAuth", "souzUserIdentity"),
                schemes.fieldNames().asSequence().toSet(),
            )
            assertApiKeyScheme(schemes["souzProxyAuth"], "X-Souz-Proxy-Auth")
            assertApiKeyScheme(schemes["souzUserIdentity"], "X-User-Id")

            operations(document).forEach { (path, _, operation) ->
                if (path.startsWith("/v1/")) {
                    val security = assertNotNull(operation["security"])
                    assertEquals(1, security.size())
                    assertEquals(
                        setOf("souzProxyAuth", "souzUserIdentity"),
                        security[0].fieldNames().asSequence().toSet(),
                    )
                    assertTrue(security[0]["souzProxyAuth"].isEmpty)
                    assertTrue(security[0]["souzUserIdentity"].isEmpty)
                    assertTrue(operation["responses"].has("401"), "$path must document proxy failures")
                    assertTrue(operation["responses"].has("500"), "$path must document internal failures")
                } else {
                    assertNull(operation["security"], "$path must remain public")
                }
            }
        }

    @Test
    fun `request parameters responses errors and write only secrets match runtime validation`() =
        testApplication {
            installBackend(BackendFeatureFlags(telegramBot = true))
            val document = openApiDocument()

            val chatLimit = parameter(document, "/v1/chats", "get", "limit")
            assertEquals(1.0, chatLimit["schema"]["minimum"].asDouble())
            assertEquals(DEFAULT_CHAT_LIMIT, chatLimit["schema"]["default"].asInt())
            assertFalse(chatLimit["schema"].has("maximum"))
            assertTrue(chatLimit["description"].asText().contains("clamped"))

            val messageBefore = parameter(document, "/v1/chats/{chatId}/messages", "get", "beforeSeq")
            val messageAfter = parameter(document, "/v1/chats/{chatId}/messages", "get", "afterSeq")
            val messageLimit = parameter(document, "/v1/chats/{chatId}/messages", "get", "limit")
            assertEquals(1.0, messageBefore["schema"]["minimum"].asDouble())
            assertEquals(1.0, messageAfter["schema"]["minimum"].asDouble())
            assertEquals(1.0, messageLimit["schema"]["minimum"].asDouble())
            assertEquals(DEFAULT_MESSAGE_LIMIT, messageLimit["schema"]["default"].asInt())
            assertFalse(messageLimit["schema"].has("maximum"))

            val eventAfter = parameter(document, "/v1/chats/{chatId}/events", "get", "afterSeq")
            val eventLimit = parameter(document, "/v1/chats/{chatId}/events", "get", "limit")
            assertEquals(0.0, eventAfter["schema"]["minimum"].asDouble())
            assertEquals(1.0, eventLimit["schema"]["minimum"].asDouble())
            assertEquals(DEFAULT_EVENT_LIMIT, eventLimit["schema"]["default"].asInt())
            assertFalse(eventLimit["schema"].has("maximum"))

            val includeArchived = parameter(document, "/v1/chats", "get", "includeArchived")
            assertEquals("boolean", includeArchived["schema"]["type"].asText())
            assertFalse(includeArchived["schema"]["default"].asBoolean())

            val chatId = parameter(document, "/v1/chats/{chatId}/messages", "get", "chatId")
            assertEquals("path", chatId["in"].asText())
            assertTrue(chatId["required"].asBoolean())
            assertEquals("uuid", chatId["schema"]["format"].asText())

            val provider = parameter(document, "/v1/me/provider-keys/{provider}", "put", "provider")
            assertEquals(
                setOf("giga", "qwen", "ai_tunnel", "anthropic", "openai", "local", "codex"),
                provider["schema"]["enum"].map { it.asText() }.toSet(),
            )

            val providerKeyBody = requestSchema(document, "/v1/me/provider-keys/{provider}", "put")
            val apiKey = providerKeyBody["properties"]["apiKey"]
            assertTrue(providerKeyBody["required"].map { it.asText() }.contains("apiKey"))
            assertTrue(apiKey["writeOnly"].asBoolean())
            assertEquals(1, apiKey["minLength"].asInt())
            assertEquals("\\S", apiKey["pattern"].asText())
            assertNoSecretExampleOrDefault(apiKey)

            val telegramBody = requestSchema(document, "/v1/chats/{chatId}/telegram-bot", "put")
            val token = telegramBody["properties"]["token"]
            assertTrue(telegramBody["required"].map { it.asText() }.contains("token"))
            assertTrue(token["writeOnly"].asBoolean())
            assertEquals(4_096, token["maxLength"].asInt())
            assertEquals("\\S", token["pattern"].asText())
            assertNoSecretExampleOrDefault(token)

            val settingsBody = requestSchema(document, "/v1/me/settings", "patch")
            assertTrue(settingsBody["properties"]["defaultModel"]["enum"].any(JsonNode::isNull))
            assertTrue(settingsBody["properties"]["interfaceLanguage"]["enum"].any(JsonNode::isNull))
            assertEquals("\\S", settingsBody["properties"]["locale"]["pattern"].asText())
            assertEquals("\\S", settingsBody["properties"]["timeZone"]["pattern"].asText())
            assertEquals("\\S", settingsBody["properties"]["enabledTools"]["items"]["pattern"].asText())
            assertEquals(
                LLMModel.entries.map { it.alias }.distinct(),
                settingsBody["properties"]["defaultModel"]["enum"]
                    .filterNot(JsonNode::isNull)
                    .map(JsonNode::asText),
            )

            val titleBody = requestSchema(document, "/v1/chats/{chatId}/title", "patch")
            assertEquals("\\S", titleBody["properties"]["title"]["pattern"].asText())

            val messageBody = requestSchema(document, "/v1/chats/{chatId}/messages", "post")
            assertTrue(messageBody["required"].map { it.asText() }.contains("content"))
            assertEquals(1, messageBody["properties"]["content"]["minLength"].asInt())
            assertEquals("\\S", messageBody["properties"]["content"]["pattern"].asText())
            val messageOptions = resolveSchema(document, messageBody["properties"]["options"])
            assertEquals(1.0, messageOptions["properties"]["contextSize"]["minimum"].asDouble())
            assertTrue(messageOptions["properties"]["model"]["enum"].any(JsonNode::isNull))
            assertEquals("\\S", messageOptions["properties"]["locale"]["pattern"].asText())
            assertEquals("\\S", messageOptions["properties"]["timeZone"]["pattern"].asText())

            val optionBody = requestSchema(document, "/v1/options/{optionId}/answer", "post")
            assertEquals("\\S", optionBody["properties"]["selectedOptionIds"]["items"]["pattern"].asText())
            assertTrue(optionBody["properties"]["metadata"]["description"].asText().contains("non-whitespace"))

            assertEquals(
                setOf("201", "400", "401", "500"),
                responseCodes(document, "/v1/chats", "post"),
            )
            val deleteKeyResponses = operation(document, "/v1/me/provider-keys/{provider}", "delete")["responses"]
            assertTrue(deleteKeyResponses.has("204"))
            assertFalse(deleteKeyResponses["204"].has("content"))
            assertTrue(operation(document, "/v1/chats/{chatId}/telegram-bot", "delete")["responses"].has("200"))
            assertTrue(operation(document, "/v1/chats/{chatId}/messages", "post")["responses"].has("409"))
            assertTrue(operation(document, "/v1/chats/{chatId}/events", "get")["responses"].has("404"))
            assertTrue(operation(document, "/v1/options/{optionId}/answer", "post")["responses"].has("404"))

            val legacyError = responseSchema(document, "/", "get", "500")
            assertEquals("string", legacyError["properties"]["error"]["type"].asText())
            val v1Error = responseSchema(document, "/v1/chats", "get", "500")
            val errorObject = resolveSchema(document, v1Error["properties"]["error"])
            assertEquals(setOf("code", "message"), errorObject["properties"].fieldNames().asSequence().toSet())
        }

    @Test
    fun `nullable response schemas preserve explicit wire nulls after component extraction`() =
        testApplication {
            installBackend(BackendFeatureFlags(telegramBot = true))
            val document = openApiDocument()

            val messages = responseSchema(document, "/v1/chats/{chatId}/messages", "get", "200")
            assertEquals(
                setOf("integer", "null"),
                schemaTypes(document, messages["properties"]["nextBeforeSeq"]),
            )

            val created = responseSchema(document, "/v1/chats/{chatId}/messages", "post", "200")
            assertEquals(
                setOf("object", "null"),
                schemaTypes(document, created["properties"]["assistantMessage"]),
            )
            assertEquals(
                setOf("object"),
                schemaTypes(document, created["properties"]["message"]),
            )
            val execution = resolveSchema(document, created["properties"]["execution"])
            assertEquals(
                setOf("object", "null"),
                schemaTypes(document, execution["properties"]["usage"]),
            )

            val telegram = responseSchema(document, "/v1/chats/{chatId}/telegram-bot", "get", "200")
            assertEquals(
                setOf("object", "null"),
                schemaTypes(document, telegram["properties"]["telegramBot"]),
            )
            assertEquals(
                setOf("string", "null"),
                schemaTypes(document, telegram["properties"]["pendingLinkCommand"]),
            )
        }

    @Test
    fun `durable replay keeps strict canonical variants and exposes a disjoint legacy fallback`() =
        testApplication {
            installBackend(BackendFeatureFlags())
            val document = openApiDocument()
            val schemas = document["components"]["schemas"]
            val durable = schemas[BackendEventOpenApiSchemas.DURABLE_EVENT]
            val delta = schemas[BackendEventOpenApiSchemas.MESSAGE_DELTA_EVENT]
            val expectedTypes = eventPayloadExpectations.keys

            assertEquals(11, durable["oneOf"].size())
            assertEquals(
                expectedTypes,
                durable["discriminator"]["mapping"].fieldNames().asSequence().toSet(),
            )
            assertEquals("type", durable["discriminator"]["propertyName"].asText())
            durable["discriminator"]["mapping"].properties().forEach { (eventType, reference) ->
                val variant = document.at(reference.asText().removePrefix("#"))
                val payloadExpectation = assertNotNull(eventPayloadExpectations[eventType])
                assertEquals(
                    setOf("seq", "durable", "chatId", "executionId", "type", "payload", "createdAt"),
                    variant["required"].map { it.asText() }.toSet(),
                )
                assertEquals(listOf(eventType), variant["properties"]["type"]["enum"].map { it.asText() })
                assertEquals(listOf(true), variant["properties"]["durable"]["enum"].map { it.asBoolean() })
                assertEquals(
                    "#/components/schemas/${payloadExpectation.componentName}",
                    variant["properties"]["payload"]["\$ref"].asText(),
                )
                assertEquals(
                    payloadExpectation.requiredFields,
                    schemas[payloadExpectation.componentName]["required"].map { it.asText() }.toSet(),
                    eventType,
                )
            }
            assertFalse(durable.toString().contains("message.delta"))
            assertEquals(listOf("message.delta"), delta["properties"]["type"]["enum"].map { it.asText() })
            assertEquals(listOf(false), delta["properties"]["durable"]["enum"].map { it.asBoolean() })
            assertEquals(
                setOf("seq", "durable", "chatId", "executionId", "type", "payload", "createdAt"),
                delta["required"].map { it.asText() }.toSet(),
            )

            val replayEvent = schemas[BackendEventOpenApiSchemas.REPLAY_EVENT]
            val legacy = schemas[BackendEventOpenApiSchemas.LEGACY_DURABLE_EVENT]
            assertEquals(
                setOf(
                    "#/components/schemas/${BackendEventOpenApiSchemas.DURABLE_EVENT}",
                    "#/components/schemas/${BackendEventOpenApiSchemas.LEGACY_DURABLE_EVENT}",
                ),
                replayEvent["oneOf"].map { it["\$ref"].asText() }.toSet(),
            )
            assertEquals(
                setOf("seq", "durable", "chatId", "executionId", "type", "payload", "createdAt"),
                legacy["required"].map { it.asText() }.toSet(),
            )
            assertEquals(1.0, legacy["properties"]["seq"]["minimum"].asDouble())
            assertEquals(listOf(true), legacy["properties"]["durable"]["enum"].map { it.asBoolean() })
            assertEquals(
                eventPayloadExpectations.keys + "message.delta",
                legacy["properties"]["type"]["enum"].map { it.asText() }.toSet(),
            )
            assertEquals("object", legacy["properties"]["payload"]["type"].asText())
            assertTrue(legacy["properties"]["payload"]["additionalProperties"].asBoolean())
            assertEquals(
                "#/components/schemas/${BackendEventOpenApiSchemas.DURABLE_EVENT}",
                legacy["not"]["\$ref"].asText(),
            )

            val replay = responseSchema(document, "/v1/chats/{chatId}/events", "get", "200")
            assertEquals(
                "#/components/schemas/${BackendEventOpenApiSchemas.REPLAY_EVENT}",
                replay["properties"]["items"]["items"]["\$ref"].asText(),
            )

            val finished = schemas["BackendExecutionFinishedEventPayload"]
            assertFalse(finished["properties"].has("usage"))
            assertTrue(finished["properties"].has("promptTokens"))
            assertTrue(finished["properties"].has("completionTokens"))
            assertTrue(finished["properties"].has("totalTokens"))
            assertTrue(finished["properties"].has("precachedTokens"))

            val toolStarted = schemas["BackendToolCallStartedEventPayload"]
            val toolFinished = schemas["BackendToolCallFinishedEventPayload"]
            val toolFailed = schemas["BackendToolCallFailedEventPayload"]
            assertFalse(toolStarted["properties"]["argumentsPreview"].has("type"))
            assertFalse(toolFinished["properties"]["resultPreview"].has("type"))
            assertEquals(listOf("finished"), toolFinished["properties"]["status"]["enum"].map { it.asText() })
            assertEquals(listOf("failed"), toolFailed["properties"]["status"]["enum"].map { it.asText() })

            val optionRequested = schemas["BackendOptionRequestedEventPayload"]
            val optionAnswered = schemas["BackendOptionAnsweredEventPayload"]
            val optionItem = schemas["BackendEventOptionItem"]
            assertTrue(optionRequested["required"].map { it.asText() }.contains("title"))
            assertTrue(optionAnswered["required"].map { it.asText() }.contains("freeText"))
            assertEquals(setOf("id", "label", "content"), optionItem["required"].map { it.asText() }.toSet())
            assertEquals(
                setOf("string", "null"),
                optionItem["properties"]["content"]["type"].map { it.asText() }.toSet(),
            )
            assertEquals("string", optionAnswered["properties"]["metadata"]["additionalProperties"]["type"].asText())
        }

    private fun ApplicationTestBuilder.installBackend(featureFlags: BackendFeatureFlags) {
        val context = routeTestContext(featureFlags = featureFlags)
        application {
            backendApplication(
                BackendHttpDependencies(
                    bootstrapService = context.bootstrapService,
                    featureFlags = featureFlags,
                    selectedModel = { context.settingsProvider.gigaModel.alias },
                    trustedProxyToken = { "proxy-secret" },
                )
            )
        }
    }

    private suspend fun ApplicationTestBuilder.openApiDocument(): JsonNode {
        val response = client.get(BackendHttpRoutes.OPENAPI_DOCUMENT)
        assertEquals(HttpStatusCode.OK, response.status)
        return json.readTree(response.bodyAsText())
    }

    private fun assertInventory(telegramEnabled: Boolean) = testApplication {
        installBackend(BackendFeatureFlags(telegramBot = telegramEnabled))
        val document = openApiDocument()
        val expected = expectedOperations(telegramEnabled)
        val actual = document["paths"].properties().asSequence().associate { (path, pathItem) ->
            path to pathItem.fieldNames().asSequence().filter(httpMethods::contains).toSet()
        }

        assertEquals(expected, actual)
        assertEquals(if (telegramEnabled) 18 else 17, actual.size)
        assertEquals(if (telegramEnabled) 24 else 21, actual.values.sumOf(Set<String>::size))
        assertFalse(actual.containsKey(BackendHttpRoutes.DOCS))
        assertFalse(actual.containsKey(BackendHttpRoutes.OPENAPI_DOCUMENT))
        assertFalse(actual.containsKey(BackendHttpRoutes.CHAT_WS_PATTERN))

        val operations = operations(document)
        val operationIds = operations.map { it.third["operationId"].asText() }
        val expectedMetadata = expectedOperationMetadata(telegramEnabled)
        assertEquals(operationIds.size, operationIds.toSet().size)
        assertEquals(expectedMetadata.keys, operationIds.toSet())
        operations.forEach { (_, _, operation) ->
            val operationId = operation["operationId"].asText()
            val expectedOperation = assertNotNull(expectedMetadata[operationId])
            assertEquals(listOf(expectedOperation.tag), operation["tags"].map { it.asText() }, operationId)
            assertEquals(
                expectedOperation.responseCodes,
                operation["responses"].fieldNames().asSequence().toSet(),
                operationId,
            )
        }
    }

    private fun assertApiKeyScheme(scheme: JsonNode, headerName: String) {
        assertEquals("apiKey", scheme["type"].asText())
        assertEquals("header", scheme["in"].asText())
        assertEquals(headerName, scheme["name"].asText())
        assertTrue(scheme["description"].asText().contains("Proxy-injected"))
        assertFalse(scheme.has("default"))
        assertFalse(scheme.has("example"))
        assertFalse(scheme.has("examples"))
    }

    private fun assertNoSecretExampleOrDefault(schema: JsonNode) {
        assertFalse(schema.has("default"))
        assertFalse(schema.has("example"))
        assertFalse(schema.has("examples"))
    }

    private companion object {
        val httpMethods = setOf("get", "post", "put", "patch", "delete")

        val eventPayloadExpectations = linkedMapOf(
            "message.created" to EventPayloadExpectation(
                "BackendMessageCreatedEventPayload",
                setOf("messageId", "seq", "role", "content"),
            ),
            "message.completed" to EventPayloadExpectation(
                "BackendMessageCompletedEventPayload",
                setOf("messageId", "seq", "role", "content"),
            ),
            "execution.started" to EventPayloadExpectation(
                "BackendExecutionStartedEventPayload",
                setOf("executionId", "streamingMessages"),
            ),
            "execution.finished" to EventPayloadExpectation(
                "BackendExecutionFinishedEventPayload",
                setOf("executionId", "status"),
            ),
            "execution.failed" to EventPayloadExpectation(
                "BackendExecutionFailedEventPayload",
                setOf("executionId", "errorCode", "errorMessage"),
            ),
            "execution.cancelled" to EventPayloadExpectation(
                "BackendExecutionCancelledEventPayload",
                setOf("executionId"),
            ),
            "tool.call.started" to EventPayloadExpectation(
                "BackendToolCallStartedEventPayload",
                setOf("toolCallId", "name", "argumentKeys"),
            ),
            "tool.call.finished" to EventPayloadExpectation(
                "BackendToolCallFinishedEventPayload",
                setOf("toolCallId", "name", "status"),
            ),
            "tool.call.failed" to EventPayloadExpectation(
                "BackendToolCallFailedEventPayload",
                setOf("toolCallId", "name", "status", "error"),
            ),
            "option.requested" to EventPayloadExpectation(
                "BackendOptionRequestedEventPayload",
                setOf("optionId", "kind", "title", "selectionMode", "options"),
            ),
            "option.answered" to EventPayloadExpectation(
                "BackendOptionAnsweredEventPayload",
                setOf("optionId", "status", "selectedOptionIds", "freeText", "metadata"),
            ),
        )

        fun expectedOperations(telegramEnabled: Boolean): Map<String, Set<String>> =
            linkedMapOf(
                "/" to setOf("get"),
                "/health" to setOf("get"),
                "/v1/bootstrap" to setOf("get"),
                "/v1/onboarding/state" to setOf("get"),
                "/v1/onboarding/complete" to setOf("post"),
                "/v1/me/settings" to setOf("get", "patch"),
                "/v1/me/provider-keys" to setOf("get"),
                "/v1/me/provider-keys/{provider}" to setOf("put", "delete"),
                "/v1/chats" to setOf("get", "post"),
                "/v1/chats/{chatId}/title" to setOf("patch"),
                "/v1/chats/{chatId}/archive" to setOf("post"),
                "/v1/chats/{chatId}/unarchive" to setOf("post"),
                "/v1/chats/{chatId}/messages" to setOf("get", "post"),
                "/v1/chats/{chatId}/events" to setOf("get"),
                "/v1/chats/{chatId}/cancel-active" to setOf("post"),
                "/v1/chats/{chatId}/executions/{executionId}/cancel" to setOf("post"),
                "/v1/options/{optionId}/answer" to setOf("post"),
            ) + if (telegramEnabled) {
                mapOf("/v1/chats/{chatId}/telegram-bot" to setOf("get", "put", "delete"))
            } else {
                emptyMap()
            }

        fun expectedOperationMetadata(telegramEnabled: Boolean): Map<String, OperationExpectation> =
            linkedMapOf(
                "getRoot" to OperationExpectation("System", setOf("200", "500")),
                "getHealth" to OperationExpectation("System", setOf("200", "500")),
                "getBootstrap" to v1Expectation("Bootstrap", "200"),
                "getOnboardingState" to v1Expectation("Onboarding", "200"),
                "completeOnboarding" to v1Expectation("Onboarding", "200", "400", "409"),
                "getUserSettings" to v1Expectation("Settings", "200"),
                "patchUserSettings" to v1Expectation("Settings", "200", "400"),
                "listProviderKeys" to v1Expectation("Provider Keys", "200"),
                "putProviderKey" to v1Expectation("Provider Keys", "200", "400"),
                "deleteProviderKey" to v1Expectation("Provider Keys", "204", "400"),
                "listChats" to v1Expectation("Chats", "200", "400"),
                "createChat" to v1Expectation("Chats", "201", "400"),
                "updateChatTitle" to v1Expectation("Chats", "200", "400", "404"),
                "archiveChat" to v1Expectation("Chats", "200", "400", "404"),
                "unarchiveChat" to v1Expectation("Chats", "200", "400", "404"),
                "listChatMessages" to v1Expectation("Messages", "200", "400", "404"),
                "createChatMessage" to v1Expectation("Messages", "200", "400", "404", "409"),
                "listChatEvents" to v1Expectation("Events", "200", "400", "404"),
                "cancelActiveExecution" to v1Expectation("Executions", "200", "400", "404"),
                "cancelExecution" to v1Expectation("Executions", "200", "400", "404"),
                "answerOption" to v1Expectation("Options", "200", "400", "404"),
            ) + if (telegramEnabled) {
                mapOf(
                    "getTelegramBotBinding" to v1Expectation("Telegram", "200", "400", "404"),
                    "upsertTelegramBotBinding" to v1Expectation("Telegram", "200", "400", "404", "409"),
                    "deleteTelegramBotBinding" to v1Expectation("Telegram", "200", "400", "404"),
                )
            } else {
                emptyMap()
            }

        fun v1Expectation(
            tag: String,
            successCode: String,
            vararg routeSpecificCodes: String,
        ): OperationExpectation =
            OperationExpectation(
                tag = tag,
                responseCodes = setOf(successCode, "401", "500") + routeSpecificCodes,
            )

        fun operations(document: JsonNode): List<Triple<String, String, JsonNode>> =
            document["paths"].properties().asSequence().flatMap { (path, pathItem) ->
                pathItem.properties().asSequence()
                    .filter { (method, _) -> method in httpMethods }
                    .map { (method, operation) -> Triple(path, method, operation) }
            }.toList()

        fun operation(document: JsonNode, path: String, method: String): JsonNode =
            assertNotNull(document["paths"][path][method], "$method $path is undocumented")

        fun parameter(document: JsonNode, path: String, method: String, name: String): JsonNode =
            assertNotNull(
                operation(document, path, method)["parameters"].firstOrNull { it["name"].asText() == name },
                "$name is undocumented for $method $path",
            )

        fun requestSchema(document: JsonNode, path: String, method: String): JsonNode =
            resolveSchema(
                document,
                operation(document, path, method)["requestBody"]["content"]["application/json"]["schema"],
            )

        fun responseSchema(document: JsonNode, path: String, method: String, status: String): JsonNode =
            resolveSchema(
                document,
                operation(document, path, method)["responses"][status]["content"]["application/json"]["schema"],
            )

        fun responseCodes(document: JsonNode, path: String, method: String): Set<String> =
            operation(document, path, method)["responses"].fieldNames().asSequence().toSet()

        fun resolveSchema(document: JsonNode, schema: JsonNode): JsonNode {
            schema["\$ref"]?.let { reference ->
                return document.at(reference.asText().removePrefix("#"))
            }
            if (schema["allOf"]?.size() == 1) {
                return resolveSchema(document, schema["allOf"][0])
            }
            return schema
        }

        fun schemaTypes(
            document: JsonNode,
            schema: JsonNode,
            visitedReferences: MutableSet<String> = mutableSetOf(),
        ): Set<String> = buildSet {
            schema["\$ref"]?.asText()?.let { reference ->
                if (visitedReferences.add(reference)) {
                    addAll(
                        schemaTypes(
                            document = document,
                            schema = document.at(reference.removePrefix("#")),
                            visitedReferences = visitedReferences,
                        )
                    )
                }
            }

            schema["type"]?.let { type ->
                when {
                    type.isTextual -> add(type.asText())
                    type.isArray -> addAll(type.map(JsonNode::asText))
                }
            }

            listOf("anyOf", "oneOf", "allOf").forEach { keyword ->
                schema[keyword]?.forEach { branch ->
                    addAll(schemaTypes(document, branch, visitedReferences))
                }
            }
        }

        data class OperationExpectation(
            val tag: String,
            val responseCodes: Set<String>,
        )

        data class EventPayloadExpectation(
            val componentName: String,
            val requiredFields: Set<String>,
        )
    }
}
