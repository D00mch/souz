package ru.souz.backend.common

/** Thrown when backend process configuration is invalid for the current server mode. */
class BackendConfigurationException(message: String) : RuntimeException(message)
