package ru.souz.android.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

data class AndroidChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val createdAt: Long,
)

class AndroidChatDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DB_NAME,
    null,
    DB_VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE messages (
                id TEXT PRIMARY KEY NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX messages_created_at_idx ON messages(created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS messages")
        onCreate(db)
    }

    fun listMessages(): List<AndroidChatMessage> {
        val messages = mutableListOf<AndroidChatMessage>()
        readableDatabase.query(
            "messages",
            arrayOf("id", "role", "content", "created_at"),
            null,
            null,
            null,
            null,
            "created_at ASC",
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("id")
            val roleIndex = cursor.getColumnIndexOrThrow("role")
            val contentIndex = cursor.getColumnIndexOrThrow("content")
            val createdAtIndex = cursor.getColumnIndexOrThrow("created_at")
            while (cursor.moveToNext()) {
                messages += AndroidChatMessage(
                    id = cursor.getString(idIndex),
                    role = cursor.getString(roleIndex),
                    content = cursor.getString(contentIndex),
                    createdAt = cursor.getLong(createdAtIndex),
                )
            }
        }
        return messages
    }

    fun appendMessage(role: String, content: String): AndroidChatMessage {
        val message = AndroidChatMessage(
            id = UUID.randomUUID().toString(),
            role = role,
            content = content,
            createdAt = System.currentTimeMillis(),
        )
        writableDatabase.insertOrThrow("messages", null, message.toContentValues())
        return message
    }

    fun updateMessageContent(id: String, content: String) {
        val values = ContentValues().apply {
            put("content", content)
        }
        writableDatabase.update("messages", values, "id = ?", arrayOf(id))
    }

    fun clear() {
        writableDatabase.delete("messages", null, null)
    }

    private fun AndroidChatMessage.toContentValues(): ContentValues = ContentValues().apply {
        put("id", id)
        put("role", role)
        put("content", content)
        put("created_at", createdAt)
    }

    private companion object {
        const val DB_NAME = "souz_android_chat.db"
        const val DB_VERSION = 1
    }
}
