package ru.souz.skilloauth.impl

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun <T> DataSource.read(block: (Connection) -> T): T =
    withContext(Dispatchers.IO) {
        connection.use(block)
    }

internal suspend fun <T> DataSource.write(block: (Connection) -> T): T =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                val result = block(connection)
                connection.commit()
                result
            } catch (t: Throwable) {
                runCatching { connection.rollback() }
                throw t
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }
    }

internal fun PreparedStatement.setInstant(index: Int, value: Instant?) {
    if (value == null) {
        setNull(index, Types.TIMESTAMP_WITH_TIMEZONE)
    } else {
        setObject(index, OffsetDateTime.ofInstant(value, ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
    }
}

internal fun ResultSet.instant(column: String): Instant =
    getObject(column, OffsetDateTime::class.java).toInstant()

internal fun ResultSet.instantOrNull(column: String): Instant? =
    getObject(column, OffsetDateTime::class.java)?.toInstant()

internal fun List<String>.toScopesColumn(): String = joinToString(",")

internal fun String?.fromScopesColumn(): List<String> =
    this?.split(",")?.map(String::trim)?.filter(String::isNotEmpty) ?: emptyList()

/**
 * SQL fragment for an `on conflict ... do update set <column> = ...` clause that merges a
 * comma-separated scopes column into the *union* of the pre-existing row's value ([table].[column])
 * and the value being inserted (`excluded`.[column]), instead of letting `excluded` blindly
 * overwrite it. [table] and [column] are always compile-time-fixed identifiers from this file's own
 * call sites, never external input, so plain string interpolation here carries no injection risk.
 */
internal fun mergedScopesColumnSql(table: String, column: String): String =
    """
    (
        select array_to_string(array(
            select distinct x from unnest(
                string_to_array(nullif($table.$column, ''), ',')
                || string_to_array(nullif(excluded.$column, ''), ',')
            ) as x
        ), ',')
    )
    """.trimIndent()
