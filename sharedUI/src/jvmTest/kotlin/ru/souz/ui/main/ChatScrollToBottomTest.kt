package ru.souz.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import ru.souz.ui.AppTheme
import souz.sharedui.generated.resources.Res
import souz.sharedui.generated.resources.chat_scroll_to_bottom
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ChatScrollToBottomTest {
    @Test
    fun `empty and short histories do not show the button`() = runComposeUiTest {
        var state by mutableStateOf(MainState())
        val label = showChat { state }
        onNodeWithContentDescription(label).assertDoesNotExist()

        runOnIdle { state = MainState(chatMessages = messages.take(2)) }
        settleAnimations()
        onNodeWithContentDescription(label).assertDoesNotExist()
    }

    @Test
    fun `scrolling up exposes a button that returns to the latest message and retains the draft`() = runComposeUiTest {
        val label = showChat { MainState(chatMessages = messages) }
        onNodeWithContentDescription(label).assertDoesNotExist()
        onNode(hasSetTextAction()).performTextInput("Unsent draft")
        onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        onNodeWithText(messages.first().text).assertIsDisplayed()
        onNodeWithContentDescription(label).assertIsDisplayed().performTouchInput { click() }
        settleAnimations()
        assertAtBottom(label)
        onNodeWithText(messages.last().text).assertIsDisplayed()
        onNode(hasSetTextAction()).assertTextContains("Unsent draft")
    }

    @Test
    fun `a reply taller than the viewport scrolls to its end`() = runComposeUiTest {
        val reply = ChatMessage(
            text = (1..100).joinToString("\n\n") { "Synthetic reply paragraph $it." },
            isUser = false,
        )
        val label = showChat { MainState(chatMessages = listOf(reply)) }
        onNodeWithContentDescription(label).assertIsDisplayed().performClick()
        settleAnimations()
        assertAtBottom(label)
    }

    @Test
    fun `the button reaches active agent actions and remains hidden after completion`() = runComposeUiTest {
        var state by mutableStateOf(MainState(
            chatMessages = messages,
            isProcessing = true,
            agentActions = listOf("Synthetic action in progress"),
        ))
        val label = showChat { state }
        onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        onNodeWithContentDescription(label).assertIsDisplayed().performClick()
        settleAnimations()
        assertAtBottom(label)
        onNodeWithText("Synthetic action in progress").assertIsDisplayed()

        runOnIdle { state = state.copy(isProcessing = false) }
        settleAnimations()
        assertAtBottom(label)
    }

    @Test
    fun `the button reaches the end of oversized active actions after they grow`() = runComposeUiTest {
        val actions = (1..8).map {
            "Synthetic action $it: " + "wrapped action detail ".repeat(8)
        }
        var state by mutableStateOf(MainState(
            chatMessages = messages,
            isProcessing = true,
            agentActions = actions,
        ))
        val label = showChat { state }
        onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        onNodeWithText(actions.last()).assertDoesNotExist()
        onNodeWithContentDescription(label).assertIsDisplayed().performClick()
        settleAnimations()
        assertAtBottom(label)
        onNodeWithText(actions.last()).assertIsDisplayed()

        val latestAction = "Latest synthetic action after growth"
        runOnIdle { state = state.copy(agentActions = actions + latestAction) }
        settleAnimations()
        onNodeWithContentDescription(label).assertIsDisplayed().performClick()
        settleAnimations()
        assertAtBottom(label)
        onNodeWithText(latestAction).assertIsDisplayed()

        runOnIdle { state = state.copy(isProcessing = false) }
        settleAnimations()
        assertAtBottom(label)
    }

    private fun ComposeUiTest.showChat(state: () -> MainState): String {
        mainClock.autoAdvance = false
        lateinit var label: String
        setContent {
            AppTheme {
                label = stringResource(Res.string.chat_scroll_to_bottom)
                Box(Modifier.size(640.dp, 600.dp)) {
                    MainScreenContent(state = state(), isOnline = true)
                }
            }
        }
        settleAnimations()
        return runOnIdle { label }
    }

    private fun ComposeUiTest.settleAnimations() {
        // Step layout as well as the clock while scrolling composes and reveals new bubbles.
        repeat(300) {
            mainClock.advanceTimeByFrame()
            waitForIdle()
        }
    }

    private fun ComposeUiTest.assertAtBottom(label: String) {
        val range = onNode(hasScrollToIndexAction()).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals(range.maxValue(), range.value(), "The entire trailing item must be visible")
        mainClock.advanceTimeByFrame()
        waitForIdle()
        onNodeWithContentDescription(label).assertDoesNotExist()
    }

    private val messages = (1..40).map {
        ChatMessage(id = "synthetic-$it", text = "Synthetic chat message $it", isUser = it % 2 == 1)
    }
}
