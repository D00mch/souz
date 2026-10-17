package ru.souz.backend.storage.postgres

import javax.sql.DataSource
import ru.souz.backend.settings.model.UserSettings
import ru.souz.backend.settings.model.PersonalitySnapshot
import ru.souz.backend.settings.repository.UserSettingsRepository

class PostgresUserSettingsRepository(
    private val dataSource: DataSource,
) : UserSettingsRepository {
    override suspend fun get(userId: String): UserSettings? = dataSource.read { connection ->
        connection.prepareStatement(
            "select * from user_settings where user_id = ?"
        ).use { statement ->
            statement.setString(1, userId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toUserSettings() else null
            }
        }
    }

    override suspend fun save(settings: UserSettings): UserSettings = dataSource.write { connection ->
        connection.prepareStatement(
            """
            insert into user_settings(user_id, settings_json, created_at, updated_at)
            values (?, ?, ?, ?)
            on conflict (user_id) do update
            set settings_json = excluded.settings_json,
                created_at = excluded.created_at,
                updated_at = excluded.updated_at
            returning *
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, settings.userId)
            statement.setJson(2, settings.toSettingsJson())
            statement.setInstant(3, settings.createdAt)
            statement.setInstant(4, settings.updatedAt)
            statement.executeQuery().use { rows -> check(rows.next()); rows.toUserSettings() }
        }
    }

    override suspend fun compareAndSetPersonality(userId: String, expectedRevision: Long, prompt: String?): PersonalitySnapshot? {
        require(expectedRevision in 1 until PersonalitySnapshot.MAX_REVISION)
        require(prompt == null || (prompt.isNotBlank() && prompt.length <= PersonalitySnapshot.MAX_PROMPT_LENGTH))
        return dataSource.write { connection ->
            if (expectedRevision == 1L) connection.prepareStatement("""
                insert into user_settings(user_id, settings_json, created_at, updated_at)
                values (?, '{}'::jsonb, now(), now()) on conflict do nothing
            """.trimIndent()).use { statement -> statement.setString(1, userId); statement.executeUpdate() }
            connection.prepareStatement("""
                update user_settings set personality_prompt = ?, personality_revision = personality_revision + 1,
                    updated_at = now() where user_id = ? and personality_revision = ?
                returning personality_revision
            """.trimIndent()).use { statement ->
                statement.setString(1, prompt)
                statement.setString(2, userId)
                statement.setLong(3, expectedRevision)
                statement.executeQuery().use { rows ->
                    if (rows.next()) PersonalitySnapshot(prompt, rows.getLong(1)) else null
                }
            }
        }
    }
}
