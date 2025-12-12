package ru.abledo.tool

import ru.abledo.giga.GigaMessageRole
import ru.abledo.giga.GigaRequest
import ru.abledo.giga.gigaJsonMapper
import com.fasterxml.jackson.module.kotlin.readValue

fun interface UserMessageClassifier {
    suspend fun classify(body: String): ToolCategory?
}

enum class ToolCategory {
    FILES,
    BROWSER,
    CONFIG,
    DESKTOP,
    IO,
    DATAANALYTICS,
    CALENDAR,
    MAIL
}

object LocalRegexClassifier : UserMessageClassifier {
    override suspend fun classify(body: String): ToolCategory? {
        val chat: GigaRequest.Chat = try {
            gigaJsonMapper.readValue(body)
        } catch (_: Exception) {
            return null
        }
        val lastUser = chat.messages.lastOrNull { it.role == GigaMessageRole.user }
            ?: return null

        val text = lastUser.content
            .substringAfter("new message:\n", lastUser.content)
            .lowercase()

        val scores = CATEGORY_PATTERNS.mapValues { (_, patterns) ->
            patterns.sumOf { (regex, weight) ->
                regex.findAll(text).count() * weight
            }
        }

        val sorted = scores.entries.sortedByDescending { it.value }
        val best = sorted.firstOrNull() ?: return null

        if (best.value == 0.0) return null

        val second = sorted.getOrNull(1)?.value ?: 0.0
        return if (best.value > second) best.key else null
    }

    private data class WeightedRegex(val regex: Regex, val weight: Double)

    private val CATEGORY_PATTERNS: Map<ToolCategory, List<WeightedRegex>> = mapOf(
        ToolCategory.FILES to listOf(
            WeightedRegex(Regex("прочитай в файле|открой файл|покажи файл|найди файл|путь к файл"), 2.0),
            WeightedRegex(Regex("создай файл|удали файл|покажи содержим|перенеси файл|поиск по файлам"), 2.0),
            WeightedRegex(Regex("файл|file|перепиши|исправь в"), 1.5),
            WeightedRegex(Regex("поправь|поправить|исправить|прочитай|папк|folder|каталог|директори|directory"), 1.0),
        ),
        ToolCategory.BROWSER to listOf(
            WeightedRegex(Regex("http[s]?://|браузер|browser|safari|Закладк"), 2.0),
            WeightedRegex(Regex("вкладк|tab|сайт|страниц|истори"), 1.0),
        ),
        ToolCategory.CONFIG to listOf(
            WeightedRegex(Regex("настрой|config|запомни инструкцию|сохрани инструкцию"), 2.0),
            WeightedRegex(Regex("громк|volume|скорост|speed|instruction|ускорь речь|замедли речь|скорость речь"), 1.0),
        ),
        ToolCategory.DESKTOP to listOf(
            WeightedRegex(Regex("перенеси окно|перейди на экран|перетащи окно|размести приложения по"), 2.0),
            WeightedRegex(Regex("окн|window|desktop|\\*.com|\\*.ru|dotcom|дотком|точка ру"), 1.5),
            WeightedRegex(Regex("папк|folder|заметк|note|телеграм|telegram|покаж|фокус|увелич|располож|сверн|сообщение"), 1.5),
            WeightedRegex(Regex("прилож|app|mouse|мыш|screen|скрин|экран"), 1.0),
        ),
        ToolCategory.IO to listOf(
            WeightedRegex(Regex("скриншот|screenshot|сфоткай экран|сфотографируй экран|что на экране|прочти весь текст"), 2.0),
            WeightedRegex(Regex("скач|download|загруз|upload|прочти экран|текст с экрана"), 1.0),
        ),
        ToolCategory.DATAANALYTICS to listOf(
            WeightedRegex(Regex("построй|созда|сделай|проанализ|график|chart|graph|plot|что на графике"), 2.0),
            WeightedRegex(Regex("find|скольк|корреляц|correlation|причин"), 1.0),
        ),
        ToolCategory.CALENDAR to listOf(
            WeightedRegex(Regex("календар|calendar|расписани|schedule"), 2.0),
            WeightedRegex(Regex("событи|event|встреч|meeting|напоминани|reminder"), 2.0),
            WeightedRegex(Regex("завтра|сегодня|послезавтра|дат|date|планируй|запланируй"), 1.0),
        ),
        ToolCategory.MAIL to listOf(
            WeightedRegex(Regex("почт|mail|email|e-mail|gmail|outlook|inbox|входящ|исходящ"), 2.0),
            WeightedRegex(Regex("письм|letter|рассылк|спам|непрочитан"), 2.0),
            WeightedRegex(Regex("отправ|send|ответ|reply|прочти|read"), 1.0),
        )
    )
}