package ru.souz.agent.scenarios

import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.util.UUID
import ru.souz.llms.LLMRequest
import ru.souz.llms.restJsonMapper

internal data class ScenarioTrial(
    val repeat: Int,
    val status: String,
    val detail: String?,
    val durationMs: Long,
    val assertions: ScenarioChecks,
    val calls: List<ModelCall>,
    val prefixes: List<ScenarioBoundary>,
    val seededHistory: HistoryMatch?,
    val seed: List<LLMRequest.Message>,
    val turns: List<ScenarioTurn>,
) {
    val scenario = "skill-switch"
}

internal class ScenarioReport(directory: Path, private val model: String, private val host: String) {
    private val runId = UUID.randomUUID().toString()
    private val jsonl = Files.createFile(Files.createDirectories(directory).resolve("$runId.jsonl"))
    private val html = jsonl.resolveSibling("$runId.html")
    val trials = mutableListOf<ScenarioTrial>()

    init {
        println("Agent evaluation reports: ${jsonl.toAbsolutePath()} and ${html.toAbsolutePath()}")
    }

    fun write(type: String, fields: Any) {
        val row = restJsonMapper.valueToTree<ObjectNode>(fields).put("type", type).put("runId", runId)
        Files.writeString(jsonl, restJsonMapper.writeValueAsString(row) + "\n", APPEND)
    }

    fun record(trial: ScenarioTrial) {
        trials += trial
        write("scenario", trial)
    }

    fun finish(durationMs: Long) {
        val calls = trials.flatMap { it.calls }
        fun sum(count: (ModelCall) -> Int?): Long? =
            calls.takeIf { it.isNotEmpty() }?.sumOf { (count(it) ?: return null).toLong() }
        fun mean(total: Long?) = if (calls.isEmpty()) null else total?.toDouble()?.div(calls.size)
        val input = sum { it.inputTokens }
        val output = sum { it.outputTokens }
        val cached = sum { it.cachedInputTokens }
        val total = if (input != null && output != null) input + output else null
        val summary = linkedMapOf(
            "scenarios" to trials.size, "solved" to trials.count { it.status == "passed" },
            "assertionsPassed" to trials.sumOf { it.assertions.passed }, "assertionsTotal" to trials.sumOf { it.assertions.total },
            "modelCalls" to calls.size, "durationMs" to durationMs, "meanModelCallMs" to mean(calls.sumOf { it.durationMs }),
            "inputTokens" to input, "outputTokens" to output, "totalTokens" to total, "cachedInputTokens" to cached,
            "cachedInputFraction" to if (input != null && input > 0 && cached != null) cached.toDouble() / input else null,
            "meanTotalTokensPerRequest" to mean(total), "meanCachedInputTokensPerRequest" to mean(cached),
        )
        write("summary", summary)
        Files.writeString(html, buildString {
            append("""<!doctype html><html lang="en"><meta charset="utf-8"><title>Agent scenario evaluation</title>
                <style>body{max-width:72rem;margin:auto;padding:2rem;font:1rem/1.5 system-ui}pre{white-space:pre-wrap;overflow-wrap:anywhere}
                td,th{padding:.25rem .6rem;text-align:left;border-bottom:1px solid #ccc}summary{cursor:pointer;font-weight:bold}
                details{margin:1em 0}dt{font-weight:bold}dd{margin-left:0}.metrics{display:grid;grid-template-columns:max-content auto;gap:0 1em}</style>
                <h1>Agent scenario evaluation</h1>""")
            append("<p>${model.html()} · ${host.html()} · <a href=\"${jsonl.fileName}\">JSONL data</a></p><dl class=metrics>")
            for ((name, value) in summary) {
                val label = name.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().replaceFirstChar(Char::uppercase)
                append("<dt>$label</dt><dd>${value ?: "—"}</dd>")
            }
            append("</dl>")
            for (trial in trials) appendTrial(trial)
            append("</html>")
        })
    }
}

private fun StringBuilder.appendTrial(trial: ScenarioTrial) {
    val score = trial.assertions
    append("<details><summary>Trial ${trial.repeat} · ${trial.status} · ${score.passed}/${score.total} checks · ${trial.durationMs} ms</summary>")
    for (failure in (listOfNotNull(trial.detail) + score.failures).distinct()) append("<p>${failure.html()}</p>")
    append("<h2>Seeded history</h2>")
    trial.seed.forEach { messageBlock(it.role.name, it.content) }
    trial.seededHistory?.let { append("<p>${it.matched}/${it.expected} messages preserved.</p>") }
    for (turn in trial.turns) {
        append("<h2>Turn ${turn.number}</h2>")
        messageBlock("User", turn.input)
        if (turn.tools.isNotEmpty()) messageBlock("Tools", turn.tools.joinToString("\n") { listOfNotNull(it.name, it.skillId).joinToString(" · ") })
        for (delivery in turn.telegram) messageBlock("Accepted by mock Telegram", delivery)
        turn.assistantReply?.let { messageBlock("Assistant", it) }
        turn.error?.let { messageBlock("Execution error", it) }
        trial.prefixes.find { it.turn == turn.number }?.let {
            append("<p>History ${it.history.matched}/${it.history.expected} · unchanged prefix ${it.unchangedMessages}/${it.previousMessages}")
            append(" · first changed role ${it.firstChangedPreviousRole?.html() ?: "—"} · common system characters ${it.commonSystemPrefixChars ?: "—"}")
            append(" · cached input ${it.previousCachedInputTokens ?: "—"} → ${it.currentCachedInputTokens ?: "—"}</p>")
        }
        append("<table><caption>Model calls</caption><tr><th>Call</th><th>Kind</th><th>ms</th><th>Input</th><th>Output</th><th>Cached input</th><th>Status</th></tr>")
        for (call in trial.calls.filter { it.turn == turn.number }) {
            append("<tr>")
            for (cell in listOf(call.number, call.kind, call.durationMs, call.inputTokens, call.outputTokens, call.cachedInputTokens, call.providerStatus ?: call.status))
                append("<td>${cell ?: "—"}</td>")
            append("</tr>")
        }
        append("</table>")
    }
    append("</details>")
}

private fun StringBuilder.messageBlock(role: String, text: String) {
    append("<strong>${role.html()}</strong><pre>${text.html()}</pre>")
}

private fun String.html() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
