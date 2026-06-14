package ru.souz.tool

import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.runtime.GeneratedImage
import ru.souz.llms.runtime.ImageGenerationGateway
import ru.souz.llms.runtime.VisionGateway
import ru.souz.runtime.sandbox.RuntimeSandboxFactory
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox
import ru.souz.test.suspendInvoke
import ru.souz.tool.files.DeferredToolModifyPermissionBroker
import ru.souz.tool.files.ToolDeleteFile
import ru.souz.tool.files.ToolModifyFile
import ru.souz.tool.files.ToolMoveFile
import ru.souz.tool.files.ToolReadFile
import kotlin.io.path.exists
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PortableRuntimeToolsModuleSafeModeTest {
    private val createdPaths = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        createdPaths.asReversed().forEach { path ->
            runCatching { path.toFile().deleteRecursively() }
        }
        createdPaths.clear()
    }

    @Test
    fun `DeleteFile from portable DI requests permission and does not delete when denied`() = runTest {
        val home = createTempDirectory("portable-delete-home-")
        val stateRoot = createTempDirectory("portable-delete-state-")
        val file = home.resolve("delete-me.txt").apply { writeText("keep") }
        val directDI = createDirectDI(home, stateRoot, safeModeEnabled = true, bindBrokers = true)
        val broker = directDI.instance<ToolPermissionBroker>()
        val tool = directDI.instance<ToolDeleteFile>()

        val result = async {
            tool.suspendInvoke(ToolDeleteFile.Input("~/delete-me.txt"))
        }
        val request = broker.requests.first()
        broker.resolve(request.id, approved = false)

        assertEquals("Delete file or folder", request.description)
        assertEquals(file.toRealPath().toString(), request.params["path"])
        assertEquals("User disapproved", result.await())
        assertTrue(file.exists())
    }

    @Test
    fun `MoveFile from portable DI requests permission and does not move when denied`() = runTest {
        val home = createTempDirectory("portable-move-home-")
        val stateRoot = createTempDirectory("portable-move-state-")
        val source = home.resolve("source.txt").apply { writeText("keep") }
        val destination = home.resolve("archive/destination.txt")
        val directDI = createDirectDI(home, stateRoot, safeModeEnabled = true, bindBrokers = true)
        val broker = directDI.instance<ToolPermissionBroker>()
        val tool = directDI.instance<ToolMoveFile>()

        val result = async {
            tool.suspendInvoke(
                ToolMoveFile.Input(
                    sourcePath = "~/source.txt",
                    destinationPath = "~/archive/destination.txt",
                )
            )
        }
        val request = broker.requests.first()
        broker.resolve(request.id, approved = false)

        assertEquals("Move file", request.description)
        assertEquals(source.toRealPath().toString(), request.params["sourcePath"])
        assertEquals(destination.toString(), request.params["destinationPath"])
        assertEquals("User disapproved", result.await())
        assertTrue(source.exists())
        assertTrue(destination.notExists())
    }

    @Test
    fun `EditFile from portable DI stages review instead of mutating immediately`() = runTest {
        val home = createTempDirectory("portable-edit-home-")
        val stateRoot = createTempDirectory("portable-edit-state-")
        val file = home.resolve("edit.txt").apply { writeText("old\n") }
        val directDI = createDirectDI(home, stateRoot, safeModeEnabled = true, bindBrokers = true)
        val broker = directDI.instance<DeferredToolModifyPermissionBroker>()
        val tool = directDI.instance<ToolModifyFile>()

        val result = tool.suspendInvoke(
            ToolModifyFile.Input(
                path = "~/edit.txt",
                oldString = "old",
                newString = "new",
            )
        )

        val review = assertNotNull(broker.snapshotPendingReview())
        assertEquals("Staged, not yet applied", result)
        assertEquals(file.toRealPath().toString(), review.items.single().path)
        assertTrue(review.items.single().patchPreview.contains("+new"))
        assertEquals("old\n", file.readText())
    }

    @Test
    fun `portable mutation tools can be resolved without optional brokers`() {
        val home = createTempDirectory("portable-no-broker-home-")
        val stateRoot = createTempDirectory("portable-no-broker-state-")
        val directDI = createDirectDI(home, stateRoot, safeModeEnabled = true, bindBrokers = false)

        assertNotNull(directDI.instance<ToolDeleteFile>())
        assertNotNull(directDI.instance<ToolMoveFile>())
        assertNotNull(directDI.instance<ToolModifyFile>())
    }

    @Test
    fun `ReadFile is exposed through portable DI catalog`() {
        val home = createTempDirectory("portable-read-home-")
        val stateRoot = createTempDirectory("portable-read-state-")
        val directDI = createDirectDI(home, stateRoot, safeModeEnabled = true, bindBrokers = false)
        val catalog = directDI.instance<AgentToolCatalog>()

        assertNotNull(directDI.instance<ToolReadFile>())
        assertTrue("ReadFile" in catalog.toolsByCategory.getValue(ToolCategory.FILES))
    }

    private fun createDirectDI(
        home: Path,
        stateRoot: Path,
        safeModeEnabled: Boolean,
        bindBrokers: Boolean,
    ) = DI.direct {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.forbiddenFolders } returns emptyList()
        every { settingsProvider.safeModeEnabled } returns safeModeEnabled

        bindSingleton<SettingsProvider> { settingsProvider }
        bindSingleton<LLMChatAPI> { mockk(relaxed = true) }
        bindSingleton<VisionGateway> { VisionGateway { "ok" } }
        bindSingleton<ImageGenerationGateway> {
            ImageGenerationGateway {
                GeneratedImage(
                    bytes = ByteArray(0),
                    mimeType = "image/png",
                    provider = "test",
                )
            }
        }
        bindSingleton<RuntimeSandboxFactory> {
            RuntimeSandboxFactory { scope ->
                LocalRuntimeSandbox(
                    scope = scope,
                    settingsProvider = settingsProvider,
                    homePath = home,
                    stateRoot = stateRoot,
                )
            }
        }
        if (bindBrokers) {
            bindSingleton<ToolPermissionBroker> { ImmediateToolPermissionBroker(instance<SettingsProvider>()) }
            bindSingleton { DeferredToolModifyPermissionBroker(instance<SettingsProvider>(), instance()) }
        }
        import(portableRuntimeToolsDiModule())
    }

    private fun createTempDirectory(prefix: String): Path =
        Files.createTempDirectory(prefix).also(createdPaths::add)
}
