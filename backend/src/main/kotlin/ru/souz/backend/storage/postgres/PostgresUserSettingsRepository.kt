package ru.souz.backend.storage.postgres

import javax.sql.DataSource
import ru.souz.backend.settings.model.UserSettings
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
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, settings.userId)
            statement.setJson(2, settings.toSettingsJson())
            statement.setInstant(3, settings.createdAt)
            statement.setInstant(4, settings.updatedAt)
            statement.executeUpdate()
        }
        settings
    }
}
