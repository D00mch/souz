package ru.souz.agent.scenarios

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.time.temporal.TemporalAdjusters
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

private const val BUDGET = 2500
private const val TELEGRAM = "telegram-fixture-1050"
private const val DOCTOR = "doctor-appointment"
private const val TRAIN = "train-tickets"
private const val THEATRE = "afisha-where-to-go-cinema-theatre-concerts-tickets"
private val url = Regex("""https?://[^\s<>()\[\]"']+""")
private val time = Regex("""(?<!\d)(?:[01]\d|2[0-3]):[0-5]\d(?!\d)""")
private val shortDate = DateTimeFormatter.ofPattern("dd.MM")

internal fun nextAppointment(today: LocalDate): LocalDate = today.with(TemporalAdjusters.next(DayOfWeek.WEDNESDAY))

internal class ScenarioFixture(private val today: LocalDate) {
    private val appointment = nextAppointment(today)
    private val trainDate = appointment.plusDays(2)
    private val theatreDate = appointment.plusDays(3)
    val seed = listOf(
        LLMRequest.Message(LLMMessageRole.user, "Болит спина."),
        LLMRequest.Message(LLMMessageRole.assistant, "Помочь подобрать врача?"),
    )
    val turns = listOf(
        "Найди пять терапевтов в Москве рядом с Новослободской в ближайшую среду после сегодняшнего дня в 18:00. Пока только список.",
        "Обнови поиск с ограничением по цене. Покажи только тех, у кого приём стоит не больше $BUDGET рублей.",
        "Пришли в Телеграм обе ссылки на варианты до $BUDGET рублей.",
        "Покажи два прямых поезда из Санкт-Петербурга в Рыбинск на $trainDate. Время отправления напиши цифрами в формате ЧЧ:ММ.",
        "Пришли в Телеграм ссылку на тот, который отправляется после полудня.",
        "Подбери два спектакля в Москве на $theatreDate. Каждый спектакль запиши отдельной строкой: название и время начала цифрами в формате ЧЧ:ММ.",
        "Пришли в Телеграм ссылку на спектакль, который начинается в 19:30.",
    ).mapIndexed { index, prompt -> ScenarioTurn(index + 1, prompt) }
    var current = turns.first()

    private val doctorSlot = "${appointment.format(shortDate)} 18:00"
    private data class Doctor(val name: String, val price: Int, val reviews: Int, val slug: String) {
        val surname = name.substringAfterLast(' ')
        val link = "https://booking.example/doctor/$slug"
        fun row(slot: String) = "DOCTOR\t$name\tтерапевт\tстаж 8 лет\tрейтинг 4.8 ($reviews отзывов)\t" +
            "$price ₽\tКлиника у Новослободской, Москва, метро Новослободская\tонлайн: нет\t$slot\t$link"
    }

    private val doctors = listOf(
        Doctor("Вера Ким", 3900, 145, "vera-kim-494"),
        Doctor("Анна Петрова", 2200, 18, "anna-petrova-317"),
        Doctor("Сергей Волков", 3100, 104, "sergei-volkov-526"),
        Doctor("Иван Соколов", 2400, 73, "ivan-sokolov-668"),
        Doctor("Олег Лебедев", 2700, 89, "oleg-lebedev-802"),
    )
    private val eligible = doctors.filter { it.price <= BUDGET }
    private val trainSlot = trainDate.format(shortDate)
    private data class Train(val departure: String, val arrival: String, val duration: String, val number: String, val link: String)
    private val trains = listOf(
        Train("08:10", "16:35", "8 ч 25 мин", "606А", "https://rail.example/rybinsk/606a-0810"),
        Train("14:20", "22:50", "8 ч 30 мин", "608А", "https://rail.example/rybinsk/608a-1420"),
    )

    private data class Show(val title: String, val start: String, val id: Int, val details: String, val link: String)
    private val shows = listOf(
        Show("Северный ветер", "17:00", 104, "драма\t16+\t8.0\t90 мин\tСценическая драма",
            "https://theatre.example/event/severnyi-veter"),
        Show("Огни на крыше", "19:30", 209, "комедия\t12+\t8.1\t110 мин\tКамерная комедия",
            "https://theatre.example/event/ogni-na-kryshe"),
    )

    val commands = object : SandboxCommandExecutor {
        override suspend fun execute(request: SandboxCommandRequest): SandboxCommandResult {
            fun search(
                skill: String,
                script: String,
                options: Set<String>,
                flags: Set<String>,
                reply: (Map<String, String>) -> String?,
            ): String? {
                if (request.environment["SOUZ_SKILL_ID"] != skill || request.runtime != SandboxCommandRuntime.BASH ||
                    request.script != null || request.scriptPath?.replace('\\', '/')?.endsWith("/scripts/$script.sh") != true
                ) return null
                val args = when {
                    skill == TRAIN -> request.args
                    request.args.firstOrNull() == "find" -> request.args.drop(1)
                    else -> return null
                }
                val parsed = parseOptions(args, options, flags) ?: return null
                val output = reply(parsed) ?: return null
                val links = output.lineSequence().drop(1).map { it.substringAfterLast('\t') }.toList()
                current.searches += Search(parsed["--max-price"]?.toIntOrNull(), links)
                return output
            }
            val output = when (current.number) {
                in 1..3 -> search(DOCTOR, "doctor", doctorOptions, setOf("--kids", "--online", "--only-free"), ::searchDoctors)
                in 4..5 -> search(TRAIN, "trains", trainOptions, setOf("--overnight"), ::searchTrains)
                else -> search(THEATRE, "afisha", theatreOptions, setOf("--with-dates"), ::searchShows)
            } ?: return SandboxCommandResult(1, "", "No result for this fixture query.")
            return SandboxCommandResult(0, output, "")
        }
    }

    val channels = ChannelProviderRegistry(listOf(object : ChannelProvider {
        override val channelType = "telegram"
        override suspend fun listChannels(userId: String) =
            listOf(ChannelDescriptor(channelType, TELEGRAM, "Scenario Telegram"))

        override suspend fun sendMessage(userId: String, channelId: String, text: String): ChannelSendResult {
            if (channelId != TELEGRAM) return ChannelSendResult.Failed("Unknown recipient.")
            current.telegram += text
            return ChannelSendResult.Delivered("Scenario delivery accepted.")
        }
    }))

    private fun searchDoctors(options: Map<String, String>): String? {
        val limit = options.int("--limit", 3)?.coerceIn(1, 8) ?: return null
        val cap = options.int("--max-price", 0) ?: return null
        val days = options.int("--days", 7)?.coerceIn(1, 21) ?: return null
        val radius = options.int("--radius", 3) ?: return null
        val order = options["--order"] ?: "rating"
        if (!options["--city"].equals("Москва", true) || !options["--speciality"].equals("терапевт", true) ||
            options["--near"]?.contains("новослободск", true) != true || order !in doctorOrders
        ) return null
        if (today.plusDays(days.toLong()) < appointment || radius < 1 || "--kids" in options || "--online" in options) return "NO_RESULTS"
        val matches = doctors.filter { cap == 0 || it.price <= cap }
        val sorted = if (order == "price") matches.sortedBy { it.price } else matches
        val rows = sorted.take(limit).map { it.row(doctorSlot) }
        return if (rows.isEmpty()) "NO_RESULTS" else
            (listOf("SEARCH\tтерапевт\tМосква\tНовослободская\tнайдено: ${rows.size}") + rows).joinToString("\n")
    }

    private fun searchTrains(options: Map<String, String>): String? {
        val limit = options.int("--limit", 5)?.coerceIn(1, 15) ?: return null
        val after = options["--after"] ?: "00:00"
        val before = options["--before"] ?: "23:59"
        if (options["--from"]?.lowercase() !in departureCities ||
            !options["--to"].equals("Рыбинск", true) || options["--date"] != trainDate.toString() ||
            !time.matches(after) || !time.matches(before) || (options["--sort"] ?: "departure") !in trainSorts
        ) return null
        if ("--overnight" in options || "--name" in options || !(options["--class"] ?: "купе").equals("купе", true)) return "NO_MATCH"
        val rows = trains.filter { it.departure in after..before }.take(limit).map {
            "TRAIN\t$trainSlot ${it.departure}\t$trainSlot ${it.arrival}\t${it.duration}\t${it.number}\t-" +
                "\tЛадожский — Рыбинск\tкупе\t${it.link}"
        }
        return if (rows.isEmpty()) "NO_MATCH" else
            (listOf("ROUTE\tСанкт-Петербург\tРыбинск\t$trainDate\t${rows.size}") + rows).joinToString("\n")
    }

    private fun searchShows(options: Map<String, String>): String? {
        val limit = options.int("--limit", 5) ?: return null
        val days = options.int("--days", 14) ?: return null
        val singleDay = options["--date"]?.let(::isShowDate) ?: (
            options["--from"]?.let(::isShowDate) == true && (options["--to"]?.let(::isShowDate) ?: (days == 1))
        )
        if (!singleDay || !options["--city"].equals("Москва", true) || options["--type"] != "theatre" || limit < 1) return null
        val rows = shows.take(limit).map {
            "EVENT\t${it.id}\ttheatre\t${it.title}\t${it.details}\t$theatreDate ${it.start}\t${it.link}"
        }
        return (listOf("SEARCH\tМосква\tспектакли\tнайдено ${rows.size}") + rows).joinToString("\n")
    }

    private fun isShowDate(input: String): Boolean {
        val text = input.trim()
        // The show is always on the Saturday after the appointment. Afisha resolves a named
        // weekday to the nearest occurrence, including today, even with the “next” prefix.
        val named = text.lowercase().replace('ё', 'е').replace(Regex("""[^\p{L}\p{N}_+]+"""), " ").trim()
        if (saturday.matches(named)) return today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)) == theatreDate
        val yearless = text.count { it == '.' } == 1
        val value = if (yearless) "$text.${today.year}" else text
        val parsed = dateFormats.firstNotNullOfOrNull { runCatching { LocalDate.parse(value, it) }.getOrNull() } ?: return false
        return (if (yearless && parsed < today.minusDays(30)) parsed.plusYears(1) else parsed) == theatreDate
    }

    fun score(checks: ScenarioChecks) {
        val all = doctors.map { it.link }
        val cheap = eligible.map { it.link }
        val rail = trains.map { it.link }
        val theatre = shows.map { it.link }
        fun search(turn: Int, links: List<String>, cap: Int? = null) {
            checks.check(turns[turn - 1].searches.any { it.cap == cap && it.links.sorted() == links.sorted() },
                "Turn $turn must return the requested options from a fresh search (price cap $cap).")
        }
        search(1, all)
        search(2, cheap, BUDGET)
        search(4, rail)
        search(6, theatre)
        for (turn in turns.take(2)) scoreDoctorReply(turn, checks)
        val timetable = turns[3].assistantReply.orEmpty()
        checks.check(trains.all { timetable.mentions(it.departure) }, "Turn 4 must show both departure times.")
        for (show in shows) {
            val presented = turns[5].assistantReply.orEmpty().lineSequence().any { line ->
                shows.singleOrNull { line.mentions(it.title) } == show && time.findAll(line).singleOrNull()?.value == show.start
            }
            checks.check(presented, "Turn 6 must show ${show.title} with its start time ${show.start} on its own line.")
        }
        val requested = mapOf(3 to cheap, 5 to listOf(rail.last()), 7 to listOf(theatre.last()))
        for (turn in turns) {
            val links = turn.telegram.flatMap { text -> url.findAll(text).map { it.value.trimEnd('.', ',', ';', '!', '?') } }
            val expected = requested[turn.number]
            checks.check(if (expected == null) turn.telegram.isEmpty() else links.sorted() == expected.sorted(),
                "Turn ${turn.number} expected ${expected ?: "no Telegram delivery"}, received ${turn.telegram}.")
        }
    }

    fun scoreDoctorReply(turn: ScenarioTurn, checks: ScenarioChecks) {
        val expected = if (turn.number == 1) doctors else eligible
        val actual = doctors.filter { turn.assistantReply.orEmpty().mentions(it.surname) }
        checks.check(actual == expected, "Turn ${turn.number} must present exactly ${expected.map { it.surname }}.")
    }
}

private val surnamePatterns = mapOf(
    "Петрова" to "Петров(?:а|ой|у|ою)",
    "Волков" to "Волков(?:а|у|ым|е)?",
    "Соколов" to "Соколов(?:а|у|ым|е)?",
    "Лебедев" to "Лебедев(?:а|у|ым|е)?",
)

private fun String.mentions(value: String): Boolean {
    val pattern = surnamePatterns[value] ?: Regex.escape(value)
    return Regex("""(?<![\p{L}\p{N}])$pattern(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE).containsMatchIn(this)
}

internal data class Search(val cap: Int?, val links: List<String>)

private val doctorOrders = setOf("rating", "price", "experience")
private val departureCities = setOf("санкт-петербург", "санкт петербург", "спб", "питер")
private val trainSorts = setOf("duration", "departure")
private val doctorOptions = setOf("--city", "--speciality", "--near", "--radius", "--order", "--max-price", "--days", "--limit")
private val trainOptions = setOf("--from", "--to", "--date", "--after", "--before", "--class", "--name", "--sort", "--limit")
private val theatreOptions = setOf("--city", "--type", "--date", "--from", "--to", "--days", "--limit")
private val saturday = Regex("""(?:(?:в|во|on|this|next|ближайш[\p{L}\p{N}_]*) )?(?:суббота|субботу|сб|saturday)""")
private val dateFormats = listOf("uuuu-M-d", "d.M.uuuu").map { DateTimeFormatter.ofPattern(it).withResolverStyle(ResolverStyle.STRICT) }

private fun Map<String, String>.int(option: String, default: Int): Int? = (get(option) ?: default.toString()).toIntOrNull()

private fun parseOptions(args: List<String>, options: Set<String>, flags: Set<String>): Map<String, String>? {
    val parsed = mutableMapOf<String, String>()
    val tokens = args.iterator()
    while (tokens.hasNext()) {
        val token = tokens.next()
        val option = token.substringBefore('=')
        when {
            token in flags -> parsed[token] = ""
            option !in options -> return null
            else -> {
                val value = when {
                    '=' in token -> token.substringAfter('=')
                    tokens.hasNext() -> tokens.next()
                    else -> return null
                }
                if (value.isBlank() || value.startsWith("--")) return null
                parsed[option] = value
            }
        }
    }
    return parsed
}
