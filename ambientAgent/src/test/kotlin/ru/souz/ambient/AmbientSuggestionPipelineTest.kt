package ru.souz.ambient

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AmbientSuggestionPipelineTest {

    @Test
    fun `candidate flow is stored and stop cancels collection`() = runTest {
        val candidates = MutableSharedFlow<AmbientTaskCandidate>()
        val store = InMemoryAmbientSuggestionStore(clock = { 1_000L })
        val pipeline = AmbientSuggestionPipeline(
            candidateFlow = candidates,
            controller = AmbientSuggestionController(store),
            scope = this,
        )

        pipeline.start()
        candidates.emit(candidate("c1"))
        advanceUntilIdle()
        pipeline.stop()
        candidates.emit(candidate("c2"))
        advanceUntilIdle()

        assertEquals(listOf("c1"), store.suggestions.value.map { it.id })
    }

    private fun candidate(id: String): AmbientTaskCandidate = AmbientTaskCandidate(
        id = id,
        title = "Title",
        taskText = "Do task $id",
        suggestionText = "Похоже, я могу помочь: Do task $id",
        confidence = 0.9,
        addressedness = AmbientAddressedness.DIRECT_TO_SOUZ,
        matchedCapabilityIds = listOf("tool:FILES:list_files"),
        missingSlots = emptyList(),
        risk = AmbientTaskRisk.LOW,
        requiresConfirmation = true,
        evidenceEventIds = listOf("e1"),
        reason = "test",
    )
}
