package ru.souz.ambient

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AmbientSuggestionControllerTest {

    @Test
    fun `low confidence rejected by addressedness thresholds`() {
        val controller = controller()

        controller.handleCandidate(candidate(id = "direct", addressedness = AmbientAddressedness.DIRECT_TO_SOUZ, confidence = 0.64))
        controller.handleCandidate(candidate(id = "implicit", addressedness = AmbientAddressedness.IMPLICIT_USER_INTENT, confidence = 0.74))
        controller.handleCandidate(candidate(id = "unknown", addressedness = AmbientAddressedness.UNKNOWN, confidence = 0.84))

        assertEquals(emptyList(), controller.store.suggestions.value)
    }

    @Test
    fun `direct implicit and unknown thresholds allow candidates`() {
        val controller = controller()

        controller.handleCandidate(candidate(id = "direct", addressedness = AmbientAddressedness.DIRECT_TO_SOUZ, confidence = 0.65))
        controller.handleCandidate(candidate(id = "implicit", addressedness = AmbientAddressedness.IMPLICIT_USER_INTENT, confidence = 0.75))
        controller.handleCandidate(candidate(id = "unknown", addressedness = AmbientAddressedness.UNKNOWN, confidence = 0.85))

        assertEquals(listOf("direct", "implicit", "unknown"), controller.store.suggestions.value.map { it.id })
    }

    @Test
    fun `background quoted rejected`() {
        val controller = controller()

        controller.handleCandidate(candidate(addressedness = AmbientAddressedness.BACKGROUND_OR_QUOTED, confidence = 1.0))

        assertTrue(controller.store.suggestions.value.isEmpty())
    }

    @Test
    fun `empty text rejected and missing title suggestion are generated`() {
        val controller = controller()

        controller.handleCandidate(candidate(id = "empty", taskText = ""))
        controller.handleCandidate(candidate(id = "fallback", title = "", suggestionText = "", taskText = "создай событие"))

        val suggestion = controller.store.suggestions.value.single()
        assertEquals("fallback", suggestion.id)
        assertEquals("создай событие", suggestion.candidate.title)
        assertEquals("Помочь выполнить: «создай событие»?", suggestion.candidate.suggestionText)
    }

    @Test
    fun `bad model suggestion text is replaced with deterministic offer`() {
        val controller = controller()

        controller.handleCandidate(
            candidate(
                id = "note-search",
                taskText = "Найди заметку с долгами",
                suggestionText = "Какую заметку надо открыть?",
            )
        )

        val suggestion = controller.store.suggestions.value.single()
        assertEquals("Помочь найти заметку с долгами?", suggestion.candidate.suggestionText)
    }

    @Test
    fun `high risk low confidence rejected unless direct`() {
        val controller = controller()

        controller.handleCandidate(
            candidate(
                id = "implicit-high",
                addressedness = AmbientAddressedness.IMPLICIT_USER_INTENT,
                confidence = 0.8,
                risk = AmbientTaskRisk.HIGH,
            )
        )
        controller.handleCandidate(
            candidate(
                id = "direct-high",
                addressedness = AmbientAddressedness.DIRECT_TO_SOUZ,
                confidence = 0.65,
                risk = AmbientTaskRisk.HIGH,
            )
        )

        assertEquals(listOf("direct-high"), controller.store.suggestions.value.map { it.id })
    }

    @Test
    fun `capability ids are optional after addressedness threshold passes`() {
        val controller = controller()

        controller.handleCandidate(
            candidate(
                id = "implicit-no-cap",
                addressedness = AmbientAddressedness.IMPLICIT_USER_INTENT,
                confidence = 0.75,
                capabilityIds = emptyList(),
            )
        )
        controller.handleCandidate(
            candidate(
                id = "direct-no-cap-low",
                addressedness = AmbientAddressedness.DIRECT_TO_SOUZ,
                confidence = 0.65,
                capabilityIds = emptyList(),
            )
        )
        controller.handleCandidate(
            candidate(
                id = "unknown-no-cap",
                addressedness = AmbientAddressedness.UNKNOWN,
                confidence = 0.85,
                capabilityIds = emptyList(),
            )
        )

        assertEquals(
            listOf("implicit-no-cap", "direct-no-cap-low", "unknown-no-cap"),
            controller.store.suggestions.value.map { it.id },
        )
    }

    private fun controller(): TestController {
        val store = InMemoryAmbientSuggestionStore(clock = { 1_000L })
        return TestController(AmbientSuggestionController(store), store)
    }

    private data class TestController(
        val controller: AmbientSuggestionController,
        val store: InMemoryAmbientSuggestionStore,
    ) {
        fun handleCandidate(candidate: AmbientTaskCandidate) = controller.handleCandidate(candidate)
    }

    private fun candidate(
        id: String = "c1",
        title: String = "Title",
        taskText: String = "Do task $id",
        suggestionText: String = "Похоже, я могу помочь: Do task $id",
        addressedness: AmbientAddressedness = AmbientAddressedness.DIRECT_TO_SOUZ,
        confidence: Double = 0.9,
        risk: AmbientTaskRisk = AmbientTaskRisk.LOW,
        capabilityIds: List<String> = listOf("tool:FILES:list_files"),
    ): AmbientTaskCandidate = AmbientTaskCandidate(
        id = id,
        title = title,
        taskText = taskText,
        suggestionText = suggestionText,
        confidence = confidence,
        addressedness = addressedness,
        matchedCapabilityIds = capabilityIds,
        missingSlots = emptyList(),
        risk = risk,
        requiresConfirmation = true,
        evidenceEventIds = listOf("e1"),
        reason = "test",
    )
}
