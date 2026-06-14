package ru.souz.runtime.sandbox

import java.io.InputStream
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import ru.souz.runtime.files.FilesToolUtil

class SandboxUserFacingPathNormalizerTest {
    private val runtimePaths = SandboxRuntimePaths(
        homePath = "/home",
        workspaceRootPath = "/workspace",
        stateRootPath = "/state",
        sessionsDirPath = "/state/sessions",
        vectorIndexDirPath = "/state/vector-index",
        logsDirPath = "/state/logs",
        modelsDirPath = "/state/models",
        nativeLibsDirPath = "/state/native",
        skillsDirPath = "/state/skills",
        skillValidationsDirPath = "/state/skill-validations",
    )

    @Test
    fun `normalizes home aliases under runtime home`() {
        assertEquals("/home", normalize("~"))
        assertEquals("/home/Documents", normalize("~/Documents"))
        assertEquals("/home/foo", normalize("\$HOME/foo"))
        assertEquals("/home", normalize("home"))
    }

    @Test
    fun `normalizes relative paths under runtime home`() {
        assertEquals("/home/relative.txt", normalize("relative.txt"))
        assertEquals("/home/relative.txt", normalize("./relative.txt"))
    }

    @Test
    fun `normalizes dot segments without escaping above root`() {
        assertEquals("/home/notes.txt", normalize("~/Documents/../notes.txt"))
        assertEquals("/etc/passwd", normalize("/home/../../etc/passwd"))
        assertEquals("/", normalize("/../../.."))
    }

    @Test
    fun `cleans quoted file uri and backslash inputs`() {
        assertEquals("/home/quoted.txt", normalize("`~/quoted.txt`"))
        assertEquals("/home/Documents", normalize("\"file://~/Documents\""))
        assertEquals("/home/single.txt", normalize("'~/single.txt'"))
        assertEquals("/home/Documents/report.txt", normalize("~\\Documents\\report.txt"))
    }

    @Test
    fun `keeps absolute paths absolute`() {
        assertEquals("/workspace/file.txt", normalize("/workspace/file.txt"))
        assertEquals("/tmp/file.txt", normalize("file:///tmp/file.txt"))
    }

    @Test
    fun `documents directory defaults to uppercase Documents under sandbox home`() {
        val filesToolUtil = FilesToolUtil(
            RuntimeSandboxStub(
                AndroidStylePathSandboxFileSystem(
                    existingDirectories = setOf("/", "/home"),
                )
            )
        )

        assertEquals("/home/Documents", filesToolUtil.resolveDocumentsDirectory().path)
    }

    @Test
    fun `documents directory falls back to existing lowercase documents under sandbox home`() {
        val filesToolUtil = FilesToolUtil(
            RuntimeSandboxStub(
                AndroidStylePathSandboxFileSystem(
                    existingDirectories = setOf("/", "/home", "/home/documents"),
                )
            )
        )

        assertEquals("/home/documents", filesToolUtil.resolveDocumentsDirectory().path)
    }

    private fun normalize(rawPath: String): String =
        SandboxUserFacingPathNormalizer.normalize(rawPath, runtimePaths)

    private inner class RuntimeSandboxStub(
        override val fileSystem: SandboxFileSystem,
    ) : RuntimeSandbox {
        override val mode: SandboxMode = SandboxMode.ANDROID
        override val scope: SandboxScope = SandboxScope(userId = "android-user")
        override val runtimePaths: SandboxRuntimePaths = fileSystem.runtimePaths
        override val commandExecutor: SandboxCommandExecutor = object : SandboxCommandExecutor {
            override suspend fun execute(request: SandboxCommandRequest): SandboxCommandResult =
                SandboxCommandResult(exitCode = 127, stdout = "", stderr = "Unsupported")
        }
    }

    private inner class AndroidStylePathSandboxFileSystem(
        private val existingDirectories: Set<String>,
    ) : SandboxFileSystem {
        override val runtimePaths: SandboxRuntimePaths = this@SandboxUserFacingPathNormalizerTest.runtimePaths

        override fun resolvePath(rawPath: String): SandboxPathInfo {
            val path = SandboxUserFacingPathNormalizer.normalize(rawPath, runtimePaths)
            val isDirectory = path == "/" || path in existingDirectories
            return SandboxPathInfo(
                rawPath = rawPath,
                path = path,
                name = if (path == "/") "/" else path.substringAfterLast('/'),
                parentPath = path.parentPath(),
                exists = isDirectory,
                isDirectory = isDirectory,
                isRegularFile = false,
                isSymbolicLink = false,
            )
        }

        override fun resolveExistingFile(rawPath: String): SandboxPathInfo =
            unsupported()

        override fun resolveExistingDirectory(rawPath: String): SandboxPathInfo =
            resolvePath(rawPath).also { require(it.exists && it.isDirectory) }

        override fun isPathSafe(path: SandboxPathInfo): Boolean = true
        override fun forbiddenPaths(): List<String> = emptyList()
        override fun readBytes(path: SandboxPathInfo): ByteArray = unsupported()
        override fun readText(path: SandboxPathInfo): String = unsupported()
        override fun openInputStream(path: SandboxPathInfo): InputStream = unsupported()
        override fun localPathOrNull(path: SandboxPathInfo): Path? = null
        override fun writeBytes(path: SandboxPathInfo, content: ByteArray) = unsupported<Unit>()
        override fun writeText(path: SandboxPathInfo, content: String) = unsupported<Unit>()
        override fun writeTextAtomically(path: SandboxPathInfo, content: String, logger: org.slf4j.Logger) =
            unsupported<Unit>()

        override fun createDirectory(path: SandboxPathInfo) = unsupported<Unit>()
        override fun delete(path: SandboxPathInfo, recursively: Boolean) = unsupported<Unit>()
        override fun listDescendants(root: SandboxPathInfo, maxDepth: Int, includeHidden: Boolean): List<SandboxPathInfo> =
            unsupported()

        override fun move(
            source: SandboxPathInfo,
            destination: SandboxPathInfo,
            replaceExisting: Boolean,
            createParents: Boolean,
            logger: org.slf4j.Logger?,
        ) = unsupported<Unit>()

        override fun moveToTrash(path: SandboxPathInfo, logger: org.slf4j.Logger?): SandboxPathInfo =
            unsupported()

        private fun String.parentPath(): String? {
            if (this == "/") return null
            val index = lastIndexOf('/')
            return if (index <= 0) "/" else substring(0, index)
        }

        private fun <T> unsupported(): T =
            throw UnsupportedOperationException("Not needed by this test")
    }
}
