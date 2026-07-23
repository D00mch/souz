package ru.souz.backend.execution.service

import java.util.UUID
import ru.souz.backend.permission.service.PermissionContinuationDispatcher

class AgentExecutionPermissionDispatcher(
    private val executionService: AgentExecutionService,
) : PermissionContinuationDispatcher {
    override suspend fun wake(executionId: UUID) {
        executionService.resumePermission(executionId)
    }
}
