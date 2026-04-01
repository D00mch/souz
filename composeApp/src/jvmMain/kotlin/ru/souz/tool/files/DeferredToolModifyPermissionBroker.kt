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
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Selection review to resolve staged edits. */
enum class ToolModifySelectionAction {
    APPLY_SELECTED,
    DISCARD_SELECTED,
}

/** Final outcome for one staged [ToolModifyFile] call after the review step finishes. */
enum class ToolModifyApplyStatus {
    APPLIED,
    DISCARDED,

    /** The staged edit no longer matched because earlier review choices changed its expected context. */
    SKIPPED_CONFLICT,

    /** The file changed on disk after staging, so replay was aborted for safety. */
    SKIPPED_EXTERNAL_CONFLICT,
}

/** Snapshot of all staged [ToolModifyFile] calls awaiting review. */
data class ToolModifyPendingReview(val items: List<Item>) {
    
    /** One staged [ToolModifyFile] call exposed to the review. */
    data class Item(
        val id: Long,
        val path: String,
        val patchPreview: String,
    )
}

/** Aggregate result of resolving the current staged edit batch. */
data class ToolModifyApplyResult(val items: List<Item>) {
    
    val appliedCount: Int get() = items.count { it.status == ToolModifyApplyStatus.APPLIED }
    val discardedCount: Int get() = items.count { it.status == ToolModifyApplyStatus.DISCARDED }
    val skippedCount: Int get() = items.count {
        it.status == ToolModifyApplyStatus.SKIPPED_CONFLICT ||
            it.status == ToolModifyApplyStatus.SKIPPED_EXTERNAL_CONFLICT
    }

    /** Result entry for one staged edit after apply/discard resolution. */
    data class Item(
        val id: Long,
        val path: String,
        val status: ToolModifyApplyStatus,
        val warning: String? = null,
    )
}

/**
 * Safe-mode broker for [ToolModifyFile].
 *
 * Instead of prompting on each edit immediately, this broker stages individual edit requests, keeps a
 * virtual per-file working state for later edits in the same run, and exposes a final review/apply step
 * that replays selected edits only if the file has not changed externally.
 */
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

    /**
     * Returns whether any staged [ToolModifyFile] calls are waiting for review.
     */
    fun hasPendingEdits(): Boolean = hasPendingEdits.get()

    /**
     * Validates and stages one [ToolModifyFile] call against the current virtual file state.
     */
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

    /**
     * Returns the current review snapshot, or `null` when there is nothing staged.
     */
    suspend fun snapshotPendingReview(): ToolModifyPendingReview? = mutex.withLock {
        if (stagedCalls.isEmpty()) return null
        ToolModifyPendingReview(
            items = stagedCalls.map { staged ->
                ToolModifyPendingReview.Item(
                    id = staged.id,
                    path = staged.path,
                    patchPreview = staged.patchPreview,
                )
            }
        )
    }

    /**
     * Applies or discards the staged batch according to [action] and [selectedIds].
     *
     * Selected edits are replayed in original order per file against a fresh disk read to ensure the final
     * write is still based on the same on-disk content that was reviewed.
     */
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
        val results = ArrayList<ToolModifyApplyResult.Item>(stagedSnapshot.size)

        stagedSnapshot.groupBy { it.path }.forEach { (path, callsForFile) ->
            val virtualFile = virtualFiles[path]
            if (virtualFile == null) {
                callsForFile.forEach { staged ->
                    results += ToolModifyApplyResult.Item(
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

            val currentFile = try {
                filesToolUtil.readEditableUtf8TextFile(virtualFile.file)
            } catch (_: BadInputException) {
                appendExternalConflictResults(
                    results = results,
                    callsForFile = callsForFile,
                    selectedForApply = selectedForApply,
                    warning = "Can't apply because the file changed on disk or is no longer readable after staging.",
                )
                return@forEach
            } catch (_: IOException) {
                appendExternalConflictResults(
                    results = results,
                    callsForFile = callsForFile,
                    selectedForApply = selectedForApply,
                    warning = "Can't apply because the file changed on disk or is no longer readable after staging.",
                )
                return@forEach
            }
            if (currentFile.rawText != virtualFile.originalRawText) {
                appendExternalConflictResults(
                    results = results,
                    callsForFile = callsForFile,
                    selectedForApply = selectedForApply,
                    warning = "Can't apply because the file changed on disk after staging.",
                )
                return@forEach
            }

            var workingRawText = virtualFile.originalRawText
            var hasAppliedChanges = false
            callsForFile.forEach { staged ->
                if (staged.id !in selectedForApply) {
                    results += ToolModifyApplyResult.Item(
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
                    results += ToolModifyApplyResult.Item(
                        id = staged.id,
                        path = staged.path,
                        status = ToolModifyApplyStatus.APPLIED,
                    )
                } catch (_: BadInputException) {
                    results += ToolModifyApplyResult.Item(
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

    /**
     * Drops all currently staged edits without applying them.
     */
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

    /**
     * Immutable record of one staged [ToolModifyFile] invocation.
     */
    private data class StagedEditCall(
        val id: Long,
        val input: ToolModifyFile.Input,
        val path: String,
        val patchPreview: String,
    )

    /**
     * Per-file virtual editing state used while additional staged edits are still accumulating.
     */
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
            normalizedTextIndex = filesToolUtil.buildNormalizedTextIndex(rawText),
            preferredLineSeparator = filesToolUtil.detectPreferredLineSeparator(rawText),
        )

    private fun appendExternalConflictResults(
        results: MutableList<ToolModifyApplyResult.Item>,
        callsForFile: List<StagedEditCall>,
        selectedForApply: Set<Long>,
        warning: String,
    ) {
        callsForFile.forEach { staged ->
            results += ToolModifyApplyResult.Item(
                id = staged.id,
                path = staged.path,
                status = if (staged.id in selectedForApply) {
                    ToolModifyApplyStatus.SKIPPED_EXTERNAL_CONFLICT
                } else {
                    ToolModifyApplyStatus.DISCARDED
                },
                warning = if (staged.id in selectedForApply) warning else null,
            )
        }
    }

    private companion object {
        val l = org.slf4j.LoggerFactory.getLogger(DeferredToolModifyPermissionBroker::class.java)
    }
}
