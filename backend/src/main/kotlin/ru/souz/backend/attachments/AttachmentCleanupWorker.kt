package ru.souz.backend.attachments

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class AttachmentCleanupWorker(private val storage: AttachmentStorage) {
    fun start(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            try {
                // cleanup counts successful deletes; any per-file failure keeps the count below a full batch.
                if (storage.cleanup() == ATTACHMENT_CLEANUP_BATCH_SIZE) continue
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LoggerFactory.getLogger(AttachmentCleanupWorker::class.java).warn(
                    "Attachment cleanup iteration failed: exceptionClass={}", error.javaClass.name,
                )
            }
            delay(60_000)
        }
    }
}
