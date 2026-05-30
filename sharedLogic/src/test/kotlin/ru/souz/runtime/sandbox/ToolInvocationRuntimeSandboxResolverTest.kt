package ru.souz.runtime.sandbox

import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import ru.souz.llms.ToolInvocationMeta

class ToolInvocationRuntimeSandboxResolverTest {
    @Test
    fun `reuses runtime sandbox for repeated resolves of the same scope`() {
        val created = AtomicInteger(0)
        val resolver = FactoryBackedToolInvocationRuntimeSandboxResolver(
            sandboxFactory = { scope ->
                created.incrementAndGet()
                sandboxFor(scope)
            },
            scopeResolver = { meta ->
                SandboxScope(userId = meta.userId)
            },
        )

        val first = resolver.resolve(ToolInvocationMeta(userId = "user-1", requestId = "request-1"))
        val second = resolver.resolve(ToolInvocationMeta(userId = "user-1", requestId = "request-2"))

        assertSame(first, second)
        assertEquals(1, created.get())
    }

    @Test
    fun `creates separate runtime sandboxes for different scopes`() {
        val created = AtomicInteger(0)
        val resolver = FactoryBackedToolInvocationRuntimeSandboxResolver(
            sandboxFactory = { scope ->
                created.incrementAndGet()
                sandboxFor(scope)
            },
            scopeResolver = { meta ->
                SandboxScope(userId = meta.userId)
            },
        )

        val first = resolver.resolve(ToolInvocationMeta(userId = "user-1"))
        val second = resolver.resolve(ToolInvocationMeta(userId = "user-2"))

        assertNotSame(first, second)
        assertEquals(2, created.get())
    }

    @Test
    fun `local metadata resolves and reuses the local default sandbox scope`() {
        val created = AtomicInteger(0)
        val defaultScope = SandboxScope.localDefault()
        val resolver = FactoryBackedToolInvocationRuntimeSandboxResolver(
            sandboxFactory = { scope ->
                created.incrementAndGet()
                sandboxFor(scope)
            },
            scopeResolver = { defaultScope },
        )

        val first = resolver.resolve(ToolInvocationMeta.localDefault())
        val second = resolver.resolve(ToolInvocationMeta.localDefault())

        assertSame(first, second)
        assertEquals(defaultScope, first.scope)
        assertEquals(1, created.get())
    }

    private fun sandboxFor(scope: SandboxScope): RuntimeSandbox =
        mockk(relaxed = true) {
            every { this@mockk.scope } returns scope
        }
}
