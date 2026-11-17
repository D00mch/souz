package ru.souz.db

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import kotlin.test.assertSame
import ru.souz.agent.session.GraphSessionRepository
import ru.souz.di.mainDiModule
import ru.souz.paths.DefaultSouzPaths
import ru.souz.paths.SouzPaths
import ru.souz.agent.session.GraphSession
import ru.souz.agent.session.GraphStepRecord

class DesktopSqliteStorageTest {
    @TempDir
    lateinit var root: Path
    private val dbPath get() = root.resolve("state/desktop.db")
    private fun item(text: String) = StorredData(text, StorredType.NOTES)

    @Test
    fun `desktop DI shares the SQLite session adapter with the agent and UI`() {
        val di = DI {
            import(mainDiModule, allowOverride = true)
            bindSingleton<SouzPaths>(overrides = true) { DefaultSouzPaths(root.resolve("state")) }
        }
        val concrete: SqliteGraphSessionRepository by di.instance()
        val contract: GraphSessionRepository by di.instance()
        assertSame(concrete, contract)
        contract.save(GraphSession("wired", 1, null, "synthetic", emptyList()))
        assertEquals(1, SqliteGraphSessionRepository(dbPath).count())
        assertFalse(Files.exists(root.resolve("state/sessions")))
    }

    @Test
    fun `vectors persist with cosine ranking threshold ties and limit`() {
        val db = VectorDB(dbPath)
        db.initializeOnce()
        val data = listOf("opposite", "orthogonal", "near", "same", "tie").map(::item)
        db.insert(data, listOf(listOf(-1.0, 0.0), listOf(0.0, 1.0), listOf(3.0, 4.0), listOf(1.0, 0.0), listOf(2.0, 0.0)))
        val reopened = VectorDB(dbPath)
        assertEquals(data, reopened.getAllData())
        assertEquals(listOf(item("same"), item("tie"), item("near")), reopened.searchSimilar(listOf(1.0, 0.0)))
        assertEquals(listOf(item("same"), item("tie")), reopened.searchSimilar(listOf(1.0, 0.0), 2))
        assertEquals(listOf(item("same"), item("tie"), item("near"), item("orthogonal")), reopened.searchSimilar(listOf(1.0, 0.0), 5, 0.5f))
        assertEquals(listOf(item("same"), item("tie")), reopened.searchSimilar(listOf(1.0, 0.0), 5, 0.81f))
        assertTrue(reopened.searchSimilar(listOf(1.0, 0.0), 0).isEmpty())
        assertTrue(reopened.searchSimilar(listOf(1.0)).isEmpty())
    }

    @Test
    fun `invalid batches leave existing vectors intact and clear permits another dimension`() {
        val db = VectorDB(dbPath)
        db.insert(listOf(item("original")), listOf(listOf(1.0, 0.0)))
        assertFailsWith<IllegalArgumentException> { db.insert(listOf(item("missing")), emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            db.insert(listOf(item("valid"), item("zero")), listOf(listOf(1.0, 1.0), listOf(0.0, 0.0)))
        }
        assertFailsWith<IllegalArgumentException> { db.insert(listOf(item("wrong dimension")), listOf(listOf(1.0))) }
        assertEquals(listOf(item("original")), VectorDB(dbPath).getAllData())
        // Exercise transaction rollback after an actual SQLite write, not only argument validation.
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
            connection.createStatement().use {
                it.execute("create trigger reject_vector before insert on desktop_vectors when new.text = 'reject' begin select raise(abort, 'fixture'); end")
            }
        }
        assertFailsWith<java.sql.SQLException> {
            db.insert(listOf(item("accepted first"), item("reject")), listOf(listOf(1.0, 1.0), listOf(1.0, 1.0)))
        }
        assertEquals(listOf(item("original")), db.getAllData())
        db.clearAllData()
        assertTrue(VectorDB(dbPath).getAllData().isEmpty())
        db.insert(listOf(item("new")), listOf(listOf(1.0)))
        assertEquals(listOf(item("new")), db.searchSimilar(listOf(1.0)))
    }

    @Test
    fun `embeddings retain the existing 1024 float dimension truncation`() {
        val db = VectorDB(dbPath)
        db.insert(listOf(item("long")), listOf(List(1025) { if (it == 1024) -100.0 else 1.0 }))
        assertEquals(listOf(item("long")), db.searchSimilar(List(1025) { 1.0 }, minScore = 1f))
    }

    @Test
    fun `sessions round trip upsert sort delete and retain by save time without touching legacy files`() {
        val legacy = Files.createDirectories(root.resolve("state/sessions")).resolve("old.json")
        Files.writeString(legacy, "legacy session")
        val index = Files.createDirectories(root.resolve("state/vector-index")).resolve("segments_1")
        Files.writeString(index, "legacy index")
        val time = Instant.parse("2026-11-01T00:00:00Z")
        fun repository(savedAt: Instant = time) = SqliteGraphSessionRepository(dbPath, Clock.fixed(savedAt, ZoneOffset.UTC))
        assertEquals(0, repository().count())
        assertTrue(VectorDB(dbPath).getAllData().isEmpty())
        val session = GraphSession("id/'quoted", 100, null, "Юникод\ninput", listOf(
            GraphStepRecord(0, "node", 200, "in", "out", "{\"x\":true}", "[]"),
        ))
        repository(time.minusMillis(1)).save(session)
        repository().save(session.copy(id = "newer", startTime = 300, endTime = 400))
        assertEquals(session, repository().loadById(session.id))
        assertEquals(listOf("newer", session.id), repository().loadAll().map { it.id })
        repository().deleteSavedBefore(time)
        assertNull(repository().loadById(session.id))
        assertEquals(1, repository().count(), "Saved exactly at cutoff must remain")
        repository(time.plusMillis(1)).save(session)
        repository(time.plusMillis(2)).save(session.copy(initialInput = "replacement"))
        assertEquals(2, repository().count())
        assertEquals("replacement", repository().loadById(session.id)?.initialInput)
        assertTrue(repository().delete(session.id))
        assertFalse(repository().delete(session.id))
        VectorDB(dbPath).insert(listOf(item("keep")), listOf(listOf(1.0)))
        repository().clearAll()
        assertEquals(0, repository().count())
        assertEquals(listOf(item("keep")), VectorDB(dbPath).getAllData())
        assertEquals("legacy session", Files.readString(legacy))
        assertEquals("legacy index", Files.readString(index))
        repository().save(session)
        assertEquals(session, repository().loadAll().single())
    }
}
