package agent

import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import ru.souz.agent.AgentId
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.backend.memory.hindsight.HindsightConversationMemoryRuntime
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.http.createStandardProviderHttpClient
import ru.souz.llms.restJsonMapper

private const val ENABLED = "SOUZ_HINDSIGHT_SCENARIOS_ON"
private const val MANIFEST = "SOUZ_HINDSIGHT_EVAL_MANIFEST"

class HindsightConversationScenariosIntegrationTest {
    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    fun conversationsAndMemoryProbes() {
        // Check before constructing clients, settings, or reading credentials.
        assumeTrue(System.getenv(ENABLED).equals("true", ignoreCase = true), "Set $ENABLED=true to run")
        runBlocking { withTimeout(30 * 60_000L) { runScenarios() } }
    }

    private suspend fun runScenarios() {
        val baseUrl = requireNotNull(System.getenv("HINDSIGHT_API_URL")) { "HINDSIGHT_API_URL is required" }
        val uri = URI(baseUrl)
        require(uri.scheme in listOf("http", "https") && uri.host != null &&
            uri.userInfo == null && uri.query == null && uri.fragment == null) { "Invalid HINDSIGHT_API_URL" }
        val token = System.getenv("HINDSIGHT_API_TOKEN")
        val model = scenarioIntegrationModel(LLMModel.AnthropicHaiku45)
        val agent = System.getenv(SOUZ_AGENT_INTEGRATION_TEST_AGENT)?.let(::parseScenarioAgentId) ?: AgentId.SKILLS_GRAPH
        val agentLlm = memoryAgentLlm(System.getenv("SOUZ_HINDSIGHT_AGENT_LLM"))
        val scripted = if (agentLlm == MemoryAgentLlm.scripted) ScriptedMemoryChatApi(agent) else null
        val settings = MemoryRunSettings(agent.storageValue, model.alias, agentLlm = agentLlm)
        val runId = UUID.randomUUID().toString()
        val output = Path.of("build", "reports", "hindsight", runId).toAbsolutePath()
        val inputPath = System.getenv(MANIFEST)?.takeIf { it.isNotBlank() }?.let(Path::of)
        var manifest = inputPath?.let { path ->
            restJsonMapper.readValue<MemoryManifest>(path.toFile()).also { it.validateForReplay() }
        } ?: newManifest(runId, settings)
        val captureJob = SupervisorJob()
        val captureScope = CoroutineScope(captureJob + Dispatchers.IO)
        val http = createStandardProviderHttpClient()
        val trace = HindsightHttpTrace(http)
        val memory = ScenarioMemoryRuntime(HindsightConversationMemoryRuntime(http, baseUrl, token))
        val support = AgentScenarioTestSupport(model, agent, memory, captureScope, useProductionPrompt = true)
        val reports = mutableListOf<MemoryProbeReport>()
        try {
            if (agentLlm == MemoryAgentLlm.live) support.checkEnvironment(ENABLED, requireCredentials = true)
            Files.createDirectories(output)
            writeMemoryJson(output.resolve("manifest.json"), manifest)
            println("Hindsight corpus=${manifest.corpusId}; agentLlm=$agentLlm; artifacts=$output")
            val toolNames = manifest.scenarios.episodes.flatMap { it.turns }.flatMap { it.toolResults.keys }.toSet()
            var activeResults: Map<String, String> = emptyMap()
            val di = support.createScenarioDi(toolNames.map { name -> fixedTool(name) { activeResults } }, scripted)

            suspend fun awaitCapture() {
                withTimeout(150_000L) { captureJob.children.toList().joinAll() }
                trace.requireHealthy()
            }

            if (inputPath == null) {
                // Recall runs before the first retain, so an empty bank must already exist.
                manifest.owners.values.forEach { trace.createBank(baseUrl, token, it) }
                for (episode in manifest.scenarios.episodes) {
                    var history = emptyList<LLMRequest.Message>()
                    for (turn in episode.turns) {
                        activeResults = turn.toolResults
                        scripted?.beginTurn(turn)
                        val messageId = UUID.randomUUID().toString()
                        val startedAt = Instant.now().toString()
                        val tools = mutableListOf<Map<String, Any?>>()
                        val result = support.runConversationTurn(
                            di, turn.text, history,
                            invocationMeta(manifest, episode.owner, episode.chat, messageId),
                            object : AgentRuntimeEventSink {
                                override suspend fun emit(event: AgentRuntimeEvent) {
                                    when (event) {
                                        is AgentRuntimeEvent.ToolCallStarted -> tools += mapOf(
                                            "kind" to "call", "id" to event.toolCallId,
                                            "name" to event.name, "arguments" to event.arguments,
                                        )
                                        is AgentRuntimeEvent.ToolCallFinished -> tools += mapOf(
                                            "kind" to "result", "id" to event.toolCallId,
                                            "name" to event.name, "result" to event.result,
                                        )
                                        else -> Unit
                                    }
                                }
                            },
                        )
                        scripted?.verifyComplete()
                        val recorded = RecordedMemoryTurn(
                            turn.id, episode.id, episode.owner, manifest.chats.getValue(episode.chat),
                            "souz-turn-$messageId", startedAt, Instant.now().toString(), turn.text, result.output, tools,
                        )
                        manifest = manifest.copy(turns = manifest.turns + recorded)
                        writeMemoryJson(output.resolve("manifest.json"), manifest)
                        writeTranscript(output, manifest)
                        awaitCapture()
                        check(recorded.documentId in memory.capturedDocuments) { "Capture did not run for ${turn.id}" }
                        trace.verifyDocument(baseUrl, token, manifest.owners.getValue(episode.owner), recorded.documentId)
                        check(result.output.isNotBlank()) { "Empty assistant answer for ${turn.id}" }
                        check(turn.toolResults.keys.all { name ->
                            tools.any { it["kind"] == "call" &&
                                (it["name"] == name || (it["arguments"] as? Map<*, *>)?.get("skillId") == name) }
                        }) { "Expected fixture tool was not called for ${turn.id}" }
                        history = result.context.history
                    }
                }
                manifest = manifest.copy(complete = true)
                writeMemoryJson(output.resolve("manifest.json"), manifest)
            } else {
                // Exact-ID checks distinguish a stale/deleted corpus from poor retrieval quality.
                manifest.turns.forEach { turn ->
                    trace.verifyDocument(baseUrl, token, manifest.owners.getValue(turn.owner), turn.documentId)
                }
                writeTranscript(output, manifest)
            }

            memory.captureEnabled = false
            activeResults = emptyMap()
            for (probe in manifest.scenarios.probes) {
                memory.resetProbe()
                scripted?.beginProbe(probe)
                val before = trace.snapshot().size
                val result = support.runConversationTurn(
                    di = di,
                    userPrompt = probe.question,
                    history = emptyList(),
                    meta = invocationMeta(manifest, probe.owner, probe.chat, UUID.randomUUID().toString()),
                )
                scripted?.verifyComplete()
                val llmMemory = scripted?.memoryContext()
                if (llmMemory != null) check(memory.contextBlocks.all { it.trim() in llmMemory }) {
                    "Retrieved memory did not reach the LLM boundary for ${probe.id}"
                }
                awaitCapture()
                val exchanges = trace.snapshot().drop(before)
                check(exchanges.none { it.operation == "retain" }) { "Evaluation mutated memory" }
                check(exchanges.any { it.operation == "recall" }) { "Probe did not search memory" }
                check(exchanges.all { it.bankId == manifest.owners.getValue(probe.owner) }) { "Wrong owner bank queried" }
                reports += evaluateMemoryProbe(
                    probe, manifest.turns, result.output, memory.contextBlocks.joinToString("\n"),
                    sourceDocuments(exchanges, memory.factIds),
                    agentLlm, llmMemory,
                ).copy(
                    usedFactIds = memory.factIds.toSet(), retrieval = exchanges,
                    unresolvedSourceFactIds = memory.factIds.filter { sourceDocuments(exchanges, setOf(it)).isEmpty() }.toSet(),
                )
                writeMemoryJson(output.resolve("results.json"), reports)
            }
            val failures = reports.filter { !it.diagnostic && it.status == "FAIL" }
            check(failures.isEmpty()) { "Memory checks failed: ${failures.map { it.id }}. See $output" }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (Files.isDirectory(output)) writeMemoryJson(output.resolve("failure.json"), mapOf("type" to error.javaClass.simpleName))
            throw error
        } finally {
            // Captures must finish or be cancelled before their HTTP client is closed.
            withContext(NonCancellable) {
                try {
                    captureJob.cancelAndJoin()
                    if (Files.isDirectory(output)) {
                        writeMemoryJson(output.resolve("hindsight-http.json"), trace.snapshot())
                        writeMemoryJson(output.resolve("evaluation.json"), mapOf("settings" to settings, "corpusId" to manifest.corpusId))
                        output.resolve("report.md").writeText(memoryReport(manifest, settings, reports))
                    }
                } finally {
                    http.close()
                    support.finish()
                }
            }
        }
    }
}

private fun newManifest(runId: String, settings: MemoryRunSettings): MemoryManifest {
    val scenarios = loadMemoryScenarios()
    val ownerNames = (scenarios.episodes.map { it.owner } + scenarios.probes.map { it.owner }).toSet()
    val chatNames = (scenarios.episodes.map { it.chat } + scenarios.probes.map { it.chat }).toSet()
    return MemoryManifest(
        corpusId = runId, settings = settings, scenarios = scenarios,
        owners = ownerNames.associateWith { "souz-memory-test-$runId-$it" },
        chats = chatNames.associateWith { UUID.randomUUID().toString() },
    )
}

private fun invocationMeta(manifest: MemoryManifest, owner: String, chat: String, messageId: String) = ToolInvocationMeta(
    userId = manifest.owners.getValue(owner),
    conversationId = manifest.chats.getValue(chat),
    requestId = UUID.randomUUID().toString(),
    locale = "ru-RU",
    timeZone = "Europe/Moscow",
    attributes = mapOf("userMessageId" to messageId, "assistantMessageId" to UUID.randomUUID().toString()),
)

private fun fixedTool(name: String, results: () -> Map<String, String>): LLMToolSetup = object : LLMToolSetup {
    override val fn = LLMRequest.Function(name = name, description = "Fixture")
    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
        LLMRequest.Message(
            role = LLMMessageRole.function,
            name = name,
            content = results()[name] ?: "No fixture result is available for this tool on this turn.",
        )
}

private fun writeTranscript(output: Path, manifest: MemoryManifest) {
    writeMemoryJson(output.resolve("transcript.json"), manifest.turns)
}

internal fun memoryReport(manifest: MemoryManifest, settings: MemoryRunSettings, reports: List<MemoryProbeReport>): String = buildString {
    appendLine("# Hindsight conversation evaluation")
    appendLine("\nCorpus: `${manifest.corpusId}`; complete: ${manifest.complete}")
    appendLine("\nSeed: ${manifest.settings.agent} / ${manifest.settings.model} / ${manifest.settings.agentLlm}. " +
        "Evaluation: ${settings.agent} / ${settings.model} / ${settings.agentLlm}.")
    appendLine("\nIn scripted mode the model is routing metadata only; no agent provider is called.")
    appendLine("\nChecks are source/regex checks, not a semantic judge. A diagnostic failure does not fail JUnit.")
    appendLine("\nPARTIAL means executed checks passed but answer checks were skipped. SKIPPED means no quality checks ran.")
    appendLine("\n| Probe | Result | Mode | Failed checks | Skipped checks |")
    appendLine("| --- | --- | --- | --- | --- |")
    reports.forEach { report ->
        val failures = report.checks.filter { it.passed == false }.joinToString { it.name }.replace("|", "\\|")
        val skipped = report.checks.filter { it.passed == null }.joinToString { "${it.name}: ${it.reason}" }.replace("|", "\\|")
        appendLine("| ${report.id} | ${report.status} | ${if (report.diagnostic) "diagnostic" else "required"} | $failures | $skipped |")
    }
    reports.forEach { report -> report.limitation?.let { appendLine("\n${report.id}: $it") } }
    appendLine("\nSee results.json for answers/context/sources; hindsight-http.json for server responses.")
    appendLine("\nBanks are preserved. Replay with SOUZ_HINDSIGHT_EVAL_MANIFEST pointing to manifest.json.")
}
