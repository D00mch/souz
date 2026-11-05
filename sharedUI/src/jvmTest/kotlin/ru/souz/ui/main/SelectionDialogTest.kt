package ru.souz.ui.main

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.click
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import ru.souz.ui.AppTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SelectionDialogTest {
    @Test
    fun `consecutive prompts reset choice even when an intermediate null is not composed`() = runComposeUiTest {
        var sourceId by mutableStateOf("chats")
        var requestId by mutableStateOf(1L)
        val chosen = mutableListOf<Long>()
        setContent {
            AppTheme {
                MainScreenContent(
                    state = MainState(selectionDialog = SelectionDialogData(
                        sourceId = sourceId,
                        requestId = requestId,
                        title = "Select $sourceId",
                        message = "Synthetic selection",
                        candidates = listOf(
                            SelectionDialogCandidateUi(11, "First", null, null, null),
                            SelectionDialogCandidateUi(22, "Second", null, null, null),
                        ),
                        confirmText = "Choose",
                        cancelText = "Cancel",
                    )),
                    isOnline = false,
                    onSelectApprovalCandidate = { chosen += it },
                )
            }
        }
        onNodeWithText("Choose", useUnmergedTree = true).performTouchInput { click() }
        assertEquals(emptyList(), chosen)
        onNodeWithText("First").performClick()
        onNodeWithText("Choose", useUnmergedTree = true).performTouchInput { click() }
        assertEquals(listOf(11L), chosen)

        // The next source deliberately reuses both requestId and candidate IDs.
        runOnIdle { sourceId = "contacts" }
        onNodeWithText("Choose", useUnmergedTree = true).performTouchInput { click() }
        assertEquals(listOf(11L), chosen)
        onNodeWithText("Second").performClick()
        onNodeWithText("Choose", useUnmergedTree = true).performTouchInput { click() }
        assertEquals(listOf(11L, 22L), chosen)

        // A subsequent prompt from the same source must reset too.
        runOnIdle { requestId = 2 }
        onNodeWithText("Choose", useUnmergedTree = true).performTouchInput { click() }
        assertEquals(listOf(11L, 22L), chosen)
    }
}
