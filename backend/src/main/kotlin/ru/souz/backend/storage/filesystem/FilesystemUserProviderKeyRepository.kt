package ru.souz.backend.storage.filesystem

import com.fasterxml.jackson.databind.ObjectMapper
import ru.souz.backend.keys.model.UserProviderKey
import ru.souz.backend.keys.repository.UserProviderKeyRepository
import ru.souz.llms.LlmProvider

class FilesystemUserProviderKeyRepository(
    dataDir: java.nio.file.Path,
    mapper: ObjectMapper = filesystemStorageObjectMapper(),
) : BaseFilesystemRepository(dataDir, mapper), UserProviderKeyRepository {

    override suspend fun get(
        userId: String,
        provider: LlmProvider,
    ): UserProviderKey? =
        withFileLock {
            readAll(userId).firstOrNull { it.provider == provider }
        }

    override suspend fun list(userId: String): List<UserProviderKey> = withFileLock { readAll(userId) }

    override suspend fun save(key: UserProviderKey): UserProviderKey =
        withFileLock {
            val updated = readAll(key.userId)
                .filterNot { it.provider == key.provider }
                .plus(key)
                .sortedBy { it.provider.name }
            mapper.writeJsonFile(
                target = layout.providerKeysFile(key.userId),
                value = updated.map(UserProviderKey::toStored),
            )
            key
        }

    override suspend fun delete(
        userId: String,
        provider: LlmProvider,
    ): Boolean =
        withFileLock {
            val existing = readAll(userId)
            val updated = existing.filterNot { it.provider == provider }
            if (updated.size == existing.size) {
                false
            } else {
                mapper.writeJsonFile(
                    target = layout.providerKeysFile(userId),
                    value = updated.map(UserProviderKey::toStored),
                )
                true
            }
        }

    private fun readAll(userId: String): List<UserProviderKey> =
        mapper.readJsonIfExists<List<StoredUserProviderKey>>(layout.providerKeysFile(userId))
            ?.mapNotNull(StoredUserProviderKey::toDomainOrNull)
            .orEmpty()
}
