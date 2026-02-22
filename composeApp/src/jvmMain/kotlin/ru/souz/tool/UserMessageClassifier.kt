package ru.souz.tool

import ru.souz.giga.GigaMessageRole
import ru.souz.giga.GigaRequest
import ru.souz.giga.gigaJsonMapper
import com.fasterxml.jackson.module.kotlin.readValue

fun interface UserMessageClassifier {
    suspend fun classify(body: String): Reply

    data class Reply(
        val categories: List<ToolCategory> = emptyList(),
        val confidence: Double,
    )
}

enum class ToolCategory {
    FILES,
    BROWSER,
    CONFIG,
    NOTES,
    APPLICATIONS,
    @Suppress("SpellCheckingInspection")
    DATAANALYTICS,
    CALENDAR,
    MAIL,
    TEXT_REPLACE,
    CALCULATOR,
    CHAT,
    TELEGRAM,
    DESKTOP,
    PRESENTATION,
    HELP,
}

object LocalRegexClassifier : UserMessageClassifier {
    private val defaultUnknown = UserMessageClassifier.Reply(emptyList(), 0.0)

    override suspend fun classify(body: String): UserMessageClassifier.Reply {
        val chat: GigaRequest.Chat = try {
            gigaJsonMapper.readValue(body)
        } catch (_: Exception) {
            return defaultUnknown
        }
        val lastUser = chat.messages.lastOrNull { it.role == GigaMessageRole.user }
            ?: return defaultUnknown

        val text = lastUser.content
            .substringAfter("new message:\n", lastUser.content)
            .lowercase()

        val scores = CATEGORY_PATTERNS.mapValues { (_, patterns) ->
            patterns.sumOf { (regex, weight) ->
                regex.findAll(text).count() * weight
            }
        }

        val sorted = scores.entries.sortedByDescending { it.value }
        val best = sorted.firstOrNull() ?: return defaultUnknown

        if (best.value == 0.0) return defaultUnknown

        val relevant = sorted.filter { it.value > 0.0 }.map { it.key }
        return UserMessageClassifier.Reply(relevant, 50.0)
    }

    private data class WeightedRegex(val regex: Regex, val weight: Double)

    private fun ToolCategory.regexps(): List<WeightedRegex> = when(this) {
        ToolCategory.FILES -> listOf(
            WeightedRegex(Regex("прочитай в файле|открой файл|покажи файл|найди файл|путь к файл|открой папк"), 2.0),
            WeightedRegex(Regex("создай файл|удали файл|покажи содержим|перенеси файл|поиск по файлам"), 2.0),
            WeightedRegex(Regex("файл(?!.*(xlsx|xls|excel|эксель|таблиц))|file|перепиши|исправь в"), 1.0),
            WeightedRegex(Regex("поправь|поправить|исправить|прочитай(?!.*(xlsx|xls|excel|эксель|таблиц))|папк|folder|каталог|директори|directory"), 1.0),
        )

        ToolCategory.BROWSER -> listOf(
            WeightedRegex(Regex("открой сайт|https?://|браузер|browser|safari|Закладк|открой.*вкладк"), 2.0),
            WeightedRegex(Regex("website|вебсайт|вкладк|сайт|страниц|истори.*браузера"), 1.0),
            WeightedRegex(Regex("tab|страниц|истори"), 1.0),
        )

        ToolCategory.CONFIG -> listOf(
            WeightedRegex(Regex("настрой|config|запомни инструкцию|сохрани инструкцию"), 2.0),
            WeightedRegex(Regex("громк|volume|скорост|speed|instruction|ускорь речь|замедли речь|скорость речь"), 1.0),
        )

        ToolCategory.NOTES -> listOf(
            WeightedRegex(Regex("создай заметку|oткрой заметку|посмотри в заметках"), 2.0),
            WeightedRegex(Regex("заметк|a note|the note"), 1.5),
            WeightedRegex(Regex("note|todo"), 1.0),
        )

        ToolCategory.APPLICATIONS -> listOf(
            WeightedRegex(Regex("приложения открыты|открытые приложения|что запущено|прилож.*запущен"), 2.0),
            WeightedRegex(Regex("запущен|прилолож"), 1.5),
            WeightedRegex(Regex("открой"), 1.0),
        )

        ToolCategory.DATAANALYTICS -> listOf(
            WeightedRegex(Regex("построй|созда|сделай|проанализ|колонк|столбец|строка|ячейк"), 1.5),
            WeightedRegex(Regex("скольк|корреляц|консолид|отчёт|отчет|причин"), 1.0),
            WeightedRegex(Regex("excel|таблиц|spreadsheet|xlsx|эксель"), 2.0)
        )

        ToolCategory.CALENDAR -> listOf(
            WeightedRegex(Regex("календар|calendar|расписани|schedule"), 2.0),
            WeightedRegex(Regex("событи|event|встреч|meeting|напоминани|reminder|созвон|call"), 2.0),
            WeightedRegex(Regex("завтра|сегодня|послезавтра|дат|date|планируй|запланируй"), 1.0),
        )

        ToolCategory.MAIL -> listOf(
            WeightedRegex(Regex("почт|mail|email|e-mail|gmail|outlook|inbox|входящ|исходящ"), 2.0),
            WeightedRegex(Regex("письм|letter|рассылк|спам|непрочитан"), 2.0),
            WeightedRegex(Regex("отправ|send|ответ|reply|прочти|read"), 1.0),
        )

        ToolCategory.TEXT_REPLACE -> listOf(
            WeightedRegex(Regex("измени стиль текста|(измени|поменяй) выделенный текст"), 2.0),
            WeightedRegex(Regex("исправь (выделенный текст|текст, который .* выделил|текст в (селекше|selecti))"), 2.0),
            WeightedRegex(Regex("выдел.* текст|текст (в|под)selection|(поменяй|измени) стиль текста"), 1.5),
            WeightedRegex(Regex("текст .* выделил"), 1.5),
            WeightedRegex(Regex("выделенн|(в|под)selection|поменяй стиль|стиль текста|селект"), 1.0),
        )

        ToolCategory.CHAT -> listOf(
            WeightedRegex(Regex("Кто такой|Как думаешь|Сколько .* в|Что будет если"), 1.5)
        )

        ToolCategory.TELEGRAM -> listOf(
            WeightedRegex(Regex("телеграм|telegram|\\bтг\\b|\\btg\\b"), 2.0),
            WeightedRegex(Regex("прочитай.*телеграм|покажи.*телеграм|в телеграм"), 1.5),
            WeightedRegex(Regex("напиши.*(в|через).*телеграм|отправь.*(в|через).*телеграм|поиск.*телеграм"), 2.0),
            WeightedRegex(Regex("архивир|замьют|mute|mark readsaved messages|избранное"), 1.2),
        )

        ToolCategory.DESKTOP -> listOf(
            WeightedRegex(Regex("сделай скриншот|заскринь|скриншот|сфоткай экран|screenshot|capture screen"), 2.0),
            WeightedRegex(Regex("запись экрана|запиши экран|screen recording|record screen|видео экрана"), 2.0),
            WeightedRegex(Regex("рабочий стол|desktop"), 1.0),
        )

        ToolCategory.CALCULATOR -> listOf(
            WeightedRegex(Regex("calculate|посчитай|вычисли|сколько будет|реши|math|count"), 2.0),
            WeightedRegex(Regex("calculator|калькулятор"), 1.5),
            WeightedRegex(Regex("\\d+\\s*[+\\-*/^]\\s*\\d+"), 1.5), // Simple math expressions
        )

        ToolCategory.PRESENTATION -> listOf(
            WeightedRegex(Regex("презентац|слайд|pptx|powerpoint|keynote|выступлени|доклад|presentation|slide"), 1.5),
            WeightedRegex(Regex("создай презентац|сделай слайд|напиши презентац|слайды"), 2.0),
            WeightedRegex(Regex("прочитай презентац|анализ слайд|что в презентац"), 1.7),
        )

        ToolCategory.HELP -> listOf(
            WeightedRegex(Regex("что (ты )?(умеешь|можешь|знаешь делать)|что ты делаешь|какие .* функци|какие .* возможност"), 2.0),
            WeightedRegex(Regex("помощь|помоги|\\bhelp\\b|на что .* способ|что .* умеет|расскажи о себе"), 2.0),
            WeightedRegex(Regex("как (тебя |тобой )?пользоваться|что ты за приложение|чем .* помочь"), 2.0),
            WeightedRegex(Regex("список команд|список функций|список возможностей|что доступно"), 2.0),
        )
    }

    private val CATEGORY_PATTERNS: Map<ToolCategory, List<WeightedRegex>> =
        ToolCategory.entries.associateWith { c -> c.regexps() }
}
