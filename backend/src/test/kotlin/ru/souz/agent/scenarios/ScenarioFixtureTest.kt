package ru.souz.agent.scenarios

import kotlinx.coroutines.test.runTest
import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ScenarioFixtureTest {
    @Test
    fun `theatre fixture requires a theatre search`() = runTest {
        val appointment = LocalDate.of(2026, 11, 25)
        val fixture = ScenarioFixture(EvaluationCase.THEATRE, appointment)
        val filters = listOf("--city", "Москва", "--date", appointment.plusDays(3).toString())
        val request = SandboxCommandRequest(
            runtime = SandboxCommandRuntime.BASH,
            scriptPath = "/skills/afisha/scripts/afisha.sh",
            environment = mapOf("SOUZ_SKILL_ID" to "afisha-where-to-go-cinema-theatre-concerts-tickets"),
        )

        for (query in listOf(listOf("find", "--type", "movie"), listOf("find"), listOf("ideas", "--type", "theatre"))) {
            val result = fixture.commands.execute(request.copy(args = query + filters))
            assertEquals(1, result.exitCode, query.joinToString(" "))
            assertEquals("", result.stdout)
        }

        val result = fixture.commands.execute(request.copy(args = listOf("find", "--type", "theatre") + filters))
        assertEquals(0, result.exitCode)
        assertEquals(2, result.stdout.lineSequence().count { it.startsWith("EVENT\t") })
    }
}
