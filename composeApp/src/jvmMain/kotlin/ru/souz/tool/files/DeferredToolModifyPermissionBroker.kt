package ru.souz.tool.files

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.db.SettingsProvider
import ru.souz.tool.BadInputException
import ru.souz.tool.ToolPermissionBroker
import ru.souz.tool.ToolPermissionRequest
import ru.souz.tool.ToolPermissionResult
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class ToolModifySelectionAction {
    APPLY_SELECTED,
    DISCARD_SELECTED,
}

enum class ToolModifyApplyStatus {
    APPLIED,
    DISCARDED,
    SKIPPED_CONFLICT,
    SKIPPED_EXTERNAL_CONFLICT,
}

data class ToolModifyPendingReviewItem(
    val id: Long,
    val path: String,
    val patchPreview: String,
)

data class ToolModifyPendingReview(
    val items: List<ToolModifyPendingReviewItem>,
)

data class ToolModifyApplyItemResult(
    val id: Long,
    val path: String,
    val status: ToolModifyApplyStatus,
    val warning: String? = null,
)

data class ToolModifyApplyResult(
    val items: List<ToolModifyApplyItemResult>,
) {
    val appliedCount: Int get() = items.count { it.status == ToolModifyApplyStatus.APPLIED }
    val discardedCount: Int get() = items.count { it.status == ToolModifyApplyStatus.DISCARDED }
    val skippedCount: Int get() = items.count {
        it.status == ToolModifyApplyStatus.SKIPPED_CONFLICT ||
            it.status == ToolModifyApplyStatus.SKIPPED_EXTERNAL_CONFLICT
    }
}

class DeferredToolModifyPermissionBroker(
    private val settingsProvider: SettingsProvider,
    private val filesToolUtil: FilesToolUtil,
) : ToolPermissionBroker {
    override val requests: Flow<ToolPermissionRequest> = emptyFlow()

    private val mutex = Mutex()
    private val stagedId = AtomicLong(0L)
    private val hasPendingEdits = AtomicBoolean(false)
    private val stagedCalls = ArrayList<StagedEditCall>()
    private val virtualFiles = LinkedHashMap<String, VirtualFileState>()

    fun shouldStageEdits(): Boolean = settingsProvider.safeModeEnabled

    fun hasPendingEdits(): Boolean = hasPendingEdits.get()

    suspend fun stageEdit(input: ToolModifyFile.Input) {
        mutex.withLock {
            val canonicalFile = filesToolUtil.resolveSafeExistingFile(input.path)
            val fileKey = canonicalFile.path
            val virtualFile = virtualFiles.getOrPut(fileKey) {
                val editable = filesToolUtil.readEditableUtf8TextFile(canonicalFile)
                VirtualFileState(
                    file = editable.file,
                    originalRawText = editable.rawText,
                    currentRawText = editable.rawText,
                )
            }
            val editableTextFile = virtualFile.toEditableTextFile(filesToolUtil)
            val prepared = ToolModifyFilePlanner.prepareEdit(input, editableTextFile, filesToolUtil)
            virtualFile.currentRawText = prepared.updatedRawText
            stagedCalls += StagedEditCall(
                id = stagedId.incrementAndGet(),
                input = input.copy(path = canonicalFile.path),
                path = canonicalFile.path,
                patchPreview = prepared.patchPreview,
            )
            hasPendingEdits.set(stagedCalls.isNotEmpty())
        }
    }

    suspend fun snapshotPendingReview(): ToolModifyPendingReview? = mutex.withLock {
        if (stagedCalls.isEmpty()) return null
        ToolModifyPendingReview(
            items = stagedCalls.map { staged ->
                ToolModifyPendingReviewItem(
                    id = staged.id,
                    path = staged.path,
                    patchPreview = staged.patchPreview,
                )
            }
        )
    }

    suspend fun applySelection(
        selectedIds: Set<Long>,
        action: ToolModifySelectionAction,
    ): ToolModifyApplyResult = mutex.withLock {
        val stagedSnapshot = stagedCalls.toList()
        if (stagedSnapshot.isEmpty()) return ToolModifyApplyResult(emptyList())

        val selectedForApply = when (action) {
            ToolModifySelectionAction.APPLY_SELECTED -> selectedIds
            ToolModifySelectionAction.DISCARD_SELECTED -> stagedSnapshot
                .map { it.id }
                .filterNot(selectedIds::contains)
                .toSet()
        }
        val results = ArrayList<ToolModifyApplyItemResult>(stagedSnapshot.size)

        stagedSnapshot.groupBy { it.path }.forEach { (path, callsForFile) ->
            val virtualFile = virtualFiles[path]
            if (virtualFile == null) {
                callsForFile.forEach { staged ->
                    results += ToolModifyApplyItemResult(
                        id = staged.id,
                        path = staged.path,
                        status = if (staged.id in selectedForApply) {
                            ToolModifyApplyStatus.SKIPPED_CONFLICT
                        } else {
                            ToolModifyApplyStatus.DISCARDED
                        },
                        warning = if (staged.id in selectedForApply) {
                            "Can't apply because the staged file state is missing."
                        } else {
                            null
                        },
                    )
                }
                return@forEach
            }

            val currentFile = filesToolUtil.readEditableUtf8TextFile(virtualFile.file)
            if (currentFile.rawText != virtualFile.originalRawText) {
                callsForFile.forEach { staged ->
                    results += ToolModifyApplyItemResult(
                        id = staged.id,
                        path = staged.path,
                        status = if (staged.id in selectedForApply) {
                            ToolModifyApplyStatus.SKIPPED_EXTERNAL_CONFLICT
                        } else {
                            ToolModifyApplyStatus.DISCARDED
                        },
                        warning = if (staged.id in selectedForApply) {
                            "Can't apply because the file changed on disk after staging."
                        } else {
                            null
                        },
                    )
                }
                return@forEach
            }

            var workingRawText = virtualFile.originalRawText
            var hasAppliedChanges = false
            callsForFile.forEach { staged ->
                if (staged.id !in selectedForApply) {
                    results += ToolModifyApplyItemResult(
                        id = staged.id,
                        path = staged.path,
                        status = ToolModifyApplyStatus.DISCARDED,
                    )
                    return@forEach
                }

                val editableTextFile = virtualFile.toEditableTextFile(filesToolUtil, rawText = workingRawText)
                try {
                    val prepared = ToolModifyFilePlanner.prepareEdit(
                        input = staged.input,
                        editableTextFile = editableTextFile,
                        filesToolUtil = filesToolUtil,
                    )
                    workingRawText = prepared.updatedRawText
                    hasAppliedChanges = true
                    results += ToolModifyApplyItemResult(
                        id = staged.id,
                        path = staged.path,
                        status = ToolModifyApplyStatus.APPLIED,
                    )
                } catch (_: BadInputException) {
                    results += ToolModifyApplyItemResult(
                        id = staged.id,
                        path = staged.path,
                        status = ToolModifyApplyStatus.SKIPPED_CONFLICT,
                        warning = "Can't apply because previous discarded changes removed the expected context.",
                    )
                }
            }

            if (hasAppliedChanges) {
                filesToolUtil.writeUtf8TextFileAtomically(virtualFile.file, workingRawText, l)
            }
        }

        clearLocked()
        ToolModifyApplyResult(items = results)
    }

    suspend fun clearPending() {
        mutex.withLock { clearLocked() }
    }

    override suspend fun requestPermission(
        description: String,
        params: Map<String, String>,
    ): ToolPermissionResult = ToolPermissionResult.Ok

    override fun resolve(requestId: Long, approved: Boolean) = Unit

    private fun clearLocked() {
        stagedCalls.clear()
        virtualFiles.clear()
        hasPendingEdits.set(false)
    }

    private data class StagedEditCall(
        val id: Long,
        val input: ToolModifyFile.Input,
        val path: String,
        val patchPreview: String,
    )

    private data class VirtualFileState(
        val file: java.io.File,
        val originalRawText: String,
        var currentRawText: String,
    )

    private fun VirtualFileState.toEditableTextFile(
        filesToolUtil: FilesToolUtil,
        rawText: String = currentRawText,
    ): FilesToolUtil.EditableTextFile =
        FilesToolUtil.EditableTextFile(
            file = file,
            rawText = rawText,
            normalizedText = filesToolUtil.normalizeLineEndings(rawText),
            lineSeparator = filesToolUtil.detectLineSeparator(rawText),
        )

    private companion object {
        val l = org.slf4j.LoggerFactory.getLogger(DeferredToolModifyPermissionBroker::class.java)
    }
}
