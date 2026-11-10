package ru.souz.mcp

/** Process-wide safety limits; owner settings cannot raise them. */
internal object McpLimits {
    const val RESPONSE_BYTES = 1_048_576L
    const val CATALOG_BYTES = 4_194_304L
    const val TOOLS = 10_000
    const val NAME_BYTES = 128
    const val DESCRIPTION_CHARS = 2_000
    const val SCHEMA_BYTES = 16_384
    const val JSON_DEPTH = 64
    const val CONTROL_TIMEOUT_MS = 500L
    const val LOCAL_CLOSE_TIMEOUT_MS = 1_000L
    const val REQUEST_TIMEOUT_MS = 60_000L
    const val PRELOAD_TIMEOUT_MS = 10_000L
    val SERVER_ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
}
