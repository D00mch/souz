package agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import ru.souz.llms.restJsonMapper
import ru.souz.tool.BadInputException
import ru.souz.tool.browser.ToolChromeInfo
import ru.souz.tool.browser.ToolSafariInfo
import ru.souz.tool.dataAnalytics.ToolCreatePlotFromCsv
import ru.souz.tool.dataAnalytics.excel.ExcelRead
import ru.souz.tool.files.ToolModifyFile
import java.nio.file.Path
import java.time.LocalDate

internal fun assertAnswerFacts(answer: String, vararg facts: String) {
    val plain = answer.lowercase().replace(Regex("[*`]"), "")
    facts.forEach { fact ->
        val found = if (fact.all(Char::isDigit)) {
            Regex("(?<!\\d)" + fact.map { Regex.escape(it.toString()) }.joinToString("[\\s\\u00a0\\u202f]?") + "(?!\\d)").containsMatchIn(plain)
        } else plain.replace(Regex("\\s+"), "").contains(fact.lowercase().replace(Regex("\\s+"), ""))
        assertTrue(found, "Expected answer to include '$fact', got: $answer")
    }
}

/** Assert the result table, keeping explanatory prose separate from returned rows. */
internal fun assertSalesRows(answer: String, expected: List<Pair<String, Int>>, ordered: Boolean = false) {
    fun date(cell: String): String? {
        val plain = cell.lowercase().replace(Regex("[*`]"), "").trim()
        if (Regex("\\d{4}-\\d{2}-\\d{2}").matches(plain)) return plain
        Regex("(\\d{1,2})[./](\\d{1,2})[./](\\d{4})").matchEntire(plain)?.let {
            return LocalDate.of(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt()).toString()
        }
        Regex("(\\d{1,2})\\s+(мая|may)(?:\\s+(\\d{4}))?(?:\\s*г\\.?)?").matchEntire(plain)?.let {
            return LocalDate.of(it.groupValues[3].ifEmpty { "2026" }.toInt(), 5, it.groupValues[1].toInt()).toString()
        }
        Regex("may\\s+(\\d{1,2}),?\\s+(\\d{4})").matchEntire(plain)?.let {
            return LocalDate.of(it.groupValues[2].toInt(), 5, it.groupValues[1].toInt()).toString()
        }
        return null
    }
    var dateColumn = -1
    var amountColumn = -1
    val rows = mutableListOf<Pair<String, Int>>()
    answer.lineSequence().filter { it.count { char -> char == '|' } >= 2 }.forEach { line ->
        val cells = line.trim().trim('|').split('|').map { it.trim().replace(Regex("[*`]"), "") }
        val dateHeader = cells.indexOfFirst { it.equals("Date", true) || it.equals("Дата", true) }
        val amountHeader = cells.indexOfFirst { it.equals("Amount", true) || it.equals("Сумма", true) }
        if (dateHeader >= 0 && amountHeader >= 0) {
            dateColumn = dateHeader
            amountColumn = amountHeader
        } else if (dateColumn >= 0 && amountColumn >= 0 && !cells.all { Regex("[: -]*").matches(it) }) {
            val rowDate = cells.getOrNull(dateColumn)?.let(::date)
            assertTrue(rowDate != null, "Expected a recognizable date in result row: $line")
            val number = cells.getOrNull(amountColumn)?.replace(Regex("[\\s\\u00a0\\u202f]+"), "")?.let {
                if (Regex("\\d{1,3}(,\\d{3})+").matches(it)) it.replace(",", "") else it.replace(',', '.')
            }
            val amount = number?.toBigDecimalOrNull()?.let { runCatching { it.intValueExact() }.getOrNull() }
            assertTrue(amount != null, "Expected numeric Amount for $rowDate: $line")
            rows += rowDate!! to amount!!
        }
    }
    assertEquals(
        if (ordered) expected else expected.sortedBy { it.first },
        if (ordered) rows else rows.sortedBy { it.first },
        "Expected the requested date/Amount pairs${if (ordered) " in order" else ""}: $answer",
    )
}

internal class ScenarioBrowserPage {
    val url = "https://example.com/orion"
    val body = "Проект Орион завершён. Выпуск назначен на пятницу."
    var pageTextCalls = 0
        private set

    fun read(input: ToolChromeInfo.Input): String = read(input.type.name, input.url, "Win 1 Tab 1: Обновления проекта")
    fun read(input: ToolSafariInfo.Input): String = read(input.type.name, input.url, "Обновления проекта: 1")

    private fun read(type: String, requestedUrl: String?, tabs: String): String = when (type) {
        "currentTabUrl" -> url
        "tabs" -> tabs
        "history" -> "$SCENARIO_TODAY 09:00:00|$url|Обновления проекта"
        "bookmarks" -> "{Обновления проекта=$url}"
        "pageText" -> {
            val target = requestedUrl ?: url
            when {
                target.isBlank() -> "Error: URL is required for pageText"
                target == url -> { pageTextCalls++; body }
                else -> "Error: Page not found: $target"
            }
        }
        else -> error("Unsupported browser information type: $type")
    }
}

internal class ScenarioFiles(initial: Map<String, String>, directories: Set<String> = emptySet()) {
    private val files = initial.mapKeys { canonical(it.key) }.toMutableMap()
    private val knownDirectories = (directories.map(::canonical) + canonical("~")).toMutableSet()

    init { (files.keys + knownDirectories).forEach(::rememberParents) }

    private fun rememberParents(path: String) {
        generateSequence(Path.of(path).parent) { it.parent }.forEach { knownDirectories += it.toString() }
    }
    private fun directoryPrefix(path: String): String {
        val directory = canonical(path)
        if (directory !in knownDirectories) throw BadInputException("Directory not found: $path")
        return directory.trimEnd('/') + "/"
    }

    fun snapshot(): Map<String, String> = files.toMap()
    fun read(path: String): String = files[canonical(path)] ?: throw BadInputException("File not found: $path")
    fun exists(path: String): Boolean = canonical(path) in files
    fun find(path: String, name: String): String {
        val prefix = directoryPrefix(path)
        val query = name.trim()
        if (query.isBlank()) throw BadInputException("fileName must not be empty")
        return restJsonMapper.writeValueAsString(files.keys.filter {
            it.startsWith(prefix) && it.substringAfterLast('/').contains(query, ignoreCase = true)
        })
    }
    fun list(path: String): String {
        val prefix = directoryPrefix(path)
        return restJsonMapper.writeValueAsString((files.keys + knownDirectories).filter { it.startsWith(prefix) })
    }
    fun create(path: String, text: String): String {
        val target = canonical(path)
        if (target in files || target in knownDirectories) throw BadInputException("File already exists: $path")
        files[target] = text
        rememberParents(target)
        return "Created $path"
    }
    fun edit(input: ToolModifyFile.Input): String {
        val content = read(input.path)
        if (input.oldString.isEmpty() || !content.contains(input.oldString)) {
            throw BadInputException("oldString must match existing content. Read the file and retry.")
        }
        if (!input.replaceAll && content.indexOf(input.oldString) != content.lastIndexOf(input.oldString)) {
            throw BadInputException("oldString is ambiguous; provide more context or replaceAll.")
        }
        files[canonical(input.path)] = if (input.replaceAll) content.replace(input.oldString, input.newString)
        else content.replaceFirst(input.oldString, input.newString)
        return "OK"
    }
    fun delete(path: String): String {
        read(path)
        files.remove(canonical(path))
        return "Deleted $path"
    }
    fun move(source: String, destination: String): String {
        val content = read(source)
        val target = canonical(destination)
        if (target in knownDirectories || target in files) throw BadInputException("Destination already exists: $destination")
        files.remove(canonical(source))
        files[target] = content
        rememberParents(target)
        return "File moved to $destination"
    }

    companion object {
        fun canonical(path: String): String {
            val cleaned = path.trim().removeSurrounding("`").removeSurrounding("\"")
                .removeSurrounding("'").removePrefix("file://").trim()
            if (cleaned.isBlank()) throw BadInputException("Path must not be blank")
            val home = System.getProperty("user.home")
            val expanded = when {
                cleaned.startsWith("~") -> cleaned.replaceFirst("~", home)
                cleaned.startsWith("\$HOME") -> cleaned.replaceFirst("\$HOME", home)
                cleaned == "home" -> home
                else -> cleaned
            }
            val candidate = Path.of(expanded)
            return (if (candidate.isAbsolute) candidate else Path.of(home).resolve(candidate)).normalize().toString()
        }
    }
}

internal class ScenarioCalendar {
    data class Event(val date: LocalDate, val time: String, val title: String)
    var events = listOf(
        Event(SCENARIO_TODAY, "09:00", "Обзор проекта"),
        Event(SCENARIO_TODAY.plusDays(1), "10:00", "Важная встреча"),
        Event(SCENARIO_TODAY.plusDays(1), "15:00", "Командный синк"),
        Event(SCENARIO_TODAY.plusDays(3), "16:00", "Планирование спринта"),
    )
        private set

    fun list(date: String): String {
        val target = if (date.isBlank()) SCENARIO_TODAY else LocalDate.parse(date)
        return events.filter { it.date == target }.joinToString("\n") { "${it.date} ${it.time} | ${it.title}" }
            .ifEmpty { "No events found for $target." }
    }
    fun delete(title: String): String {
        val matching = events.filter { it.title.contains(title, ignoreCase = true) }
        if (title.isBlank() || matching.isEmpty()) throw BadInputException("Event not found: $title")
        events = events - matching.toSet()
        return "Deleted ${matching.joinToString { it.title }}"
    }
}

internal class ScenarioChart(private val path: String, private val headers: List<String>) {
    val created = mutableListOf<ToolCreatePlotFromCsv.Input>()

    fun plot(input: ToolCreatePlotFromCsv.Input): String {
        if (ScenarioFiles.canonical(input.path) != ScenarioFiles.canonical(path)) throw BadInputException("File not found: ${input.path}")
        if (input.xColumn == null || input.yColumn == null) return "Columns required. Available headers: $headers"
        if (headers.none { it.equals(input.xColumn, ignoreCase = true) } || headers.none { it.equals(input.yColumn, ignoreCase = true) }) {
            throw BadInputException("Missing columns. Found: $headers. Requested: ${input.xColumn}, ${input.yColumn}")
        }
        created += input
        return "Plot saved to ${input.output ?: "~/souz/Documents/plot.png"}"
    }
}

/** Mirrors ExcelRead: QUERY aggregates; CELL returns raw rows; STRUCTURE and LOOKUP support discovery. */
internal object ScenarioExcel {
    private val sales = listOf(
        mapOf("Date" to "2026-05-01", "Amount" to "500"),
        mapOf("Date" to "2026-05-02", "Amount" to "2500"),
        mapOf("Date" to "2026-05-03", "Amount" to "750"),
        mapOf("Date" to "2026-05-04", "Amount" to "1500"),
    )
    private val prices = listOf(mapOf("Товар" to "Ноутбук", "Цена" to "45000"), mapOf("Товар" to "Монитор", "Цена" to "15000"))

    fun read(input: ExcelRead.Input): String {
        val rows = when (input.path.substringAfterLast('/')) {
            "sales.xlsx" -> sales
            "price.xlsx" -> prices
            else -> throw BadInputException("File not found: ${input.path}; available: ~/sales.xlsx, ~/price.xlsx")
        }
        val columns = rows.first().keys.toList()
        if (input.sheet != null && input.sheet != "Sheet1") throw BadInputException("Sheet '${input.sheet}' not found; available: Sheet1")
        fun requireColumn(column: String?): String = column?.takeIf { it in columns }
            ?: throw BadInputException("Column '$column' not found. Available: ${columns.joinToString()}")
        return when (input.operation) {
            ExcelRead.ReadOperation.STRUCTURE -> restJsonMapper.writeValueAsString(mapOf(
                "rows" to rows.size, "columns" to columns,
                "stringSamples" to columns.filter { rows.first().getValue(it).toDoubleOrNull() == null }
                    .associateWith { col -> rows.map { it.getValue(col) } },
                "numericStats" to columns.filter { rows.first().getValue(it).toDoubleOrNull() != null }.associateWith { col ->
                    rows.map { it.getValue(col).toDouble() }.let { values ->
                        mapOf("sum" to values.sum(), "avg" to values.average(), "min" to values.min(), "max" to values.max())
                    }
                }
            ))
            ExcelRead.ReadOperation.CELL -> {
                val address = input.range ?: input.returnColumn ?: throw BadInputException("range required, for example B5")
                fun coordinates(cell: String): Pair<Int, Int> {
                    val match = Regex("([A-Z])(\\d+)").matchEntire(cell) ?: throw BadInputException("Invalid cell address: $cell")
                    return match.groupValues[2].toInt() - 2 to match.groupValues[1][0] - 'A'
                }
                val parts = address.split(':')
                val (startRow, startCol) = coordinates(parts.first())
                val (endRow, endCol) = coordinates(parts.last())
                (startRow..endRow).joinToString("\n") { row ->
                    (startCol..endCol).joinToString("\t") { col ->
                        val header = columns.getOrNull(col)
                        if (row == -1) header.orEmpty() else header?.let { rows.getOrNull(row)?.get(it) }.orEmpty()
                    }
                }.ifEmpty { "(empty)" }
            }
            ExcelRead.ReadOperation.LOOKUP -> {
                val lookup = requireColumn(input.lookupColumn)
                val result = requireColumn(input.returnColumn)
                val value = input.lookupValue ?: throw BadInputException("lookupValue required")
                rows.firstOrNull { it.getValue(lookup).equals(value, ignoreCase = true) }?.getValue(result) ?: "Not found: '$value'"
            }
            ExcelRead.ReadOperation.QUERY -> {
                var result = rows
                input.filter?.let { filter ->
                    val match = Regex("\\s*(.+?)\\s*(>=|<=|<>|>|<|=)\\s*(.+?)\\s*").matchEntire(filter)
                        ?: throw BadInputException("Invalid filter: $filter; use Column>Value")
                    val column = requireColumn(match.groupValues[1])
                    val value = match.groupValues[3].trim('"', '\'')
                    result = result.filter { row ->
                        val actual = row.getValue(column)
                        val compared = actual.toDoubleOrNull()?.let { a -> value.toDoubleOrNull()?.let { a.compareTo(it) } }
                            ?: actual.compareTo(value, ignoreCase = true)
                        when (match.groupValues[2]) {
                            ">" -> compared > 0; "<" -> compared < 0; ">=" -> compared >= 0
                            "<=" -> compared <= 0; "<>" -> compared != 0; else -> compared == 0
                        }
                    }
                }
                input.column?.let(::requireColumn)
                input.groupBy?.let(::requireColumn)
                val aggregated = result.groupBy { row -> input.groupBy?.let { row.getValue(it) } ?: "total" }
                    .mapValues { (_, group) ->
                        val values = group.map { row -> input.column?.let { row.getValue(it).toDoubleOrNull() ?: 0.0 } ?: 1.0 }
                        when (input.aggregation?.uppercase() ?: "COUNT") {
                            "SUM" -> values.sum(); "AVG" -> values.average(); "MIN" -> values.min(); "MAX" -> values.max()
                            else -> values.size.toDouble()
                        }
                    }.entries.toList()
                val order = input.sortOrder?.uppercase() ?: "DESC"
                if (order != "ASC" && order != "DESC") throw BadInputException("sortOrder must be ASC or DESC")
                val sorted = if (input.sortBy != null && input.sortBy.equals(input.groupBy, ignoreCase = true)) {
                    aggregated.sortedBy { it.key }
                } else aggregated.sortedBy { it.value }
                if ((input.limit ?: 10) < 0) throw BadInputException("limit must be non-negative")
                (if (order == "DESC") sorted.reversed() else sorted).take(input.limit ?: 10)
                    .joinToString("\n") { "${it.key}: ${it.value.toBigDecimal().stripTrailingZeros().toPlainString()}" }

            }
        }
    }
}
