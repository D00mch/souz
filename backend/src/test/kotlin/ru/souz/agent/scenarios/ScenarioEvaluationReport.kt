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
            "scenarioVersion" to "agent-scenarios-v11",
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
        totalModelMs += trace.calls.sumOf { it.durationMs }
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
            "calls" to trace.calls,
            "prefixes" to trace.prefixes,
            "turns" to turns,
        ))
        html.addTrial(scenario, repeat, status, detail, durationMs, fixture.initialHistory, turns, trace, score)
    }

    fun finish(durationMs: Long) {
        this.durationMs = durationMs
        write("summary", mapOf(
            "scenarios" to trials,
            "solved" to solved,
            "assertionsPassed" to assertionsPassed,
            "assertionsTotal" to assertionsTotal,
            "modelCalls" to modelCalls,
            "durationMs" to durationMs,
            "meanModelCallMs" to if (modelCalls == 0) null else totalModelMs.toDouble() / modelCalls,
        ))
    }

    private fun write(type: String, fields: Map<String, Any?>) {
        writer.write(restJsonMapper.writeValueAsString(mapOf("type" to type, "runId" to runId) + fields))
        writer.newLine()
        writer.flush()
    }

    override fun close() {
        writer.close()
        val meanMs = if (modelCalls == 0) null else totalModelMs / modelCalls
        val page = html.render(solved, trials, assertionsPassed, assertionsTotal, modelCalls, durationMs, meanMs, path.fileName.toString())
        Files.writeString(htmlPath, page, CREATE_NEW)
    }
}

internal data class ScenarioTurnEvidence(
    val number: Int,
    val input: String,
    val assistantReply: String?,
    val tools: List<ScenarioToolUse>,
    val telegram: List<String>,
)
