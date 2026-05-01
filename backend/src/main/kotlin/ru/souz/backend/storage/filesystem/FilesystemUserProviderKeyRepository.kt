package ru.souz.backend.storage.filesystem

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.keys.model.UserProviderKey
import ru.souz.backend.keys.repository.UserProviderKeyRepository
import ru.souz.llms.LlmProvider

class FilesystemUserProviderKeyRepository(
    dataDir: java.nio.file.Path,
    @Suppress("unused") private val masterKey: String,
    private val mapper: ObjectMapper = filesystemStorageObjectMapper(),
) : UserProviderKeyRepository {
    private val mutex = Mutex()
    private val layout = FilesystemStorageLayout(dataDir)

    override suspend fun get(
        userId: String,
        provider: LlmProvider,
    ): UserProviderKey? = mutex.withLock {
        filesystemIo {
            readAll(userId).firstOrNull { it.provider == provider }
        }
    }

    override suspend fun list(userId: String): List<UserProviderKey> = mutex.withLock {
        filesystemIo {
            readAll(userId)
        }
    }

    override suspend fun save(key: UserProviderKey): UserProviderKey = mutex.withLock {
        filesystemIo {
            val updated = readAll(key.userId)
                .filterNot { it.provider == key.provider }
                .plus(key)
                .sortedBy { it.provider.name }
            writeAtomicString(
                target = layout.providerKeysFile(key.userId),
                content = mapper.writeValueAsString(updated.map(UserProviderKey::toStored)),
            )
            key
        }
    }

    override suspend fun delete(
        userId: String,
        provider: LlmProvider,
    ): Boolean = mutex.withLock {
        filesystemIo {
            val existing = readAll(userId)
            val updated = existing.filterNot { it.provider == provider }
            if (updated.size == existing.size) {
                false
            } else {
                writeAtomicString(
                    target = layout.providerKeysFile(userId),
                    content = mapper.writeValueAsString(updated.map(UserProviderKey::toStored)),
                )
                true
            }
        }
    }

    private fun readAll(userId: String): List<UserProviderKey> =
        readTextIfExists(layout.providerKeysFile(userId))
            ?.let { raw ->
                mapper.readValue<List<StoredUserProviderKey>>(raw).map(StoredUserProviderKey::toDomain)
            }
            .orEmpty()
}
