package ru.souz.backend.user.model

import java.time.Duration
import java.time.Instant

data class UserRecord(
    val id: String,
    val createdAt: Instant,
    val lastSeenAt: Instant?,
)

internal val USER_LAST_SEEN_UPDATE_INTERVAL: Duration = Duration.ofMinutes(10)

internal fun UserRecord.refreshLastSeenAt(now: Instant): UserRecord {
    val updatedLastSeenAt = when {
        lastSeenAt == null -> now
        lastSeenAt.isBefore(now.minus(USER_LAST_SEEN_UPDATE_INTERVAL)) -> now
        else -> lastSeenAt
    }
    return copy(lastSeenAt = updatedLastSeenAt)
}
