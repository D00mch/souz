package ru.souz.backend.agent.runtime.conversation

import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.app.BackendLlmLimits
import ru.souz.backend.llm.quota.ExecutionQuotaManager
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.giga.toGiga
import ru.souz.llms.runtime.GeneratedImage
import ru.souz.llms.runtime.ImageGenerationGateway
import ru.souz.llms.runtime.ImageSource
import ru.souz.llms.runtime.VisionGateway
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxPathInfo
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.tool.ToolCategory
import ru.souz.tool.files.ToolGenerateImage
import ru.souz.tool.files.ToolViewImage
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BackendSandboxImageAdmissionTest {
    @Test
    fun `sandbox tools share image capacity before reads through provider waits and cancellation cleanup`() = runTest {
        val png = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()
        val source = SandboxPathInfo(
            rawPath = "/sandbox/source.png", path = "/sandbox/source.png", name = "source.png", parentPath = "/sandbox",
            exists = true, isDirectory = false, isRegularFile = true, isSymbolicLink = false,
        )
        for (name in listOf(ToolGenerateImage.NAME, ToolViewImage.NAME)) {
            val quotas = ExecutionQuotaManager(BackendLlmLimits(globalProviderConcurrency = 1))
            var reads = 0
            var writes = 0
            var corrupt = false
            val seenMeta = mutableListOf<ToolInvocationMeta>()
            val fileSystem = mockk<SandboxFileSystem> {
                every { resolveExistingFile(source.path) } returns source
                every { openInputStream(source) } answers {
                    reads++
                    (if (corrupt) byteArrayOf(0) else png).inputStream()
                }
                every { resolvePath("/sandbox/output.png") } returns source.copy(path = "/sandbox/output.png")
                every { writeBytes(any(), any()) } answers { writes++ }
            }
            val sandbox = mockk<RuntimeSandbox> { every { this@mockk.fileSystem } returns fileSystem }
            val files = FilesToolUtil(mockk<ToolInvocationRuntimeSandboxResolver> {
                every { resolve(any()) } answers { seenMeta += firstArg<ToolInvocationMeta>(); sandbox }
            })
            val prepared = CompletableDeferred<Unit>()
            val cleanup = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            var hold = true
            var failProvider = false
            suspend fun provider(images: List<ImageSource>) {
                assertEquals(5, images.size)
                images.forEach { assertContentEquals(png, it.bytes) }
                if (hold) {
                    prepared.complete(Unit)
                    try {
                        quotas.withProviderPermit(LlmProvider.OPENAI) { awaitCancellation() }
                    } finally {
                        withContext(NonCancellable) { cleanup.complete(Unit); releaseCleanup.await() }
                    }
                }
                quotas.withProviderPermit(LlmProvider.OPENAI) { check(!failProvider) { "provider failed" } }
            }
            val tools = AgentToolCatalog(mapOf(
                ToolCategory.IMAGE to listOf(ToolViewImage(files, VisionGateway {
                    provider(it.images)
                    "Observed"
                }).toGiga()),
                ToolCategory.IMAGE_GENERATION to listOf(ToolGenerateImage(files, ImageGenerationGateway {
                    provider(it.images)
                    GeneratedImage(ImageSource.read(png), "OPENAI")
                }).toGiga()),
            ))
            val catalog = backendExecutionToolCatalog(
                executionQuotas = quotas, compiledToolCatalog = AgentToolCatalog(), executionLlmToolCatalog = tools,
                enabledCompiledToolNames = null, clientToolCatalog = AgentToolCatalog(), includeFewShotExamples = false,
            )
            suspend fun invoke(toolName: String, meta: ToolInvocationMeta? = null) =
                catalog.toolsByCategory.values.first { toolName in it }.getValue(toolName).let { tool ->
                    val arguments = mapOf(
                        "imagePaths" to List(5) { source.path }, "question" to "Inspect", "prompt" to "Edit",
                        "outputPath" to "/sandbox/output.png",
                    ).filterKeys { if (toolName == ToolViewImage.NAME) it == "imagePaths" || it == "question" else it != "question" }
                    val call = LLMResponse.FunctionCall(toolName, arguments)
                    if (meta == null) tool.invoke(call) else tool.invoke(call, meta)
                }
            val releaseChat = CompletableDeferred<Unit>()
            val chatEntered = CompletableDeferred<Unit>()
            val chat = async { quotas.withImageProjectionPermit { chatEntered.complete(Unit); releaseChat.await() } }
            val releaseProvider = CompletableDeferred<Unit>()
            val providerEntered = CompletableDeferred<Unit>()
            val occupiedProvider = async {
                quotas.withProviderPermit(LlmProvider.OPENAI) { providerEntered.complete(Unit); releaseProvider.await() }
            }
            chatEntered.await()
            providerEntered.await()
            val meta = ToolInvocationMeta("owner", "chat", "execution")
            val running = async { invoke(name, meta) }
            prepared.await()
            val other = if (name == ToolViewImage.NAME) ToolGenerateImage.NAME else ToolViewImage.NAME
            val waiting = async { invoke(other) }
            try {
                runCurrent()
                assertEquals(5, reads, "Queued $other must retain paths only")
                assertFalse(waiting.isCompleted)
                assertTrue(seenMeta.all { it == meta }, "Keep owner-scoped sandbox routing")
                running.cancel()
                cleanup.await()
                runCurrent()
                assertEquals(5, reads, "Cancellation cleanup still owns capacity")
                assertFalse(waiting.isCompleted)
                waiting.cancelAndJoin()
                assertEquals(5, reads, "Queued cancellation must not read a source")
            } finally {
                releaseCleanup.complete(Unit)
                running.cancelAndJoin()
                waiting.cancelAndJoin()
                releaseProvider.complete(Unit)
                occupiedProvider.await()
            }
            hold = false
            try {
                // One chat still owns capacity: a leaked sandbox permit would block every following invocation.
                assertTrue(invoke(other).content.contains(if (other == ToolViewImage.NAME) "Observed" else "outputPath"))
                assertEquals(10, reads)
                assertEquals(if (other == ToolGenerateImage.NAME) 1 else 0, writes)
                assertEquals(ToolInvocationMeta.localDefault(), seenMeta.last())
                corrupt = true
                assertTrue(invoke(name, meta).content.contains("image_format_unsupported"))
                corrupt = false
                failProvider = true
                assertTrue(invoke(name, meta).content.contains("provider failed"))
                failProvider = false
                assertTrue(invoke(name, meta).content.contains(if (name == ToolViewImage.NAME) "Observed" else "outputPath"))
            } finally {
                releaseChat.complete(Unit)
                chat.await()
            }
            withTimeout(1_000) { quotas.withImageProjectionPermit { quotas.withImageProjectionPermit { } } }
        }
    }
}
