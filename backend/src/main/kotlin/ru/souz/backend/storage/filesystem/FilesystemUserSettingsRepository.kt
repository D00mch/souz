package ru.souz.backend.storage.filesystem

import com.fasterxml.jackson.databind.ObjectMapper
import ru.souz.backend.settings.model.UserSettings
import ru.souz.backend.settings.repository.UserSettingsRepository

class FilesystemUserSettingsRepository(
    dataDir: java.nio.file.Path,
    mapper: ObjectMapper = filesystemStorageObjectMapper(),
) : BaseFilesystemRepository(dataDir, mapper), UserSettingsRepository {

    override suspend fun get(userId: String): UserSettings? =
        withFileLock {
            mapper.readJsonIfExists<StoredUserSettings>(layout.settingsFile(userId))?.toDomain()
        }

    override suspend fun save(settings: UserSettings): UserSettings =
        withFileLock {
            mapper.writeJsonFile(
                target = layout.settingsFile(settings.userId),
                value = settings.toStored(),
            )
            settings
        }
}
