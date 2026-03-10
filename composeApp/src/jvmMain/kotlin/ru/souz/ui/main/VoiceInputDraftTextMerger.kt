package ru.souz.ui.main

internal fun mergeVoiceDraftIntoInputText(currentInput: String, voiceDraft: String): String {
    if (currentInput.isBlank()) return voiceDraft
    val separator = if (currentInput.endsWith('\n') || currentInput.endsWith(' ')) "" else "\n"
    return currentInput + separator + voiceDraft
}
