package ru.souz.agent.scenarios

import ru.souz.llms.restJsonMapper
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal class ScenarioEvaluationReport(
    directory: Path,
    model: ScenarioLiveModel,
    skills: ScenarioSkillSet,
    appointment: LocalDate,
    repeats: Int,
) : AutoCloseable {
    private val runId = UUID.randomUUID().toString()
    val path: Path = Files.createDirectories(directory).resolve("$runId.jsonl")
    val htmlPath: Path = path.resolveSibling("$runId.html")
    private val html = ScenarioEvaluationHtml(model.modelId, model.providerHost, appointment.toString())
    private val writer: BufferedWriter = Files.newBufferedWriter(path, CREATE_NEW)
    private var trials = 0
    private var solved = 0
    private var assertionsPassed = 0
    private var assertionsTotal = 0
    private var modelCalls = 0
    private var totalModelMs = 0L
    private var inputTokens: Long? = 0L
    private var outputTokens: Long? = 0L
    private var cachedInputTokens: Long? = 0L
    private var durationMs: Long? = null

    init {
        write("run", mapOf(
            "schemaVersion" to 1,
            "startedAt" to Instant.now().toString(),
            "revision" to System.getenv("SOUZ_SCENARIO_REVISION"),
            "model" to model.modelId,
            "provider" to model.provider.name,
            "providerHost" to model.providerHost,
            "reasoningEffort" to model.reasoningEffort,
            "scenarioVersion" to "agent-scenarios-v20",
            "skillsSha256" to skills.sha256,
            "skillsCount" to skills.bundles.size,
            "appointmentDate" to appointment.toString(),
            "repeats" to repeats,
        ))
    }

    fun trial(
        scenario: String,
        repeat: Int,
        status: String,
        detail: String?,
        durationMs: Long,
        trace: ScenarioTrace,
        fixture: ScenarioFixture,
        score: ScenarioAssertionResult,
    ) {
        trials++
        if (status == "passed") solved++
        assertionsPassed += score.passed
        assertionsTotal += score.total
        modelCalls += trace.calls.size
        for (call in trace.calls) {
            totalModelMs += call.durationMs
            inputTokens = inputTokens.plusReported(call.inputTokens)
            outputTokens = outputTokens.plusReported(call.outputTokens)
            cachedInputTokens = cachedInputTokens.plusReported(call.cachedInputTokens)
        }
        val turns = (1..trace.startedTurns).map { turn ->
            ScenarioTurnEvidence(
                number = turn,
                input = fixture.turns[turn - 1],
                assistantReply = fixture.answerOnTurn(turn),
                tools = trace.toolsOnTurn(turn),
                telegram = fixture.deliveriesOnTurn(turn),
            )
        }
        write("scenario", mapOf(
            "scenario" to scenario,
            "repeat" to repeat,
            "status" to status,
            "detail" to detail,
            "durationMs" to durationMs,
            "assertions" to score,
            "seededHistory" to trace.seededHistory,
            "calls" to trace.calls,
            "prefixes" to trace.prefixes,
            "turns" to turns,
        ))
        html.addTrial(scenario, repeat, status, detail, durationMs, fixture.initialHistory, turns, trace, score)
    }

    fun finish(durationMs: Long) {
        this.durationMs = durationMs
        val tokens = tokenSummary()
        write("summary", mapOf(
            "scenarios" to trials,
            "solved" to solved,
            "assertionsPassed" to assertionsPassed,
            "assertionsTotal" to assertionsTotal,
            "modelCalls" to modelCalls,
            "durationMs" to durationMs,
            "meanModelCallMs" to if (modelCalls == 0) null else totalModelMs.toDouble() / modelCalls,
            "inputTokens" to tokens.input,
            "outputTokens" to tokens.output,
            "totalTokens" to tokens.total,
            "cachedInputTokens" to tokens.cachedInput,
            "cachedInputFraction" to tokens.cachedInputFraction,
            "meanTotalTokensPerRequest" to tokens.meanTotal,
            "meanCachedInputTokensPerRequest" to tokens.meanCachedInput,
        ))
    }

    private fun tokenSummary() = ScenarioTokenSummary(
        input = inputTokens.takeIf { modelCalls > 0 },
        output = outputTokens.takeIf { modelCalls > 0 },
        cachedInput = cachedInputTokens.takeIf { modelCalls > 0 },
        modelCalls = modelCalls,
    )

    private fun write(type: String, fields: Map<String, Any?>) {
        writer.write(restJsonMapper.writeValueAsString(mapOf("type" to type, "runId" to runId) + fields))
        writer.newLine()
        writer.flush()
    }

    override fun close() {
        writer.close()
        val meanMs = if (modelCalls == 0) null else totalModelMs / modelCalls
        val page = html.render(
            solved, trials, assertionsPassed, assertionsTotal, modelCalls, durationMs, meanMs, tokenSummary(),
            path.fileName.toString(),
        )
        Files.writeString(htmlPath, page, CREATE_NEW)
    }
}

private fun Long?.plusReported(count: Int?): Long? =
    if (this != null && count != null) this + count else null

internal class ScenarioTokenSummary(
    val input: Long?,
    val output: Long?,
    val cachedInput: Long?,
    modelCalls: Int,
) {
    val total = if (input != null && output != null) input + output else null
    val cachedInputFraction =
        if (input != null && input > 0 && cachedInput != null) cachedInput.toDouble() / input else null
    val meanTotal = if (modelCalls > 0) total?.toDouble()?.div(modelCalls) else null
    val meanCachedInput = if (modelCalls > 0) cachedInput?.toDouble()?.div(modelCalls) else null
}

internal data class ScenarioTurnEvidence(
    val number: Int,
    val input: String,
    val assistantReply: String?,
    val tools: List<ScenarioToolUse>,
    val telegram: List<String>,
)
