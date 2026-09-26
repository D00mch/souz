package ru.souz.backend.hooks

import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import ru.souz.backend.http.BackendV1Exception
import ru.souz.db.SettingsProvider
import ru.souz.runtime.sandbox.DefaultRuntimeSandboxFactory
import ru.souz.runtime.sandbox.RuntimeSandboxModeResolver

class HookVerifierTest {
    @TempDir lateinit var workspace: Path
    private val owner = "owner"
    private val script get() = workspace.resolve("hooks/check/scripts/verify.py")
    private val manifest get() = workspace.resolve("hooks/check/hook.yaml")
    private val config = HookConfig(setOf(owner), concurrentVerifiers = 1)
    private val sandboxes by lazy {
        val settings = mockk<SettingsProvider> { every { forbiddenFolders } returns emptyList() }
        DefaultRuntimeSandboxFactory(settings, RuntimeSandboxModeResolver { "local" }, workspace, workspace.resolve("state"), workspace)
    }

    @Test
    fun `malformed output timeout and unsafe manifests fail closed and clean temporary files`() = runBlocking {
        val definitions = HookDefinitions(sandboxes, config)
        val verifier = HookVerifier(config.copy(verifierTimeoutMillis = 400), sandboxes)
        for (source in listOf(
            "print('{}')",
            "print('{\"accept\":true,\"eventId\":\"ok\"} {}')",
            "print('{\"accept\":true,\"eventId\":\"\"}')",
            "print('{\"accept\":false,\"accept\":true}')",
            "print('x'*80000)",
            "import sys; sys.stderr.write('private diagnostic'); sys.exit(2)",
            "import time; time.sleep(30)",
        )) {
            writeHook(source)
            val error = assertFailsWith<BackendV1Exception> { verifier.verify(definitions.load(owner).single(), request()) }
            assertEquals(503, error.status.value, source)
            assertFalse(error.message.contains("private diagnostic"))
            assertTrue(temporaryDirectories().isEmpty())
        }
        val valid = Files.readString(manifest)
        for (invalid in listOf(
            valid + "\nauth: {type: bearer, tokenSha256: '${"a".repeat(64)}'}",
            valid.replace("PYTHON", "BASH"),
            valid.replace("scripts/verify.py", "../outside.py"),
            valid.replace("parameters:", "unknownField:"),
        )) {
            Files.writeString(manifest, invalid)
            assertTrue(definitions.loadSafely(owner).isEmpty())
        }
        Files.writeString(manifest, valid)
        Files.delete(script)
        Files.createSymbolicLink(script, Files.writeString(workspace.resolve("outside.py"), "print('{}')"))
        assertTrue(definitions.loadSafely(owner).isEmpty())
    }

    @Test
    fun `cancellation removes temporary files and releases verifier capacity`() = runBlocking {
        writeHook("import pathlib,time; pathlib.Path('ready').touch(); time.sleep(30)")
        val definitions = HookDefinitions(sandboxes, config)
        val loaded = definitions.load(owner).single()
        val verifier = HookVerifier(config, sandboxes)
        withTimeout(5_000) {
            val running = async { verifier.verify(loaded, request(ByteArray(HookDefinitions.MAX_BODY_BYTES))) }
            while (temporaryDirectories().none { Files.exists(it.resolve("ready")) }) delay(20)
            assertEquals(429, assertFailsWith<BackendV1Exception> { verifier.verify(loaded, request()) }.status.value)
            running.cancelAndJoin()
            assertTrue(temporaryDirectories().isEmpty())
            writeHook("print('{\"accept\":true,\"eventId\":\"after-cleanup\"}')")
            assertEquals("{}", verifier.verify(definitions.load(owner).single(), request()).payload)
        }
    }

    private fun writeHook(source: String) {
        Files.createDirectories(script.parent)
        Files.writeString(script, source)
        Files.writeString(manifest, """
            version: 1
            hookId: check
            ownerUserId: $owner
            verify:
              runtime: PYTHON
              script: scripts/verify.py
              parameters:
                workspace: "$workspace"
            prompt: Process this verified event
        """.trimIndent())
    }

    private fun request(body: ByteArray = "{}".toByteArray()) =
        HookRequest("POST", "/hooks/check", emptyMap(), body)

    private fun temporaryDirectories(): List<Path> = Files.list(workspace).use { paths ->
        paths.filter { it.fileName.toString().startsWith(".hook-verifier-") }.toList()
    }

}
