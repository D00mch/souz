package ru.souz.memory

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryBlockRendererTest {
    @Test
    fun `render returns compact memory block`() {
        val rendered = MemoryBlockRenderer.render(
            facts = listOf(
                MemoryFact(
                    id = "fact-1",
                    scope = MemoryScope("global", "global"),
                    kind = MemoryFactKind.PROJECT_RULE,
                    title = "Write tests first",
                    body = "Always add tests before implementation.",
                    slotKey = null,
                    status = MemoryFactStatus.ACTIVE,
                    confidence = 0.9f,
                    pinned = false,
                    createdBy = "writer",
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                    supersedesFactId = null,
                )
            )
        )

        assertEquals(
            "Relevant memory:\n- [project_rule] Write tests first: Always add tests before implementation.",
            rendered,
        )
    }
}
