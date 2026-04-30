package ru.souz.skill

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.llms.EmbeddingInputKind
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse

class EmbeddingSkillSearchTest {
    @Test
    fun `search builds index from skill summaries and returns relevant match`() = runTest {
        val fixture = createFixture()
        fixture.writeSkill(
            sourceDir = fixture.workspaceDir,
            folderName = "weather",
            markdown = """
            ---
            name: weather
            description: Weather and forecast helper
            when_to_use: Use for forecast and temperature questions
            ---
            body with unrelated details
            """.trimIndent(),
        )
        fixture.writeSkill(
            sourceDir = fixture.workspaceDir,
            folderName = "calendar",
            markdown = """
            ---
            name: calendar
            description: Calendar and schedule helper
            when_to_use: Use for events and planning
            ---
            body
            """.trimIndent(),
        )

        val matches = fixture.search.searchRelevantSkills("forecast for Moscow", limit = 2)

        assertEquals("weather", matches.firstOrNull()?.summary?.name)
        assertTrue((matches.firstOrNull()?.score ?: 0f) > 0f)
    }

    @Test
    fun `search ignores body-only terms because embeddings index summaries`() = runTest {
        val fixture = createFixture()
        fixture.writeSkill(
            sourceDir = fixture.workspaceDir,
            folderName = "weather",
            markdown = """
            ---
            name: weather
            description: Weather helper
            when_to_use: Use for forecast questions
            ---
            This body mentions accounting budget payroll finance terms.
            """.trimIndent(),
        )

        val matches = fixture.search.searchRelevantSkills("finance budget", limit = 3)

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `search refreshes the index when skill summaries change on disk`() = runTest {
        val fixture = createFixture()
        fixture.writeSkill(
            sourceDir = fixture.workspaceDir,
            folderName = "assistant",
            markdown = """
            ---
            name: assistant
            description: Weather helper
            when_to_use: Use for forecast questions
            ---
            body
            """.trimIndent(),
        )

        assertEquals("assistant", fixture.search.searchRelevantSkills("forecast", limit = 1).single().summary.name)

        fixture.writeSkill(
            sourceDir = fixture.workspaceDir,
            folderName = "assistant",
            markdown = """
            ---
            name: assistant
            description: Planner helper
            when_to_use: Use for project planning and scheduling
            ---
            body
            """.trimIndent(),
        )

        val matches = fixture.search.searchRelevantSkills("planning schedule", limit = 1)

        assertEquals("assistant", matches.single().summary.name)
        assertTrue(matches.single().summary.description.contains("Planner"))
    }

    @Test
    fun `loadSkill stays filesystem-backed after embedding search`() = runTest {
        val fixture = createFixture()
        fixture.writeSkill(
            sourceDir = fixture.workspaceDir,
            folderName = "weather",
            markdown = """
            ---
            name: weather
            description: Weather helper
            when_to_use: Use for forecast questions
            ---
            full skill body
            """.trimIndent(),
        )

        fixture.search.searchRelevantSkills("forecast", limit = 1)
        val skill = fixture.search.loadSkill("weather")

        assertNotNull(skill)
        assertEquals("full skill body", skill.body)
    }

    private fun createFixture(): Fixture {
        val root = Files.createTempDirectory("skill-embedding-search")
        val workspaceDir = root.resolve("workspace").resolve("skills")
        val managedDir = root.resolve("managed")
        val indexDir = root.resolve("index")
        workspaceDir.createDirectories()
        managedDir.createDirectories()
        indexDir.createDirectories()

        val api = mockk<LLMChatAPI>()
        val embeddings = FakeEmbeddings()
        coEvery { api.embeddings(any<LLMRequest.Embeddings>()) } answers {
            val request = firstArg<LLMRequest.Embeddings>()
            LLMResponse.Embeddings.Ok(
                data = request.input.mapIndexed { idx, text ->
                    LLMResponse.Embedding(
                        embedding = embeddings.embed(text, request.inputKind),
                        index = idx,
                        objectType = "embedding",
                    )
                },
                model = "fake-embeddings",
                objectType = "list",
            )
        }

        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.embeddingsModel } returns EmbeddingsModel.GigaEmbeddings
        every { settingsProvider.gigaChatKey } returns "giga-key"

        val catalog = FilesystemSkillCatalog(
            directories = SkillDirectories(
                workspaceSkillsDir = workspaceDir,
                managedSkillsDir = managedDir,
            ),
        )
        val search = EmbeddingSkillSearch(
            catalog = catalog,
            api = api,
            settingsProvider = settingsProvider,
            index = SkillVectorIndex(indexDirectory = indexDir),
        )
        return Fixture(
            workspaceDir = workspaceDir,
            search = search,
        )
    }

    private data class Fixture(
        val workspaceDir: java.nio.file.Path,
        val search: EmbeddingSkillSearch,
    ) {
        fun writeSkill(
            sourceDir: java.nio.file.Path,
            folderName: String,
            markdown: String,
        ) {
            val directory = sourceDir.resolve(folderName)
            directory.createDirectories()
            directory.resolve("SKILL.md").writeText(markdown)
        }
    }

    private class FakeEmbeddings {
        private val tokenIndex = LinkedHashMap<String, Int>()

        fun embed(
            text: String,
            inputKind: EmbeddingInputKind,
        ): List<Double> {
            val vector = DoubleArray(64)
            tokenize(text).forEach { token ->
                val idx = tokenIndex.getOrPut(token) { tokenIndex.size % vector.size }
                vector[idx] += if (inputKind == EmbeddingInputKind.QUERY) 1.0 else 1.5
            }
            return vector.toList()
        }

        private fun tokenize(text: String): List<String> =
            Regex("""[\p{L}\p{N}_-]+""").findAll(text.lowercase()).map { it.value }.toList()
    }
}
