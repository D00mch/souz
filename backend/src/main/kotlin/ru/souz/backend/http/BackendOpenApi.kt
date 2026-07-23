package ru.souz.backend.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.GenericElement
import io.ktor.openapi.JsonSchema
import io.ktor.openapi.JsonSchemaInference
import io.ktor.openapi.JsonType
import io.ktor.openapi.Operation
import io.ktor.openapi.Parameters
import io.ktor.openapi.ReferenceOr
import io.ktor.openapi.RequestBody
import io.ktor.openapi.Responses
import io.ktor.openapi.jsonSchema
import io.ktor.server.routing.Route
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider

internal object BackendOpenApiTags {
    const val SYSTEM = "System"
    const val BOOTSTRAP = "Bootstrap"
    const val ONBOARDING = "Onboarding"
    const val SETTINGS = "Settings"
    const val PROVIDER_KEYS = "Provider Keys"
    const val CHATS = "Chats"
    const val MESSAGES = "Messages"
    const val EVENTS = "Events"
    const val EXECUTIONS = "Executions"
    const val OPTIONS = "Options"
    const val PERMISSIONS = "Permissions"
    const val TELEGRAM = "Telegram"
}

internal object BackendOpenApiSecurity {
    const val PROXY_AUTH_SCHEME = "souzProxyAuth"
    const val USER_IDENTITY_SCHEME = "souzUserIdentity"
    const val PROXY_AUTH_HEADER = "X-Souz-Proxy-Auth"
    const val USER_IDENTITY_HEADER = "X-User-Id"
}

/** Adds the metadata shared by every trusted-proxy `/v1` operation. */
@OptIn(ExperimentalKtorApi::class)
internal fun Route.describeV1(
    operationId: String,
    tag: String,
    summary: String,
    description: String? = null,
    configure: Operation.Builder.() -> Unit,
): Route =
    describe {
        this.operationId = operationId
        this.summary = summary
        this.description = description
        tag(tag)
        security {
            requirement(
                mapOf(
                    BackendOpenApiSecurity.PROXY_AUTH_SCHEME to emptyList(),
                    BackendOpenApiSecurity.USER_IDENTITY_SCHEME to emptyList(),
                )
            )
        }
        configure()
    }

/** Adds metadata for a public operation without inheriting the `/v1` security requirement. */
@OptIn(ExperimentalKtorApi::class)
internal fun Route.describePublic(
    operationId: String,
    tag: String,
    summary: String,
    description: String? = null,
    configure: Operation.Builder.() -> Unit,
): Route =
    describe {
        this.operationId = operationId
        this.summary = summary
        this.description = description
        tag(tag)
        configure()
    }

internal inline fun <reified T : Any> RequestBody.Builder.jsonBody(
    description: String? = null,
    noinline schemaTransform: (JsonSchema) -> JsonSchema = { it },
) {
    required = true
    this.description = description
    ContentType.Application.Json {
        schema = schemaTransform(jsonSchema<T>())
    }
}

internal inline fun <reified T : Any> Responses.Builder.jsonResponse(
    status: HttpStatusCode,
    description: String,
    noinline schemaTransform: (JsonSchema) -> JsonSchema = { it },
) {
    response(status.value) {
        this.description = description
        ContentType.Application.Json {
            schema = schemaTransform(jsonSchema<T>())
        }
    }
}

internal fun Responses.Builder.jsonResponse(
    status: HttpStatusCode,
    description: String,
    schema: JsonSchema,
) {
    response(status.value) {
        this.description = description
        ContentType.Application.Json {
            this.schema = schema
        }
    }
}

internal fun Responses.Builder.emptyResponse(
    status: HttpStatusCode,
    description: String,
) {
    response(status.value) {
        this.description = description
    }
}

/** Adds common 401/500 responses plus any route-specific V1 error statuses. */
internal fun Responses.Builder.v1ErrorResponses(vararg routeSpecific: HttpStatusCode) {
    buildSet {
        add(HttpStatusCode.Unauthorized)
        add(HttpStatusCode.InternalServerError)
        addAll(routeSpecific)
    }.sortedBy(HttpStatusCode::value).forEach { status ->
        jsonResponse<BackendV1ErrorEnvelope>(
            status = status,
            description = status.v1ErrorDescription(),
        )
    }
}

internal fun Responses.Builder.legacyErrorResponse() {
    jsonResponse<ErrorResponse>(
        status = HttpStatusCode.InternalServerError,
        description = "Internal server error.",
    )
}

internal fun Parameters.Builder.uuidPathParameter(
    name: String,
    description: String,
) {
    path(name) {
        this.description = description
        schema = JsonSchema(type = JsonType.STRING, format = "uuid")
    }
}

internal fun Parameters.Builder.providerPathParameter() {
    path("provider") {
        description =
            "LLM provider identifier. Values are case-insensitive; hyphens are accepted in place of underscores."
        schema = JsonSchema(
            type = JsonType.STRING,
            enum = LlmProvider.entries.map { GenericElement(it.name.lowercase()) },
        )
    }
}

internal fun Parameters.Builder.positiveIntQueryParameter(
    name: String,
    description: String,
    defaultValue: Int? = null,
) {
    query(name) {
        this.description = description
        required = false
        schema = JsonSchema(
            type = JsonType.INTEGER,
            minimum = 1.0,
            default = defaultValue?.let(::GenericElement),
        )
    }
}

internal fun Parameters.Builder.positiveLongQueryParameter(
    name: String,
    description: String,
) {
    query(name) {
        this.description = description
        required = false
        schema = JsonSchema(type = JsonType.INTEGER, minimum = 1.0)
    }
}

internal fun Parameters.Builder.nonNegativeLongQueryParameter(
    name: String,
    description: String,
) {
    query(name) {
        this.description = description
        required = false
        schema = JsonSchema(type = JsonType.INTEGER, minimum = 0.0)
    }
}

internal fun Parameters.Builder.strictBooleanQueryParameter(
    name: String,
    description: String,
    defaultValue: Boolean,
) {
    query(name) {
        this.description = description
        required = false
        schema = JsonSchema(
            type = JsonType.BOOLEAN,
            default = GenericElement(defaultValue),
        )
    }
}

/** Explicit schema corrections for validation and wire details that reflection cannot discover. */
internal object BackendOpenApiSchemas {
    fun messagesResponse(schema: JsonSchema): JsonSchema =
        schema
            .withoutRequiredProperty("nextBeforeSeq")
            .withProperty("nextBeforeSeq") { nextBeforeSeq ->
                nextBeforeSeq.copy(
                    type = JsonSchema.SchemaType.AnyOf(listOf(JsonType.INTEGER, JsonType.NULL)),
                )
            }

    fun createMessageResponse(schema: JsonSchema): JsonSchema =
        schema.withOptionalNullableObject("assistantMessage")

    fun settingsPatch(schema: JsonSchema): JsonSchema =
        schema
            .withModelAliases("defaultModel")
            .withPositiveNumber("contextSize", "Must be a positive integer.")
            .withPropertyDescription("temperature", "Must be a finite JSON number.")
            .withNonBlankString("locale", "A recognized BCP 47 language tag.")
            .withNonBlankString("timeZone", "An IANA time-zone identifier.")
            .withNonBlankArrayItems("enabledTools")
            .withStringEnum("interfaceLanguage", listOf("en", "ru"))
            .withMinimum("requestTimeoutMillis", 1_000.0, "Must be at least 1000 milliseconds.")

    fun onboardingComplete(schema: JsonSchema): JsonSchema =
        schema
            .withModelAliases("defaultModel")
            .withNonBlankString("locale", "A recognized BCP 47 language tag.")
            .withNonBlankString("timeZone", "An IANA time-zone identifier.")
            .withNonBlankArrayItems("enabledTools")
            .withStringEnum("interfaceLanguage", listOf("en", "ru"))
            .withMinimum("requestTimeoutMillis", 1_000.0, "Must be at least 1000 milliseconds.")

    fun providerKey(schema: JsonSchema): JsonSchema =
        schema
            .copy(required = listOf("apiKey"))
            .withNonBlankString("apiKey", "Provider API key.", writeOnly = true)

    fun updateChatTitle(schema: JsonSchema): JsonSchema =
        schema
            .copy(required = listOf("title"))
            .withNonBlankString("title", "New non-blank chat title.")

    fun createMessage(schema: JsonSchema): JsonSchema =
        schema
            .copy(required = listOf("content"))
            .withNonBlankString("content", "Non-blank user message content.")
            .withProperty("options") { options ->
                options
                    .withModelAliases("model")
                    .withPositiveNumber("contextSize", "Must be a positive integer.")
                    .withPropertyDescription("temperature", "Must be a finite JSON number.")
                    .withNonBlankString("locale", "A recognized BCP 47 language tag.")
                    .withNonBlankString("timeZone", "An IANA time-zone identifier.")
            }

    fun answerOption(schema: JsonSchema): JsonSchema =
        schema
            .copy(required = listOf("selectedOptionIds"))
            .withProperty("selectedOptionIds") { selected ->
                selected.copy(
                    minItems = 1,
                    items = selected.items?.mapSchema { item ->
                        item.copy(minLength = 1, pattern = NON_BLANK_PATTERN)
                    },
                )
            }
            .withPropertyDescription(
                "metadata",
                "String metadata. Keys must contain at least one non-whitespace character.",
            )

    fun telegramToken(schema: JsonSchema): JsonSchema =
        schema
            .copy(required = listOf("token"))
            .withProperty("token") { token ->
                token.copy(
                    type = JsonType.STRING,
                    description = "Telegram bot token.",
                    minLength = 1,
                    pattern = NON_BLANK_PATTERN,
                    maxLength = 4_096,
                    writeOnly = true,
                    default = null,
                    example = null,
                    examples = null,
                )
            }

    fun permissionDecision(schema: JsonSchema): JsonSchema =
        schema
            .copy(required = listOf("decision"))
            .withStringEnum("decision", listOf("grant", "deny"))
}

private fun HttpStatusCode.v1ErrorDescription(): String =
    when (this) {
        HttpStatusCode.BadRequest -> "The request path, query, content type, or JSON body is invalid."
        HttpStatusCode.Unauthorized -> "Trusted proxy authentication or user identity is missing or invalid."
        HttpStatusCode.NotFound -> "The resource does not exist or the requested feature is disabled."
        HttpStatusCode.Conflict -> "The request conflicts with the current resource or execution state."
        HttpStatusCode.InternalServerError -> "The backend is unavailable or the request failed internally."
        else -> description
    }

private fun JsonSchema.withModelAliases(propertyName: String): JsonSchema =
    withProperty(propertyName) { property ->
        property.copy(
            description = "Known Souz model alias.",
            enum = property.enumValuesPreservingNull(LLMModel.entries.map { it.alias }.distinct()),
        )
    }

private fun JsonSchema.withPositiveNumber(
    propertyName: String,
    description: String,
): JsonSchema =
    withMinimum(propertyName, 1.0, description)

private fun JsonSchema.withMinimum(
    propertyName: String,
    minimum: Double,
    description: String,
): JsonSchema =
    withProperty(propertyName) { property ->
        property.copy(minimum = minimum, description = description)
    }

private fun JsonSchema.withNonBlankString(
    propertyName: String,
    description: String,
    writeOnly: Boolean? = null,
): JsonSchema =
    withProperty(propertyName) { property ->
        property.copy(
            description = description,
            minLength = 1,
            pattern = NON_BLANK_PATTERN,
            writeOnly = writeOnly ?: property.writeOnly,
            default = null,
            example = null,
            examples = null,
        )
    }

private fun JsonSchema.withNonBlankArrayItems(propertyName: String): JsonSchema =
    withProperty(propertyName) { property ->
        property.copy(
            items = property.items?.mapSchema { item ->
                item.copy(minLength = 1, pattern = NON_BLANK_PATTERN)
            },
        )
    }

private fun JsonSchema.withStringEnum(
    propertyName: String,
    values: List<String>,
): JsonSchema =
    withProperty(propertyName) { property ->
        property.copy(enum = property.enumValuesPreservingNull(values))
    }

private fun JsonSchema.enumValuesPreservingNull(values: List<String>): List<GenericElement?> =
    values.map(::GenericElement) + if (allowsNull()) listOf(null) else emptyList()

private fun JsonSchema.allowsNull(): Boolean =
    when (val schemaType = type) {
        JsonType.NULL -> true
        is JsonSchema.SchemaType.AnyOf -> JsonType.NULL in schemaType.types
        else -> anyOf.orEmpty().any { candidate ->
            candidate is ReferenceOr.Value && candidate.value.allowsNull()
        }
    }

private fun JsonSchema.withPropertyDescription(
    propertyName: String,
    description: String,
): JsonSchema =
    withProperty(propertyName) { property -> property.copy(description = description) }

private fun JsonSchema.withoutRequiredProperty(name: String): JsonSchema =
    copy(required = required?.filterNot { it == name }?.takeIf { it.isNotEmpty() })

private fun JsonSchema.withOptionalNullableObject(name: String): JsonSchema =
    withoutRequiredProperty(name).copy(
        properties = properties?.mapValues { (propertyName, propertySchema) ->
            if (propertyName == name) propertySchema.asTitlelessNullableWrapper() else propertySchema
        }
    )

private fun ReferenceOr<JsonSchema>.asTitlelessNullableWrapper(): ReferenceOr<JsonSchema> =
    ReferenceOr.Value(
        JsonSchema(
            anyOf = listOf(
                withoutNullability(),
                ReferenceOr.Value(JsonSchema(type = JsonType.NULL)),
            )
        )
    )

private fun ReferenceOr<JsonSchema>.withoutNullability(): ReferenceOr<JsonSchema> =
    when (this) {
        is ReferenceOr.Reference -> this
        is ReferenceOr.Value -> ReferenceOr.Value(value.withoutNullability())
    }

private fun JsonSchema.withoutNullability(): JsonSchema =
    copy(
        type = when (val schemaType = type) {
            is JsonSchema.SchemaType.AnyOf -> schemaType.types
                .filterNot { it == JsonType.NULL }
                .let { nonNullTypes ->
                    when (nonNullTypes.size) {
                        0 -> null
                        1 -> nonNullTypes.single()
                        else -> JsonSchema.SchemaType.AnyOf(nonNullTypes)
                    }
                }

            JsonType.NULL -> null
            else -> schemaType
        },
        anyOf = anyOf?.filterNot(ReferenceOr<JsonSchema>::isNullSchema)?.takeIf { it.isNotEmpty() },
        oneOf = oneOf?.filterNot(ReferenceOr<JsonSchema>::isNullSchema)?.takeIf { it.isNotEmpty() },
    )

private fun ReferenceOr<JsonSchema>.isNullSchema(): Boolean =
    this is ReferenceOr.Value && value.type == JsonType.NULL

private fun JsonSchema.withProperty(
    name: String,
    transform: (JsonSchema) -> JsonSchema,
): JsonSchema =
    copy(
        properties = properties?.mapValues { (propertyName, propertySchema) ->
            if (propertyName == name) propertySchema.mapSchema(transform) else propertySchema
        }
    )

private fun ReferenceOr<JsonSchema>.mapSchema(
    transform: (JsonSchema) -> JsonSchema,
): ReferenceOr<JsonSchema> =
    when (this) {
        is ReferenceOr.Reference -> this
        is ReferenceOr.Value -> ReferenceOr.Value(transform(value))
    }

private const val NON_BLANK_PATTERN = "\\S"
