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

        val invalidQueries = listOf(
            listOf("find", "--type", "movie"),
            listOf("find"),
            listOf("ideas", "--type", "theatre"),
            listOf("find", "--type", "theatre", "extra"),
        )
        for (query in invalidQueries) {
            val result = fixture.commands.execute(theatreRequest(query))
            assertEquals(1, result.exitCode, query.joinToString(" "))
            assertEquals("", result.stdout)
        }

        val result = fixture.commands.execute(theatreRequest(listOf("find", "--type", "theatre")))
        assertEquals(0, result.exitCode)
        assertEquals(2, result.stdout.lineSequence().count { it.startsWith("EVENT\t") })

        val limited = fixture.commands.execute(theatreRequest(listOf("find", "--type", "theatre", "--limit", "1")))
        assertEquals(0, limited.exitCode)
        assertEquals(1, limited.stdout.lineSequence().count { it.startsWith("EVENT\t") })
    }

    @Test
    fun `train fixture rejects positional arguments and malformed options`() = runTest {
        val fixture = ScenarioFixture(EvaluationCase.TRAIN, appointment)
        val request = trainRequest(emptyList())
        val invalidQueries = listOf(
            listOf("find") + request.args,
            request.args + "extra",
            request.args + "--limit",
            request.args + listOf("--unknown", "1"),
            request.args + listOf("--sort", "unknown"),
        )
        for (args in invalidQueries) {
            val result = fixture.commands.execute(request.copy(args = args))
            assertEquals(1, result.exitCode, args.joinToString(" "))
            assertEquals("", result.stdout)
        }

        val result = fixture.commands.execute(trainRequest(listOf("--limit=2")))
        assertEquals(0, result.exitCode)
        assertEquals(2, result.stdout.lineSequence().count { it.startsWith("TRAIN\t") })
    }

    @Test
    fun `doctor fixture rejects malformed search options`() = runTest {
        val fixture = ScenarioFixture(EvaluationCase.DOCTOR_SEND, appointment)
        val request = SandboxCommandRequest(
            runtime = SandboxCommandRuntime.BASH,
            scriptPath = "/skills/doctor-appointment/scripts/doctor.sh",
            args = listOf("find", "--city", "Москва", "--near", "Новослободская", "--speciality", "терапевт", "--limit", "5"),
            environment = mapOf("SOUZ_SKILL_ID" to "doctor-appointment"),
        )
        val invalidOptions = listOf(listOf("extra"), listOf("--max-price"), listOf("--max-price", "unknown"))
        for (options in invalidOptions) {
            val result = fixture.commands.execute(request.copy(args = request.args + options))
            assertEquals(1, result.exitCode, options.joinToString(" "))
            assertEquals("", result.stdout)
        }

        val result = fixture.commands.execute(request)
        assertEquals(0, result.exitCode)
        assertEquals(5, result.stdout.lineSequence().count { it.startsWith("DOCTOR\t") })
    }

    @Test
    fun `train fixture applies limit and departure filters`() = runTest {
        val fixture = ScenarioFixture(EvaluationCase.TRAIN, appointment)
        val filters = listOf(
            emptyList<String>() to 2,
            listOf("--limit", "1") to 1,
            listOf("--after", "12:00") to 1,
            listOf("--before", "12:00") to 1,
            listOf("--overnight") to 0,
            listOf("--class", "СВ") to 0,
            listOf("--name", "Ласточка") to 0,
        )
        for ((query, matches) in filters) {
            val result = fixture.commands.execute(trainRequest(query))
            assertEquals(0, result.exitCode)
            assertEquals(matches, result.stdout.lineSequence().count { it.startsWith("TRAIN\t") }, query.joinToString(" "))
        }
    }

    @Test
    fun `train scenario requires two initial matches even when the correct link is delivered`() = runTest {
        val fixture = ScenarioFixture(EvaluationCase.TRAIN, appointment)
        val result = fixture.commands.execute(trainRequest(listOf("--after", "12:00")))
        val link = result.stdout.lineSequence().single { it.startsWith("TRAIN\t") }.substringAfterLast('\t')
        assertEquals("https://rail.example/rybinsk/608a-1420", link)
        fixture.afterTurn(1, "Here is the afternoon train.")

        val channel = fixture.channels.listAll("test-user").single()
        assertIs<ChannelSendResult.Delivered>(fixture.channels.send("test-user", "telegram", channel.channelId, link))

        val failure = assertFailsWith<AssertionError> { fixture.verify() }
        assertContains(failure.message.orEmpty(), "did not return the expected initial search results")
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

    private fun trainRequest(filters: List<String>) = SandboxCommandRequest(
        runtime = SandboxCommandRuntime.BASH,
        scriptPath = "/skills/train-tickets/scripts/trains.sh",
        args = listOf("--from", "Санкт-Петербург", "--to", "Рыбинск", "--date", appointment.plusDays(2).toString()) + filters,
        environment = mapOf("SOUZ_SKILL_ID" to "train-tickets"),
    )
}
