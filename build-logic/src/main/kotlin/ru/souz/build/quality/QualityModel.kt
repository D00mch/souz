package ru.souz.build.quality

import java.io.File
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.time.TimeSource

internal enum class QualityEnforcement(val wireName: String) {
    BLOCKING("blocking"),
    ADVISORY("advisory"),
}

internal enum class QualityStatus(val wireName: String) {
    PASS("pass"),
    FAIL("fail"),
    WARNING("warning"),
    ERROR("error"),
    SKIPPED("skipped"),
    NOT_AUTHORITATIVE("not_authoritative"),
}

internal data class QualityCheckDefinition(
    val id: String,
    val implementationVersion: Int,
    val description: String,
    val policy: String,
    val enforcement: QualityEnforcement = QualityEnforcement.BLOCKING,
)

internal data class QualityDiagnostic(
    val path: String?,
    val line: Int?,
    val message: String,
)

internal data class GitIdentity(
    val testedCommitSha: String?,
    val prBaseSha: String?,
    val prHeadSha: String?,
    val dirtyWorktree: Boolean?,
)

internal data class QualityCheckResult(
    val definition: QualityCheckDefinition,
    val status: QualityStatus,
    val durationMs: Long,
    val diagnostics: List<QualityDiagnostic>,
    val gitIdentity: GitIdentity,
)

internal object SouzQualityChecks {
    val gitMetadata = QualityCheckDefinition(
        id = "git-metadata",
        implementationVersion = 1,
        description = "The gate can identify the tested Git checkout and worktree state.",
        policy = "AGENTS.md",
    )

    val repositoryContracts = QualityCheckDefinition(
        id = "repository-contracts",
        implementationVersion = 1,
        description = "Repository module policy and local documentation links agree with the Gradle model.",
        policy = "AGENTS.md",
    )

    val moduleBoundaries = QualityCheckDefinition(
        id = "module-boundaries",
        implementationVersion = 1,
        description = "Direct production project dependencies stay within the reviewed module allowlist.",
        policy = "AGENTS.md",
    )

    val cancellationPropagation = QualityCheckDefinition(
        id = "cancellation-propagation",
        implementationVersion = 2,
        description = "Suspend paths propagate CancellationException immediately.",
        policy = "AGENTS.md",
        enforcement = QualityEnforcement.ADVISORY,
    )

    val coroutineThreadLocal = QualityCheckDefinition(
        id = "coroutine-thread-local",
        implementationVersion = 2,
        description = "JVM ThreadLocal state is reviewed explicitly before use.",
        policy = "AGENTS.md",
        enforcement = QualityEnforcement.ADVISORY,
    )

    val coroutineMonitorUse = QualityCheckDefinition(
        id = "coroutine-monitor-use",
        implementationVersion = 2,
        description = "JVM monitor coordination directly inside coroutine execution is reviewed.",
        policy = "AGENTS.md",
        enforcement = QualityEnforcement.ADVISORY,
    )

    val fast = listOf(
        gitMetadata,
        repositoryContracts,
        moduleBoundaries,
        cancellationPropagation,
        coroutineThreadLocal,
        coroutineMonitorUse,
    )
}

internal object QualityCheckRunner {
    fun run(
        definition: QualityCheckDefinition,
        repositoryDirectory: File,
        gitIdentity: GitIdentity,
        check: () -> List<QualityDiagnostic>,
    ): QualityCheckResult {
        val started = TimeSource.Monotonic.markNow()
        val diagnostics = mutableListOf<QualityDiagnostic>()
        var status = QualityStatus.PASS

        try {
            diagnostics += check()
            if (diagnostics.isNotEmpty()) {
                status = when (definition.enforcement) {
                    QualityEnforcement.BLOCKING -> QualityStatus.FAIL
                    QualityEnforcement.ADVISORY -> QualityStatus.WARNING
                }
            }
        } catch (exception: Exception) {
            status = QualityStatus.ERROR
            diagnostics += internalErrorDiagnostic(definition.id, exception, repositoryDirectory)
        }

        return QualityCheckResult(
            definition = definition,
            status = status,
            durationMs = started.elapsedNow().inWholeMilliseconds.coerceAtLeast(0),
            diagnostics = diagnostics.sortedWith(
                compareBy<QualityDiagnostic>({ it.path.orEmpty() }, { it.line ?: Int.MAX_VALUE }, { it.message })
            ),
            gitIdentity = gitIdentity,
        )
    }

    private fun internalErrorDiagnostic(
        component: String,
        exception: Exception,
        repositoryDirectory: File,
    ): QualityDiagnostic {
        val home = System.getProperty("user.home").orEmpty()
        val rawMessage = exception.message.orEmpty().ifBlank { "No error message was provided." }
        val safeMessage = rawMessage
            .replace(repositoryDirectory.absolutePath, ".")
            .let { message -> if (home.isBlank()) message else message.replace(home, "<home>") }
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .take(500)

        return QualityDiagnostic(
            path = null,
            line = null,
            message = "Internal $component error (${exception.javaClass.simpleName}): $safeMessage",
        )
    }
}

private const val RECORD_SEPARATOR = '\u001f'

internal data class ProjectDescriptor(
    val path: String,
    val directory: String,
    val buildFile: String,
) {
    fun encode(): String = listOf(path, directory, buildFile).joinToString(RECORD_SEPARATOR.toString())

    companion object {
        fun decode(value: String): ProjectDescriptor {
            val fields = value.split(RECORD_SEPARATOR)
            require(fields.size == 3) { "Invalid project descriptor input." }
            return ProjectDescriptor(fields[0], fields[1], fields[2])
        }
    }
}

internal data class DependencyEdge(
    val source: String,
    val configuration: String,
    val sourceSet: String,
    val target: String,
    val buildFile: String,
) {
    fun encode(): String = listOf(source, configuration, sourceSet, target, buildFile)
        .joinToString(RECORD_SEPARATOR.toString())

    companion object {
        fun decode(value: String): DependencyEdge {
            val fields = value.split(RECORD_SEPARATOR)
            require(fields.size == 5) { "Invalid dependency edge input." }
            return DependencyEdge(fields[0], fields[1], fields[2], fields[3], fields[4])
        }
    }
}

internal const val UNCLASSIFIED_SOURCE_SET = "<unclassified>"

internal fun dependencySourceSet(configurationName: String): String? {
    val mainConfigurations = mapOf(
        "api" to "main",
        "implementation" to "main",
        "compileOnly" to "main",
        "compileOnlyApi" to "main",
        "runtimeOnly" to "main",
        "annotationProcessor" to "main",
    )
    mainConfigurations[configurationName]?.let { return it }

    val suffix = listOf("AnnotationProcessor", "CompileOnlyApi", "Implementation", "CompileOnly", "RuntimeOnly", "Api")
        .firstOrNull(configurationName::endsWith)
        ?: return UNCLASSIFIED_SOURCE_SET
    val sourceSet = configurationName.removeSuffix(suffix)
    return when {
        sourceSet == "test" || sourceSet == "testFixtures" ||
            sourceSet.endsWith("Test") || sourceSet.endsWith("TestFixtures") -> null
        sourceSet.endsWith("Main") -> sourceSet
        else -> UNCLASSIFIED_SOURCE_SET
    }
}

internal fun File.relativeInvariantPath(root: File): String =
    root.toPath().toAbsolutePath().normalize()
        .relativize(toPath().toAbsolutePath().normalize())
        .invariantSeparatorsPathString
