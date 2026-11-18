package ru.souz.agent.scenarios

import ru.souz.backend.channels.ChannelDescriptor
import ru.souz.backend.channels.ChannelProvider
import ru.souz.backend.channels.ChannelProviderRegistry
import ru.souz.backend.channels.ChannelSendResult
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.runtime.sandbox.SandboxCommandExecutor
import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.SandboxCommandResult
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal enum class EvaluationCase(val id: String) {
    DOCTOR_SEND("doctor-send-link"),
    DOCTOR_CHOOSE("doctor-choose-and-send"),
    TRAIN("rybinsk-train"),
    THEATRE("afisha-theatre"),
    SKILL_SWITCH("skill-switch"),
}

internal class ScenarioFixture(private val case: EvaluationCase, appointment: LocalDate) {
    private val doctor = doctorTask(appointment, case)
    private val train = trainTask(appointment.plusDays(2))
    private val theatre = theatreTask(appointment.plusDays(3))
    private val tasks = when (case) {
        EvaluationCase.DOCTOR_SEND, EvaluationCase.DOCTOR_CHOOSE -> listOf(doctor)
        EvaluationCase.TRAIN -> listOf(train)
        EvaluationCase.THEATRE -> listOf(theatre)
        EvaluationCase.SKILL_SWITCH -> listOf(doctor, train, theatre)
    }
    private val taskByTurn = tasks.flatMap { task -> List(task.turns.size) { task } }
    val turns = tasks.flatMap { it.turns }
    val initialHistory = if (tasks.first() === doctor) listOf(
        LLMRequest.Message(LLMMessageRole.user, "Болит спина."),
        LLMRequest.Message(LLMMessageRole.assistant, "Помочь подобрать врача?"),
    ) else emptyList()

    private data class SearchResult(val args: List<String>, val output: String)

    private data class Turn(
        var answer: String = "",
        val searches: MutableList<SearchResult> = mutableListOf(),
        val deliveries: MutableList<String> = mutableListOf(),
    )

    private val evidence = List(turns.size) { Turn() }
    private var turn = 0

    val commands = object : SandboxCommandExecutor {
        override suspend fun execute(request: SandboxCommandRequest): SandboxCommandResult {
            val task = taskByTurn[turn]
            val matchesScript = request.environment["SOUZ_SKILL_ID"] == task.skillId &&
                request.runtime == SandboxCommandRuntime.BASH &&
                request.scriptPath?.replace('\\', '/')?.endsWith("/${task.script}") == true &&
                request.script == null
            val data = if (matchesScript) task.search(request.args) else null
            if (data == null) return SandboxCommandResult(1, "", "No result for this fixture query.")
            evidence[turn].searches += SearchResult(request.args, data)
            return SandboxCommandResult(0, data, "")
        }
    }

    val channels = ChannelProviderRegistry(listOf(object : ChannelProvider {
        override val channelType = "telegram"
        override suspend fun listChannels(userId: String) =
            listOf(ChannelDescriptor(channelType, TELEGRAM_ID, "Scenario Telegram"))

        override suspend fun sendMessage(userId: String, channelId: String, text: String): ChannelSendResult {
            if (channelId != TELEGRAM_ID) return ChannelSendResult.Failed("Unknown fixture recipient.")
            evidence[turn].deliveries += text
            return ChannelSendResult.Delivered("Scenario delivery accepted.")
        }
    }))

    fun afterTurn(completed: Int, answer: String) {
        evidence[completed - 1].answer = answer
        turn = completed
    }

    fun answerOnTurn(number: Int): String? = evidence[number - 1].answer.takeIf(String::isNotEmpty)

    fun deliveriesOnTurn(number: Int): List<String> = evidence[number - 1].deliveries.toList()

    fun verify() {
        var first = 0
        for (task in tasks) {
            val last = first + task.turns.lastIndex
            assertTrue(evidence[first].searches.any { task.initialQuery(it.args) },
                "${task.skillId} did not return the expected initial search results.")
            if (case == EvaluationCase.DOCTOR_SEND) verifyDoctorBudgetSearch()
            task.choice?.let { (index, name) ->
                assertTrue(evidence[first + index].answer.contains(name, ignoreCase = true),
                    "${task.skillId} did not name the requested choice.")
            }
            assertTrue((first until last).none { evidence[it].deliveries.isNotEmpty() },
                "${task.skillId} delivered a message before the user requested a link.")
            assertEquals(1, evidence[last].deliveries.size,
                "${task.skillId} must deliver exactly one Telegram message on the final turn.")
            val links = URL.findAll(evidence[last].deliveries.single())
                .map { it.value.trimEnd('.', ',', ';', '!', '?') }.toList()
            assertEquals(task.expectedLinks.sorted(), links.sorted(),
                "${task.skillId} must deliver each requested link exactly once.")
            first = last + 1
        }
    }

    private fun verifyDoctorBudgetSearch() {
        val search = assertNotNull(
            evidence[1].searches.lastOrNull { it.args.option("--max-price")?.toIntOrNull() == BUDGET },
            "${doctor.skillId} must rerun the search with a price limit of $BUDGET on the budget turn.",
        )
        val links = search.output.lineSequence()
            .filter { it.startsWith("DOCTOR\t") }
            .map { it.substringAfterLast('\t') }
            .toList()
        assertEquals(2, links.size, "${doctor.skillId} must return exactly two doctors on the budget turn.")
        assertEquals(doctor.expectedLinks, links.toSet(), "${doctor.skillId} must return only the eligible doctors.")
    }
}

private data class ScenarioTask(
    val skillId: String,
    val script: String,
    val turns: List<String>,
    val search: (List<String>) -> String?,
    val expectedLinks: Set<String>,
    val initialQuery: (List<String>) -> Boolean = { true },
    val choice: Pair<Int, String>? = null,
)

private data class Doctor(val name: String, val surname: String, val price: Int, val reviews: Int, val slug: String) {
    val link = "https://booking.example/doctor/$slug"
}

private fun doctorTask(appointment: LocalDate, case: EvaluationCase): ScenarioTask {
    val choose = case == EvaluationCase.DOCTOR_CHOOSE
    val doctors = listOf(
        Doctor("Вера Ким", "Ким", 3900, 145, "vera-kim-494"),
        Doctor("Анна Петрова", "Петрова", 2200, 18, "anna-petrova-317"),
        Doctor("Сергей Волков", "Волков", 3100, 104, "sergei-volkov-526"),
        Doctor("Иван Соколов", "Соколов", 2400, 73, "ivan-sokolov-668"),
        Doctor("Олег Лебедев", "Лебедев", 2700, 89, "oleg-lebedev-802"),
    )
    val eligible = doctors.filter { it.price <= BUDGET }
    val selected = eligible.maxBy { it.reviews }
    val slot = appointment.format(DateTimeFormatter.ofPattern("dd.MM")) + " 18:00"
    return ScenarioTask(
        skillId = "doctor-appointment",
        script = "scripts/doctor.sh",
        turns = buildList {
            add("Найди пять терапевтов в Москве рядом с Новослободской в ближайшую среду после сегодняшнего дня в 18:00. Пока только список.")
            add(if (case == EvaluationCase.DOCTOR_SEND) {
                "Обнови поиск с ограничением по цене. Покажи только тех, у кого приём стоит не больше $BUDGET рублей."
            } else {
                "Оставь только тех, у кого приём стоит не больше $BUDGET рублей."
            })
            if (choose) add("У кого из оставшихся больше отзывов? Выбери этого врача и скажи почему.")
            add(if (choose) "Пришли в Телеграм ссылку на него." else "Пришли в Телеграм обе ссылки на варианты до 2500 рублей.")
        },
        search = { args ->
            if (args.firstOrNull() != "find" ||
                !args.option("--city").equals("Москва", ignoreCase = true) ||
                !args.option("--speciality").equals("терапевт", ignoreCase = true) ||
                args.option("--near")?.contains("новослободск", ignoreCase = true) != true ||
                "--online" in args || "--kids" in args
            ) {
                null
            } else {
                val priceCap = args.option("--max-price")?.toIntOrNull() ?: Int.MAX_VALUE
                val limit = args.option("--limit")?.toIntOrNull()?.coerceAtLeast(0) ?: 3
                val found = doctors.filter { it.price <= priceCap }.take(limit)
                if (found.isEmpty()) "NO_RESULTS" else buildList {
                    add("SEARCH\tтерапевт\tМосква\tНовослободская\tнайдено: ${found.size}")
                    found.forEach { doctor ->
                        add("DOCTOR\t${doctor.name}\tтерапевт\tстаж 8 лет\tрейтинг 4.8 (${doctor.reviews} отзывов)\t" +
                            "${doctor.price} ₽\tКлиника у Новослободской, Москва, метро Новослободская\tонлайн: нет\t$slot\t${doctor.link}")
                    }
                }.joinToString("\n")
            }
        },
        expectedLinks = if (choose) setOf(selected.link) else eligible.mapTo(mutableSetOf()) { it.link },
        initialQuery = { it.option("--max-price") == null && (it.option("--limit")?.toIntOrNull() ?: 3) >= doctors.size },
        choice = if (choose) 2 to selected.surname else null,
    )
}

private fun trainTask(date: LocalDate): ScenarioTask {
    val slot = date.format(DateTimeFormatter.ofPattern("dd.MM"))
    val early = "https://rail.example/rybinsk/606a-0810"
    val late = "https://rail.example/rybinsk/608a-1420"
    return ScenarioTask(
        skillId = "train-tickets",
        script = "scripts/trains.sh",
        turns = listOf(
            "Покажи два прямых поезда из Санкт-Петербурга в Рыбинск на $date. Пока только расписание.",
            "Пришли в Телеграм ссылку на тот, который отправляется после полудня.",
        ),
        search = { args ->
            val from = args.option("--from").orEmpty().lowercase()
            if (from !in SAINT_PETERSBURG_NAMES ||
                !args.option("--to").equals("Рыбинск", ignoreCase = true) ||
                args.option("--date") != date.toString()
            ) {
                null
            } else {
                listOf(
                    "ROUTE\tСанкт-Петербург\tРыбинск",
                    "TRAIN\t$slot 08:10\t$slot 16:35\t8 ч 25 мин\t606А\t-\tЛадожский — Рыбинск\tкупе\t$early",
                    "TRAIN\t$slot 14:20\t$slot 22:50\t8 ч 30 мин\t608А\t-\tЛадожский — Рыбинск\tкупе\t$late",
                ).joinToString("\n")
            }
        },
        expectedLinks = setOf(late),
    )
}

private fun theatreTask(date: LocalDate): ScenarioTask {
    val early = "https://theatre.example/event/severnyi-veter"
    val late = "https://theatre.example/event/ogni-na-kryshe"
    return ScenarioTask(
        skillId = "afisha-where-to-go-cinema-theatre-concerts-tickets",
        script = "scripts/afisha.sh",
        turns = listOf(
            "Подбери два спектакля в Москве на $date. Пока только варианты со временем начала.",
            "Пришли в Телеграм ссылку на спектакль, который начинается в 19:30.",
        ),
        search = { args ->
            if (args.firstOrNull() != "find" ||
                args.option("--type") != "theatre" ||
                !args.option("--city").equals("Москва", ignoreCase = true) ||
                args.option("--date") != date.toString()
            ) {
                null
            } else {
                listOf(
                    "SEARCH\tМосква\tспектакли\tнайдено 2",
                    "EVENT\t104\ttheatre\tСеверный ветер\tдрама\t16+\t8.0\t90 мин\tСценическая драма\t$date 17:00\t$early",
                    "EVENT\t209\ttheatre\tОгни на крыше\tкомедия\t12+\t8.1\t110 мин\tКамерная комедия\t$date 19:30\t$late",
                ).joinToString("\n")
            }
        },
        expectedLinks = setOf(late),
    )
}

private fun List<String>.option(name: String): String? =
    lastIndexOf(name).takeIf { it >= 0 }?.let { getOrNull(it + 1) }

private const val TELEGRAM_ID = "telegram-fixture-1050"
private const val BUDGET = 2500
private val SAINT_PETERSBURG_NAMES = setOf("санкт-петербург", "санкт петербург", "питер", "спб")
private val URL = Regex("""https?://[^\s<>()\[\]"']+""")
