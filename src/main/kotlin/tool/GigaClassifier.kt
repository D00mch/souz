package com.dumch.tool

fun interface GigaClassifier {
    suspend fun classify(body: String): ToolCategory?
}

enum class ToolCategory {
    CODER,
    BROWSER,
    CONFIG,
    DESKTOP,
    IO,
}
