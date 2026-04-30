package ru.souz.agent.skill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentSkillMarkdownParserTest {
    @Test
    fun `parses supported frontmatter subset`() {
        val markdown = """
            ---
            name: weather
            description: Get current weather for a city
            when_to_use: Use when the user asks about weather or forecast
            disable-model-invocation: false
            user-invocable: true
            allowed-tools:
              - Bash
              - RunBashCommand
            metadata:
              openclaw:
                requires:
                  bins:
                    - curl
                os:
                  - linux
                  - macos
            ---
            # Weather
            Use wttr.in first.
        """.trimIndent()

        val summary = AgentSkillMarkdownParser.parseSummary(
            markdown = markdown,
            fallbackName = "fallback",
            source = AgentSkillSource.WORKSPACE,
        )
        val skill = AgentSkillMarkdownParser.parseSkill(
            markdown = markdown,
            fallbackName = "fallback",
            source = AgentSkillSource.WORKSPACE,
        )

        assertEquals("weather", summary.name)
        assertEquals("Get current weather for a city", summary.description)
        assertEquals("Use when the user asks about weather or forecast", summary.whenToUse)
        assertFalse(summary.disableModelInvocation)
        assertTrue(summary.userInvocable)
        assertEquals(setOf("Bash", "RunBashCommand"), summary.allowedTools)
        assertEquals(listOf("curl"), summary.requiresBins)
        assertEquals(listOf("linux", "macos"), summary.supportedOs)
        assertTrue(summary.requiresRunBashCommand())
        assertEquals("# Weather\nUse wttr.in first.", skill.body)
    }
}
