package ru.souz.agent.scenarios

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class AgentScenarioEvaluationTest {
    @Test
    fun `evaluate agent tasks across turns`() = runBlocking {
        val suiteStarted = TimeSource.Monotonic.markNow()
        ScenarioLiveModel.fromEnvironment(captureWire = true).use { model ->
            val wire = requireNotNull(model.wire) { "Scenario evaluation requires an OpenAI-compatible endpoint." }
            val skills = ScenarioSkillSet.load()
            val repeats = (System.getenv("SOUZ_SCENARIO_REPEATS") ?: "2").toInt()
            require(repeats > 0) { "SOUZ_SCENARIO_REPEATS must be positive." }
            val appointment = LocalDate.now(ZoneId.of(SCENARIO_ZONE))
                .with(TemporalAdjusters.next(DayOfWeek.WEDNESDAY))
            val reportDir = Path.of(System.getenv("SOUZ_SCENARIO_REPORT_DIR") ?: "build/reports/agent-evaluation")
            val failures = mutableListOf<String>()
            ScenarioEvaluationReport(reportDir, model, skills, appointment, repeats).use { report ->
                println("Agent evaluation HTML: ${report.htmlPath.toAbsolutePath()}")
                println("Agent evaluation JSONL: ${report.path.toAbsolutePath()}")
                repeat(repeats) { index ->
                    for (case in EvaluationCase.entries) {
                        val fixture = ScenarioFixture(case, appointment)
                        val trace = ScenarioTrace(
                            model.modelId, model.reasoningEffort, wire, normalizeDate = false,
                            maxModelCalls = maxOf(24, fixture.turns.size * 8),
                        )
                        val started = TimeSource.Monotonic.markNow()
                        var status = "passed"
                        var detail: String? = null
                        try {
                            model.runConversation(
                                turns = fixture.turns,
                                trace = trace,
                                initialHistory = fixture.initialHistory,
                                skillBundles = skills.bundles,
                                commands = fixture.commands,
                                channels = fixture.channels,
                                afterTurn = fixture::afterTurn,
                            )
                            trace.assertHistoryPreserved(fixture.turns.size)
                            fixture.verify()
                        } catch (failure: AssertionError) {
                            status = if (trace.calls.any { it.status == "error" }) "provider_error" else "failed"
                            detail = failure.message?.lineSequence()?.firstOrNull()
                        } catch (failure: Exception) {
                            currentCoroutineContext().ensureActive()
                            status = if (trace.calls.any { it.status == "error" }) "provider_error" else "execution_error"
                            val frame = failure.stackTrace.firstOrNull { it.className.startsWith("ru.souz.") }
                            detail = listOfNotNull(failure.javaClass.simpleName, frame?.toString()).joinToString(" at ")
                        }
                        if (status != "passed") failures += "${case.id} #${index + 1}: $status ($detail)"
                        report.trial(case.id, index + 1, status, detail, started.elapsedNow().inWholeMilliseconds, trace, fixture)
                    }
                }
                report.finish(suiteStarted.elapsedNow().inWholeMilliseconds)
            }
            assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        }
    }
}
