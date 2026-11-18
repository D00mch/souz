package ru.souz.agent.scenarios

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class ScenarioEvaluationHtmlTest {
    @Test
    fun `renders actual skill calls and delivery while escaping model text`() {
        val trace = ScenarioTrace("fixture-model", null)
        val turn = ScenarioTurnEvidence(
            number = 1,
            input = "Send a link.",
            assistantReply = "<script>MODEL_REPLY</script>",
            tools = listOf(ScenarioToolUse(1, "RunSkillCommand", "doctor-appointment")),
            telegram = listOf("https://booking.example/fixture"),
        )
        val report = ScenarioEvaluationHtml("fixture-model", "provider.example", "2026-11-25")
        report.addTrial("doctor-choose-and-send", 1, "passed", null, 50, emptyList(), listOf(turn), trace)
        val html = report.render(1, 1, 1, 50, 42, "run.jsonl")

        assertContains(html, "doctor-appointment")
        assertContains(html, "https://booking.example/fixture")
        assertContains(html, "&lt;script&gt;MODEL_REPLY&lt;/script&gt;")
        assertContains(html, "run.jsonl")
        assertFalse(html.contains("<script>"))
    }
}
