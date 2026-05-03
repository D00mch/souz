package ru.souz.runtime.sandbox.docker

internal class DockerContainerHandle(
    private val dockerCli: DockerCli,
    private val layout: DockerSandboxLayout,
    private val containerName: String,
    private val imageName: String,
    private val userSpec: String?,
) {
    fun ensureStarted() {
        when (val existingContainer = inspectContainerState()) {
            null -> createAndStart()
            else -> {
                requireMatchingHostRoot(existingContainer)
                if (existingContainer.running) {
                    return
                }
                val start = dockerCli.run(listOf("start", containerName))
                check(start.exitCode == 0) {
                    "Failed to start Docker sandbox container '$containerName'. stdout:\n${start.stdout}\nstderr:\n${start.stderr}"
                }
            }
        }
    }

    suspend fun exec(
        command: List<String>,
        workingDirectory: String? = null,
        environment: Map<String, String> = emptyMap(),
        stdin: String? = null,
        timeoutMillis: Long? = null,
    ): DockerCliResult {
        val arguments = buildList {
            add("exec")
            add("-i")
            workingDirectory?.let {
                add("--workdir")
                add(it)
            }
            linkedMapOf("HOME" to layout.runtimePaths.homePath)
                .apply { putAll(environment) }
                .forEach { (key, value) ->
                    add("--env")
                    add("$key=$value")
                }
            add(containerName)
            addAll(command)
        }
        val localTimeout = timeoutMillis?.plus(DOCKER_EXEC_TIMEOUT_GRACE_MILLIS)
        return dockerCli.runAsync(arguments, stdin = stdin, timeoutMillis = localTimeout)
    }

    fun verifyRuntimeExecutables() {
        val verification = dockerCli.run(
            arguments = listOf(
                "exec",
                "-i",
                "--env",
                "HOME=${layout.runtimePaths.homePath}",
                containerName,
                "bash",
                "-lc",
                "command -v bash >/dev/null && command -v python3 >/dev/null && command -v node >/dev/null",
            ),
        )
        check(verification.exitCode == 0) {
            "Docker sandbox image '$imageName' is missing required runtimes (bash, python3, node). stdout:\n${verification.stdout}\nstderr:\n${verification.stderr}"
        }
    }

    fun removeIfExists() {
        dockerCli.run(listOf("rm", "-f", containerName))
    }

    private fun createAndStart() {
        val result = dockerCli.run(
            buildList {
                add("run")
                add("-d")
                add("--name")
                add(containerName)
                add("--hostname")
                add(containerName)
                add("--mount")
                add("type=bind,src=${layout.hostRoot},dst=${layout.containerRoot}")
                add("--workdir")
                add(layout.runtimePaths.homePath)
                add("--env")
                add("HOME=${layout.runtimePaths.homePath}")
                userSpec?.let {
                    add("--user")
                    add(it)
                }
                add(imageName)
                add("bash")
                add("-lc")
                add("while true; do sleep 3600; done")
            },
        )
        check(result.exitCode == 0) {
            "Failed to create Docker sandbox container '$containerName'. stdout:\n${result.stdout}\nstderr:\n${result.stderr}"
        }
    }

    private fun inspectContainerState(): DockerContainerState? {
        val inspection = dockerCli.run(
            listOf(
                "container",
                "inspect",
                "--format",
                "{{.State.Running}}\t{{range .Mounts}}{{if eq .Destination \"/souz\"}}{{.Source}}{{end}}{{end}}",
                containerName,
            ),
        )
        if (inspection.exitCode != 0) {
            return null
        }
        val output = inspection.stdout.trimEnd()
        val running = output.substringBefore('\t').trim().equals("true", ignoreCase = true)
        val mountedHostRoot = output.substringAfter('\t', missingDelimiterValue = "").trim()
            .takeIf(String::isNotEmpty)
            ?.let { normalizeHostPath(java.nio.file.Path.of(it)) }
        return DockerContainerState(
            running = running,
            mountedHostRoot = mountedHostRoot,
        )
    }

    private fun requireMatchingHostRoot(existingContainer: DockerContainerState) {
        val mountedHostRoot = existingContainer.mountedHostRoot
            ?: throw IllegalStateException(
                "Docker sandbox container '$containerName' exists without the expected ${layout.containerRoot} bind mount."
            )
        check(mountedHostRoot == layout.hostRoot) {
            "Docker sandbox container '$containerName' already exists with a different host root: expected ${layout.hostRoot}, actual $mountedHostRoot"
        }
    }

    private companion object {
        const val DOCKER_EXEC_TIMEOUT_GRACE_MILLIS = 2_000L
    }
}

internal data class DockerContainerState(
    val running: Boolean,
    val mountedHostRoot: java.nio.file.Path?,
)
