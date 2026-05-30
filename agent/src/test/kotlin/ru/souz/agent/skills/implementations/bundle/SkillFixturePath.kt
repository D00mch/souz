package ru.souz.agent.skills.implementations.bundle

import java.nio.file.Path
import java.nio.file.Paths

fun skillFixturePath(slug: String): Path {
    val resource = checkNotNull(object {}.javaClass.getResource("/skills/$slug")) {
        "Missing skill fixture resource: /skills/$slug"
    }
    return Paths.get(resource.toURI())
}
