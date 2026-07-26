package ru.souz.tool.knowledge

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException
import java.math.BigInteger
import kotlinx.coroutines.CancellationException
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.knowledge.KnowledgeContent
import ru.souz.agent.knowledge.KnowledgeEntry
import ru.souz.agent.knowledge.KnowledgeStoreUnavailableException
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper

/** Reads complete or selected retained content from conversation-scoped Knowledge. */
class ToolGetKnowledge internal constructor(
    private val knowledgeStore: ConversationKnowledgeStore,
) : LLMToolSetup {
    data class Input(
        val knowledgeId: String,
        val regex: String? = null,
        val charsBefore: Int? = null,
        val charsAfter: Int? = null,
        val maxMatches: Int? = null,
    )

    override val fn: LLMRequest.Function = LLMRequest.Function(
        name = NAME,
        description = "Read a temporary Knowledge entry by ID, or find retained text with a case-sensitive RE2 regular expression. Backreferences and lookaround are unsupported. Offsets use UTF-16 indices and end offsets are exclusive.",
        parameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf(
                "knowledgeId" to LLMRequest.Property(
                    type = "string",
                    description = "Opaque Knowledge ID from a tool result in this conversation.",
                ),
                "regex" to LLMRequest.Property(
                    type = "string",
                    description = "Optional RE2 regular expression without backreferences or lookaround. Matching is case-sensitive unless changed with inline flags.",
                ),
                "charsBefore" to LLMRequest.Property(
                    type = "integer",
                    description = "UTF-16 context units before each match (default 256, range 0..4096). Requires regex.",
                ),
                "charsAfter" to LLMRequest.Property(
                    type = "integer",
                    description = "UTF-16 context units after each match (default 256, range 0..4096). Requires regex.",
                ),
                "maxMatches" to LLMRequest.Property(
                    type = "integer",
                    description = "Maximum matches to return (default 20, range 1..100). Requires regex.",
                ),
            ),
            required = listOf("knowledgeId"),
        ),
        returnParameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf(
                "knowledgeId" to LLMRequest.Property("string", "The requested Knowledge ID."),
                "sourceTool" to LLMRequest.Property("string", "Tool that produced the stored result."),
                "originalLength" to LLMRequest.Property("integer", "Original UTF-16 content length."),
                "storedLength" to LLMRequest.Property("integer", "Retained UTF-16 content length."),
                "truncated" to LLMRequest.Property("boolean", "Whether the storage omitted a middle range."),
                "text" to LLMRequest.Property("string", "Complete retained text for an untruncated full read."),
                "head" to LLMRequest.Property("object", "Retained head text and its original UTF-16 range."),
                "tail" to LLMRequest.Property("object", "Retained tail text and its original UTF-16 range."),
                "omitted" to LLMRequest.Property("object", "Omitted original UTF-16 range."),
                "matches" to LLMRequest.Property("array", "Regex matches and their retained excerpts."),
                "error" to LLMRequest.Property("object", "A structured retrieval error."),
            ),
        ),
    )

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
        invoke(functionCall, ToolInvocationMeta.localDefault())

    override suspend fun invoke(
        functionCall: LLMResponse.FunctionCall,
        meta: ToolInvocationMeta,
    ): LLMRequest.Message {
        val input = try {
            functionCall.arguments.toInput()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return errorMessage(
                functionCall.name,
                INVALID_ARGUMENTS,
                error.message ?: "GetKnowledge arguments are invalid.",
            )
        }

        validate(input)?.let { message ->
            return errorMessage(functionCall.name, INVALID_ARGUMENTS, message)
        }

        val pattern = input.regex?.let { regex ->
            try {
                Pattern.compile(regex)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: PatternSyntaxException) {
                return errorMessage(
                    functionCall.name,
                    INVALID_REGEX,
                    error.message ?: "The RE2 regular expression is invalid.",
                )
            } catch (error: IllegalArgumentException) {
                return errorMessage(
                    functionCall.name,
                    INVALID_REGEX,
                    error.message ?: "The RE2 regular expression is invalid.",
                )
            }
        }

        val knowledgeId = input.knowledgeId.trim()
        val entry = try {
            knowledgeStore.get(meta, knowledgeId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: KnowledgeStoreUnavailableException) {
            return errorMessage(
                functionCall.name,
                CONVERSATION_UNAVAILABLE,
                error.message ?: "Knowledge requires an available conversation scope.",
            )
        } catch (error: Exception) {
            return errorMessage(
                functionCall.name,
                STORAGE_FAILURE,
                error.message ?: "Knowledge storage failed.",
            )
        } ?: return errorMessage(
            functionCall.name,
            KNOWLEDGE_NOT_FOUND,
            "Knowledge is unavailable in this conversation: $knowledgeId",
        )

        val response = if (pattern == null) {
            entry.fullResponse()
        } else {
            entry.regexResponse(
                pattern = pattern,
                charsBefore = input.charsBefore ?: DEFAULT_CONTEXT_CHARS,
                charsAfter = input.charsAfter ?: DEFAULT_CONTEXT_CHARS,
                maxMatches = input.maxMatches ?: DEFAULT_MAX_MATCHES,
            )
        }
        return functionMessage(functionCall.name, response)
    }

    private fun Map<String, Any>.toInput(): Input {
        val unknownArguments = keys - INPUT_ARGUMENT_NAMES
        if (unknownArguments.isNotEmpty()) {
            throw IllegalArgumentException("Unknown GetKnowledge arguments: ${unknownArguments.sorted().joinToString()}.")
        }
        return Input(
            knowledgeId = requiredString("knowledgeId"),
            regex = optionalString("regex"),
            charsBefore = optionalInt("charsBefore"),
            charsAfter = optionalInt("charsAfter"),
            maxMatches = optionalInt("maxMatches"),
        )
    }

    private fun Map<String, Any>.requiredString(name: String): String =
        this[name] as? String ?: throw IllegalArgumentException("$name must be a string.")

    private fun Map<String, Any>.optionalString(name: String): String? {
        if (!containsKey(name)) return null
        return this[name] as? String ?: throw IllegalArgumentException("$name must be a string.")
    }

    private fun Map<String, Any>.optionalInt(name: String): Int? {
        if (!containsKey(name)) return null
        return when (val value = this[name]) {
            is Byte -> value.toInt()
            is Short -> value.toInt()
            is Int -> value
            is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            is BigInteger -> value.takeIf { it in MIN_INPUT_INTEGER..MAX_INPUT_INTEGER }?.toInt()
            else -> null
        } ?: throw IllegalArgumentException("$name must be an integer.")
    }

    private fun validate(input: Input): String? {
        if (input.knowledgeId.isBlank()) {
            return "knowledgeId must not be blank."
        }
        if (input.regex == null &&
            (input.charsBefore != null || input.charsAfter != null || input.maxMatches != null)
        ) {
            return "charsBefore, charsAfter, and maxMatches require regex."
        }
        if (input.charsBefore != null && input.charsBefore !in MIN_CONTEXT_CHARS..MAX_CONTEXT_CHARS) {
            return "charsBefore must be between $MIN_CONTEXT_CHARS and $MAX_CONTEXT_CHARS."
        }
        if (input.charsAfter != null && input.charsAfter !in MIN_CONTEXT_CHARS..MAX_CONTEXT_CHARS) {
            return "charsAfter must be between $MIN_CONTEXT_CHARS and $MAX_CONTEXT_CHARS."
        }
        if (input.maxMatches != null && input.maxMatches !in MIN_MATCHES..MAX_MATCHES) {
            return "maxMatches must be between $MIN_MATCHES and $MAX_MATCHES."
        }
        return null
    }

    private fun KnowledgeEntry.fullResponse(): Map<String, Any> = when (val retained = content) {
        is KnowledgeContent.Complete -> metadata() + ("text" to retained.content)
        is KnowledgeContent.Truncated -> {
            val tailStart = originalLength - retained.tail.length
            metadata() + mapOf(
                "head" to RetainedSegment(
                    text = retained.head,
                    start = 0,
                    end = retained.head.length,
                ),
                "tail" to RetainedSegment(
                    text = retained.tail,
                    start = tailStart,
                    end = originalLength,
                ),
                "omitted" to OffsetRange(
                    start = retained.head.length,
                    end = tailStart,
                ),
            )
        }
    }

    private fun KnowledgeEntry.regexResponse(
        pattern: Pattern,
        charsBefore: Int,
        charsAfter: Int,
        maxMatches: Int,
    ): Map<String, Any> {
        val retainedSegments = when (val retained = content) {
            is KnowledgeContent.Complete -> listOf(SearchSegment(retained.content, originalStart = 0))
            is KnowledgeContent.Truncated -> listOf(
                SearchSegment(retained.head, originalStart = 0),
                SearchSegment(retained.tail, originalStart = originalLength - retained.tail.length),
            )
        }
        val matches = buildList {
            for ((text, originalStart) in retainedSegments) {
                val matcher = pattern.matcher(text)
                while (size < maxMatches && matcher.find()) {
                    val localMatchStart = matcher.start()
                    val localMatchEnd = matcher.end()
                    val localExcerptStart = text.safeExcerptStart(localMatchStart, charsBefore)
                    val localExcerptEnd = text.safeExcerptEnd(localMatchEnd, charsAfter)
                    add(
                        KnowledgeMatch(
                            text = matcher.group(),
                            start = originalStart + localMatchStart,
                            end = originalStart + localMatchEnd,
                            excerpt = text.substring(localExcerptStart, localExcerptEnd),
                            excerptStart = originalStart + localExcerptStart,
                            excerptEnd = originalStart + localExcerptEnd,
                        )
                    )
                }
                if (size == maxMatches) break
            }
        }
        return metadata() + ("matches" to matches)
    }

    private fun KnowledgeEntry.metadata(): Map<String, Any> = linkedMapOf(
        "knowledgeId" to id,
        "sourceTool" to sourceTool,
        "originalLength" to originalLength,
        "storedLength" to storedLength,
        "truncated" to (content is KnowledgeContent.Truncated),
    )

    private fun errorMessage(
        functionName: String,
        code: String,
        message: String,
    ): LLMRequest.Message = functionMessage(
        functionName,
        mapOf("error" to KnowledgeError(code, message)),
    )

    private fun functionMessage(
        functionName: String,
        response: Any,
    ): LLMRequest.Message = LLMRequest.Message(
        role = LLMMessageRole.function,
        content = restJsonMapper.writeValueAsString(response),
        name = functionName,
    )

    private data class SearchSegment(
        val text: String,
        val originalStart: Int,
    )

    private data class RetainedSegment(
        val text: String,
        val start: Int,
        val end: Int,
    )

    private data class OffsetRange(
        val start: Int,
        val end: Int,
    )

    private data class KnowledgeMatch(
        val text: String,
        val start: Int,
        val end: Int,
        val excerpt: String,
        val excerptStart: Int,
        val excerptEnd: Int,
    )

    private data class KnowledgeError(
        val code: String,
        val message: String,
    )

    companion object {
        const val NAME = "GetKnowledge"
        const val DEFAULT_CONTEXT_CHARS = 256
        const val DEFAULT_MAX_MATCHES = 20
        const val MAX_CONTEXT_CHARS = 4096
        const val MAX_MATCHES = 100

        private const val MIN_CONTEXT_CHARS = 0
        private const val MIN_MATCHES = 1
        private const val INVALID_ARGUMENTS = "invalid_arguments"
        private const val INVALID_REGEX = "invalid_regex"
        private const val KNOWLEDGE_NOT_FOUND = "knowledge_not_found"
        private const val CONVERSATION_UNAVAILABLE = "conversation_unavailable"
        private const val STORAGE_FAILURE = "storage_failure"
        private val MIN_INPUT_INTEGER = BigInteger.valueOf(Int.MIN_VALUE.toLong())
        private val MAX_INPUT_INTEGER = BigInteger.valueOf(Int.MAX_VALUE.toLong())
        private val INPUT_ARGUMENT_NAMES = setOf(
            "knowledgeId",
            "regex",
            "charsBefore",
            "charsAfter",
            "maxMatches",
        )
    }
}

private fun String.safeExcerptStart(matchStart: Int, charsBefore: Int): Int {
    var excerptStart = (matchStart - charsBefore).coerceAtLeast(0)
    if (excerptStart in 1..<length &&
        this[excerptStart].isLowSurrogate() &&
        this[excerptStart - 1].isHighSurrogate()
    ) {
        excerptStart--
    }
    return excerptStart
}

private fun String.safeExcerptEnd(matchEnd: Int, charsAfter: Int): Int {
    var excerptEnd = (matchEnd + charsAfter).coerceAtMost(length)
    if (excerptEnd in 1..<length &&
        this[excerptEnd].isLowSurrogate() &&
        this[excerptEnd - 1].isHighSurrogate()
    ) {
        excerptEnd++
    }
    return excerptEnd
}
