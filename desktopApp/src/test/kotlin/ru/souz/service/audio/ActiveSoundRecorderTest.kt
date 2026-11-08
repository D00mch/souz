package ru.souz.service.audio

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActiveSoundRecorderTest {
    @Test
    fun `capture publishes frames without acquiring the lifecycle mutex`() = withRecorder { recorder, line, mutex ->
        recorder.startRecording()
        var read = line.nextRead()
        val before = mutex.locks.get()
        val frames = 120
        repeat(frames) { index ->
            read.complete(ByteArray(640) { index.toByte() })
            // Reaching the next native read proves that the previous trySend finished.
            read = line.nextRead()
        }
        val captureLocks = mutex.locks.get() - before
        println("ActiveSoundRecorder: frames=$frames, capture lifecycle lock acquisitions=$captureLocks")
        assertEquals(0, captureLocks)
        val recording = recorder.stopRecording()
        assertContentEquals(ByteArray(frames * 640) { (it / 640).toByte() }, recording)
    }

    @Test
    fun `partial and zero length reads preserve frame order and independent buffers`() = withRecorder { recorder, line, _ ->
        recorder.startRecording()
        line.nextRead().complete(ByteArray(137) { 1 })
        val partial = line.nextRead()
        assertEquals(137, partial.offset)
        assertEquals(503, partial.length)
        partial.complete(ByteArray(0))
        line.nextRead().complete(ByteArray(503) { 2 })
        val nextFrame = line.nextRead()
        assertEquals(0, nextFrame.offset)
        nextFrame.complete(ByteArray(640) { 3 })
        line.nextRead()

        assertContentEquals(
            ByteArray(137) { 1 } + ByteArray(503) { 2 } + ByteArray(640) { 3 },
            recorder.stopRecording(),
        )
        assertContentEquals(ByteArray(0), recorder.stopRecording())
    }

    @Test
    fun `a blocked native read does not prevent stop and routes subsequent frames to the new session`() = withRecorder { recorder, line, _ ->
        recorder.startRecording()
        line.nextRead().complete(ByteArray(640) { 1 })
        val blockedRead = line.nextRead()
        assertContentEquals(ByteArray(640) { 1 }, recorder.stopRecording())
        assertFailsWith<IllegalStateException> { recorder.frames().toList() }

        // The native capture job survives stop; its destination must not be hoisted.
        recorder.startRecording()
        blockedRead.complete(ByteArray(640) { 2 })
        line.nextRead().complete(ByteArray(640) { 3 })
        line.nextRead()
        assertContentEquals(ByteArray(640) { 2 } + ByteArray(640) { 3 }, recorder.stopRecording())
        assertEquals(1, line.opens)
        assertEquals(2, line.starts)
    }

    @Test
    fun `frames after stop are discarded and a live collector completes when stopped`() = withRecorder { recorder, line, _ ->
        recorder.startRecording()
        val blockedRead = line.nextRead()
        recorder.stopRecording()
        blockedRead.complete(ByteArray(640) { 1 })
        val idleRead = line.nextRead()

        recorder.startRecording()
        val received = Channel<ByteArray>(Channel.UNLIMITED)
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            recorder.frames().onEach { received.send(it) }.toList()
        }
        idleRead.complete(ByteArray(640) { 2 })
        line.nextRead().complete(ByteArray(640) { 3 })
        line.nextRead()
        repeat(2) { received.receive() }
        assertContentEquals(ByteArray(0), recorder.stopRecording())
        val frames = collected.await()
        assertEquals(2, frames.size)
        assertContentEquals(ByteArray(640) { 2 }, frames[0])
        assertContentEquals(ByteArray(640) { 3 }, frames[1])
    }

    @Test
    fun `full recording buffer drops excess frames without blocking capture`() = withRecorder { recorder, line, _ ->
        recorder.startRecording()
        var read = line.nextRead()
        repeat(3_073) { index ->
            read.complete(ByteArray(640) { index.toByte() })
            read = line.nextRead()
        }
        assertContentEquals(ByteArray(3_072 * 640) { (it / 640).toByte() }, recorder.stopRecording())
    }

    @Test
    fun `start failure clears the destination so a later recording can succeed`() = withRecorder { recorder, line, _ ->
        line.startFailure = IllegalStateException("synthetic start failure")
        assertFailsWith<IllegalStateException> { recorder.startRecording() }
        assertContentEquals(ByteArray(0), recorder.stopRecording())
        assertFailsWith<IllegalStateException> { recorder.frames().toList() }
        line.startFailure = null
        recorder.startRecording()
        line.nextRead().complete(ByteArray(640) { 4 })
        line.nextRead()
        assertContentEquals(ByteArray(640) { 4 }, recorder.stopRecording())
    }

    @Test
    fun `prepare is idempotent and duplicate start preserves the original recording`() = withRecorder { recorder, line, _ ->
        recorder.prepare()
        recorder.prepare()
        assertEquals(1, line.opens)
        assertEquals(0, line.starts)
        assertFailsWith<IllegalStateException> { recorder.frames().toList() }
        recorder.startRecording()
        val read = line.nextRead()
        assertFailsWith<IllegalStateException> { recorder.startRecording() }
        read.complete(ByteArray(640) { 5 })
        line.nextRead()
        assertContentEquals(ByteArray(640) { 5 }, recorder.stopRecording())
    }

    @Test
    fun `close unblocks native reading and completes an active frame collector`() = withRecorder { recorder, line, _ ->
        recorder.startRecording()
        line.nextRead()
        val collected = async(start = CoroutineStart.UNDISPATCHED) { recorder.frames().toList() }
        recorder.close()
        assertEquals(emptyList(), collected.await())
        assertFalse(line.isOpen)
        assertTrue(line.stops > 0)
    }

    private fun withRecorder(block: suspend kotlinx.coroutines.CoroutineScope.(ActiveSoundRecorderImpl, ControlledLine, CountingMutex) -> Unit) = runBlocking {
        val line = ControlledLine()
        val recorder = ActiveSoundRecorderImpl()
        val mutex = CountingMutex()
        // Instrument only the private lifecycle mutex, leaving production API and behavior intact.
        recorder.javaClass.getDeclaredField("stateMutex").apply { isAccessible = true }.set(recorder, mutex)
        mockkStatic(AudioSystem::class)
        try {
            every { AudioSystem.getLine(any<DataLine.Info>()) } returns line
            withTimeout(10_000) { block(recorder, line, mutex) }
        } finally {
            recorder.close()
            unmockkStatic(AudioSystem::class)
        }
    }

    private class CountingMutex(private val delegate: Mutex = Mutex()) : Mutex by delegate {
        val locks = AtomicInteger()
        override suspend fun lock(owner: Any?) {
            locks.incrementAndGet()
            delegate.lock(owner)
        }
    }

    /** A real blocking read boundary, controlled without a microphone or application settings. */
    private class ControlledLine : TargetDataLine by mockk(relaxed = true) {
        private val reads = LinkedBlockingQueue<Read>()
        @Volatile private var opened = false
        @Volatile private var pending: Read? = null
        var opens = 0
        var starts = 0
        var stops = 0
        var startFailure: Throwable? = null

        override fun open(format: AudioFormat, bufferSize: Int) {
            opens++
            opened = true
            assertEquals(16_000f, format.sampleRate)
            assertEquals(16_384, bufferSize)
        }

        override fun isOpen() = opened
        override fun start() {
            starts++
            startFailure?.let { throw it }
        }
        override fun stop() { stops++ }
        override fun flush() = Unit
        override fun close() {
            opened = false
            pending?.complete(ByteArray(0))
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = Read(offset, length)
            pending = read
            if (!opened) return 0
            reads.add(read)
            val bytes = read.bytes.get(5, TimeUnit.SECONDS)
            bytes.copyInto(buffer, offset)
            return bytes.size
        }

        fun nextRead(): Read = reads.poll(5, TimeUnit.SECONDS) ?: error("Capture did not reach the next read")
    }

    private class Read(val offset: Int, val length: Int) {
        val bytes = CompletableFuture<ByteArray>()
        fun complete(value: ByteArray) {
            require(value.size <= length)
            bytes.complete(value)
        }
    }
}
