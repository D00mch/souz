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
        val events = mutableListOf<MainEvent>()
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
                    onSelectApprovalCandidate = { source, request, candidate ->
                        events += MainEvent.SelectApprovalCandidate(source, request, candidate)
                    },
                    onCancelSelectionDialog = { source, request ->
                        events += MainEvent.CancelSelectionDialog(source, request)
                    },
                )
            }
        }
        onNodeWithText("Choose", useUnmergedTree = true).performTouchInput { click() }
        assertEquals(emptyList(), events)
        onNodeWithText("First").performClick()
        onNodeWithText("Choose", useUnmergedTree = true).performTouchInput { click() }
        val first: MainEvent = MainEvent.SelectApprovalCandidate("chats", 1, 11)
        assertEquals(listOf(first), events)

        // The next source deliberately reuses both requestId and candidate IDs.
        runOnIdle { sourceId = "contacts" }
        onNodeWithText("Choose", useUnmergedTree = true).performTouchInput { click() }
        assertEquals(listOf(first), events)
        onNodeWithText("Second").performClick()
        onNodeWithText("Choose", useUnmergedTree = true).performTouchInput { click() }
        val second: MainEvent = MainEvent.SelectApprovalCandidate("contacts", 1, 22)
        assertEquals(listOf(first, second), events)

        // A subsequent prompt from the same source must reset too.
        runOnIdle { requestId = 2 }
        onNodeWithText("Choose", useUnmergedTree = true).performTouchInput { click() }
        assertEquals(listOf(first, second), events)
        onNodeWithText("Cancel", useUnmergedTree = true).performClick()
        assertEquals(listOf(first, second, MainEvent.CancelSelectionDialog("contacts", 2)), events)
    }
}
