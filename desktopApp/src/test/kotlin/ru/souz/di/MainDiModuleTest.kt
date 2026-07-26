package ru.souz.di

import org.kodein.di.DI
import org.kodein.di.direct
import org.kodein.di.instance
import ru.souz.GraphBasedAgent
import ru.souz.SkillsGraphBasedAgent
import kotlin.test.Test
import kotlin.test.assertNotNull

class MainDiModuleTest {
    @Test
    fun `main di module resolves both graph agents without override conflict`() {
        val di = DI {
            import(mainDiModule, allowOverride = true)
        }

        assertNotNull(di.direct.instance<GraphBasedAgent>())
        assertNotNull(di.direct.instance<SkillsGraphBasedAgent>())
    }
}
