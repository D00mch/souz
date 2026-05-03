package ru.souz.backend.storage.filesystem

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import ru.souz.backend.user.model.UserRecord
import ru.souz.backend.user.model.refreshLastSeenAt
import ru.souz.backend.user.repository.UserRepository

class FilesystemUserRepository(
    dataDir: java.nio.file.Path,
    mapper: ObjectMapper = filesystemStorageObjectMapper(),
) : BaseFilesystemRepository(dataDir, mapper), UserRepository {

    override suspend fun ensureUser(userId: String): UserRecord =
        withFileLock {
            val now = Instant.now()
            val existing = mapper.readJsonIfExists<StoredUserRecord>(layout.userFile(userId))?.toDomain()
            val ensured = when (existing) {
                null -> UserRecord(
                    id = userId,
                    createdAt = now,
                    lastSeenAt = now,
                )
                else -> existing.refreshLastSeenAt(now)
            }
            mapper.writeJsonFile(
                target = layout.userFile(userId),
                value = ensured.toStored(),
            )
            ensured
        }
}
