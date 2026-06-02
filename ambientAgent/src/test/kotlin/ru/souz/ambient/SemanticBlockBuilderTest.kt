package ru.souz.ambient

import ru.souz.service.speech.ambient.AmbientTranscriptEvent
import ru.souz.service.speech.ambient.AmbientTranscriptSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemanticBlockBuilderTest {

    @Test
    fun `ignores volatile and blank events`() {
        val builder = SemanticBlockBuilder(clock = { 2_000L })

        assertEquals(emptyList(), builder.accept(event(id = "v1", text = "draft", isFinal = false)))
        assertEquals(emptyList(), builder.accept(event(id = "f1", text = "   ", isFinal = true)))
        assertNull(builder.flush())
    }

    @Test
    fun `repeated identical final text is preserved`() {
        val builder = SemanticBlockBuilder(clock = { 5_000L })

        builder.accept(event(id = "e1", text = "да", startedAtMs = 0, endedAtMs = 100))
        builder.accept(event(id = "e2", text = "да", startedAtMs = 110, endedAtMs = 200))

        val block = builder.flush(closeReason = AmbientBlockCloseReason.MANUAL_FLUSH)

        assertEquals("да да", block?.text)
        assertEquals(listOf("e1", "e2"), block?.eventIds)
    }

    @Test
    fun `closes block on pause and starts a new one`() {
        val builder = SemanticBlockBuilder(
            clock = { 10_000L },
            config = SemanticBlockBuilderConfig(pauseToCloseMs = 500L, minUsefulChars = 1),
        )

        builder.accept(event(id = "e1", text = "first", startedAtMs = 0, endedAtMs = 100))
        val closed = builder.accept(event(id = "e2", text = "second", startedAtMs = 700, endedAtMs = 800))

        assertEquals(1, closed.size)
        assertEquals("first", closed.single().text)
        assertEquals(AmbientBlockCloseReason.PAUSE, closed.single().closeReason)
        assertEquals("second", builder.flush()?.text)
    }

    @Test
    fun `closes block on max chars`() {
        val builder = SemanticBlockBuilder(
            clock = { 10_000L },
            config = SemanticBlockBuilderConfig(maxBlockChars = 10, minUsefulChars = 1),
        )

        builder.accept(event(id = "e1", text = "hello", startedAtMs = 0, endedAtMs = 100))
        val closed = builder.accept(event(id = "e2", text = "worldwide", startedAtMs = 120, endedAtMs = 200))

        assertEquals("hello", closed.single().text)
        assertEquals(AmbientBlockCloseReason.MAX_CHARS, closed.single().closeReason)
        assertEquals("worldwide", builder.flush()?.text)
    }

    @Test
    fun `closes block on max duration`() {
        val builder = SemanticBlockBuilder(
            clock = { 10_000L },
            config = SemanticBlockBuilderConfig(maxBlockDurationMs = 1_000L, minUsefulChars = 1),
        )

        builder.accept(event(id = "e1", text = "start", startedAtMs = 0, endedAtMs = 100))
        val closed = builder.accept(event(id = "e2", text = "later", startedAtMs = 1_100, endedAtMs = 1_200))

        assertEquals("start", closed.single().text)
        assertEquals(AmbientBlockCloseReason.MAX_DURATION, closed.single().closeReason)
        assertEquals("later", builder.flush()?.text)
    }

    @Test
    fun `default builder groups speech into three second windows`() {
        val builder = SemanticBlockBuilder(clock = { 20_000L })

        builder.accept(event(id = "e1", text = "давай посмотрим календарь", startedAtMs = 0, endedAtMs = 3_000))
        val closed = builder.accept(event(id = "e2", text = "а это уже следующее окно", startedAtMs = 3_000, endedAtMs = 6_000))

        assertEquals(1, closed.size)
        assertEquals(
            "давай посмотрим календарь",
            closed.single().text,
        )
        assertEquals(AmbientBlockCloseReason.MAX_DURATION, closed.single().closeReason)
        assertEquals("а это уже следующее окно", builder.flush()?.text)
    }

    @Test
    fun `default builder keeps contiguous batch fallback windows together up to batch duration`() {
        val builder = SemanticBlockBuilder(clock = { 20_000L })

        assertEquals(
            emptyList(),
            builder.accept(
                event(
                    id = "e1",
                    text = "надо поставить созвон",
                    startedAtMs = 0,
                    endedAtMs = 3_000,
                    source = AmbientTranscriptSource.BATCH_FALLBACK,
                )
            ),
        )
        val closed = builder.accept(
            event(
                id = "e2",
                text = "с командой завтра",
                startedAtMs = 3_000,
                endedAtMs = 6_000,
                source = AmbientTranscriptSource.BATCH_FALLBACK,
            )
        )

        assertEquals(emptyList(), closed)
        assertEquals("надо поставить созвон с командой завтра", builder.flush()?.text)
    }

    @Test
    fun `flush closes open block with requested reason`() {
        val builder = SemanticBlockBuilder(clock = { 3_000L })
        builder.accept(event(id = "e1", text = "создай заметку", startedAtMs = 100, endedAtMs = 400))

        val block = builder.flush(closeReason = AmbientBlockCloseReason.STOPPED)

        assertEquals(AmbientBlockCloseReason.STOPPED, block?.closeReason)
        assertEquals(3_000L, block?.closedAtMs)
    }

    @Test
    fun `direct address is probably user and is not dropped when short`() {
        val builder = SemanticBlockBuilder(
            clock = { 1_000L },
            config = SemanticBlockBuilderConfig(minUsefulChars = 40),
        )

        builder.accept(event(id = "e1", text = "Союз, стоп", startedAtMs = 100, endedAtMs = 200))
        val block = builder.flush()

        assertEquals(AmbientAddressedness.DIRECT_TO_SOUZ, block?.addressedness)
        assertEquals(AmbientSpeakerRole.PROBABLY_USER, block?.speakerRole)
        assertEquals("Союз, стоп", block?.text)
    }

    @Test
    fun `quoted background speech is marked as background media`() {
        val builder = SemanticBlockBuilder(clock = { 1_000L })
        builder.accept(event(id = "e1", text = "в видео сказали создай задачу", startedAtMs = 100, endedAtMs = 200))

        val block = builder.flush()

        assertEquals(AmbientAddressedness.BACKGROUND_OR_QUOTED, block?.addressedness)
        assertEquals(AmbientSpeakerRole.BACKGROUND_MEDIA, block?.speakerRole)
    }

    @Test
    fun `null timestamps fallback to received time`() {
        val builder = SemanticBlockBuilder(clock = { 2_000L })
        builder.accept(event(id = "e1", text = "напомни мне купить хлеб", receivedAtMs = 1_234))

        val block = builder.flush()

        assertEquals(1_234L, block?.startedAtMs)
        assertEquals(1_234L, block?.endedAtMs)
    }

    @Test
    fun `implicit user intent is probably user`() {
        val builder = SemanticBlockBuilder(clock = { 2_000L })
        builder.accept(event(id = "e1", text = "потом надо отправить отчет", receivedAtMs = 1_000))

        val block = builder.flush()

        assertEquals(AmbientAddressedness.IMPLICIT_USER_INTENT, block?.addressedness)
        assertEquals(AmbientSpeakerRole.PROBABLY_USER, block?.speakerRole)
        assertTrue(block!!.text.contains("отправить отчет"))
    }

    @Test
    fun `indirect weather help request is implicit user intent`() {
        val builder = SemanticBlockBuilder(clock = { 2_000L })
        builder.accept(event(id = "e1", text = "я хотел бы чтобы кто-то посмотрел погоду", receivedAtMs = 1_000))

        val block = builder.flush()

        assertEquals(AmbientAddressedness.IMPLICIT_USER_INTENT, block?.addressedness)
        assertEquals(AmbientSpeakerRole.PROBABLY_USER, block?.speakerRole)
    }

    @Test
    fun `indirect weather question is implicit user intent`() {
        val builder = SemanticBlockBuilder(clock = { 2_000L })
        builder.accept(event(id = "e1", text = "интересно, какая погода в Москве", receivedAtMs = 1_000))

        val block = builder.flush()

        assertEquals(AmbientAddressedness.IMPLICIT_USER_INTENT, block?.addressedness)
        assertEquals(AmbientSpeakerRole.PROBABLY_USER, block?.speakerRole)
    }

    private fun event(
        id: String,
        text: String,
        isFinal: Boolean = true,
        startedAtMs: Long? = null,
        endedAtMs: Long? = null,
        receivedAtMs: Long = endedAtMs ?: startedAtMs ?: 1_000L,
        source: AmbientTranscriptSource = AmbientTranscriptSource.LIVE,
    ): AmbientTranscriptEvent = AmbientTranscriptEvent(
        id = id,
        text = text,
        isFinal = isFinal,
        startedAtMs = startedAtMs,
        endedAtMs = endedAtMs,
        receivedAtMs = receivedAtMs,
        source = source,
    )
}
