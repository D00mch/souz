package ru.souz.ambient

import kotlin.coroutines.cancellation.CancellationException

class LocalLlmAmbientBlockAnalyzer(
    private val localLlm: AmbientLocalLlm,
    private val parser: AmbientAnalysisJsonParser = AmbientAnalysisJsonParser(),
    private val manifestRenderer: AmbientCapabilityManifestRenderer = AmbientCapabilityManifestRenderer(),
    private val clock: () -> Long = System::currentTimeMillis,
) : AmbientBlockAnalyzer {
    override suspend fun analyze(
        block: AmbientSemanticBlock,
        recentContext: List<AmbientSemanticBlock>,
        capabilities: List<AmbientCapability>,
    ): AmbientAnalysisResult {
        val raw = try {
            localLlm.completeJson(
                systemPrompt = systemPrompt(),
                userPrompt = userPrompt(block, recentContext.takeLast(5), capabilities),
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return AmbientAnalysisResult(
                blockId = block.id,
                blockSummary = "local analyzer unavailable",
                extractedStatements = emptyList(),
                taskCandidates = emptyList(),
                rawModelOutputPreview = error.message?.take(240),
            )
        }

        return parser.parse(
            blockId = block.id,
            raw = raw,
            blockAddressedness = block.addressedness,
            allowedCapabilityIds = capabilities.mapTo(mutableSetOf()) { it.id },
            evidenceEventIds = block.eventIds,
        )
    }

    private fun systemPrompt(): String =
        """
        Ты локальный анализатор ambient speech для Souz.
        Верни ровно один JSON object без markdown и текста вокруг.
        Не выполняй задачи, не вызывай инструменты, не пиши в память и не предлагай действие без подтверждения.
        Для фоновой, цитируемой речи и медиа обычно возвращай пустой task_candidates.
        Не выдумывай capability ids: используй только ids из manifest.
        Все task candidates должны иметь requires_confirmation=true.
        JSON schema:
        {"type":"ambient_analysis","block_summary":"","statements":[{"text":"","kind":"NOTE|PREFERENCE|FACT|QUESTION|OTHER","confidence":0.0,"evidence_event_ids":[]}],"task_candidates":[{"title":"","task_text":"","suggestion_text":"","confidence":0.0,"addressedness":"DIRECT_TO_SOUZ|IMPLICIT_USER_INTENT|AMBIENT_CONVERSATION|BACKGROUND_OR_QUOTED|UNKNOWN","matched_capability_ids":[],"missing_slots":[],"risk":"LOW|MEDIUM|HIGH|UNKNOWN","requires_confirmation":true,"evidence_event_ids":[],"reason":""}]}
        """.trimIndent()

    private fun userPrompt(
        block: AmbientSemanticBlock,
        recentContext: List<AmbientSemanticBlock>,
        capabilities: List<AmbientCapability>,
    ): String = buildString {
        appendLine("current_time_ms: ${clock()}")
        appendLine("semantic_block:")
        appendLine("id: ${block.id}")
        appendLine("addressedness: ${block.addressedness}")
        appendLine("speaker_role: ${block.speakerRole}")
        appendLine("started_at_ms: ${block.startedAtMs}")
        appendLine("ended_at_ms: ${block.endedAtMs}")
        appendLine("event_ids: ${block.eventIds.joinToString(",")}")
        appendLine("text:")
        appendLine(block.text)
        appendLine()
        appendLine("recent_context:")
        recentContext.forEach { context ->
            appendLine("- ${context.id} | ${context.addressedness} | ${context.text.take(240)}")
        }
        appendLine()
        appendLine("capability_manifest:")
        appendLine(manifestRenderer.render(capabilities))
    }
}
