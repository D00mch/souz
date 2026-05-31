package ru.souz.android.sandbox

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.slf4j.Logger
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.RuntimeSandboxFactory
import ru.souz.runtime.sandbox.SandboxCommandExecutor
import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.SandboxCommandResult
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxMode
import ru.souz.runtime.sandbox.SandboxPathInfo
import ru.souz.runtime.sandbox.SandboxRuntimePaths
import ru.souz.runtime.sandbox.SandboxScope
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Path

class AndroidRuntimeSandboxFactory(
    private val context: Context,
) : RuntimeSandboxFactory {
    override fun create(scope: SandboxScope): RuntimeSandbox =
        AndroidRuntimeSandbox(context.applicationContext, scope)
}

class AndroidRuntimeSandbox(
    context: Context,
    override val scope: SandboxScope = SandboxScope(userId = "android-user"),
) : RuntimeSandbox {
    override val mode: SandboxMode = SandboxMode.ANDROID
    override val fileSystem: SandboxFileSystem = AndroidSQLiteSandboxFileSystem(context)
    override val runtimePaths: SandboxRuntimePaths = fileSystem.runtimePaths
    override val commandExecutor: SandboxCommandExecutor = AndroidUnsupportedCommandExecutor
}

object AndroidUnsupportedCommandExecutor : SandboxCommandExecutor {
    override suspend fun execute(request: SandboxCommandRequest): SandboxCommandResult =
        SandboxCommandResult(
            exitCode = 127,
            stdout = "",
            stderr = "Process execution is not available in the Android runtime sandbox.",
        )
}

class AndroidSQLiteSandboxFileSystem(context: Context) : SQLiteOpenHelper(
    context,
    DB_NAME,
    null,
    DB_VERSION,
), SandboxFileSystem {
    override val runtimePaths: SandboxRuntimePaths = SandboxRuntimePaths(
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

    init {
        writableDatabase
        listOf(
            runtimePaths.homePath,
            runtimePaths.workspaceRootPath,
            runtimePaths.stateRootPath,
            runtimePaths.sessionsDirPath,
            runtimePaths.vectorIndexDirPath,
            runtimePaths.logsDirPath,
            runtimePaths.modelsDirPath,
            runtimePaths.nativeLibsDirPath,
            runtimePaths.skillsDirPath,
            runtimePaths.skillValidationsDirPath,
        ).filterNotNull().forEach { createDirectory(resolvePath(it)) }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sandbox_entries (
                path TEXT PRIMARY KEY NOT NULL,
                is_directory INTEGER NOT NULL,
                content BLOB,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX sandbox_entries_parent_idx ON sandbox_entries(path)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS sandbox_entries")
        onCreate(db)
    }

    override fun resolvePath(rawPath: String): SandboxPathInfo {
        val normalized = normalize(rawPath)
        val row = row(normalized)
        val parent = normalized.parentPath()
        return SandboxPathInfo(
            rawPath = rawPath,
            path = normalized,
            name = normalized.name(),
            parentPath = parent,
            exists = row != null || normalized == ROOT,
            isDirectory = normalized == ROOT || row?.isDirectory == true,
            isRegularFile = row?.isDirectory == false,
            isSymbolicLink = false,
            sizeBytes = row?.content?.size?.toLong(),
        )
    }

    override fun resolveExistingFile(rawPath: String): SandboxPathInfo =
        resolvePath(rawPath).also {
            require(it.exists && it.isRegularFile) { "File does not exist in Android sandbox: ${it.path}" }
        }

    override fun resolveExistingDirectory(rawPath: String): SandboxPathInfo =
        resolvePath(rawPath).also {
            require(it.exists && it.isDirectory) { "Directory does not exist in Android sandbox: ${it.path}" }
        }

    override fun isPathSafe(path: SandboxPathInfo): Boolean =
        normalize(path.path) == path.path

    override fun forbiddenPaths(): List<String> = emptyList()

    override fun readBytes(path: SandboxPathInfo): ByteArray {
        val row = row(path.path) ?: error("File does not exist in Android sandbox: ${path.path}")
        require(!row.isDirectory) { "Path is a directory in Android sandbox: ${path.path}" }
        return row.content ?: ByteArray(0)
    }

    override fun readText(path: SandboxPathInfo): String =
        readBytes(path).toString(Charsets.UTF_8)

    override fun openInputStream(path: SandboxPathInfo): InputStream =
        ByteArrayInputStream(readBytes(path))

    override fun writeBytes(path: SandboxPathInfo, content: ByteArray) {
        require(path.path != ROOT) { "Cannot write root directory as a file." }
        ensureParents(path.path)
        upsert(path.path, isDirectory = false, content = content)
    }

    override fun writeText(path: SandboxPathInfo, content: String) =
        writeBytes(path, content.toByteArray(Charsets.UTF_8))

    override fun writeTextAtomically(path: SandboxPathInfo, content: String, logger: Logger) =
        writeText(path, content)

    override fun createDirectory(path: SandboxPathInfo) {
        if (path.path == ROOT) return
        ensureParents(path.path)
        upsert(path.path, isDirectory = true, content = null)
    }

    override fun delete(path: SandboxPathInfo, recursively: Boolean) {
        require(path.path != ROOT) { "Cannot delete Android sandbox root." }
        val existing = row(path.path) ?: return
        val descendants = descendantRows(path.path)
        if (existing.isDirectory && descendants.isNotEmpty() && !recursively) {
            error("Directory is not empty in Android sandbox: ${path.path}")
        }
        writableDatabase.delete(
            "sandbox_entries",
            "path = ? OR path LIKE ?",
            arrayOf(path.path, descendantLike(path.path)),
        )
    }

    override fun listDescendants(
        root: SandboxPathInfo,
        maxDepth: Int,
        includeHidden: Boolean,
    ): List<SandboxPathInfo> {
        val rootPath = root.path
        val rootDepth = rootPath.depth()
        return descendantRows(rootPath)
            .asSequence()
            .map { resolvePath(it.path) }
            .filter { info -> info.path.depth() - rootDepth <= maxDepth }
            .filter { info -> includeHidden || !info.path.relativeSegments(rootPath).any { it.startsWith(".") } }
            .sortedBy { it.path }
            .toList()
    }

    override fun move(
        source: SandboxPathInfo,
        destination: SandboxPathInfo,
        replaceExisting: Boolean,
        createParents: Boolean,
        logger: Logger?,
    ) {
        require(source.path != ROOT) { "Cannot move Android sandbox root." }
        require(destination.path != ROOT) { "Cannot replace Android sandbox root." }
        require(destination.path != source.path && !destination.path.startsWith("${source.path}/")) {
            "Cannot move ${source.path} into itself."
        }
        row(source.path) ?: error("Source does not exist in Android sandbox: ${source.path}")
        if (row(destination.path) != null) {
            if (!replaceExisting) error("Destination already exists in Android sandbox: ${destination.path}")
            delete(destination, recursively = true)
        }
        if (createParents) {
            ensureParents(destination.path)
        } else {
            val parent = destination.path.parentPath()
            require(parent == null || resolvePath(parent).isDirectory) {
                "Destination parent does not exist in Android sandbox: $parent"
            }
        }

        val rows = listOf(row(source.path) ?: error("Source disappeared: ${source.path}")) + descendantRows(source.path)
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete(
                "sandbox_entries",
                "path = ? OR path LIKE ?",
                arrayOf(source.path, descendantLike(source.path)),
            )
            rows.forEach { entry ->
                val suffix = entry.path.removePrefix(source.path)
                upsert(destination.path + suffix, entry.isDirectory, entry.content)
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    override fun moveToTrash(path: SandboxPathInfo, logger: Logger?): SandboxPathInfo {
        require(path.path != ROOT) { "Cannot move Android sandbox root to trash." }
        createDirectory(resolvePath(TRASH_DIR))
        val destination = resolvePath("$TRASH_DIR/${System.currentTimeMillis()}_${path.path.name()}")
        move(path, destination, replaceExisting = true, createParents = true, logger = logger)
        return resolvePath(destination.path)
    }

    private fun ensureParents(path: String) {
        val parent = path.parentPath() ?: return
        if (parent == ROOT) return
        if (row(parent) == null) {
            ensureParents(parent)
            upsert(parent, isDirectory = true, content = null)
        }
    }

    private fun upsert(path: String, isDirectory: Boolean, content: ByteArray?) {
        val values = ContentValues().apply {
            put("path", path)
            put("is_directory", if (isDirectory) 1 else 0)
            if (content == null) putNull("content") else put("content", content)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.replaceOrThrow("sandbox_entries", null, values)
    }

    private fun row(path: String): EntryRow? {
        if (path == ROOT) return EntryRow(path = ROOT, isDirectory = true, content = null)
        readableDatabase.query(
            "sandbox_entries",
            arrayOf("path", "is_directory", "content"),
            "path = ?",
            arrayOf(path),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return EntryRow(
                path = cursor.getString(cursor.getColumnIndexOrThrow("path")),
                isDirectory = cursor.getInt(cursor.getColumnIndexOrThrow("is_directory")) == 1,
                content = cursor.getBlob(cursor.getColumnIndexOrThrow("content")),
            )
        }
    }

    private fun descendantRows(path: String): List<EntryRow> {
        val rows = mutableListOf<EntryRow>()
        readableDatabase.query(
            "sandbox_entries",
            arrayOf("path", "is_directory", "content"),
            "path LIKE ?",
            arrayOf(descendantLike(path)),
            null,
            null,
            "path ASC",
        ).use { cursor ->
            val pathIndex = cursor.getColumnIndexOrThrow("path")
            val directoryIndex = cursor.getColumnIndexOrThrow("is_directory")
            val contentIndex = cursor.getColumnIndexOrThrow("content")
            while (cursor.moveToNext()) {
                rows += EntryRow(
                    path = cursor.getString(pathIndex),
                    isDirectory = cursor.getInt(directoryIndex) == 1,
                    content = cursor.getBlob(contentIndex),
                )
            }
        }
        return rows
    }

    private fun normalize(rawPath: String): String {
        val raw = rawPath.ifBlank { ROOT }.replace('\\', '/')
        val stack = ArrayDeque<String>()
        raw.split('/').forEach { segment ->
            when {
                segment.isBlank() || segment == "." -> Unit
                segment == ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(segment)
            }
        }
        return if (stack.isEmpty()) ROOT else stack.joinToString(prefix = ROOT, separator = "/")
    }

    private fun descendantLike(path: String): String =
        if (path == ROOT) "/%" else "$path/%"

    private fun String.parentPath(): String? {
        if (this == ROOT) return null
        val index = lastIndexOf('/')
        return if (index <= 0) ROOT else substring(0, index)
    }

    private fun String.name(): String =
        if (this == ROOT) ROOT else substringAfterLast('/')

    private fun String.depth(): Int =
        trim('/').takeIf { it.isNotBlank() }?.split('/')?.size ?: 0

    private fun String.relativeSegments(rootPath: String): List<String> {
        val relative = if (rootPath == ROOT) trim('/') else removePrefix("$rootPath/").trim('/')
        return relative.takeIf { it.isNotBlank() }?.split('/').orEmpty()
    }

    private data class EntryRow(
        val path: String,
        val isDirectory: Boolean,
        val content: ByteArray?,
    )

    private companion object {
        const val DB_NAME = "souz_android_sandbox.db"
        const val DB_VERSION = 1
        const val ROOT = "/"
        const val TRASH_DIR = "/.trash"
    }
}
