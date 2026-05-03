package ru.souz.backend.options.model

enum class OptionStatus(val value: String) {
    PENDING("pending"),
    ANSWERED("answered"),
    CANCELLED("cancelled"),
    EXPIRED("expired"),
}
