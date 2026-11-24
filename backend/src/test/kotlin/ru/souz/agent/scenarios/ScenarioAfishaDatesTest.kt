package ru.souz.agent.scenarios

import kotlinx.coroutines.test.runTest
import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ScenarioAfishaDatesTest {
    @Test
    fun `equivalent explicit dates return the same theatre results`() = runTest {
        val fixture = theatreFixture("2026-11-24", "2026-11-25")
        val dates = listOf("2026-11-28", "28.11.2026", "28.11", " 28.11.2026 ")
        val queries = dates.map { listOf("--date", it) } +
            dates.flatMap { from -> dates.map { to -> listOf("--from", from, "--to", to) } } +
            dates.map { listOf("--from", it, "--days", "1") }
        val expected = fixture.search(listOf("--date", "2026-11-28"))
        assertEquals(0, expected.exitCode)
        assertEquals(2, expected.stdout.lineSequence().count { it.startsWith("EVENT\t") })

        for (query in queries) assertEquals(expected, fixture.search(query), query.toString())
    }

    @Test
    fun `invalid and different dates cannot return theatre results`() = runTest {
        val fixture = theatreFixture("2026-11-24", "2026-11-25")
        val invalid = listOf("2026-11-27", "27.11.2026", "27.11", "28.11.2027",
            "2026-11-31", "31.11.2026", "31.11", "29.02.2026", "29.02", "28/11/2026", "not-a-date")
        for (date in invalid) {
            val queries = listOf(
                listOf("--date", date),
                listOf("--from", date, "--to", "2026-11-28"),
                listOf("--from", "2026-11-28", "--to", date),
            )
            for (query in queries) {
                val result = fixture.search(query)
                assertEquals(1, result.exitCode, query.toString())
                assertEquals("", result.stdout, query.toString())
            }
        }
    }

    @Test
    fun `yearless dates use the scenario calendar across new year`() = runTest {
        val fixture = theatreFixture("2026-12-29", "2026-12-30")
        val expected = fixture.search(listOf("--date", "2027-01-02"))
        assertEquals(0, expected.exitCode)
        for (query in listOf(
            listOf("--date", "02.01"),
            listOf("--date", "2.1.2027"),
            listOf("--from", "2.1", "--to", "02.01.2027"),
            listOf("--from", "2027-01-02", "--to", "2.1"),
            listOf("--from", "02.01", "--days", "1"),
        )) assertEquals(expected, fixture.search(query), query.toString())
    }

    private fun theatreFixture(today: String, appointment: String) =
        ScenarioFixture(LocalDate.parse(today), LocalDate.parse(appointment)).apply { afterTurn(5, null) }

    private suspend fun ScenarioFixture.search(dates: List<String>) = commands.execute(SandboxCommandRequest(
        runtime = SandboxCommandRuntime.BASH,
        scriptPath = "/skills/$SKILL_ID/scripts/afisha.sh",
        args = listOf("find", "--city", "Москва", "--type", "theatre") + dates,
        environment = mapOf("SOUZ_SKILL_ID" to SKILL_ID),
    ))

    private companion object {
        const val SKILL_ID = "afisha-where-to-go-cinema-theatre-concerts-tickets"
    }
}
