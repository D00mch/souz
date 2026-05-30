package ru.souz.backend.agent.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import ru.souz.llms.ToolInvocationMeta

class BackendSandboxScopeResolverTest {
    @Test
    fun `backend sandbox scope is derived from user id only`() {
        val scope = BackendSandboxScopeResolver.resolve(
            ToolInvocationMeta(
                userId = "user-42",
                conversationId = "chat-99",
                requestId = "req-1",
            )
        )

        assertEquals("user-42", scope.userId)
        assertNull(scope.conversationId)
    }
}
