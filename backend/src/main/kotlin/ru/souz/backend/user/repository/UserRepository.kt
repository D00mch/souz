package ru.souz.backend.user.repository

import ru.souz.backend.user.model.UserRecord

interface UserRepository {
    suspend fun ensureUser(userId: String): UserRecord
}
