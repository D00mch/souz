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

private const val SCENARIO_ID = "skill-switch"

class AgentScenarioEvaluationTest {
    @Test
    fun `evaluate skill switching in one conversation`() = runBlocking {
        val suiteStarted = TimeSource.Monotonic.markNow()
        ScenarioLiveModel.fromEnvironment().use { model ->
            val skills = ScenarioSkillSet.load()
            val repeats = (System.getenv("SOUZ_SCENARIO_REPEATS") ?: "1").toInt()
            require(repeats > 0) { "SOUZ_SCENARIO_REPEATS must be positive." }
            val today = LocalDate.now(ZoneId.of(SCENARIO_ZONE))
            val appointment = today.with(TemporalAdjusters.next(DayOfWeek.WEDNESDAY))
            val reportDir = Path.of(System.getenv("SOUZ_SCENARIO_REPORT_DIR") ?: "build/reports/agent-evaluation")
            val failures = mutableListOf<String>()
            ScenarioEvaluationReport(reportDir, model, skills, appointment, repeats).use { report ->
                println("Agent evaluation HTML: ${report.htmlPath.toAbsolutePath()}")
                println("Agent evaluation JSONL: ${report.path.toAbsolutePath()}")
                repeat(repeats) { index ->
                    val fixture = ScenarioFixture(today, appointment)
                    val assertions = ScenarioAssertions()
                    val trace = ScenarioTrace(
                        model.reasoningEffort, model.wire,
                        initialHistory = fixture.initialHistory,
                        assertions = assertions,
                    )
                    val started = TimeSource.Monotonic.markNow()
                    val executionError = try {
                        model.runConversation(fixture, skills.bundles, trace)
                        null
                    } catch (failure: AssertionError) {
                        failure.message?.lineSequence()?.firstOrNull() ?: "AssertionError"
                    } catch (failure: Exception) {
                        currentCoroutineContext().ensureActive()
                        val frame = failure.stackTrace.firstOrNull { it.className.startsWith("ru.souz.") }
                        listOfNotNull(failure.javaClass.simpleName, frame?.toString()).joinToString(" at ")
                    }
                    trace.assertHistoryPreserved(fixture.turns.size)
                    fixture.verify(assertions)
                    val score = assertions.result()
                    val status = when {
                        trace.calls.any { it.status == "error" } -> "provider_error"
                        executionError != null || trace.hasTurnErrors -> "execution_error"
                        score.passed < score.total -> "failed"
                        else -> "passed"
                    }
                    val detail = executionError ?: score.failures.firstOrNull()
                    if (status != "passed") {
                        failures += "$SCENARIO_ID #${index + 1}: $status (${score.passed}/${score.total} checks; $detail)"
                    }
                    report.trial(SCENARIO_ID, index + 1, status, detail, started.elapsedNow().inWholeMilliseconds, trace, fixture, score)
                }
                report.finish(suiteStarted.elapsedNow().inWholeMilliseconds)
            }
            assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        }
    }
}
