package ru.souz.agent.memory

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryDeterministicLogicTest {
    private val scope = MemoryScope(type = MemoryScopeType.PROJECT, id = "project-1")

    @Test
    fun `explicit remember candidate with user evidence is accepted`() {
        val candidate = candidate()

        val result = DefaultMemoryCandidateValidator().validate(
            MemoryCandidateValidationInput(
                candidate = candidate,
                evidenceIndex = mapOf("user-1" to evidence("user-1", MemoryEvidenceType.USER_MESSAGE)),
            )
        )

        assertTrue(result.accepted)
        assertNull(result.rejectionReason)
    }

    @Test
    fun `candidate without evidence refs is rejected`() {
        val candidate = candidate(evidenceRefs = emptyList())

        val result = DefaultMemoryCandidateValidator().validate(
            MemoryCandidateValidationInput(
                candidate = candidate,
                evidenceIndex = emptyMap(),
            )
        )

        assertFalse(result.accepted)
        assertEquals(MemoryCandidateRejectionReason.NO_EVIDENCE_REFS, result.rejectionReason)
    }

    @Test
    fun `assistant only evidence is rejected`() {
        val candidate = candidate(evidenceRefs = listOf("assistant-1"))

        val result = DefaultMemoryCandidateValidator().validate(
            MemoryCandidateValidationInput(
                candidate = candidate,
                evidenceIndex = mapOf("assistant-1" to evidence("assistant-1", MemoryEvidenceType.ASSISTANT_MESSAGE)),
            )
        )

        assertFalse(result.accepted)
        assertEquals(MemoryCandidateRejectionReason.ASSISTANT_ONLY_EVIDENCE, result.rejectionReason)
    }

    @Test
    fun `unsupported predicate is rejected`() {
        val candidate = candidate(predicate = "favorite_color")

        val result = DefaultMemoryCandidateValidator().validate(
            MemoryCandidateValidationInput(
                candidate = candidate,
                evidenceIndex = mapOf("user-1" to evidence("user-1", MemoryEvidenceType.USER_MESSAGE)),
            )
        )

        assertFalse(result.accepted)
        assertEquals(MemoryCandidateRejectionReason.UNSUPPORTED_PREDICATE, result.rejectionReason)
    }

    @Test
    fun `low confidence candidate is rejected`() {
        val candidate = candidate(confidence = 0.74)

        val result = DefaultMemoryCandidateValidator().validate(
            MemoryCandidateValidationInput(
                candidate = candidate,
                evidenceIndex = mapOf("user-1" to evidence("user-1", MemoryEvidenceType.USER_MESSAGE)),
            )
        )

        assertFalse(result.accepted)
        assertEquals(MemoryCandidateRejectionReason.LOW_CONFIDENCE, result.rejectionReason)
    }

    @Test
    fun `blank scope id is rejected`() {
        val candidate = candidate(scope = MemoryScope(type = MemoryScopeType.PROJECT, id = "   "))

        val result = DefaultMemoryCandidateValidator().validate(
            MemoryCandidateValidationInput(
                candidate = candidate,
                evidenceIndex = mapOf("user-1" to evidence("user-1", MemoryEvidenceType.USER_MESSAGE)),
            )
        )

        assertFalse(result.accepted)
        assertEquals(MemoryCandidateRejectionReason.INVALID_SCOPE, result.rejectionReason)
    }

    @Test
    fun `blank slot key is rejected for conflict bearing predicate`() {
        val candidate = candidate(predicate = "status", slotKey = " ")

        val result = DefaultMemoryCandidateValidator().validate(
            MemoryCandidateValidationInput(
                candidate = candidate,
                evidenceIndex = mapOf("user-1" to evidence("user-1", MemoryEvidenceType.USER_MESSAGE)),
            )
        )

        assertFalse(result.accepted)
        assertEquals(MemoryCandidateRejectionReason.BLANK_CONFLICT_SLOT_KEY, result.rejectionReason)
    }

    @Test
    fun `obviously ephemeral candidate is rejected`() {
        val candidate = candidate(
            predicate = "status",
            slotKey = "conversation",
            objectValueText = "thanks",
            reasonToStore = "Brief chit-chat thanks exchange",
        )

        val result = DefaultMemoryCandidateValidator().validate(
            MemoryCandidateValidationInput(
                candidate = candidate,
                evidenceIndex = mapOf("user-1" to evidence("user-1", MemoryEvidenceType.USER_MESSAGE)),
            )
        )

        assertFalse(result.accepted)
        assertEquals(MemoryCandidateRejectionReason.EPHEMERAL, result.rejectionReason)
    }

    @Test
    fun `duplicate active fact is rejected`() {
        val candidate = candidate()
        val activeFact = candidate.toFactSnapshot(
            id = "fact-1",
            createdAt = Instant.parse("2026-05-21T10:15:30Z"),
        )

        val result = DefaultMemoryCandidateValidator().validate(
            MemoryCandidateValidationInput(
                candidate = candidate,
                evidenceIndex = mapOf("user-1" to evidence("user-1", MemoryEvidenceType.USER_MESSAGE)),
                activeFacts = listOf(activeFact),
            )
        )

        assertFalse(result.accepted)
        assertEquals(MemoryCandidateRejectionReason.DUPLICATE_ACTIVE_FACT, result.rejectionReason)
    }

    @Test
    fun `per turn accepted limit is enforced`() {
        val candidate = candidate()
        val validator = DefaultMemoryCandidateValidator(perTurnAcceptedLimit = 1)

        val result = validator.validate(
            MemoryCandidateValidationInput(
                candidate = candidate,
                evidenceIndex = mapOf("user-1" to evidence("user-1", MemoryEvidenceType.USER_MESSAGE)),
                acceptedCountThisTurn = 1,
            )
        )

        assertFalse(result.accepted)
        assertEquals(MemoryCandidateRejectionReason.TURN_LIMIT_EXCEEDED, result.rejectionReason)
    }

    @Test
    fun `same slot key supersedes old fact`() {
        val existingFact = factSnapshot(
            id = "fact-old",
            predicate = "status",
            slotKey = "initiative:memory-core",
            objectValueText = "in_progress",
            createdAt = Instant.parse("2026-05-21T08:00:00Z"),
        )
        val newFact = factSnapshot(
            id = "fact-new",
            predicate = "status",
            slotKey = "initiative:memory-core",
            objectValueText = "done",
            createdAt = Instant.parse("2026-05-21T09:00:00Z"),
        )

        val resolution = DefaultMemoryConflictResolver().resolve(
            existingActiveFacts = listOf(existingFact),
            acceptedFact = newFact,
        )

        assertEquals(listOf("fact-old"), resolution.supersededFactIds)
        assertEquals(MemoryFactStatus.SUPERSEDED, resolution.updatedExistingFacts.single().status)
    }

    @Test
    fun `different slot key keeps facts independent`() {
        val existingFact = factSnapshot(
            id = "fact-old",
            predicate = "status",
            slotKey = "initiative:memory-core",
        )
        val newFact = factSnapshot(
            id = "fact-new",
            predicate = "status",
            slotKey = "initiative:search-mode",
        )

        val resolution = DefaultMemoryConflictResolver().resolve(
            existingActiveFacts = listOf(existingFact),
            acceptedFact = newFact,
        )

        assertTrue(resolution.supersededFactIds.isEmpty())
        assertEquals(MemoryFactStatus.ACTIVE, resolution.updatedExistingFacts.single().status)
    }

    @Test
    fun `packet renderer excludes superseded facts and inactive episodes`() {
        val renderer = DefaultMemoryPacketRenderer()
        val activeFact = factSnapshot(
            id = "fact-active",
            predicate = "works_on",
            slotKey = "task:memory",
            objectValueText = "memory-core",
        )
        val supersededFact = factSnapshot(
            id = "fact-superseded",
            predicate = "status",
            slotKey = "initiative:memory-core",
            objectValueText = "in_progress",
            status = MemoryFactStatus.SUPERSEDED,
        )
        val activeEpisode = MemoryEpisodeSnapshot(
            id = "episode-active",
            scope = scope,
            title = "Memory hardening",
            summary = "Implemented deterministic guards for memory writes.",
            nextAction = "Wire storage later",
            lastTouchedAt = Instant.parse("2026-05-21T11:00:00Z"),
        )
        val inactiveEpisode = MemoryEpisodeSnapshot(
            id = "episode-closed",
            scope = scope,
            title = "Closed",
            summary = "Should not render",
            status = "SUPERSEDED",
            lastTouchedAt = Instant.parse("2026-05-21T11:05:00Z"),
        )

        val result = renderer.render(
            MemoryPacketRenderInput(
                facts = listOf(activeFact, supersededFact),
                episodes = listOf(activeEpisode, inactiveEpisode),
                maxChars = 1_000,
            )
        )

        assertTrue(result.renderedBlock.contains("memory-core"))
        assertTrue(result.renderedBlock.contains("Memory hardening"))
        assertFalse(result.renderedBlock.contains("fact-superseded"))
        assertFalse(result.renderedBlock.contains("Should not render"))
        assertFalse(result.renderedBlock.contains("in_progress"))
    }

    @Test
    fun `packet renderer keeps one fact per slot key`() {
        val renderer = DefaultMemoryPacketRenderer()
        val olderFact = factSnapshot(
            id = "fact-old",
            predicate = "status",
            slotKey = "initiative:memory-core",
            objectValueText = "in_progress",
            createdAt = Instant.parse("2026-05-21T09:00:00Z"),
        )
        val newerFact = factSnapshot(
            id = "fact-new",
            predicate = "status",
            slotKey = "initiative:memory-core",
            objectValueText = "done",
            createdAt = Instant.parse("2026-05-21T10:00:00Z"),
        )

        val result = renderer.render(
            MemoryPacketRenderInput(
                facts = listOf(olderFact, newerFact),
                maxChars = 1_000,
            )
        )

        assertFalse(result.renderedBlock.contains("in_progress"))
        assertTrue(result.renderedBlock.contains("done"))
        assertEquals(1, result.packets.size)
    }

    @Test
    fun `packet renderer enforces budget`() {
        val renderer = DefaultMemoryPacketRenderer()
        val result = renderer.render(
            MemoryPacketRenderInput(
                facts = listOf(
                    factSnapshot(
                        id = "fact-1",
                        predicate = "requires",
                        slotKey = "task:memory-core",
                        objectValueText = "A very long requirement text that should consume budget quickly.",
                    ),
                    factSnapshot(
                        id = "fact-2",
                        predicate = "uses_module",
                        slotKey = "module:agent",
                        objectValueText = "agent",
                    ),
                ),
                maxChars = 60,
            )
        )

        assertTrue(result.renderedBlock.length <= 60)
    }

    @Test
    fun `packet renderer emits no raw ids`() {
        val renderer = DefaultMemoryPacketRenderer()
        val result = renderer.render(
            MemoryPacketRenderInput(
                facts = listOf(
                    factSnapshot(
                        id = "db-fact-123",
                        predicate = "approved_decision",
                        slotKey = "decision:memory",
                        objectValueText = "Use deterministic validation before storage.",
                    )
                ),
                episodes = listOf(
                    MemoryEpisodeSnapshot(
                        id = "db-episode-456",
                        scope = scope,
                        title = "Memory work",
                        summary = "Decision captured.",
                        lastTouchedAt = Instant.parse("2026-05-21T11:30:00Z"),
                    )
                ),
                maxChars = 1_000,
            )
        )

        assertFalse(result.renderedBlock.contains("db-fact-123"))
        assertFalse(result.renderedBlock.contains("db-episode-456"))
        assertTrue(result.packets.none { it.text.contains("db-fact-123") || it.text.contains("db-episode-456") })
    }

    private fun candidate(
        predicate: String = "prefers_language",
        slotKey: String? = "profile:language",
        objectValueText: String? = "Kotlin",
        evidenceRefs: List<String> = listOf("user-1"),
        confidence: Double = 0.9,
        reasonToStore: String = "Explicit remember request from the user.",
        scope: MemoryScope = this.scope,
    ): MemoryCandidate = MemoryCandidate(
        subjectEntityType = "user",
        subjectCanonicalName = "Duxx",
        subjectDisplayName = "Duxx",
        subjectNormalizedKey = "user:duxx",
        predicate = predicate,
        objectKind = MemoryObjectKind.TEXT,
        objectValueText = objectValueText,
        scope = scope,
        slotKey = slotKey,
        confidence = confidence,
        reasonToStore = reasonToStore,
        evidenceRefs = evidenceRefs,
    )

    private fun evidence(ref: String, type: MemoryEvidenceType): MemoryEvidenceRecord = MemoryEvidenceRecord(
        scope = scope,
        evidenceType = type,
        sourceRef = ref,
    )

    private fun factSnapshot(
        id: String,
        predicate: String,
        slotKey: String? = "profile:language",
        objectValueText: String? = "Kotlin",
        status: MemoryFactStatus = MemoryFactStatus.ACTIVE,
        createdAt: Instant = Instant.parse("2026-05-21T10:00:00Z"),
    ): MemoryFactSnapshot = MemoryFactSnapshot(
        id = id,
        scope = scope,
        subjectEntityType = "user",
        subjectCanonicalName = "Duxx",
        subjectDisplayName = "Duxx",
        subjectNormalizedKey = "user:duxx",
        predicate = predicate,
        objectKind = MemoryObjectKind.TEXT,
        objectValueText = objectValueText,
        slotKey = slotKey,
        confidence = 0.9,
        status = status,
        createdAt = createdAt,
    )
}
