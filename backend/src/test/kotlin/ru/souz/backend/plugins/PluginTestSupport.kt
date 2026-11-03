package ru.souz.backend.plugins

import ru.souz.backend.attachments.AttachmentMinio

/** A private bucket and scoped credentials on the shared real S3-compatible test service. */
internal fun pluginArtifactConfig(): S3PluginArtifactConfig = AttachmentMinio.config().let {
    S3PluginArtifactConfig(it.endpoint, it.bucket, it.accessKey, it.secretKey)
}
