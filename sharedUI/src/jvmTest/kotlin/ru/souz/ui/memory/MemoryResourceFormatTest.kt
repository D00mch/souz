package ru.souz.ui.memory

import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import java.nio.file.Files
import java.nio.file.Path

class MemoryResourceFormatTest {
    @Test
    fun `dreamer status resources use positional placeholders`() {
        val root = projectRoot()
        listOf(
            root.resolve("sharedUI/src/commonMain/composeResources/values/strings.xml"),
            root.resolve("sharedUI/src/commonMain/composeResources/values-ru/strings.xml"),
        ).forEach { file ->
            val text = file.readText()
            listOf(
                "memory_dreamer_attempted",
                "memory_dreamer_completed",
                "memory_dreamer_error",
                "memory_dreamer_blocked",
            ).forEach { name ->
                val value = Regex("""<string name="$name">([^<]+)</string>""")
                    .find(text)
                    ?.groupValues
                    ?.get(1)
                    ?: error("Missing $name in $file")
                assertFalse(
                    "%s" in value,
                    "$name in $file must use %1\$s so Compose resources format it correctly",
                )
            }
        }
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
