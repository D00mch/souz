package ru.souz.ui.memory

import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import java.nio.file.Files
import java.nio.file.Path

class MemoryScreenSourceContractTest {
    @Test
    fun `memory screen uses shared liquid glass container instead of root black backing`() {
        val source = projectRoot()
            .resolve("sharedUI/src/jvmMain/kotlin/ru/souz/ui/memory/MemoryScreen.kt")
            .readText()

        assertContains(source, "RealLiquidGlassCard(")
        assertContains(source, "isWindowFocused = isFocused")
        assertFalse(
            ".fillMaxSize()\n            .background(MemoryUiColors.Screen)" in source,
            "MemoryScreen root must not paint a full-size black backing over the app window",
        )
    }

    private fun projectRoot(): Path {
        var current = Path.of("").toAbsolutePath()
        while (current.parent != null) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) return current
            current = current.parent
        }
        error("Project root not found")
    }
}
