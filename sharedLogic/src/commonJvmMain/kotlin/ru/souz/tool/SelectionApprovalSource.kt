package ru.souz.tool

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow

data class SelectionApprovalCandidate(
    val id: Long,
    val title: String,
    val badge: String? = null,
    val meta: String? = null,
    val preview: String? = null,
)

data class SelectionApprovalRequest(
    val sourceId: String,
    val requestId: Long,
    val title: String,
    val message: String,
    val confirmText: String,
    val cancelText: String,
    val candidates: List<SelectionApprovalCandidate>,
    /** Completes on resolution or caller cancellation, including while queued for display. */
    val completion: Job,
)

interface SelectionApprovalSource {
    val sourceId: String
    val requests: Flow<SelectionApprovalRequest>
    fun resolve(requestId: Long, selectedId: Long?)
}
