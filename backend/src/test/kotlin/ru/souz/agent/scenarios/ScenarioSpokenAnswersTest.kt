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
    fun `ordinary hour only readings pass theatre scoring`() = runTest {
        for (time in listOf("семнадцать", "семнадцать часов", "семнадцать часов вечера",
            "семнадцать часов ноль ноль", "семнадцать часов ноль ноль минут")) {
            val score = evaluate(mapOf(
                6 to "Северный ветер — в $time, Огни на крыше — в девятнадцать тридцать.",
            ))
            assertTrue(score.failures.isEmpty(), "$time: ${score.failures}")
        }
    }

    @Test
    fun `swapped show times fail despite correct searches and deliveries`() = runTest {
        for (answer in listOf(
            "Северный ветер — 19:30, Огни на крыше — 17:00.",
            "Северный ветер — в девятнадцать тридцать, Огни на крыше — в пять вечера.",
            "Огни на крыше — в семнадцать часов, Северный ветер — в девятнадцать тридцать.",
            "19:30 — Северный ветер; 17:00 — Огни на крыше.",
            "Северный ветер, Огни на крыше. Начало в 17:00 и 19:30.",
            "Северный ветер — 17:00 и 19:30. Огни на крыше — 17:00 и 19:30.",
            "Северный ветер — 17:00, Огни на крыше.",
            "Северный ветер — 17:00, другой спектакль — 19:30.",
        )) assertAnswerFailure(evaluate(mapOf(6 to answer)), 6)
    }

    @Test
    fun `show time associations accept reordered lists and time first schedules`() = runTest {
        for (answer in listOf(
            "Огни на крыше — 19:30; Северный ветер — 17:00.",
            "1. **Северный ветер** — в семнадцать часов.\n2. **Огни на крыше** — в девятнадцать тридцать.",
            "| Спектакль | Начало |\n| Северный ветер | 17:00 |\n| Огни на крыше | 19:30 |",
            "17:00 — Северный ветер; 19:30 — Огни на крыше.",
            "В ДЕВЯТНАДЦАТЬ\u00a0ТРИДЦАТЬ — Огни на крыше; в семнадцать часов — Северный ветер.",
        )) {
            val score = evaluate(mapOf(6 to answer))
            assertTrue(score.failures.isEmpty(), "$answer: ${score.failures}")
        }
    }

    @Test
    fun `schedule clauses can mix title first and time first phrasing`() = runTest {
        for (answer in listOf(
            "Северный ветер начинается в 17:00, а в 19:30 — Огни на крыше.",
            "В 17:00 — Северный ветер, а Огни на крыше начинаются в 19:30.",
            "Огни на крыше начинаются в 19:30, а в 17:00 — Северный ветер.",
            "В 19:30 — Огни на крыше, а Северный ветер начинается в 17:00.",
            "Северный ветер — в пять вечера; в семь тридцать вечера — Огни на крыше.",
            "В девятнадцать тридцать — Огни на крыше. Северный ветер — в семнадцать часов.",
            "17:00 — Северный ветер\nОгни на крыше — 19:30",
            "| Северный ветер | 17:00 |\n| 19:30 | Огни на крыше |",
        )) {
            val score = evaluate(mapOf(6 to answer))
            assertTrue(score.failures.isEmpty(), "$answer: ${score.failures}")
        }
    }

    @Test
    fun `mixed schedule clauses still reject swapped and unassociated times`() = runTest {
        for (answer in listOf(
            "Северный ветер начинается в 19:30, а в 17:00 — Огни на крыше.",
            "В 17:00 — Огни на крыше, а Северный ветер начинается в 19:30.",
            "Северный ветер — в семь тридцать вечера; в пять вечера — Огни на крыше.",
            "Северный ветер. В 17:00 и 19:30 — Огни на крыше.",
            "Северный ветер — 19:30; Огни на крыше — 17:00. Могу прислать Северный ветер.",
        )) assertAnswerFailure(evaluate(mapOf(6 to answer)), 6)
    }

    @Test
    fun `show titles outside the scored schedule do not need repeated times`() = runTest {
        for (answer in listOf(
            "Северный ветер — 17:00; Огни на крыше — 19:30. Могу прислать ссылку на «Огни на крыше».",
            "Северный ветер — 17:00; Огни на крыше — 19:30. Какой выбрать: Северный ветер или Огни на крыше?",
            "Нашёл Северный ветер и Огни на крыше. Северный ветер — 17:00; Огни на крыше — 19:30.",
            "Огни на крыше — в девятнадцать тридцать; Северный ветер — в пять вечера. Могу прислать Северный ветер.",
            "17:00 — Северный ветер; 19:30 — Огни на крыше. Могу прислать ссылку на «Огни на крыше».",
            "В семнадцать часов — Северный ветер; в девятнадцать тридцать — Огни на крыше. Выбирайте Северный ветер.",
        )) {
            val score = evaluate(mapOf(6 to answer))
            assertTrue(score.failures.isEmpty(), "$answer: ${score.failures}")
        }
    }

    @Test
    fun `repeated titles still require a correct schedule association for each show`() = runTest {
        for (answer in listOf(
            "Северный ветер — 19:30; Огни на крыше — 17:00. Могу прислать Огни на крыше.",
            "Северный ветер — 17:00; Огни на крыше. Какой выбрать: Северный ветер или Огни на крыше?",
            "19:30 — Северный ветер; 17:00 — Огни на крыше. Могу прислать Северный ветер.",
        )) assertAnswerFailure(evaluate(mapOf(6 to answer)), 6)
    }

    @Test
    fun `doctor links may be delivered in separate Telegram messages`() = runTest {
        val score = evaluate(deliveries = mapOf(3 to DOCTOR_LINKS.reversed()))
        assertTrue(score.failures.isEmpty(), score.failures.toString())
    }

    @Test
    fun `split doctor deliveries still reject missing duplicated and unexpected links`() = runTest {
        for (messages in listOf(
            emptyList(),
            DOCTOR_LINKS.take(1),
            listOf(DOCTOR_LINKS[0], DOCTOR_LINKS[0]),
            DOCTOR_LINKS + DOCTOR_LINKS[1],
            DOCTOR_LINKS + "https://booking.example/doctor/vera-kim-494",
        )) {
            val score = evaluate(deliveries = mapOf(3 to messages))
            assertTrue(score.failures.any { it.contains("must deliver each requested link exactly once") },
                "$messages: ${score.failures}")
        }
    }

    @Test
    fun `split doctor deliveries still reject links sent before the request`() = runTest {
        val score = evaluate(deliveries = mapOf(2 to DOCTOR_LINKS.take(1), 3 to DOCTOR_LINKS))
        assertTrue(score.failures.any { it.contains("delivered a message before the user requested a link") },
            score.failures.toString())
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
    fun `nonzero and longer spoken minutes cannot match theatre start times`() = runTest {
        val wrongEarly = listOf("17:05", "семнадцать пять", "семнадцать часов пять минут",
            "семнадцать ноль пять", "семнадцать часов ноль пять минут", "семнадцать тридцать",
            "семнадцать часов двадцать минут")
        val answers = wrongEarly.map { "Северный ветер — в $it, Огни на крыше — в девятнадцать тридцать." } +
            "Северный ветер — в пять вечера, Огни на крыше — в девятнадцать тридцать пять."
        for (answer in answers) assertAnswerFailure(evaluate(mapOf(6 to answer)), 6)
    }

    private fun assertAnswerFailure(score: ScenarioAssertionResult, turn: Int) {
        assertEquals(1, score.failures.size, score.failures.toString())
        assertTrue(score.failures.single().contains("answer on turn $turn"), score.failures.toString())
    }

    private suspend fun evaluate(
        answers: Map<Int, String> = emptyMap(),
        deliveries: Map<Int, List<String>> = emptyMap(),
    ): ScenarioAssertionResult {
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
            val defaultDelivery = when (turn) {
                3 -> DOCTOR_LINKS.joinToString("\n")
                5 -> "https://rail.example/rybinsk/608a-1420"
                7 -> "https://theatre.example/event/ogni-na-kryshe"
                else -> null
            }
            for (delivery in deliveries[turn] ?: listOfNotNull(defaultDelivery)) {
                val recipient = fixture.channels.listAll("test-user").single()
                assertIs<ChannelSendResult.Delivered>(fixture.channels.send(
                    "test-user", recipient.channelType, recipient.channelId, delivery,
                ))
            }
            fixture.afterTurn(turn, answers[turn] ?: replies[turn - 1])
        }
        return ScenarioAssertions().also(fixture::verify).result()
    }

    private companion object {
        val DOCTOR_LINKS = listOf(
            "https://booking.example/doctor/anna-petrova-317",
            "https://booking.example/doctor/ivan-sokolov-668",
        )
    }
}
