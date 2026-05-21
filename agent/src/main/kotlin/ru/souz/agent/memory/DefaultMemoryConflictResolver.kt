package ru.souz.agent.memory

class DefaultMemoryConflictResolver {
    fun resolve(
        existingActiveFacts: List<MemoryFactSnapshot>,
        acceptedFact: MemoryFactSnapshot,
    ): MemoryConflictResolution {
        val acceptedSlotKey = acceptedFact.slotGroupingKey()
        if (acceptedSlotKey == null) {
            return MemoryConflictResolution(
                updatedExistingFacts = existingActiveFacts,
                supersededFactIds = emptyList(),
            )
        }

        val supersededFactIds = mutableListOf<String>()
        val updatedExistingFacts = existingActiveFacts.map { existingFact ->
            if (
                existingFact.status == MemoryFactStatus.ACTIVE &&
                existingFact.slotGroupingKey() == acceptedSlotKey
            ) {
                existingFact.id?.let(supersededFactIds::add)
                existingFact.copy(status = MemoryFactStatus.SUPERSEDED)
            } else {
                existingFact
            }
        }

        return MemoryConflictResolution(
            updatedExistingFacts = updatedExistingFacts,
            supersededFactIds = supersededFactIds,
        )
    }
}
