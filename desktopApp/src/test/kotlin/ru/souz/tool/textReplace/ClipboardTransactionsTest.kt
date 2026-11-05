package ru.souz.tool.textReplace

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.ToolRunBashCommand
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.Transferable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ClipboardTransactionsTest {
    private var onRestore: () -> Unit = {}
    private val clipboard = object : Clipboard("isolated synthetic clipboard") {
        override fun setContents(contents: Transferable?, owner: ClipboardOwner?) {
            if (contents === original) onRestore()
            super.setContents(contents, owner)
        }
    }
    private val original = StringSelection("ORIGINAL")
    private val meta = ToolInvocationMeta.localDefault()

    @BeforeEach
    fun setUp() {
        val toolkit = mockk<Toolkit>()
        mockkStatic(Toolkit::class)
        every { Toolkit.getDefaultToolkit() } returns toolkit
        every { toolkit.systemClipboard } returns clipboard
        mockkObject(ToolRunBashCommand)
        clipboard.setContents(original, null)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @ParameterizedTest
    @CsvSource("copy,copy", "paste,paste", "copy,paste", "paste,copy", "copy,read")
    fun `whole transactions serialize across tool instances and kinds`(firstKind: String, secondKind: String) {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondThread = AtomicReference<Thread>()
        val secondStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        every { ToolRunBashCommand.apple(any()) } answers {
            val label = Thread.currentThread().name
            if (label == "FIRST") {
                firstEntered.countDown()
                check(releaseFirst.await(5, TimeUnit.SECONDS))
            }
            if (firstArg<String>().contains("\"c\"")) {
                clipboard.setContents(StringSelection("selection-$label"), null)
            } else {
                assertEquals("replacement-$label", clipboard.getData(DataFlavor.stringFlavor))
            }
            ""
        }
        try {
            val first = executor.submit<String> {
                Thread.currentThread().name = "FIRST"
                invoke(firstKind, "FIRST")
            }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            val second = executor.submit<String> {
                Thread.currentThread().name = "SECOND"
                secondThread.set(Thread.currentThread())
                secondStarted.countDown()
                invoke(secondKind, "SECOND")
            }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            awaitClipboardLock(secondThread.get())
            releaseFirst.countDown()
            assertEquals(expected(firstKind, "FIRST"), first.get(5, TimeUnit.SECONDS))
            assertEquals(expected(secondKind, "SECOND"), second.get(5, TimeUnit.SECONDS))
            assertSame(original, clipboard.getContents(null))
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `failed transaction restores full clipboard and releases ownership`() {
        every { ToolRunBashCommand.apple(any()) } throws IllegalStateException("synthetic failure")
        assertFailsWith<IllegalStateException> { invoke("paste", "failed") }
        assertSame(original, clipboard.getContents(null))
        every { ToolRunBashCommand.apple(any()) } returns ""
        assertEquals("ok", invoke("paste", "next"))
        assertSame(original, clipboard.getContents(null))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `cancelled paste keeps replacement through settling before restoring and releasing ownership`(
        interruptCommand: Boolean,
    ) = runBlocking {
        var cancelledAt = 0L
        var restoredAfter = 0L
        var restoredText: Any? = null
        onRestore = {
            restoredAfter = System.nanoTime() - cancelledAt
            restoredText = clipboard.getData(DataFlavor.stringFlavor)
        }
        lateinit var active: Deferred<String>
        every { ToolRunBashCommand.apple(any()) } answers {
            active.cancel()
            cancelledAt = System.nanoTime()
            if (interruptCommand) throw InterruptedException("Cancelled after native paste dispatch")
            ""
        }
        active = async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            ToolTextReplace(ToolRunBashCommand).suspendInvoke(ToolTextReplace.Input("temporary"), meta)
        }
        active.start()
        withTimeout(5_000) { active.join() }
        assertTrue(active.isCancelled)
        assertEquals("temporary", restoredText)
        assertTrue(restoredAfter >= TimeUnit.MILLISECONDS.toNanos(70), "Paste settle ended early: $restoredAfter ns")
        assertSame(original, clipboard.getContents(null))
        assertEquals("ORIGINAL", ToolGetClipboard().suspendInvoke(ToolGetClipboard.Input, meta))
    }

    @Test
    fun `cancelled child restores clipboard and a waiting child can be cancelled`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val blockedThread = CompletableDeferred<Thread>()
        val release = CountDownLatch(1)
        every { ToolRunBashCommand.apple(any()) } answers {
            entered.complete(Unit)
            release.await()
            ""
        }
        val active = async(Dispatchers.Default) {
            ToolTextReplace(ToolRunBashCommand).suspendInvoke(ToolTextReplace.Input("temporary"), meta)
        }
        try {
            withTimeout(5_000) { entered.await() }
            val waiting = async(Dispatchers.IO) {
                blockedThread.complete(Thread.currentThread())
                ToolTextUnderSelection(ToolRunBashCommand, ToolGetClipboard())
                    .suspendInvoke(ToolTextUnderSelection.Input, meta)
            }
            val thread = withTimeout(5_000) { blockedThread.await() }
            awaitClipboardLock(thread)
            withTimeout(5_000) { waiting.cancelAndJoin() }
            assertTrue(waiting.isCancelled)
            withTimeout(5_000) { active.cancelAndJoin() }
            assertSame(original, clipboard.getContents(null))
        } finally {
            release.countDown()
            active.cancelAndJoin()
        }
    }

    private fun invoke(kind: String, label: String): String = when (kind) {
        "copy" -> ToolTextUnderSelection(ToolRunBashCommand, ToolGetClipboard()).invoke(ToolTextUnderSelection.Input, meta)
        "paste" -> ToolTextReplace(ToolRunBashCommand).invoke(ToolTextReplace.Input("replacement-$label"), meta)
        else -> ToolGetClipboard().invoke(ToolGetClipboard.Input, meta)
    }

    private fun expected(kind: String, label: String): String = when (kind) {
        "copy" -> "selection-$label"
        "paste" -> "ok"
        else -> "ORIGINAL"
    }

    private fun awaitClipboardLock(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (thread.stackTrace.any { it.className == "ru.souz.tool.textReplace.ClipboardTransactions" }) {
                if (thread.state == Thread.State.WAITING) return
            }
            Thread.yield()
        }
        error("Second tool did not wait for the shared clipboard transaction: ${thread.stackTrace.toList()}")
    }
}
