package ru.souz.ui.main.usecases

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.souz.ui.main.ChatMessage
import ru.souz.ui.main.ChatSearchState

class ChatSearchUseCaseTest {

    private val useCase = ChatSearchUseCase()

    @Test
    fun `sync searches rendered markdown text instead of raw source`() {
        val visibleLink = ChatMessage(
            text = "[Visible label](https://example.com/private-destination)",
            isUser = false,
        )
        val emphasized = ChatMessage(
            text = "prefix **bold target** suffix",
            isUser = false,
        )

        val visibleResults = useCase.sync(
            messages = listOf(visibleLink, emphasized),
            search = ChatSearchState(query = "visible label"),
        )
        assertEquals(listOf(visibleLink.id), visibleResults.results.map { it.messageId })

        val markdownResults = useCase.sync(
            messages = listOf(visibleLink, emphasized),
            search = ChatSearchState(query = "bold target"),
        )
        assertEquals(listOf(emphasized.id), markdownResults.results.map { it.messageId })

        val hiddenDestinationResults = useCase.sync(
            messages = listOf(visibleLink, emphasized),
            search = ChatSearchState(query = "private-destination"),
        )
        assertEquals(emptyList(), hiddenDestinationResults.results)
    }

    @Test
    fun `open and sync use normalized query`() {
        val matched = ChatMessage(text = "target", isUser = true)
        val search = ChatSearchState(query = "  target  ")

        val opened = useCase.open(search)
        val synced = useCase.sync(messages = listOf(matched), search = search)

        assertEquals(true, opened.selectAllOnActivate)
        assertEquals(listOf(matched.id), synced.results.map { it.messageId })
    }

    @Test
    fun `sync counts repeated matches inside the same message`() {
        val first = ChatMessage(text = "target and target", isUser = true)
        val second = ChatMessage(text = "before target after", isUser = false)

        val state = useCase.sync(
            messages = listOf(first, second),
            search = ChatSearchState(query = "target"),
        )

        assertEquals(3, state.results.size)
        assertEquals(
            listOf(
                first.id to 0,
                first.id to 1,
                second.id to 0,
            ),
            state.results.map { it.messageId to it.matchIndexInMessage },
        )
    }
}
