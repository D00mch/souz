package ru.souz.service.telegram

import it.tdlight.jni.TdApi

internal fun userDisplayName(user: TdApi.User?): String {
    user ?: return "Unknown"
    val fullName = listOf(user.firstName.orEmpty().trim(), user.lastName.orEmpty().trim())
        .filter(String::isNotBlank)
        .joinToString(" ")
    if (fullName.isNotBlank()) return fullName

    val username = user.usernames?.activeUsernames?.firstOrNull()?.trim()
    if (!username.isNullOrEmpty()) return "@$username"

    val phoneNumber = user.phoneNumber.orEmpty()
    if (phoneNumber.isNotBlank()) return "+$phoneNumber"

    return user.id.toString()
}
