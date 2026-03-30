package ru.souz.ui.main

import io.mockk.coEvery
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.getString
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.chat_action_internet_research
import souz.composeapp.generated.resources.chat_action_web_search
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatAgentActionFormatterTest {
    @BeforeTest
    fun setUp() {
        mockkStatic("org.jetbrains.compose.resources.StringResourcesKt")
        coEvery { getString(any()) } answers { firstArg<Any>().toString() }
        coEvery { getString(Res.string.chat_action_web_search) } returns "Ищу в интернете: %1\$s"
        coEvery { getString(Res.string.chat_action_internet_research) } returns
            "Провожу исследование в интернете: %1\$s"
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `formats internet search action`() = runTest {
        val actual = ChatAgentActionFormatter().format(
            functionCall = ru.souz.giga.GigaResponse.FunctionCall(
                name = "InternetSearch",
                arguments = mapOf("query" to "котлин корутины"),
            )
        )

        assertEquals("Ищу в интернете: котлин корутины", actual)
    }

    @Test
    fun `formats internet research action`() = runTest {
        val actual = ChatAgentActionFormatter().format(
            functionCall = ru.souz.giga.GigaResponse.FunctionCall(
                name = "InternetResearch",
                arguments = mapOf("query" to "сравнение MCP серверов"),
            )
        )

        assertEquals("Провожу исследование в интернете: сравнение MCP серверов", actual)
    }
}
