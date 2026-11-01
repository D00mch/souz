package ru.souz.backend.e2e

import io.ktor.client.request.patch
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.plugins.PluginOperator
import ru.souz.runtime.sandbox.RuntimeSandboxFactory
import ru.souz.runtime.sandbox.DefaultRuntimeSandboxFactory
import ru.souz.runtime.sandbox.RuntimeSandboxModeResolver
import ru.souz.runtime.sandbox.docker.DockerRuntimeSandbox

@EnabledIfEnvironmentVariable(named = "SOUZ_TEST_DOCKER", matches = "1")
class BackendPluginSandboxE2eTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `Docker file Skills and restricted composites coexist with hot plugin updates`() {
        val sandboxes = CopyOnWriteArrayList<DockerRuntimeSandbox>()
        try {
            backendE2eTest("e2e_plugin_sandbox", startBackgroundServices = true, sandboxFactory = { settings ->
                val factory = DefaultRuntimeSandboxFactory(settings, RuntimeSandboxModeResolver { "docker" }, dockerHostRoot = directory)
                RuntimeSandboxFactory { scope ->
                    (factory.create(scope) as DockerRuntimeSandbox).also { sandboxes += it }
                }
            }) {
                val owner = UUID.randomUUID().toString()
                val chat = createPublicChat(owner)
                backend.skillRegistry.saveSkillBundle(owner, SkillBundle.fromFiles(SkillId("mixed-skills"), listOf(
                    SkillFile("SKILL.md", """
                        ---
                        name: Mixed Skills
                        description: Format plugin results with a sandboxed Python script.
                        commands:
                          report:
                            inputs: [text]
                            steps:
                              - id: report
                                tool: plugin.reference.report
                                arguments:
                                  text: "${'$'}{inputs.text}"
                              - id: formatted
                                script: scripts/format.py
                                runtime: PYTHON
                                args: ["${'$'}{report}"]
                            returns: "${'$'}{formatted}"
                          state:
                            steps:
                              - id: saved
                                tool: plugin.reference.state
                                arguments:
                                  key: forbidden
                                  json: "true"
                            returns: "${'$'}{saved}"
                        ---
                        Use report for CSV, or run the Python script directly.
                    """.trimIndent().toByteArray()),
                    SkillFile("scripts/format.py", "import sys\nprint('file:' + sys.argv[1])\n".toByteArray()),
                )))
                suspend fun run(prompt: String, arguments: Map<String, Any>) =
                    json.readTree(runSkill(prompt, "mixed-skills", arguments, owner, chat))
                suspend fun plain(prompt: String) {
                    val result = run(prompt, mapOf("runtime" to "PYTHON", "scriptPath" to "scripts/format.py", "args" to listOf("ok")))
                    assertEquals(0, result["exitCode"].asInt(), result.toString())
                    assertEquals("file:ok", result["stdout"].asText().trim())
                }
                suspend fun report(prompt: String, version: String) {
                    val result = run(prompt, mapOf("composite" to "report", "inputs" to mapOf("text" to "a,b\nc,d")))
                    assertEquals(0, result["exitCode"].asInt(), result.toString())
                    assertTrue(result["stdout"].asText().startsWith("file:$version:2:"), result.toString())
                }
                val operator = PluginOperator(backend.pluginRepository)
                operator.execute(listOf("install", System.getProperty("plugin.fixture.kotlin-v1"), "0"))
                eventually("v1") { true.takeIf { backend.pluginRuntime.snapshot.status.singleOrNull()?.appliedDigest != null } }
                assertTrue(runSkill("plugin-only", "plugin.reference.report", mapOf("text" to "a"), owner, chat).startsWith("v1:"))
                assertTrue(sandboxes.isNotEmpty())
                sandboxes.forEach { sandbox ->
                    assertTrue(docker("container", "inspect", sandbox.containerName) != 0, "Plugin-only execution started a container.")
                }
                plain("before-composite")
                report("mixed-v1", "v1")
                operator.execute(listOf("install", System.getProperty("plugin.fixture.kotlin-v2"), "1"))
                val digest = backend.pluginRepository.desired().single().digest
                eventually("v2") { true.takeIf { backend.pluginRuntime.snapshot.status.single().appliedDigest == digest } }
                report("mixed-v2", "v2")
                client.patch(BackendHttpRoutes.SETTINGS) {
                    trusted(owner); jsonBody("""{"enabledTools":["plugin.reference.report"]}""")
                }
                val denied = run("composite-restricted", mapOf("composite" to "state"))
                assertTrue(denied["exitCode"].asInt() != 0, denied.toString())
                assertTrue(denied["stderr"].asText().contains("plugin.reference.state"), denied.toString())
                assertEquals(null, backend.pluginRepository.state("reference", owner).get("forbidden"))
                operator.execute(listOf("disable", "reference", "2"))
                eventually("disabled") { true.takeIf { backend.pluginRuntime.snapshot.enabledIds.isEmpty() } }
                val disabled = run("composite-disabled", mapOf("composite" to "report", "inputs" to mapOf("text" to "a")))
                assertTrue(disabled["exitCode"].asInt() != 0, disabled.toString())
                plain("after-disable")
            }
        } finally {
            sandboxes.asReversed().forEach { it.close(); docker("rm", "-f", it.containerName) }
        }
    }

    private fun docker(vararg arguments: String): Int {
        val process = ProcessBuilder(listOf(System.getenv("SOUZ_DOCKER_CLI") ?: "docker") + arguments)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            check(process.waitFor(10, TimeUnit.SECONDS)) { "Docker test command timed out." }
            return process.exitValue()
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
}
