package ru.souz.skills.bundle

import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.bundle.SkillBundleException
import ru.souz.agent.skills.validation.SkillValidationPolicy
import ru.souz.db.SettingsProvider
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox
import ru.souz.skills.filesystem.LocalSkillBundleFileSystem
import ru.souz.skills.filesystem.SkillBundleFileSystem
import ru.souz.skills.filesystem.SkillBundleFsContext
import ru.souz.tool.files.FilesToolUtil
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileSystemSkillBundleLoaderTest {
    private val createdPaths = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        createdPaths.asReversed().forEach { path ->
            runCatching { path.toFile().deleteRecursively() }
        }
        createdPaths.clear()
    }

    @Test
    fun `loads valid skill folder`() = runTest {
        val root = createHomeTempDirectory("skill-loader-valid-")
        writeSkillFixture(root)
        val loader = createLoader()

        val bundle = loader.loadDirectory(
            context = SkillBundleFsContext(userId = "user-1"),
            skillId = SkillId("paper-summarize-academic"),
            rawRoot = root.toString(),
        )

        assertEquals("paper-summarize-academic", bundle.skillId.value)
        assertEquals("Paper Summarizer", bundle.manifest.name)
        assertEquals(
            listOf("README.md", "SKILL.md", "templates/example.ts"),
            bundle.files.map { it.normalizedPath },
        )
    }

    @Test
    fun `rejects missing SKILL dot md`() = runTest {
        val root = createHomeTempDirectory("skill-loader-missing-skill-md-")
        root.resolve("README.md").writeText("docs")
        val loader = createLoader()

        val error = assertFailsWith<SkillBundleException> {
            loader.loadDirectory(
                context = SkillBundleFsContext(userId = "user-1"),
                skillId = SkillId("missing-skill-md"),
                rawRoot = root.toString(),
            )
        }

        assertContains(error.message.orEmpty(), "missing SKILL.md")
    }

    @Test
    fun `rejects duplicate normalized paths`() = runTest {
        val root = createHomeTempDirectory("skill-loader-duplicate-paths-")
        val fileSystem = object : SkillBundleFileSystem {
            override suspend fun resolveSafeDirectory(context: SkillBundleFsContext, rawPath: String): Path = root

            override suspend fun listRegularFiles(context: SkillBundleFsContext, root: Path): List<Path> = listOf(
                root.resolve("SKILL.md"),
                root.resolve("SKILL.md"),
            )

            override suspend fun readUtf8File(context: SkillBundleFsContext, path: Path, maxBytes: Long): String =
                """
                ---
                name: Duplicate Test
                description: duplicate
                ---
                body
                """.trimIndent()
        }
        val loader = FileSystemSkillBundleLoader(fileSystem = fileSystem)

        val error = assertFailsWith<SkillBundleException> {
            loader.loadDirectory(
                context = SkillBundleFsContext(userId = "user-1"),
                skillId = SkillId("duplicate"),
                rawRoot = root.toString(),
            )
        }

        assertContains(error.message.orEmpty(), "Duplicate normalized path")
    }

    @Test
    fun `rejects symlink escape`() = runTest {
        val root = createHomeTempDirectory("skill-loader-symlink-root-")
        val outside = createHomeTempDirectory("skill-loader-symlink-outside-")
        writeSkillFixture(root)
        outside.resolve("secret.txt").writeText("secret")
        Files.createSymbolicLink(root.resolve("escape.txt"), outside.resolve("secret.txt"))
        val loader = createLoader()

        val error = assertFailsWith<SkillBundleException> {
            loader.loadDirectory(
                context = SkillBundleFsContext(userId = "user-1"),
                skillId = SkillId("symlink-escape"),
                rawRoot = root.toString(),
            )
        }

        assertContains(error.message.orEmpty(), "Symbolic link")
    }

    @Test
    fun `rejects symlink file even when target stays inside root`() = runTest {
        val root = createHomeTempDirectory("skill-loader-symlink-inside-root-")
        writeSkillFixture(root)
        val target = root.resolve("README.md")
        Files.delete(target)
        root.resolve("docs").createDirectories()
        root.resolve("docs/README.md").writeText("Read me")
        Files.createSymbolicLink(target, root.resolve("docs/README.md"))
        val loader = createLoader()

        val error = assertFailsWith<SkillBundleException> {
            loader.loadDirectory(
                context = SkillBundleFsContext(userId = "user-1"),
                skillId = SkillId("symlink-inside"),
                rawRoot = root.toString(),
            )
        }

        assertContains(error.message.orEmpty(), "Symbolic link")
    }

    @Test
    fun `rejects symlink directory even when target stays inside root`() = runTest {
        val root = createHomeTempDirectory("skill-loader-symlink-dir-inside-root-")
        writeSkillFixture(root)
        val templates = root.resolve("templates")
        val realTemplates = root.resolve("real-templates")
        templates.toFile().deleteRecursively()
        realTemplates.createDirectories()
        realTemplates.resolve("example.ts").writeText("export const template = true\n")
        Files.createSymbolicLink(templates, realTemplates)
        val loader = createLoader()

        val error = assertFailsWith<SkillBundleException> {
            loader.loadDirectory(
                context = SkillBundleFsContext(userId = "user-1"),
                skillId = SkillId("symlink-dir-inside"),
                rawRoot = root.toString(),
            )
        }

        assertContains(error.message.orEmpty(), "Symbolic link")
    }

    @Test
    fun `rejects unsafe and forbidden roots`() = runTest {
        val forbiddenRoot = createHomeTempDirectory("skill-loader-forbidden-")
        writeSkillFixture(forbiddenRoot)
        val forbiddenLoader = createLoader(forbiddenFolders = listOf(forbiddenRoot.toString()))

        val forbiddenError = assertFailsWith<SkillBundleException> {
            forbiddenLoader.loadDirectory(
                context = SkillBundleFsContext(userId = "user-1"),
                skillId = SkillId("forbidden"),
                rawRoot = forbiddenRoot.toString(),
            )
        }
        assertTrue(
            forbiddenError.message.orEmpty().contains("Forbidden") ||
                forbiddenError.message.orEmpty().contains("Access denied")
        )

        val invalidLoader = createLoader()
        val invalidError = assertFailsWith<SkillBundleException> {
            invalidLoader.loadDirectory(
                context = SkillBundleFsContext(userId = "user-1"),
                skillId = SkillId("unsafe"),
                rawRoot = "   ",
            )
        }
        assertContains(invalidError.message.orEmpty(), "blank")
    }

    @Test
    fun `rejects too many files`() = runTest {
        val root = createHomeTempDirectory("skill-loader-too-many-files-")
        writeSkillFixture(root)
        root.resolve("notes.md").writeText("extra")
        val loader = createLoader(maxFiles = 2)

        val error = assertFailsWith<SkillBundleException> {
            loader.loadDirectory(
                context = SkillBundleFsContext(userId = "user-1"),
                skillId = SkillId("too-many"),
                rawRoot = root.toString(),
            )
        }

        assertContains(error.message.orEmpty(), "Too many files")
    }

    @Test
    fun `rejects disallowed extension`() = runTest {
        val root = createHomeTempDirectory("skill-loader-bad-ext-")
        writeSkillFixture(root)
        root.resolve("image.png").writeText("not really a png")
        val loader = createLoader()

        val error = assertFailsWith<SkillBundleException> {
            loader.loadDirectory(
                context = SkillBundleFsContext(userId = "user-1"),
                skillId = SkillId("bad-ext"),
                rawRoot = root.toString(),
            )
        }

        assertContains(error.message.orEmpty(), "Disallowed skill file extension")
    }

    @Test
    fun `rejects file over max file bytes`() = runTest {
        val root = createHomeTempDirectory("skill-loader-max-file-bytes-")
        writeSkillFixture(root)
        root.resolve("README.md").writeText("x".repeat(128))
        val loader = createLoader()
        val policy = SkillValidationPolicy.default().copy(maxFileBytes = 32)

        val error = assertFailsWith<SkillBundleException> {
            loader.loadDirectory(
                context = SkillBundleFsContext(userId = "user-1"),
                skillId = SkillId("file-too-large"),
                rawRoot = root.toString(),
                policy = policy,
            )
        }

        assertContains(error.message.orEmpty(), "exceeds")
    }

    @Test
    fun `rejects bundle over max bundle bytes`() = runTest {
        val root = createHomeTempDirectory("skill-loader-max-bundle-bytes-")
        writeSkillFixture(root)
        root.resolve("README.md").writeText("a".repeat(40))
        root.resolve("templates").createDirectories()
        root.resolve("templates/example.ts").writeText("b".repeat(40))
        val loader = createLoader()
        val policy = SkillValidationPolicy.default().copy(maxFileBytes = 128, maxBundleBytes = 64)

        val error = assertFailsWith<SkillBundleException> {
            loader.loadDirectory(
                context = SkillBundleFsContext(userId = "user-1"),
                skillId = SkillId("bundle-too-large"),
                rawRoot = root.toString(),
                policy = policy,
            )
        }

        assertContains(error.message.orEmpty(), "bundle")
    }

    private fun createLoader(
        forbiddenFolders: List<String> = emptyList(),
        maxFiles: Int = 64,
    ): FileSystemSkillBundleLoader {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.forbiddenFolders } returns forbiddenFolders
        val sandbox = LocalRuntimeSandbox(
            scope = SandboxScope.localDefault(),
            settingsProvider = settingsProvider,
        )
        val fileSystem = LocalSkillBundleFileSystem(FilesToolUtil(sandbox))
        return FileSystemSkillBundleLoader(fileSystem = fileSystem, maxFiles = maxFiles)
    }

    private fun createHomeTempDirectory(prefix: String): Path =
        Files.createTempDirectory(Path.of(FilesToolUtil.homeStr), prefix)
            .also(createdPaths::add)

    private fun writeSkillFixture(root: Path) {
        root.resolve("SKILL.md").writeText(
            """
            ---
            name: Paper Summarizer
            description: Summarize papers
            ---
            Use this skill.
            """.trimIndent()
        )
        root.resolve("README.md").writeText("Read me")
        root.resolve("templates").createDirectories()
        root.resolve("templates/example.ts").writeText("export const template = true\n")
    }
}
