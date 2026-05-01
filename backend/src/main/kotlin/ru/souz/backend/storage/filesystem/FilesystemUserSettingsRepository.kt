package ru.souz.backend.storage.filesystem

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.settings.model.UserSettings
import ru.souz.backend.settings.repository.UserSettingsRepository

class FilesystemUserSettingsRepository(
    dataDir: java.nio.file.Path,
    private val mapper: ObjectMapper = filesystemStorageObjectMapper(),
) : UserSettingsRepository {
    private val mutex = Mutex()
    private val layout = FilesystemStorageLayout(dataDir)

    override suspend fun get(userId: String): UserSettings? = mutex.withLock {
        filesystemIo {
            readTextIfExists(layout.settingsFile(userId))
                ?.let { mapper.readValue<StoredUserSettings>(it).toDomain() }
        }
    }

    override suspend fun save(settings: UserSettings): UserSettings = mutex.withLock {
        filesystemIo {
            writeAtomicString(
                target = layout.settingsFile(settings.userId),
                content = mapper.writeValueAsString(settings.toStored()),
            )
            settings
        }
    }
}
