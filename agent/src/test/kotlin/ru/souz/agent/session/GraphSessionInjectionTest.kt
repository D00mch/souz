package ru.souz.agent.session

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import ru.souz.agent.agentDiModule

class GraphSessionInjectionTest {
    @Test
    fun `session service requires host persistence`() {
        val di = DI {
            bindSingleton<ObjectMapper> { jacksonObjectMapper() }
            import(agentDiModule())
        }

        assertFailsWith<DI.NotFoundException> { di.direct.instance<GraphSessionService>() }
    }

    @Test
    fun `session service saves each completed trace through the host repository`() {
        val repository = mockk<GraphSessionRepository>(relaxed = true)
        val di = DI {
            bindSingleton<ObjectMapper> { jacksonObjectMapper() }
            bindSingleton<GraphSessionRepository> { repository }
            import(agentDiModule())
        }
        val service = di.direct.instance<GraphSessionService>()

        service.startTask("synthetic first")
        service.finishTask()
        service.finishTask()
        service.startTask("synthetic second")
        service.finishTask()

        val saved = mutableListOf<GraphSession>()
        verify(exactly = 2) { repository.save(capture(saved)) }
        assertEquals(listOf("synthetic first", "synthetic second"), saved.map { it.initialInput })
        assertEquals(2, saved.map { it.id }.distinct().size)
    }
}
