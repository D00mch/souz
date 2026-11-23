package ru.souz.agent.scenarios

import ru.souz.llms.LLMRequest
import java.text.NumberFormat
import java.util.Locale

/** Standalone test report created alongside the JSONL on every evaluation run. */
internal class ScenarioEvaluationHtml(
    private val model: String,
    private val providerHost: String?,
    private val appointment: String,
) {
    private val trials = StringBuilder()

    fun addTrial(
        scenario: String,
        repeat: Int,
        status: String,
        detail: String?,
        durationMs: Long,
        seed: List<LLMRequest.Message>,
        turns: List<ScenarioTurnEvidence>,
        trace: ScenarioTrace,
        score: ScenarioAssertionResult,
    ) = with(trials) {
        append("<details><summary>")
        appendText("$scenario · repeat $repeat · $status · ${score.passed}/${score.total} assertions passed · $durationMs ms")
        append("</summary>")
        if (detail != null && detail !in score.failures) {
            append("<p class=\"failure\">")
            appendText(detail)
            append("</p>")
        }
        if (score.failures.isNotEmpty()) {
            append("<h3>Failed assertions</h3><ul class=\"failure\">")
            for (failure in score.failures) {
                append("<li>")
                appendText(failure)
                append("</li>")
            }
            append("</ul>")
        }
        if (seed.isNotEmpty()) {
            append("<h3>Seeded history</h3>")
            trace.seededHistory?.let {
                append("<p>")
                appendText("${it.matched}/${it.expected} seeded messages reached the first conversational request.")
                append("</p>")
            }
            for (message in seed) appendMessage(message.role.name, message.content)
        }
        for (turn in turns) {
            append("<section><h3>Turn ${turn.number}</h3>")
            appendMessage("User (fixture)", turn.input)
            if (turn.tools.isNotEmpty()) {
                append("<p><strong>Tool calls</strong></p><ol>")
                for (call in turn.tools) {
                    append("<li>")
                    appendText(listOfNotNull(call.name, call.skillId).joinToString(" · "))
                    append("</li>")
                }
                append("</ol>")
            }
            for (message in turn.telegram) appendMessage("Accepted by mock Telegram", message)
            turn.assistantReply?.let { appendMessage("Assistant (model)", it) }
            trace.prefixes.firstOrNull { it.turn == turn.number }?.let { boundary ->
                append("<p class=\"boundary\">")
                boundary.history?.let { appendText("History: ${it.matched}/${it.expected} messages · ") }
                appendText("Matching prefix: ${boundary.unchangedMessages}/${boundary.previousMessages} previous messages")
                boundary.firstChangedPreviousRole?.let { appendText(" · first changed role: $it") }
                boundary.commonSystemPrefixChars?.let { appendText(" · common start of system text: $it chars") }
                appendText(" · cached input: ${boundary.previousCachedInputTokens ?: "—"} → ${boundary.currentCachedInputTokens ?: "—"}")
                append("</p>")
            }
            val calls = trace.calls.filter { it.turn == turn.number }
            if (calls.isNotEmpty()) appendCalls(calls)
            append("</section>")
        }
        append("</details>")
    }

    fun render(
        solved: Int,
        count: Int,
        assertionsPassed: Int,
        assertionsTotal: Int,
        calls: Int,
        durationMs: Long?,
        meanMs: Long?,
        tokens: ScenarioTokenSummary,
        jsonlName: String,
    ): String = buildString {
        append(HTML_HEAD)
        append("<h1>Agent scenario evaluation</h1><p class=\"muted\">")
        appendText(listOfNotNull(model, providerHost, "Appointment fixture: $appointment").joinToString(" · "))
        append("</p><p>")
        appendText("$solved/$count trials passed · $assertionsPassed/$assertionsTotal assertions passed · $calls model calls")
        durationMs?.let { appendText(" · $it ms total") } ?: append(" · partial run")
        meanMs?.let { appendText(" · $it ms mean model latency") }
        append("</p>")
        appendTokenSummary(tokens)
        append("<p><a href=\"")
        appendText(jsonlName)
        append("\">JSONL data</a></p>")
        append(trials)
        append("</main></body></html>")
    }
}

private fun StringBuilder.appendTokenSummary(tokens: ScenarioTokenSummary) {
    val integers = NumberFormat.getIntegerInstance(Locale.US)
    val percent = NumberFormat.getPercentInstance(Locale.US).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }
    fun count(value: Number?) = value?.let { integers.format(it) } ?: "—"
    fun share(value: Double?) = value?.let { percent.format(it) } ?: "—"
    append("<p>")
    appendText("${count(tokens.total)} total tokens (${count(tokens.input)} input + ${count(tokens.output)} output)")
    appendText(" · ${count(tokens.cachedInput)} cached input tokens (${share(tokens.cachedInputFraction)} of input)")
    append("</p><p>")
    appendText("Per request, on average · ${count(tokens.meanTotal)} total tokens")
    appendText(" · ${count(tokens.meanCachedInput)} cached input tokens")
    append("</p>")
}

private fun StringBuilder.appendMessage(role: String, content: String) {
    append("<p><strong>")
    appendText(role)
    append("</strong></p><pre>")
    appendText(content)
    append("</pre>")
}

private fun StringBuilder.appendCalls(calls: List<ScenarioCall>) {
    append(
        "<table><caption>Model calls</caption><thead><tr><th>Call</th><th>Kind</th><th>Time (ms)</th>" +
            "<th>Input tokens</th><th>Output tokens</th><th>Cached input</th><th>Status</th></tr></thead><tbody>",
    )
    for (call in calls) {
        append("<tr>")
        for (value in listOf(call.number, call.kind, call.durationMs, call.inputTokens, call.outputTokens,
            call.cachedInputTokens, call.providerStatus ?: call.status)) {
            append("<td>")
            appendText(value?.toString() ?: "—")
            append("</td>")
        }
        append("</tr>")
    }
    append("</tbody></table>")
}

private fun StringBuilder.appendText(text: String) {
    for (char in text) {
        append(when (char) {
            '&' -> "&amp;"
            '<' -> "&lt;"
            '>' -> "&gt;"
            '"' -> "&quot;"
            '\'' -> "&#39;"
            else -> char.toString()
        })
    }
}

private const val HTML_HEAD = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Agent scenario evaluation</title>
<style>
  body{margin:0;color:#182b43;background:#eef3f8;font:15px/1.5 system-ui,sans-serif}
  main{max-width:1040px;margin:auto;padding:24px}
  h1{margin-bottom:0} h3{font-size:16px;margin:18px 0 6px}
  p{margin:6px 0} .muted{color:#455b73}
  details{margin:12px 0;padding:12px 16px;background:#fff;border:1px solid #ccd9e8;border-radius:6px}
  summary{cursor:pointer;font-weight:650}
  section{margin:16px 0;padding-top:8px;border-top:1px solid #d9e2ee;overflow:auto}
  pre{white-space:pre-wrap;overflow-wrap:anywhere;margin:4px 0 10px;padding:8px 10px;background:#f1f5fa;font:inherit}
  ol{margin:4px 0 10px} .failure{color:#8f252e} .boundary{background:#edf4ff;padding:6px 8px}
  table{width:100%;border-collapse:collapse;text-align:left;font-variant-numeric:tabular-nums}
  caption{text-align:left;font-weight:650} th,td{padding:5px 8px;border-bottom:1px solid #d9e2ee}
</style>
</head>
<body><main>"""
