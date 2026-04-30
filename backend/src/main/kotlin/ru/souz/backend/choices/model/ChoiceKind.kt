package ru.souz.backend.choices.model

enum class ChoiceKind(val value: String) {
    TEXT_EDIT_VARIANT("text_edit_variant"),
    TELEGRAM_RECIPIENT("telegram_recipient"),
    FILE_CANDIDATE("file_candidate"),
    TOOL_CONFIRMATION("tool_confirmation"),
    GENERIC_SELECTION("generic_selection"),
}
