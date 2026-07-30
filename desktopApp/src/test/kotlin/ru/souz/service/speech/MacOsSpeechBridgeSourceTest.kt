package ru.souz.service.speech

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacOsSpeechBridgeSourceTest {

    @Test
    fun `live speech transcriber uses progressive live preset without audio time range configuration`() {
        val source = swiftSource()

        assertTrue(
            source.contains("preset: .progressiveTranscription"),
            "Ambient live SpeechTranscriber should use Apple's live transcription preset.",
        )
        assertFalse(
            source.contains("attributeOptions: [.audioTimeRange]"),
            "audioTimeRange made AssetInventory report the ru-RU live configuration as unsupported.",
        )
    }

    @Test
    fun `live speech bridge falls back to dictation transcriber when speech transcriber configuration is unsupported`() {
        val source = swiftSource()

        assertTrue(
            source.contains("SpeechTranscriber(locale: supportedLocale, preset: .progressiveTranscription)"),
            "Ambient live should try the general-purpose SpeechTranscriber first.",
        )
        assertTrue(
            source.contains("DictationTranscriber(locale: supportedLocale, preset: .progressiveLongDictation)"),
            "Ambient live should fall back to DictationTranscriber for locales/configurations rejected by SpeechTranscriber.",
        )
        assertTrue(
            source.contains("AssetInventory.status(forModules: candidate.modules)"),
            "Ambient live should choose a transcriber by AssetInventory status instead of assuming one preset works.",
        )
    }

    @Test
    fun `live async wait cancels task when timeout elapses`() {
        val source = swiftSource()
        val waitForLiveAsync = source.section(
            startMarker = "private func waitForLiveAsync<T>",
            endMarker = "private func liveSpeechErrorMessage",
        )

        assertTrue(
            waitForLiveAsync.contains("let task = Task"),
            "waitForLiveAsync should retain the Task handle so timeout can cancel the async operation.",
        )
        assertTrue(
            waitForLiveAsync.contains("task.cancel()"),
            "waitForLiveAsync should cancel the async operation when the blocking timeout elapses.",
        )
    }

    @Test
    fun `live cancel removes registry session only after async cancel is attempted`() {
        val source = swiftSource()
        val cancelFunction = source.section(
            startMarker = "@_cdecl(\"souz_macos_live_speech_cancel\")",
            endMarker = "@_cdecl(\"souz_macos_speech_string_free\")",
        )

        cancelFunction.assertOrdered(
            first = "try waitForLiveAsync",
            second = "LiveSpeechSessionRegistry.shared.remove(sessionId)",
            "Live cancel should keep the session registered until the async cancel operation has been attempted.",
        )
    }

    private fun swiftSource(): String {
        val candidates = listOf(
            Path.of("desktopApp/src/main/swift/MacOsSpeechBridge.swift"),
            Path.of("src/main/swift/MacOsSpeechBridge.swift"),
        )
        val sourcePath = candidates.firstOrNull(Files::exists)
            ?: error("MacOsSpeechBridge.swift was not found from test working directory.")
        return Files.readString(sourcePath)
    }

    private fun String.section(
        startMarker: String,
        endMarker: String,
    ): String {
        val start = indexOf(startMarker)
        assertTrue(start >= 0, "Swift source should contain section start marker: $startMarker")
        val contentStart = start + startMarker.length
        val end = indexOf(endMarker, contentStart)
        assertTrue(end >= 0, "Swift source should contain section end marker: $endMarker")
        return substring(contentStart, end)
    }

    private fun String.assertOrdered(
        first: String,
        second: String,
        message: String,
    ) {
        val firstIndex = indexOf(first)
        val secondIndex = indexOf(second)
        assertTrue(firstIndex >= 0, "Section should contain: $first")
        assertTrue(secondIndex >= 0, "Section should contain: $second")
        assertTrue(firstIndex < secondIndex, message)
    }
}
