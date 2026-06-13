package ru.souz.ambient

import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

class LocalLlmAmbientBlockAnalyzer(
    private val localLlm: AmbientLocalLlm,
) : AmbientBlockAnalyzer {
    override suspend fun analyze(block: AmbientSemanticBlock): AmbientTaskCandidate? {
        val raw = try {
            localLlm.complete(
                systemPrompt = systemPrompt(),
                userPrompt = userPrompt(block),
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            logger.warn(
                "Ambient local analysis failed: blockId={} error={}",
                block.id,
                error.message ?: error::class.simpleName,
            )
            return null
        }

        val task = parseTask(raw) ?: run {
            logger.debug("Ambient analysis completed: blockId={} hasCandidate=false", block.id)
            return null
        }
        val candidate = AmbientTaskCandidate(
            id = "task:${block.id}:1",
            taskText = task,
            addressedness = block.addressedness,
            confidence = 1.0,
            evidenceEventIds = block.eventIds,
        )
        logger.debug("Ambient analysis completed: blockId={} hasCandidate=true", block.id)
        return candidate
    }

    private fun systemPrompt(): String =
        """
        You are a local ambient analyzer for Souz.
        Input is a short speech block.
        Return EMPTY if there is no clear user task.
        Return TASK: <task> if the user likely wants Souz to help.
        The task must be a short natural-language instruction for the main agent.
        Do not output JSON.
        Do not output markdown.
        Do not output explanations.
        Do not include tool ids, capability ids, function names, slots, or risk labels.
        Ambient analysis only proposes a task; it never executes actions.
        """.trimIndent()

    private fun userPrompt(block: AmbientSemanticBlock): String =
        """
        addressedness: ${block.addressedness}
        speech:
        ${block.text.compact(MAX_BLOCK_TEXT_CHARS)}
        """.trimIndent()

    private fun parseTask(raw: String): String? {
        val normalized = raw.replace("\r\n", "\n").trim()
        if (normalized.isBlank() || normalized.equals("EMPTY", ignoreCase = true)) return null
        val lines = normalized.lines()
        if (lines.any { it.trim().startsWith("```") }) return null
        val taskLine = lines.singleOrNull { line ->
            line.trimStart().startsWith("TASK:", ignoreCase = true)
        } ?: return null
        return taskLine
            .substringAfter(':', missingDelimiterValue = "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_TASK_TEXT_CHARS)
            .ifBlank { null }
    }

    private fun String.compact(limit: Int): String =
        replace(Regex("\\s+"), " ").trim().let { normalized ->
            if (normalized.length <= limit) normalized else normalized.take(limit)
        }

    private companion object {
        val logger = LoggerFactory.getLogger(LocalLlmAmbientBlockAnalyzer::class.java)
        const val MAX_BLOCK_TEXT_CHARS = 1_600
        const val MAX_TASK_TEXT_CHARS = 240
    }
}
