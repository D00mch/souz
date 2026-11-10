package ru.souz.backend.plugins

import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import java.nio.file.Files
import java.nio.file.Path

internal fun pluginJarUpload(path: Path, revision: Long) = MultiPartFormDataContent(formData {
    append("expectedRevision", revision.toString())
    append("jar", Files.readAllBytes(path), Headers.build {
        append(HttpHeaders.ContentType, "application/java-archive")
        append(HttpHeaders.ContentDisposition, "filename=untrusted-plugin-name.jar")
    })
})
