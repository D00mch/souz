package ru.souz.backend.storage.postgres

import javax.sql.DataSource
import ru.souz.backend.user.model.UserRecord
import ru.souz.backend.user.repository.UserRepository

class PostgresUserRepository(
    private val dataSource: DataSource,
) : UserRepository {
    override suspend fun ensureUser(userId: String): UserRecord = dataSource.write { connection ->
        connection.prepareStatement(
            """
            insert into users (id, created_at, last_seen_at)
            values (?, now(), now())
            on conflict (id) do update
            set last_seen_at =
              case
                when users.last_seen_at is null
                  or users.last_seen_at < now() - interval '10 minutes'
                then now()
                else users.last_seen_at
              end
            returning id, created_at, last_seen_at
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "Expected ensured user row for '$userId'." }
                resultSet.toUserRecord()
            }
        }
    }
}
