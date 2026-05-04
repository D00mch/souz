package ru.souz.runtime.sandbox

import ru.souz.llms.LocalUserId

data class SandboxScope(
    val userId: String,
    val conversationId: String? = null,
) {
    init {
        require(userId.isNotBlank()) { "SandboxScope.userId must not be blank." }
    }

    companion object {
        fun localDefault(): SandboxScope = SandboxScope(
            userId = LocalUserId.default(),
        )
    }
}
