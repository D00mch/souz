package agent

import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Path
import java.time.Instant
import ru.souz.llms.restJsonMapper

internal data class MemoryScenarios(
    val episodes: List<MemoryEpisode>,
    val probes: List<MemoryProbe>,
) {
    fun validate() {
        require(episodes.isNotEmpty() && probes.isNotEmpty())
        val turns = episodes.flatMap { it.turns }
        require(turns.isNotEmpty() && turns.map { it.id }.distinct().size == turns.size) { "Turn IDs must be unique" }
        require(episodes.map { it.id }.distinct().size == episodes.size) { "Episode IDs must be unique" }
        require(probes.map { it.id }.distinct().size == probes.size) { "Probe IDs must be unique" }
        require(turns.all { it.id.isNotBlank() && it.text.isNotBlank() })
        probes.forEach { probe ->
            require(probe.question.isNotBlank())
            require((probe.sourceTurns + listOfNotNull(probe.answerFromTurn)).all { id -> turns.any { it.id == id } }) {
                "Unknown source turn in ${probe.id}"
            }
            (probe.contextPatterns + probe.answerPatterns + probe.forbiddenAnswerPatterns).forEach(::Regex)
        }
    }
}

internal data class MemoryEpisode(
    val id: String,
    val chat: String = "main",
    val owner: String = "main",
    val turns: List<MemoryTurn>,
)

internal data class MemoryTurn(
    val id: String,
    val text: String,
    val toolResults: Map<String, String> = emptyMap(),
    val assistant: String? = null,
    val toolCalls: List<MemoryToolCall> = emptyList(),
)

internal data class MemoryToolCall(val name: String, val arguments: Map<String, Any> = emptyMap())

internal enum class MemoryAgentLlm { scripted, live }

internal fun memoryAgentLlm(value: String?): MemoryAgentLlm =
    value?.let { MemoryAgentLlm.valueOf(it.trim().lowercase()) } ?: MemoryAgentLlm.scripted

internal data class MemoryProbe(
    val id: String,
    val question: String,
    val chat: String = "main",
    val owner: String = "main",
    val sourceTurns: List<String> = emptyList(),
    val contextPatterns: List<String> = emptyList(),
    val answerPatterns: List<String> = emptyList(),
    val forbiddenAnswerPatterns: List<String> = emptyList(),
    val answerFromTurn: String? = null,
    val forbiddenSourceOwner: String? = null,
    val diagnostic: Boolean = false,
    val limitation: String? = null,
)

internal data class RecordedMemoryTurn(
    val id: String,
    val episode: String,
    val owner: String,
    val chatId: String,
    val documentId: String,
    val startedAt: String,
    val completedAt: String,
    val user: String,
    val assistant: String,
    val tools: List<Map<String, Any?>>,
)

internal data class MemoryRunSettings(
    val agent: String,
    val model: String,
    val temperature: Float = 0f,
    val contextSize: Int = 32_000,
    val locale: String = "ru-RU",
    val timeZone: String = "Europe/Moscow",
    // Version-1 manifests without this field were seeded with a real provider.
    val agentLlm: MemoryAgentLlm = MemoryAgentLlm.live,
)

internal data class MemoryManifest(
    val version: Int = 1,
    val corpusId: String,
    val createdAt: String = Instant.now().toString(),
    val settings: MemoryRunSettings,
    val owners: Map<String, String>,
    val chats: Map<String, String>,
    val scenarios: MemoryScenarios,
    val turns: List<RecordedMemoryTurn> = emptyList(),
    val complete: Boolean = false,
) {
    fun validateForReplay() {
        require(version == 1 && complete) { "Only completed version-1 manifests can be replayed" }
        scenarios.validate()
        require(owners.values.all { it.startsWith("souz-memory-test-") }) { "Manifest must reference test banks" }
        require(turns.map { it.id }.toSet() == scenarios.episodes.flatMap { it.turns }.map { it.id }.toSet())
        require(turns.map { it.documentId }.distinct().size == turns.size)
        require(scenarios.episodes.all { it.owner in owners && it.chat in chats })
        require(scenarios.probes.all { it.owner in owners && it.chat in chats })
    }
}

internal data class MemoryCheck(val name: String, val passed: Boolean?, val reason: String? = null) {
    val status: String get() = when (passed) { true -> "PASS"; false -> "FAIL"; null -> "SKIPPED" }
}

internal data class MemoryProbeReport(
    val id: String,
    val question: String,
    val answer: String,
    val memoryContext: String,
    val sourceDocuments: Set<String>,
    val checks: List<MemoryCheck>,
    val diagnostic: Boolean,
    val limitation: String?,
    val usedFactIds: Set<String> = emptySet(),
    val retrieval: List<MemoryHttpExchange> = emptyList(),
    val unresolvedSourceFactIds: Set<String> = emptySet(),
    val llmRequestMemoryContext: String? = null,
) {
    val passed: Boolean get() = checks.all { it.passed == true }
    val status: String get() = when {
        checks.any { it.passed == false } -> "FAIL"
        checks.all { it.passed == null } -> "SKIPPED"
        checks.any { it.passed == null } -> "PARTIAL"
        else -> "PASS"
    }
}

internal fun evaluateMemoryProbe(
    probe: MemoryProbe,
    turns: List<RecordedMemoryTurn>,
    answer: String,
    memoryContext: String,
    sourceDocuments: Set<String>,
    agentLlm: MemoryAgentLlm = MemoryAgentLlm.live,
    llmRequestMemoryContext: String? = null,
): MemoryProbeReport {
    val byId = turns.associateBy { it.id }
    val checks = buildList {
        fun answerCheck(name: String, passed: Boolean) {
            add(if (agentLlm == MemoryAgentLlm.scripted) MemoryCheck(name, null, "фиксированный ответ агента")
                else MemoryCheck(name, passed))
        }
        fun contextCheck(name: String, matches: (String) -> Boolean) {
            add(MemoryCheck("context:$name", matches(memoryContext)))
            llmRequestMemoryContext?.let { add(MemoryCheck("llm-context:$name", matches(it))) }
        }
        probe.sourceTurns.forEach { id ->
            add(MemoryCheck("source:$id", byId.getValue(id).documentId in sourceDocuments))
        }
        probe.contextPatterns.forEach { pattern ->
            contextCheck(pattern) { Regex(pattern).containsMatchIn(it) }
        }
        probe.answerPatterns.forEach { pattern ->
            answerCheck("answer:$pattern", Regex(pattern).containsMatchIn(answer))
        }
        probe.forbiddenAnswerPatterns.forEach { pattern ->
            answerCheck("answer-excludes:$pattern", !Regex(pattern).containsMatchIn(answer))
            if (probe.forbiddenSourceOwner != null) {
                contextCheck("excludes:$pattern") { !Regex(pattern).containsMatchIn(it) }
            }
        }
        probe.answerFromTurn?.let { id ->
            val value = byId.getValue(id).assistant.trim().trim('«', '»', '"', '.', ' ')
            answerCheck("assistant-value:$id", value.isNotBlank() && answer.contains(value, ignoreCase = true))
            contextCheck("assistant-value:$id") { value.isNotBlank() && it.contains(value, ignoreCase = true) }
        }
        probe.forbiddenSourceOwner?.let { owner ->
            val forbidden = turns.filter { it.owner == owner }.map { it.documentId }.toSet()
            add(MemoryCheck("sources-exclude-owner:$owner", sourceDocuments.intersect(forbidden).isEmpty()))
        }
    }
    require(checks.isNotEmpty()) { "Probe ${probe.id} has no checks" }
    return MemoryProbeReport(probe.id, probe.question, answer, memoryContext, sourceDocuments, checks, probe.diagnostic,
        probe.limitation, llmRequestMemoryContext = llmRequestMemoryContext)
}

internal fun writeMemoryJson(path: Path, value: Any) {
    restJsonMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value)
}

internal fun loadMemoryScenarios(): MemoryScenarios =
    requireNotNull(MemoryScenarios::class.java.getResourceAsStream("/agent/hindsight-conversations.json")) {
        "Missing Hindsight scenarios"
    }.use { restJsonMapper.readValue<MemoryScenarios>(it) }.also { it.validate() }
