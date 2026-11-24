package ru.souz.agent.scenarios

import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.HexFormat
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillFile

class AgentScenarioEvaluationTest {
    @Test
    fun `evaluate skill switching in one conversation`() = runBlocking {
        withScenarioModel { model ->
            val started = TimeSource.Monotonic.markNow()
            val repeats = (System.getenv("SOUZ_SCENARIO_REPEATS") ?: "1").toInt()
            require(repeats > 0) { "SOUZ_SCENARIO_REPEATS must be positive." }
            val directory = Path.of(System.getenv("SOUZ_SCENARIO_REPORT_DIR") ?: "build/reports/agent-evaluation")
            val clock = Clock.fixed(Instant.now(), ZoneId.of("Europe/Moscow"))
            val today = LocalDate.now(clock)
            val resource = AgentScenarioEvaluationTest::class.java.getResourceAsStream("/skills/scenario-set.zip")
            val archive = requireNotNull(resource).use { it.readBytes() }
            val skills = loadSkills(archive)
            val report = ScenarioReport(directory, model.modelId, model.providerHost)
            report.write("run", mapOf(
                "schemaVersion" to 1, "startedAt" to clock.instant().toString(), "revision" to System.getenv("SOUZ_SCENARIO_REVISION"),
                "model" to model.modelId, "provider" to "OPENAI", "providerHost" to model.providerHost,
                "reasoningEffort" to model.reasoningEffort, "scenarioVersion" to "skill-switch-numeric-v1",
                "skillsSha256" to HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive)),
                "skillsCount" to skills.size, "appointmentDate" to nextAppointment(today).toString(), "repeats" to repeats,
            ))
            repeat(repeats) { index ->
                val fixture = ScenarioFixture(today)
                val checks = ScenarioChecks()
                val trace = ScenarioTrace(model, fixture, checks)
                val trialStarted = TimeSource.Monotonic.markNow()
                val error = try {
                    model.runConversation(fixture, skills, trace, clock)
                    fixture.turns.firstNotNullOfOrNull { it.error }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // Provider exceptions can contain the request including credentials.
                    failure.javaClass.simpleName
                } catch (failure: AssertionError) {
                    failure.message ?: "AssertionError"
                }
                fixture.score(checks)
                val status = when {
                    trace.calls.any { it.status == "error" } -> "provider_error"
                    error != null -> "execution_error"
                    checks.failures.isNotEmpty() -> "failed"
                    else -> "passed"
                }
                report.record(ScenarioTrial(
                    index + 1, status, error ?: checks.failures.firstOrNull(), trialStarted.elapsedNow().inWholeMilliseconds,
                    checks, trace.calls, trace.prefixes, trace.seededHistory, fixture.seed, fixture.turns,
                ))
            }
            report.finish(started.elapsedNow().inWholeMilliseconds)
            val failed = report.trials.filter { it.status != "passed" }
            assertTrue(failed.isEmpty(), failed.joinToString("\n") { "Trial ${it.repeat} ${it.status}: ${it.detail}" })
        }
    }
}

private fun loadSkills(archive: ByteArray): List<SkillBundle> {
    val files = mutableMapOf<String, MutableList<SkillFile>>()
    ZipInputStream(archive.inputStream()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val path = entry.name.split('/', limit = 3)
            if (!entry.isDirectory && path.size == 3 && path.first() == "skills") {
                files.getOrPut(path[1]) { mutableListOf() } += SkillFile(path[2], zip.readBytes())
            }
        }
    }
    check(files.size == 31) { "Expected 31 bundled Skills, found ${files.size}." }
    return files.toSortedMap().map { (id, contents) -> SkillBundle.fromFiles(SkillId(id), contents) }
}
