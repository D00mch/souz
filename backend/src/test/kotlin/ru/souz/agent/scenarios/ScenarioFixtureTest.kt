package ru.souz.agent.scenarios

import kotlinx.coroutines.test.runTest
import ru.souz.backend.channels.ChannelSendResult
import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ScenarioFixtureTest {
    private val today = LocalDate.of(2026, 11, 18)
    private val appointment = LocalDate.of(2026, 11, 25)

    @Test
    fun `search fixtures validate commands and apply filters`() = runTest {
        assertSearchRows(
            EvaluationCase.THEATRE, "EVENT\t",
            theatreRequest("find", "--type", "movie") to null,
            theatreRequest("find") to null,
            theatreRequest("ideas", "--type", "theatre") to null,
            theatreRequest("find", "--type", "theatre") to 2,
            theatreRequest("find", "--type", "theatre", "--limit", "1") to 1,
        )
        val train = trainRequest()
        assertSearchRows(
            EvaluationCase.TRAIN, "TRAIN\t",
            train.copy(args = listOf("find") + train.args) to null,
            trainRequest("extra") to null,
            train to 2,
            trainRequest("--limit", "1") to 1,
            trainRequest("--overnight") to 0,
        )
        assertSearchRows(
            EvaluationCase.DOCTOR_SEND, "DOCTOR\t",
            doctorRequest("--days", "1") to 0,
            doctorRequest("--days", "6") to 0,
            doctorRequest("--days", "7") to 5,
        )
    }

    @Test
    fun `train scenario requires two initial matches even when the correct link is delivered`() = runTest {
        val fixture = ScenarioFixture(EvaluationCase.TRAIN, today, appointment)
        val result = fixture.commands.execute(trainRequest("--after", "12:00"))
        val link = result.stdout.lineSequence().single { it.startsWith("TRAIN\t") }.substringAfterLast('\t')
        assertEquals("https://rail.example/rybinsk/608a-1420", link)
        fixture.afterTurn(1, "The departures are at 8:10 and 14:20.")

        val channel = fixture.channels.listAll("test-user").single()
        assertIs<ChannelSendResult.Delivered>(fixture.channels.send("test-user", "telegram", channel.channelId, link))

        val assertions = ScenarioAssertions()
        fixture.verify(assertions)
        val score = assertions.result()
        assertEquals(4, score.passed)
        assertEquals(5, score.total)
        assertContains(score.failures.single(), "did not return the expected initial search results")
    }

    @Test
    fun `an incomplete search reply and a repeated link fail independently`() = runTest {
        val fixture = ScenarioFixture(EvaluationCase.THEATRE, today, appointment)
        val result = fixture.commands.execute(theatreRequest("find", "--type", "theatre"))
        assertEquals(0, result.exitCode)
        fixture.afterTurn(1, "Огни на крыше начинаются в 19:30.")

        val channel = fixture.channels.listAll("test-user").single()
        val link = "https://theatre.example/event/ogni-na-kryshe"
        assertIs<ChannelSendResult.Delivered>(fixture.channels.send("test-user", "telegram", channel.channelId, "$link\n$link"))

        val assertions = ScenarioAssertions()
        fixture.verify(assertions)
        val score = assertions.result()
        assertEquals(3, score.passed)
        assertEquals(5, score.total)
        assertContains(score.failures[0], "did not present the expected options")
        assertContains(score.failures[1], "must deliver each requested link exactly once")
    }

    private suspend fun assertSearchRows(
        case: EvaluationCase,
        recordPrefix: String,
        vararg searches: Pair<SandboxCommandRequest, Int?>,
    ) {
        val fixture = ScenarioFixture(case, today, appointment)
        for ((request, expected) in searches) {
            val result = fixture.commands.execute(request)
            val count = result.stdout.lineSequence().count { it.startsWith(recordPrefix) }
            val rows = count.takeIf { result.exitCode == 0 }
            assertEquals(expected, rows, "${case.id}: ${request.args.joinToString(" ")}")
        }
    }

    private fun theatreRequest(vararg query: String) = SandboxCommandRequest(
        runtime = SandboxCommandRuntime.BASH,
        scriptPath = "/skills/afisha/scripts/afisha.sh",
        args = query.toList() + listOf("--city", "Москва", "--date", appointment.plusDays(3).toString()),
        environment = mapOf("SOUZ_SKILL_ID" to "afisha-where-to-go-cinema-theatre-concerts-tickets"),
    )

    private fun trainRequest(vararg filters: String) = SandboxCommandRequest(
        runtime = SandboxCommandRuntime.BASH,
        scriptPath = "/skills/train-tickets/scripts/trains.sh",
        args = listOf("--from", "Санкт-Петербург", "--to", "Рыбинск", "--date", appointment.plusDays(2).toString()) + filters,
        environment = mapOf("SOUZ_SKILL_ID" to "train-tickets"),
    )

    private fun doctorRequest(vararg filters: String) = SandboxCommandRequest(
        runtime = SandboxCommandRuntime.BASH,
        scriptPath = "/skills/doctor-appointment/scripts/doctor.sh",
        args = listOf("find", "--city", "Москва", "--near", "Новослободская", "--speciality", "терапевт", "--limit", "5") + filters,
        environment = mapOf("SOUZ_SKILL_ID" to "doctor-appointment"),
    )
}
