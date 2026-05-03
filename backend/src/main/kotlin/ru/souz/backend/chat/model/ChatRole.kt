package ru.souz.backend.chat.model

enum class ChatRole(val value: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system"),
    TOOL("tool"),
}
