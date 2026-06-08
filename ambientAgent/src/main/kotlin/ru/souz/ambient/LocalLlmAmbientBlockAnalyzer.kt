package ru.souz.ambient

import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

sealed interface AmbientLocalAnalysisDiagnosticEvent {
    data class Prompt(
        val blockId: String,
        val systemPrompt: String,
        val userPrompt: String,
    ) : AmbientLocalAnalysisDiagnosticEvent

    data class RawOutput(
        val blockId: String,
        val raw: String,
    ) : AmbientLocalAnalysisDiagnosticEvent
}

class LocalLlmAmbientBlockAnalyzer(
    private val localLlm: AmbientLocalLlm,
    private val parser: AmbientAnalysisJsonParser = AmbientAnalysisJsonParser(),
    private val textParser: AmbientAnalysisTextParser = AmbientAnalysisTextParser(),
    private val manifestRenderer: AmbientCapabilityManifestRenderer = AmbientCapabilityManifestRenderer(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val diagnostics: (AmbientLocalAnalysisDiagnosticEvent) -> Unit = {},
) : AmbientBlockAnalyzer {
    override suspend fun analyze(
        block: AmbientSemanticBlock,
        recentContext: List<AmbientSemanticBlock>,
        capabilities: List<AmbientCapability>,
    ): AmbientAnalysisResult {
        val raw = try {
            val systemPrompt = systemPrompt()
            val userPrompt = userPrompt(block, recentContext.takeLast(1), capabilities)
            logger.info(
                "Ambient local analyzer prompt block={} systemPrompt={}",
                block.id,
                systemPrompt,
            )
            logger.info(
                "Ambient local analyzer user prompt block={} userPrompt={}",
                block.id,
                userPrompt,
            )
            diagnostics(AmbientLocalAnalysisDiagnosticEvent.Prompt(block.id, systemPrompt, userPrompt))
            localLlm.completeJson(
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            logger.warn(
                "Ambient local analyzer failed block={} error={}",
                block.id,
                error.message ?: error::class.simpleName,
                error,
            )
            return AmbientAnalysisResult(
                blockId = block.id,
                blockSummary = "local analyzer unavailable",
                extractedStatements = emptyList(),
                taskCandidates = emptyList(),
                rawModelOutputPreview = error.message?.take(240),
            )
        }

        logger.info("Ambient local analyzer raw output block={} raw={}", block.id, raw)
        diagnostics(AmbientLocalAnalysisDiagnosticEvent.RawOutput(block.id, raw))
        val allowedCapabilityIds = capabilities.mapTo(mutableSetOf()) { it.id }
        val jsonResult = parser.parse(
            blockId = block.id,
            raw = raw,
            blockAddressedness = block.addressedness,
            allowedCapabilityIds = allowedCapabilityIds,
            evidenceEventIds = block.eventIds,
        )
        if (jsonResult.taskCandidates.isNotEmpty() || jsonResult.extractedStatements.isNotEmpty()) {
            return jsonResult
        }
        return textParser.parse(
            blockId = block.id,
            raw = raw,
            blockAddressedness = block.addressedness,
            allowedCapabilityIds = allowedCapabilityIds,
            evidenceEventIds = block.eventIds,
        )
    }

    private fun systemPrompt(): String =
        """
        Ты ambient-анализатор Souz. Локальный runtime требует {"type":"final","content":"..."}.
        speech - 3-секундное окно речи. Выделяй цельные мысли и предложи одну явную/неявную задачу; если задачи нет, верни EMPTY.
        capabilities показывают, что умеет основной агент; используй их только как контекст.
        Внутри content не возвращай названия tools, ids, функции, JSON-структуры, confidence, risk или вопрос для UI.
        В content верни ровно EMPTY или одну строку:
        TASK: естественная команда для основного агента
        TASK примеры: какая погода в Москве; проверь календарь на завтра; напомни поставить встречу в 18:00.
        """.trimIndent()

    private fun userPrompt(
        block: AmbientSemanticBlock,
        recentContext: List<AmbientSemanticBlock>,
        capabilities: List<AmbientCapability>,
    ): String = buildString {
        appendLine("addressedness: ${block.addressedness}")
        appendLine("speech:")
        appendLine(block.text.compact(MAX_BLOCK_TEXT_CHARS))
        if (recentContext.isNotEmpty()) {
            appendLine("context:")
        }
        recentContext.forEach { context ->
            appendLine("- ${context.addressedness}: ${context.text.compact(MAX_CONTEXT_TEXT_CHARS)}")
        }
        appendLine("capabilities:")
        appendLine(manifestRenderer.render(capabilities))
    }

    private fun String.compact(limit: Int): String =
        replace(Regex("\\s+"), " ").trim().let { normalized ->
            if (normalized.length <= limit) normalized else normalized.take(limit)
        }

    private companion object {
        val logger = LoggerFactory.getLogger(LocalLlmAmbientBlockAnalyzer::class.java)
        const val MAX_BLOCK_TEXT_CHARS = 1_600
        const val MAX_CONTEXT_TEXT_CHARS = 180
    }
}
