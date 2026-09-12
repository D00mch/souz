package ru.souz.backend.common

private val identifierControlCharacters = Regex("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]")

internal fun String.sanitizedIdentifier(): String = take(128).replace(identifierControlCharacters, "_")