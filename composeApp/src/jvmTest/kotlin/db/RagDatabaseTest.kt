package ru.abledo.db

import kotlin.test.Test
import kotlin.test.assertEquals
import java.io.File
import kotlin.test.Ignore

class RagDatabaseTest {
    private fun reset() {
        ConfigStore.prefs.remove("rag_db_initialized")
        File("build/rag_index").deleteRecursively()
    }

    @Test
    @Ignore
    fun writesAndReads() {
        reset()
        VectorDB.initializeOnce()
        val testText = "hello world"
        val data = listOf(StorredData(testText, StorredType.NOTES))
        val emb = List(1536) { 0.01 }
        VectorDB.insert(data, listOf(emb))

        // get all query
        assertEquals(data, VectorDB.getAllData())

        // similar query
        val result = VectorDB.searchSimilar(emb, 1)
        assertEquals(data.first(), result.first())
    }
}
