package ru.souz.service.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActiveRecorderPcmAudioFrameSourceTest {

    @Test
    fun `frames are batched into target ambient chunks`() = runTest {
        val recorder = FakeActiveSoundRecorder(
            frameFlow = List(11) { index -> ByteArray(640) { index.toByte() } }.asFlow()
        )
        val source = ActiveRecorderPcmAudioFrameSource(recorder = recorder, chunkMillis = 200)

        val chunks = source.frames().toList()

        assertEquals(2, chunks.size)
        assertEquals(6_400, chunks[0].size)
        assertEquals(640, chunks[1].size)
        assertContentEquals(ByteArray(640) { 0 }, chunks[0].copyOfRange(0, 640))
        assertContentEquals(ByteArray(640) { 9 }, chunks[0].copyOfRange(5_760, 6_400))
        assertContentEquals(ByteArray(640) { 10 }, chunks[1])
    }

    @Test
    fun `start returns false when recorder cannot open microphone`() = runTest {
        val recorder = FakeActiveSoundRecorder(
            startError = IllegalStateException("microphone busy"),
        )
        val source = ActiveRecorderPcmAudioFrameSource(recorder = recorder)

        assertFalse(source.start())
        assertEquals(1, recorder.startCalls)
    }

    @Test
    fun `start and stop delegate to active recorder`() = runTest {
        val recorder = FakeActiveSoundRecorder()
        val source = ActiveRecorderPcmAudioFrameSource(recorder = recorder)

        assertTrue(source.start())
        source.stop()

        assertEquals(1, recorder.startCalls)
        assertEquals(1, recorder.stopCalls)
    }

    @Test
    fun `active sound recorder avoids tryLock and JVM concurrency primitives`() {
        val source = activeSoundRecorderSource()
        val forbiddenTokens = listOf(
            "tryLock(",
            "ReentrantLock",
            "Atomic",
            "@Volatile",
            "synchronized(",
        )

        forbiddenTokens.forEach { token ->
            assertFalse(source.contains(token), "ActiveSoundRecorderImpl must not use $token")
        }
    }

    private fun activeSoundRecorderSource(): String {
        val candidates = listOf(
            File("desktopApp/src/main/kotlin/ru/souz/service/audio/ActiveSoundRecorder.kt"),
            File("src/main/kotlin/ru/souz/service/audio/ActiveSoundRecorder.kt"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("ActiveSoundRecorder.kt source was not found")
    }

    private class FakeActiveSoundRecorder(
        private val startError: Throwable? = null,
        private val frameFlow: Flow<ByteArray> = emptyList<ByteArray>().asFlow(),
    ) : ActiveSoundRecorder {
        var startCalls: Int = 0
        var stopCalls: Int = 0

        override val sampleRateHz: Int = 16_000
        override val channels: Int = 1
        override val bitsPerSample: Int = 16

        override fun prepare() = Unit

        override fun startRecording() {
            startCalls += 1
            startError?.let { throw it }
        }

        override suspend fun stopRecording(): ByteArray {
            stopCalls += 1
            return ByteArray(0)
        }

        override fun frames(): Flow<ByteArray> = frameFlow
    }
}
