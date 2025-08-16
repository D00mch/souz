package com.dumch.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File
import com.dumch.tool.config.ConfigStore

class RagDatabaseTest {
    private fun reset() {
        ConfigStore.prefs.remove("rag_db_initialized")
        File("build/rag.db").delete()
    }

    @Test
    fun writesAndReads() {
        val ext = System.getenv("SQLITE_VEC_PATH")
        if (ext == null) {
            println("SQLITE_VEC_PATH not set, skipping test")
            return
        }
        reset()
        RagDatabase.initializeOnce()
        val data = listOf("hello world")
        val emb = List(1536) { 0.01 }
        RagDatabase.insert(data, listOf(emb))
        assertEquals(data, RagDatabase.getAllTexts())
        val result = RagDatabase.searchSimilar(emb, 1)
        assertTrue(result.contains("hello world"))
    }
}
