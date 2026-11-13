package ru.souz.backend.storage.postgres

import com.fasterxml.jackson.module.kotlin.readValue
import javax.sql.DataSource
import ru.souz.backend.settings.model.PersonalitySnapshot
import ru.souz.backend.settings.model.UserSettings
import ru.souz.backend.settings.repository.UserSettingsRepository
import ru.souz.backend.settings.model.PersonalityParameters

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
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.toUserSettings()
            }
        }
    }

    override suspend fun personality(userId: String): PersonalitySnapshot = dataSource.read { connection ->
        connection.prepareStatement("select parameters, revision from user_personalities where user_id = ?").use { statement ->
            statement.setString(1, userId)
            statement.executeQuery().use { rows ->
                if (rows.next()) PersonalitySnapshot(postgresStorageMapper.readValue(rows.getString("parameters")), rows.getLong("revision"))
                else PersonalitySnapshot()
            }
        }
    }

    override suspend fun compareAndSetPersonality(
        userId: String,
        previous: PersonalitySnapshot,
        parameters: PersonalityParameters,
    ): PersonalitySnapshot? = dataSource.write { connection ->
        val next = PersonalitySnapshot(parameters, previous.revision + 1)
        connection.prepareStatement("""
            insert into user_personalities(user_id, parameters, revision) values (?, ?::jsonb, ?)
            on conflict (user_id) do update set parameters = excluded.parameters, revision = excluded.revision
            where user_personalities.revision = ? and user_personalities.parameters = ?::jsonb
        """.trimIndent()).use { statement ->
            statement.setString(1, userId)
            statement.setString(2, postgresStorageMapper.writeValueAsString(parameters))
            statement.setLong(3, next.revision)
            statement.setLong(4, previous.revision)
            // A first public chat may have supplied different initial parameters at revision 1.
            statement.setString(5, postgresStorageMapper.writeValueAsString(previous.parameters))
            next.takeIf { statement.executeUpdate() == 1 }
        }
    }
}
