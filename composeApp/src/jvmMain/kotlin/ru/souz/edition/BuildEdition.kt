package ru.souz.edition

enum class BuildEdition {
    RU,
    EN;

    companion object {
        fun parse(raw: String?): BuildEdition = when (raw?.trim()?.lowercase()) {
            "en" -> EN
            else -> RU
        }
    }
}

object BuildEditionConfig {
    val current: BuildEdition = BuildEdition.parse(
        System.getProperty("souz.edition")
            ?: System.getenv("souz_EDITION")
            ?: "ru"
    )
}
