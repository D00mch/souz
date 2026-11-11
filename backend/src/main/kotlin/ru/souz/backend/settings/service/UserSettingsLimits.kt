package ru.souz.backend.settings.service

import ru.souz.backend.http.invalidV1Request

internal object UserSettingsLimits {
    const val MAX_BODY_BYTES = 65_536
    const val MAX_ENABLED_TOOLS = 512
    const val MAX_ENABLED_TOOL_BYTES = 16_384

    fun validateEnabledTools(names: Collection<String>?) {
        if (names == null) return
        if (names.size > MAX_ENABLED_TOOLS) {
            throw invalidV1Request("enabledTools must contain at most $MAX_ENABLED_TOOLS names.")
        }
        var remaining = MAX_ENABLED_TOOL_BYTES
        for (name in names) {
            // Reject huge stored strings before allocating their UTF-8 representation.
            if (name.length > remaining) throw namesTooLarge()
            remaining -= name.toByteArray(Charsets.UTF_8).size
            if (remaining < 0) throw namesTooLarge()
        }
    }

    private fun namesTooLarge() =
        invalidV1Request("enabledTools names must total at most $MAX_ENABLED_TOOL_BYTES UTF-8 bytes.")
}
