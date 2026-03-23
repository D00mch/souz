package ru.souz.tool.web.internal

import ru.souz.tool.FewShotExample
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.web.ToolInternetSearch

internal class InternetSearchMetadata {
    val description: String =
        "Web Search Tool. Modes: QUICK_ANSWER (simple facts/weather) and RESEARCH " +
            "(multi-step search, comparisons, synthesis). " +
            "Statuses: COMPLETE (output ready), PARTIAL (sources found but synthesis failed - DO NOT invent facts), " +
            "NO_RESULTS (no info found), PROVIDER_BLOCKED/UNAVAILABLE (API error, not lack of info). " +
            "`reportFilePath` is provided ONLY if a long-form .md report is generated (COMPLETE status)."

    val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Какая погода в Таллине",
            params = mapOf("query" to "Какая погода в Таллине", "mode" to ToolInternetSearch.SearchMode.QUICK_ANSWER.name)
        ),
        FewShotExample(
            request = "Проведи исследование про ИИ во Франции",
            params = mapOf("query" to "Проведи исследование про ИИ во Франции", "mode" to ToolInternetSearch.SearchMode.RESEARCH.name, "maxSources" to 6)
        ),
        FewShotExample(
            request = "Нужно найти подходящую библиотеку для создания презентаций",
            params = mapOf("query" to "Нужно найти подходящую библиотеку для создания презентаций", "mode" to ToolInternetSearch.SearchMode.RESEARCH.name, "maxSources" to 5)
        ),
    )

    val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "status" to ReturnProperty("string", "Result status: COMPLETE, PARTIAL, NO_RESULTS, PROVIDER_BLOCKED, or PROVIDER_UNAVAILABLE"),
            "mode" to ReturnProperty("string", "Resolved mode: QUICK_ANSWER or RESEARCH"),
            "query" to ReturnProperty("string", "Original user query"),
            "answer" to ReturnProperty("string", "Synthesized answer"),
            "reportMarkdown" to ReturnProperty("string", "Ready-to-send markdown report or an inline preview when the full report was exported to a file"),
            "reportFilePath" to ReturnProperty("string", "Absolute path to a saved .md research report when status=COMPLETE and the report was too large to keep inline"),
            "sources" to ReturnProperty("array", "Sources actually used in the final answer"),
            "strategy" to ReturnProperty("object", "Search strategy used for research mode"),
        )
    )
}
