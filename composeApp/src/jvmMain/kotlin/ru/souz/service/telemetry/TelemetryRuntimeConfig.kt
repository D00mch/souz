package ru.souz.service.telemetry

data class TelemetryRuntimeConfig(
    val baseUrl: String,
    val registrationPath: String = DEFAULT_REGISTRATION_PATH,
    val batchPath: String = DEFAULT_BATCH_PATH,
    val appName: String = DEFAULT_APP_NAME,
    val keyAlgorithm: String = DEFAULT_KEY_ALGORITHM,
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    val autoFlushIntervalMs: Long = DEFAULT_AUTO_FLUSH_INTERVAL_MS,
    val autoFlushMaxBatches: Int = DEFAULT_AUTO_FLUSH_MAX_BATCHES,
    val manualFlushMaxBatches: Int = DEFAULT_MANUAL_FLUSH_MAX_BATCHES,
    val baseRetryDelayMs: Long = DEFAULT_BASE_RETRY_DELAY_MS,
) {
    companion object {
        const val DEFAULT_APP_NAME = "souz-desktop"
        const val DEFAULT_BASE_URL = "https://souz.app"
        const val DEFAULT_REGISTRATION_PATH = "/v1/installations/register"
        const val DEFAULT_BATCH_PATH = "/v1/metrics/batch"
        const val DEFAULT_KEY_ALGORITHM = "Ed25519"
        const val DEFAULT_BATCH_SIZE = 50
        const val DEFAULT_AUTO_FLUSH_INTERVAL_MS = 15_000L
        const val DEFAULT_AUTO_FLUSH_MAX_BATCHES = 3
        const val DEFAULT_MANUAL_FLUSH_MAX_BATCHES = 10
        const val DEFAULT_BASE_RETRY_DELAY_MS = 5_000L

        fun production(): TelemetryRuntimeConfig = TelemetryRuntimeConfig(baseUrl = DEFAULT_BASE_URL)
    }
}
