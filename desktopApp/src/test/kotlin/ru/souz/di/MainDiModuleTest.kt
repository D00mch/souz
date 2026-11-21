package ru.souz.di

import io.mockk.every
import io.mockk.mockk
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import ru.souz.GraphBasedAgent
import ru.souz.SkillsGraphBasedAgent
import ru.souz.db.DesktopModelCatalog
import ru.souz.llms.local.LocalProviderAvailability
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MainDiModuleTest {
    @Test
    fun `desktop model catalog uses the host local capabilities`() {
        val localModels = mockk<LocalProviderAvailability> {
            every { isProviderAvailable() } returns true
        }
        val di = DI {
            import(mainDiModule, allowOverride = true)
            bindSingleton<LocalProviderAvailability>(overrides = true) { localModels }
        }

        assertTrue(di.direct.instance<DesktopModelCatalog>().supportsLocalInference)
    }

    // Disabled because resolving the desktop DI graph loads macOS CoreGraphics, which fails on Linux CI.
    @Ignore
    @Test
    fun `main di module resolves both graph agents without override conflict`() {
        val di = DI {
            import(mainDiModule, allowOverride = true)
        }

        assertNotNull(di.direct.instance<GraphBasedAgent>())
        assertNotNull(di.direct.instance<SkillsGraphBasedAgent>())
    }
}
