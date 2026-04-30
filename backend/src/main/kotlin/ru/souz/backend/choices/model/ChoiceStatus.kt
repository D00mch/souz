package ru.souz.backend.choices.model

enum class ChoiceStatus(val value: String) {
    PENDING("pending"),
    ANSWERED("answered"),
    CANCELLED("cancelled"),
    EXPIRED("expired"),
}
