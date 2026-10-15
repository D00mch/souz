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
                storage.cleanup()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                LoggerFactory.getLogger(AttachmentCleanupWorker::class.java).warn("Attachment cleanup iteration failed")
            }
            delay(60_000)
        }
    }
}
