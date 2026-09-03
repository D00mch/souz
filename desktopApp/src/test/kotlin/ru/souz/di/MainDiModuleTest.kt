package ru.souz.di

import org.kodein.di.DI
import org.kodein.di.direct
import org.kodein.di.instance
import ru.souz.agent.AgentFacade
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertNotNull

class MainDiModuleTest {
    // Disabled because resolving the desktop DI graph loads macOS CoreGraphics, which fails on Linux CI.
    @Ignore
    @Test
    fun `main di module resolves the agent facade without override conflict`() {
        val di = DI {
            import(mainDiModule, allowOverride = true)
        }

        assertNotNull(di.direct.instance<AgentFacade>())
    }
}
