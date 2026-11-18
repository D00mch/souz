package ru.souz.agent.scenarios

import kotlinx.coroutines.test.runTest
import ru.souz.backend.channels.ChannelSendResult
import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ScenarioFixtureTest {
    private val appointment = LocalDate.of(2026, 11, 25)

    @Test
    fun `theatre fixture requires a theatre search`() = runTest {
        val fixture = ScenarioFixture(EvaluationCase.THEATRE, appointment)

        for (query in listOf(listOf("find", "--type", "movie"), listOf("find"), listOf("ideas", "--type", "theatre"))) {
            val result = fixture.commands.execute(theatreRequest(query))
            assertEquals(1, result.exitCode, query.joinToString(" "))
            assertEquals("", result.stdout)
        }

        val result = fixture.commands.execute(theatreRequest(listOf("find", "--type", "theatre")))
        assertEquals(0, result.exitCode)
        assertEquals(2, result.stdout.lineSequence().count { it.startsWith("EVENT\t") })
    }

    @Test
    fun `telegram delivery rejects a repeated link`() = runTest {
        val fixture = ScenarioFixture(EvaluationCase.THEATRE, appointment)
        val result = fixture.commands.execute(theatreRequest(listOf("find", "--type", "theatre")))
        assertEquals(0, result.exitCode)
        fixture.afterTurn(1, "Here are two shows.")

        val channel = fixture.channels.listAll("test-user").single()
        val link = "https://theatre.example/event/ogni-na-kryshe"
        assertIs<ChannelSendResult.Delivered>(fixture.channels.send("test-user", "telegram", channel.channelId, "$link\n$link"))

        val failure = assertFailsWith<AssertionError> { fixture.verify() }
        assertContains(failure.message.orEmpty(), "must deliver each requested link exactly once")
    }

    private fun theatreRequest(query: List<String>) = SandboxCommandRequest(
        runtime = SandboxCommandRuntime.BASH,
        scriptPath = "/skills/afisha/scripts/afisha.sh",
        args = query + listOf("--city", "Москва", "--date", appointment.plusDays(3).toString()),
        environment = mapOf("SOUZ_SKILL_ID" to "afisha-where-to-go-cinema-theatre-concerts-tickets"),
    )
}
