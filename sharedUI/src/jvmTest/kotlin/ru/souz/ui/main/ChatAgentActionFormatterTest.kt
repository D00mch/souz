package ru.souz.ui.main

import io.mockk.coEvery
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.getString
import ru.souz.agent.AgentId
import ru.souz.llms.LLMResponse
import ru.souz.tool.knowledge.ToolGetKnowledge
import ru.souz.tool.skills.ToolGetSkills
import ru.souz.tool.skills.ToolInvokeSkill
import souz.sharedui.generated.resources.Res
import souz.sharedui.generated.resources.chat_action_generic_tool
import souz.sharedui.generated.resources.chat_action_internet_research
import souz.sharedui.generated.resources.chat_action_skill
import souz.sharedui.generated.resources.chat_action_web_search
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatAgentActionFormatterTest {
    @BeforeTest
    fun setUp() {
        mockkStatic("org.jetbrains.compose.resources.StringResourcesKt")
        coEvery { getString(any()) } answers { firstArg<Any>().toString() }
        coEvery { getString(Res.string.chat_action_web_search) } returns "Ищу в интернете: %1\$s"
        coEvery { getString(Res.string.chat_action_internet_research) } returns
            "Провожу исследование в интернете: %1\$s"
        coEvery { getString(Res.string.chat_action_generic_tool) } returns "Запускаю инструмент: %1\$s"
        coEvery { getString(Res.string.chat_action_skill) } returns "Использую навык: %1\$s"
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `formats internet search action`() = runTest {
        val actual = ChatAgentActionFormatter().format(
            functionCall = LLMResponse.FunctionCall(
                name = "InternetSearch",
                arguments = mapOf("query" to "котлин корутины"),
            )
        )

        assertEquals("Ищу в интернете: котлин корутины", actual)
    }

    @Test
    fun `formats internet research action`() = runTest {
        val actual = ChatAgentActionFormatter().format(
            functionCall = LLMResponse.FunctionCall(
                name = "InternetResearch",
                arguments = mapOf("query" to "сравнение MCP серверов"),
            )
        )

        assertEquals("Провожу исследование в интернете: сравнение MCP серверов", actual)
    }

    @Test
    fun `skills graph hides discovery and Knowledge actions`() = runTest {
        val formatter = ChatAgentActionFormatter()

        assertNull(
            formatter.format(
                AgentId.SKILLS_GRAPH,
                LLMResponse.FunctionCall(ToolGetSkills.NAME, mapOf("skillIds" to emptyList<String>())),
            )
        )
        assertNull(
            formatter.format(
                AgentId.SKILLS_GRAPH,
                LLMResponse.FunctionCall(ToolGetKnowledge.NAME, mapOf("knowledgeId" to "knowledge-1")),
            )
        )
    }

    @Test
    fun `skills graph formats a delegated tool with its nested arguments`() = runTest {
        val formatter = ChatAgentActionFormatter { skillId ->
            skillId.takeIf { it == "InternetResearch" }
        }

        val actual = formatter.format(
            AgentId.SKILLS_GRAPH,
            LLMResponse.FunctionCall(
                name = ToolInvokeSkill.NAME,
                arguments = mapOf(
                    "skillId" to "InternetResearch",
                    "arguments" to mapOf("query" to "сравнение MCP серверов"),
                ),
            ),
        )

        assertEquals("Провожу исследование в интернете: сравнение MCP серверов", actual)
    }

    @Test
    fun `skills graph displays a file backed skill by id`() = runTest {
        val actual = ChatAgentActionFormatter().format(
            AgentId.SKILLS_GRAPH,
            LLMResponse.FunctionCall(
                name = ToolInvokeSkill.NAME,
                arguments = mapOf(
                    "skillId" to "report-writer",
                    "arguments" to mapOf("topic" to "Kotlin"),
                ),
            ),
        )

        assertEquals("Использую навык: report-writer", actual)
    }

    @Test
    fun `skills graph preserves directly invoked tool formatting`() = runTest {
        val actual = ChatAgentActionFormatter().format(
            AgentId.SKILLS_GRAPH,
            LLMResponse.FunctionCall(
                name = "InternetSearch",
                arguments = mapOf("query" to "Kotlin coroutines"),
            ),
        )

        assertEquals("Ищу в интернете: Kotlin coroutines", actual)
    }

    @Test
    fun `classic graph preserves core and runtime command formatting`() = runTest {
        val formatter = ChatAgentActionFormatter { "InternetSearch" }

        assertEquals(
            "Запускаю инструмент: GetSkills",
            formatter.format(
                AgentId.GRAPH,
                LLMResponse.FunctionCall(ToolGetSkills.NAME, emptyMap()),
            ),
        )
        assertEquals(
            "Запускаю инструмент: RunSkillCommand",
            formatter.format(
                AgentId.GRAPH,
                LLMResponse.FunctionCall(
                    ToolInvokeSkill.NAME,
                    mapOf("skillId" to "InternetSearch", "arguments" to emptyMap<String, Any>()),
                ),
            ),
        )
    }

    @Test
    fun `malformed runtime command uses generic fallback`() = runTest {
        @Suppress("UNCHECKED_CAST")
        val explicitNullArguments = mapOf<String, Any?>(
            "skillId" to "InternetSearch",
            "arguments" to null,
        ) as Map<String, Any>
        val malformedArguments = listOf(
            emptyMap(),
            mapOf("skillId" to " "),
            mapOf("skillId" to "InternetSearch", "arguments" to "not-an-object"),
            explicitNullArguments,
        )
        val formatter = ChatAgentActionFormatter { "InternetSearch" }

        malformedArguments.forEach { arguments ->
            assertEquals(
                "Запускаю инструмент: RunSkillCommand",
                formatter.format(
                    AgentId.SKILLS_GRAPH,
                    LLMResponse.FunctionCall(ToolInvokeSkill.NAME, arguments),
                ),
            )
        }
    }
}
