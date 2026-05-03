package ru.souz.runtime.sandbox

data class SandboxScope(
    val userId: String,
    val conversationId: String? = null,
) {
    companion object {
        fun localDefault(): SandboxScope = SandboxScope(
            userId = System.getProperty("user.name")
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: "local-user",
        )
    }
}
