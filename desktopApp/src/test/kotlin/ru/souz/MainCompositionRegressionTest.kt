package ru.souz

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class MainCompositionRegressionTest {
    @Test
    fun `App is not inside a keyed subtree`() {
        val source = mainSource()
        val appCall = checkNotNull(Regex("""(?m)^\s*App\s*\(""").find(source)?.range?.first)

        Regex("""\bkey\s*\([^)]*\)\s*\{""")
            .findAll(source.take(appCall))
            .forEach { keyCall ->
                assertTrue(
                    closingBrace(source, source.indexOf('{', keyCall.range.last)) < appCall,
                    "A key around App recreates navigation and local Compose state.",
                )
            }
    }

    private fun closingBrace(source: String, openingBrace: Int): Int {
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return index
            }
        }
        return source.length
    }

    private fun mainSource(): String = listOf(
        File("desktopApp/src/main/kotlin/ru/souz/Main.kt"),
        File("src/main/kotlin/ru/souz/Main.kt"),
    ).firstOrNull(File::isFile)?.readText() ?: error("Main.kt source was not found")
}
