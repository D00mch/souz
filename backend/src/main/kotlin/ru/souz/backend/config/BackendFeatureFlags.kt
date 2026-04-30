package ru.souz.backend.config

import ru.souz.backend.common.BackendConfigurationException

data class BackendFeatureFlags(
    val wsEvents: Boolean = false,
    val streamingMessages: Boolean = false,
    val toolEvents: Boolean = false,
    val choices: Boolean = false,
    val durableEventReplay: Boolean = false,
) {
    companion object {
        fun load(source: BackendConfigSource = SystemBackendConfigSource): BackendFeatureFlags =
            BackendFeatureFlags(
                wsEvents = source.booleanValue(
                    envKey = "SOUZ_FEATURE_WS_EVENTS",
                    propertyKey = "souz.backend.feature.wsEvents",
                    default = false,
                ),
                streamingMessages = source.booleanValue(
                    envKey = "SOUZ_FEATURE_STREAMING_MESSAGES",
                    propertyKey = "souz.backend.feature.streamingMessages",
                    default = false,
                ),
                toolEvents = source.booleanValue(
                    envKey = "SOUZ_FEATURE_TOOL_EVENTS",
                    propertyKey = "souz.backend.feature.toolEvents",
                    default = false,
                ),
                choices = source.booleanValue(
                    envKey = "SOUZ_FEATURE_CHOICES",
                    propertyKey = "souz.backend.feature.choices",
                    default = false,
                ),
                durableEventReplay = source.booleanValue(
                    envKey = "SOUZ_FEATURE_DURABLE_EVENT_REPLAY",
                    propertyKey = "souz.backend.feature.durableEventReplay",
                    default = false,
                ),
            )
    }
}

internal fun BackendConfigSource.booleanValue(
    envKey: String,
    propertyKey: String,
    default: Boolean,
): Boolean {
    val rawValue = value(envKey, propertyKey)?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return default
    return when {
        rawValue.equals("true", ignoreCase = true) -> true
        rawValue.equals("false", ignoreCase = true) -> false
        else -> throw BackendConfigurationException(
            "Invalid boolean value '$rawValue' for $envKey / $propertyKey."
        )
    }
}
