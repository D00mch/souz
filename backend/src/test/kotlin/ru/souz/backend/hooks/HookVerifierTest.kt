package ru.souz.backend.hooks

import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
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
import ru.souz.runtime.sandbox.RuntimeSandboxFactory
import ru.souz.runtime.sandbox.RuntimeSandboxModeResolver

class HookVerifierTest {
    @TempDir lateinit var workspace: Path
    private val owner = "owner"
    private val secret = "test-signing-secret"
    private val script get() = workspace.resolve("hooks/check/scripts/verify.py")
    private val manifest get() = workspace.resolve("hooks/check/hook.yaml")
    private val config = HookConfig(setOf(owner), concurrentVerifiers = 1)
    private val sandboxes by lazy {
        val settings = mockk<SettingsProvider> { every { forbiddenFolders } returns emptyList() }
        val factory = DefaultRuntimeSandboxFactory(settings, RuntimeSandboxModeResolver { "local" }, workspace, workspace.resolve("state"), workspace)
        RuntimeSandboxFactory { scope ->
            assertEquals(owner, scope.userId)
            factory.create(scope)
        }
    }

    @Test
    fun `signed raw event runs in owner local sandbox using captured scripts`() = runBlocking {
        writeHook(SIGNED_VERIFIER)
        val definitions = HookDefinitions(sandboxes, config)
        val loaded = definitions.load(owner).single()
        val verifier = HookVerifier(config, sandboxes)
        val raw = byteArrayOf(0, -1, 10, 32, 65)
        assertEquals(401, assertFailsWith<BackendV1Exception> {
            verifier.verify(loaded, request(raw, listOf("bad")))
        }.status.value)
        val signature = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        }.doFinal(raw).joinToString("") { "%02x".format(it) }
        assertEquals(401, assertFailsWith<BackendV1Exception> {
            verifier.verify(loaded, request(raw, listOf(signature, signature)))
        }.status.value)
        val event = verifier.verify(loaded, request(raw, listOf(signature)))
        assertEquals("signed-event", event.eventId)
        assertTrue(event.payload.contains(sha256(raw)))
        assertFalse(event.payload.contains(secret))

        Files.writeString(script, "print('{\"accept\":false}')")
        assertEquals(event, verifier.verify(loaded, request(raw, listOf(signature))))
        val reloaded = definitions.load(owner).single()
        assertTrue(loaded.revision != reloaded.revision)
        assertEquals(401, assertFailsWith<BackendV1Exception> { verifier.verify(reloaded, request()) }.status.value)
        assertTrue(temporaryDirectories().isEmpty())
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
    fun `concurrent checks are bounded and cancellation cleans child processes and files`() = runBlocking {
        writeHook("""
            import pathlib,subprocess,time
            child=subprocess.Popen(['python3','-c','import time; time.sleep(30)'])
            pathlib.Path('child.pid').write_text(str(child.pid))
            time.sleep(30)
        """.trimIndent())
        val loaded = HookDefinitions(sandboxes, config).load(owner).single()
        val verifier = HookVerifier(config.copy(verifierTimeoutMillis = 10_000), sandboxes)
        val running = async { verifier.verify(loaded, request()) }
        val pid = withTimeout(5_000) {
            var found: Long? = null
            while (found == null) {
                found = temporaryDirectories().firstOrNull { Files.exists(it.resolve("child.pid")) }
                    ?.resolve("child.pid")?.let { Files.readString(it).toLongOrNull() }
                if (found == null) delay(20)
            }
            found
        }
        assertEquals(429, assertFailsWith<BackendV1Exception> { verifier.verify(loaded, request()) }.status.value)
        running.cancelAndJoin()
        withTimeout(3_000) { while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(20) }
        assertTrue(temporaryDirectories().isEmpty())
        writeHook("print('{\"accept\":true,\"eventId\":\"after-cancel\"}')")
        assertEquals("{}", verifier.verify(HookDefinitions(sandboxes, config).load(owner).single(), request()).payload)
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
                secret: $secret
                workspace: "$workspace"
            prompt: Process this verified event
        """.trimIndent())
    }

    private fun request(body: ByteArray = "{}".toByteArray(), signatures: List<String> = emptyList()) =
        HookRequest("POST", "/hooks/check", mapOf("x-signature" to signatures), body)

    private fun temporaryDirectories(): List<Path> = Files.list(workspace).use { paths ->
        paths.filter { it.fileName.toString().startsWith(".hook-verifier-") }.toList()
    }

    private companion object {
        val SIGNED_VERIFIER = """
            import base64,hashlib,hmac,json,pathlib,sys
            request=json.load(sys.stdin)
            assert request['version']==1 and request['method']=='POST'
            assert request['path']=='/hooks/check' and request['checkedAt'].endswith('Z')
            assert pathlib.Path.cwd().parent==pathlib.Path(request['parameters']['workspace']).resolve()
            body=base64.b64decode(request['bodyBase64'],validate=True)
            expected=hmac.new(request['parameters']['secret'].encode(),body,hashlib.sha256).hexdigest()
            values=request['headers'].get('x-signature',[])
            accepted=len(values)==1 and hmac.compare_digest(values[0],expected)
            print(json.dumps({'accept':True,'eventId':'signed-event','payload':{'normalized':hashlib.sha256(body).hexdigest()}}) if accepted else '{"accept":false}')
        """.trimIndent()
    }
}
