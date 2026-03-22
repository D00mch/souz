package ru.souz.tool.web

import ru.souz.tool.FewShotExample
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty

internal const val INTERNET_SEARCH_DESCRIPTION =
    "High-level internet search with two modes. Use QUICK_ANSWER for a simple web fact like weather, current status, or one direct answer. " +
        "Use RESEARCH for multi-step research, comparisons, library/tool selection, market overviews, or thematic analysis. " +
        "In RESEARCH mode the tool builds a search strategy, runs multiple queries, studies sources, and returns a synthesized answer with sources. " +
        "Interpret `status` carefully: COMPLETE means the output is ready for user delivery; PARTIAL means the tool found sources but failed to produce a reliable final synthesis, so do not invent missing facts; NO_RESULTS means usable sources were not found; PROVIDER_BLOCKED or PROVIDER_UNAVAILABLE mean the web provider failed and this should not be treated as lack of sources. " +
        "`reportFilePath` is present only when a COMPLETE long-form report was exported to a .md file."

internal val INTERNET_SEARCH_FEW_SHOTS = listOf(
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

internal val INTERNET_SEARCH_RETURN_PARAMETERS = ReturnParameters(
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
