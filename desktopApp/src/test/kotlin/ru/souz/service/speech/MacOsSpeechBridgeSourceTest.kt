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

    private fun swiftSource(): String {
        val candidates = listOf(
            Path.of("desktopApp/src/main/swift/MacOsSpeechBridge.swift"),
            Path.of("src/main/swift/MacOsSpeechBridge.swift"),
        )
        val sourcePath = candidates.firstOrNull(Files::exists)
            ?: error("MacOsSpeechBridge.swift was not found from test working directory.")
        return Files.readString(sourcePath)
    }
}
