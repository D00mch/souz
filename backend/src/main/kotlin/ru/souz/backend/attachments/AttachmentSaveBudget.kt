package ru.souz.backend.attachments

data class AttachmentSaveBudget(
    val maxExecutionSaves: Int = 20,
    val maxExecutionSaveBytes: Long = 200_000_000,
    val maxOwnerSavesPer24h: Int = 100,
    val maxOwnerSaveBytesPer24h: Long = 1_000_000_000,
) {
    init {
        require(maxExecutionSaves > 0 && maxExecutionSaveBytes > 0) {
            "Attachment execution save limits must be positive."
        }
        require(maxOwnerSavesPer24h > 0 && maxOwnerSaveBytesPer24h > 0) {
            "Attachment owner save limits must be positive."
        }
    }
}

internal const val ATTACHMENT_EXECUTION_SAVE_LIMIT = "attachment_execution_save_limit_exceeded"
internal const val ATTACHMENT_OWNER_SAVE_LIMIT = "attachment_owner_save_limit_exceeded"
