package ru.souz.backend.channels

import java.util.UUID

/** Every current [ChannelProvider]'s channelId is a chat UUID; malformed input parses to null. */
internal fun String.toChannelUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
