package ru.souz.agent.scenarios

import kotlinx.coroutines.test.runTest
import ru.souz.backend.channels.ChannelSendResult
import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScenarioSpokenAnswersTest {
    @Test
    fun `full names and numeric times still pass`() = runTest {
        val score = evaluate()
        assertTrue(score.failures.isEmpty(), score.failures.toString())
    }

    @Test
    fun `surnames and spoken times pass with the same searches and deliveries`() = runTest {
        val score = evaluate(mapOf(
            1 to "Терапевты Ким, Петрова, Волков, Соколов и Лебедев.",
            2 to "До 2500 рублей подходят Петрова и Соколов.",
            4 to "Первый отправляется в восемь десять, второй — в четырнадцать двадцать.",
            6 to "Северный ветер — в пять вечера, Огни на крыше — в девятнадцать тридцать.",
        ))
        assertTrue(score.failures.isEmpty(), score.failures.toString())
    }

    @Test
    fun `spoken times accept units and explicit afternoon or evening markers`() = runTest {
        val score = evaluate(mapOf(
            4 to "В ВОСЕМЬ\u00a0ЧАСОВ ДЕСЯТЬ МИНУТ утра и в два часа двадцать минут дня.",
            6 to "Северный ветер — в семнадцать ноль ноль, Огни на крыше — в семь тридцать вечера.",
        ))
        assertTrue(score.failures.isEmpty(), score.failures.toString())
    }

    @Test
    fun `missing and ineligible surnames still fail answer scoring`() = runTest {
        for (answer in listOf("Петрова.", "Петрова, Соколов и Ким.", "Петрова и Соколовский.")) {
            assertAnswerFailure(evaluate(mapOf(2 to answer)), 2)
        }
    }

    @Test
    fun `incorrect and incomplete departure times still fail answer scoring`() = runTest {
        for (answer in listOf(
            "В восемь двадцать и в четырнадцать двадцать.",
            "В восемь десять.",
            "В восемь десять и в два двадцать ночи.",
            "В восемь десять вечера и в четырнадцать двадцать.",
            "В восемь часов десять минут вечера и в четырнадцать двадцать.",
            "В восемь десять и в четырнадцать двадцать пять.",
            "В восемь десять и в четырнадцать двадцать одна минута.",
            "108:10 и 14:20.",
            "Восемь десятилетий и четырнадцать двадцатых.",
        )) assertAnswerFailure(evaluate(mapOf(4 to answer)), 4)
    }

    @Test
    fun `a longer spoken minute cannot match the theatre start time`() = runTest {
        assertAnswerFailure(evaluate(mapOf(
            6 to "Северный ветер — в пять вечера, Огни на крыше — в девятнадцать тридцать пять.",
        )), 6)
    }

    private fun assertAnswerFailure(score: ScenarioAssertionResult, turn: Int) {
        assertEquals(1, score.failures.size, score.failures.toString())
        assertTrue(score.failures.single().contains("answer on turn $turn"), score.failures.toString())
    }

    private suspend fun evaluate(answers: Map<Int, String> = emptyMap()): ScenarioAssertionResult {
        val fixture = ScenarioFixture(LocalDate.parse("2026-11-24"), LocalDate.parse("2026-11-25"))
        val replies = listOf(
            "Вера Ким, Анна Петрова, Сергей Волков, Иван Соколов, Олег Лебедев.",
            "Анна Петрова и Иван Соколов.",
            "Обе ссылки отправлены.",
            "Поезда в 8:10 и 14:20.",
            "Ссылка отправлена.",
            "Северный ветер — 17:00, Огни на крыше — 19:30.",
            "Ссылка отправлена.",
        )
        for (turn in 1..fixture.turns.size) {
            val query = when (turn) {
                1, 2 -> Triple("doctor-appointment", "doctor.sh",
                    listOf("find", "--city", "Москва", "--speciality", "терапевт", "--near", "Новослободская",
                        "--limit", "5") + if (turn == 2) listOf("--max-price", "2500") else emptyList())
                4 -> Triple("train-tickets", "trains.sh",
                    listOf("--from", "Санкт-Петербург", "--to", "Рыбинск", "--date", "2026-11-27"))
                6 -> Triple("afisha-where-to-go-cinema-theatre-concerts-tickets", "afisha.sh",
                    listOf("find", "--city", "Москва", "--type", "theatre", "--date", "2026-11-28"))
                else -> null
            }
            if (query != null) {
                val (skillId, script, args) = query
                val result = fixture.commands.execute(SandboxCommandRequest(
                    runtime = SandboxCommandRuntime.BASH,
                    scriptPath = "/skills/$skillId/scripts/$script",
                    args = args,
                    environment = mapOf("SOUZ_SKILL_ID" to skillId),
                ))
                assertEquals(0, result.exitCode, result.stderr)
            }
            val delivery = when (turn) {
                3 -> "https://booking.example/doctor/anna-petrova-317\nhttps://booking.example/doctor/ivan-sokolov-668"
                5 -> "https://rail.example/rybinsk/608a-1420"
                7 -> "https://theatre.example/event/ogni-na-kryshe"
                else -> null
            }
            if (delivery != null) {
                val recipient = fixture.channels.listAll("test-user").single()
                assertIs<ChannelSendResult.Delivered>(fixture.channels.send(
                    "test-user", recipient.channelType, recipient.channelId, delivery,
                ))
            }
            fixture.afterTurn(turn, answers[turn] ?: replies[turn - 1])
        }
        return ScenarioAssertions().also(fixture::verify).result()
    }
}
