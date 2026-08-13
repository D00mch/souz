package ru.souz.tool.knowledge

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException
import java.math.BigInteger
import kotlinx.coroutines.CancellationException
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.knowledge.KnowledgeContent
import ru.souz.agent.knowledge.KnowledgeEntry
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta

/** Searches retained conversation-scoped Knowledge with a bounded RE2 regular expression. */
class ToolSearchKnowledge internal constructor(
    private val retriever: KnowledgeRetriever,
) : LLMToolSetup {
    constructor(
        knowledgeStore: ConversationKnowledgeStore,
    ) : this(KnowledgeRetriever(knowledgeStore))

    data class Input(
        val knowledgeId: String,
        val regex: String,
        val charsBefore: Int? = null,
        val charsAfter: Int? = null,
        val maxMatches: Int? = null,
    )

    override val fn: LLMRequest.Function = LLMRequest.Function(
        name = NAME,
        description = "Search retained Knowledge with a case-sensitive RE2 regular expression. Use this for targeted retrieval; use GetKnowledge when all retained content is needed. Backreferences and lookaround are unsupported. Offsets use UTF-16 indices and end offsets are exclusive.",
        parameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf(
                "knowledgeId" to LLMRequest.Property(
                    type = "string",
                    description = "Opaque Knowledge ID from a tool result in this conversation.",
                ),
                "regex" to LLMRequest.Property(
                    type = "string",
                    description = "RE2 regular expression without backreferences or lookaround. Matching is case-sensitive unless changed with inline flags.",
                ),
                "charsBefore" to LLMRequest.Property(
                    type = "integer",
                    description = "UTF-16 context units before each match (default 256, range 0..4096).",
                ),
                "charsAfter" to LLMRequest.Property(
                    type = "integer",
                    description = "UTF-16 context units after each match (default 256, range 0..4096).",
                ),
                "maxMatches" to LLMRequest.Property(
                    type = "integer",
                    description = "Maximum matches to return (default 20, range 1..100).",
                ),
            ),
            required = listOf("knowledgeId", "regex"),
        ),
        returnParameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf(
                "knowledgeId" to LLMRequest.Property("string", "The requested Knowledge ID."),
                "sourceTool" to LLMRequest.Property("string", "Tool that produced the stored result."),
                "originalLength" to LLMRequest.Property("integer", "Original UTF-16 content length."),
                "storedLength" to LLMRequest.Property("integer", "Retained UTF-16 content length."),
                "truncated" to LLMRequest.Property("boolean", "Whether storage omitted a middle range."),
                "matches" to LLMRequest.Property("array", "Exact matches with optional surrounding excerpts."),
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
            return knowledgeErrorMessage(
                functionCall.name,
                INVALID_ARGUMENTS,
                error.message ?: "SearchKnowledge arguments are invalid.",
            )
        }

        validate(input)?.let { message ->
            return knowledgeErrorMessage(functionCall.name, INVALID_ARGUMENTS, message)
        }

        val pattern = try {
            Pattern.compile(input.regex)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: PatternSyntaxException) {
            return knowledgeErrorMessage(
                functionCall.name,
                INVALID_REGEX,
                error.message ?: "The RE2 regular expression is invalid.",
            )
        } catch (error: IllegalArgumentException) {
            return knowledgeErrorMessage(
                functionCall.name,
                INVALID_REGEX,
                error.message ?: "The RE2 regular expression is invalid.",
            )
        }

        return retriever.get(meta, input.knowledgeId).toMessage(functionCall.name) { entry ->
            entry.searchResponse(
                pattern = pattern,
                charsBefore = input.charsBefore ?: DEFAULT_CONTEXT_CHARS,
                charsAfter = input.charsAfter ?: DEFAULT_CONTEXT_CHARS,
                maxMatches = input.maxMatches ?: DEFAULT_MAX_MATCHES,
            )
        }
    }

    private fun Map<String, Any>.toInput(): Input {
        val unknownArguments = keys - INPUT_ARGUMENT_NAMES
        if (unknownArguments.isNotEmpty()) {
            throw IllegalArgumentException(
                "Unknown SearchKnowledge arguments: ${unknownArguments.sorted().joinToString()}.",
            )
        }
        return Input(
            knowledgeId = requiredString("knowledgeId"),
            regex = requiredString("regex"),
            charsBefore = optionalInt("charsBefore"),
            charsAfter = optionalInt("charsAfter"),
            maxMatches = optionalInt("maxMatches"),
        )
    }

    private fun Map<String, Any>.requiredString(name: String): String =
        this[name] as? String ?: throw IllegalArgumentException("$name must be a string.")

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
        if (input.knowledgeId.isBlank()) return "knowledgeId must not be blank."
        if (input.regex.isBlank()) return "regex must not be blank."
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

    private fun KnowledgeEntry.searchResponse(
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
                    val exactText = matcher.group()
                    val excerpt = text.substring(localExcerptStart, localExcerptEnd)
                    add(
                        linkedMapOf<String, Any>(
                            "text" to exactText,
                            "start" to originalStart + localMatchStart,
                            "end" to originalStart + localMatchEnd,
                        ).apply {
                            if (excerpt != exactText) {
                                put("excerpt", excerpt)
                                put("excerptStart", originalStart + localExcerptStart)
                                put("excerptEnd", originalStart + localExcerptEnd)
                            }
                        }
                    )
                }
                if (size == maxMatches) break
            }
        }
        return metadata() + ("matches" to matches)
    }

    private data class SearchSegment(
        val text: String,
        val originalStart: Int,
    )

    companion object {
        const val NAME = "SearchKnowledge"
        const val DEFAULT_CONTEXT_CHARS = 256
        const val DEFAULT_MAX_MATCHES = 20
        const val MAX_CONTEXT_CHARS = 4096
        const val MAX_MATCHES = 100

        private const val MIN_CONTEXT_CHARS = 0
        private const val MIN_MATCHES = 1
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
