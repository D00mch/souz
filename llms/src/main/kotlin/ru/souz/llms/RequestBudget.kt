package ru.souz.llms

const val MAX_INPUT_IMAGES = 5
// Admission allowance for a <= 2048px vision projection, independent of compressed/base64 size.
const val IMAGE_INPUT_TOKEN_ALLOWANCE = 6_144

/** The same ordered references are reserved before compaction and projected by the host. */
fun LLMRequest.Chat.imageFileReferences(): List<Pair<Int, String>> {
    if (isSummarization) return emptyList()
    val firstToolResult = messages.indexOfLast { it.role != LLMMessageRole.function } + 1
    return messages.withIndex().flatMap { (index, message) ->
        if (message.role == LLMMessageRole.user || index >= firstToolResult && message.role == LLMMessageRole.function)
            message.files.filter { it.mimeType.startsWith("image/") }.map { index to it.fileId }
        else emptyList()
    }.distinct().takeLast(MAX_INPUT_IMAGES)
}

// Once the host projects references, count only actual pixels (not unavailable files or dedicated-route inputs).
fun LLMRequest.Chat.estimatedInputTokens(includeImageReferences: Boolean = true): Int =
    ((messages.sumOf { message ->
        16L + message.content.length + message.name.orEmpty().length +
            message.functionsStateId.orEmpty().length +
            (message.functionCall?.let { it.name.length.toLong() + it.arguments.length } ?: 0L)
    } + restJsonMapper.writeValueAsString(functions).length + 3) / 4 +
        if (isSummarization) 0L else IMAGE_INPUT_TOKEN_ALLOWANCE.toLong() *
            (messages.sumOf { it.attachments.orEmpty().size.toLong() } +
                if (includeImageReferences) imageFileReferences().size else 0))
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

fun LLMRequest.Chat.exceedsContextBudget(contextSize: Int, includeImageReferences: Boolean = true): Boolean =
    estimatedInputTokens(includeImageReferences) > contextSize * 0.8
