package ru.souz.tool

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
)

interface SelectionApprovalSource {
    val sourceId: String
    val requests: Flow<SelectionApprovalRequest>
    fun resolve(requestId: Long, selectedId: Long?)
}
