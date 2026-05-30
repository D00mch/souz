package ru.souz.di

import org.kodein.di.DI
import kotlin.test.Test

class MainDiModuleTest {
    @Test
    fun `main di module can be imported without override conflict`() {
        DI {
            import(mainDiModule, allowOverride = true)
        }
    }
}
