# Metrics accounting

Count active-to-terminal execution transitions and exhausted history fragments in the repositories' post-commit callbacks inside the JDBC dispatcher. Request retries and replay leave counters unchanged. Runtime state and durable replay remain independent of meters. [Execution writes](../src/main/kotlin/ru/souz/backend/storage/postgres/PostgresAgentExecutionRepository.kt) · [History writes](../src/main/kotlin/ru/souz/backend/storage/postgres/PostgresHistoryMemoryRepository.kt)

Use one application-owned registry for backend, JVM, Hikari and job meters. Keep queue SQL in `:job-impl`, install Hikari's tracker before creating the datasource, scrape on `Dispatchers.IO` for JDBC reads, and close meters after application work and storage. Aggregate shared PostgreSQL gauges with `max` per database. Keep labels bounded and free of request data. [Meter registration](../src/main/kotlin/ru/souz/backend/metrics/BackendMetrics.kt) · [Meter semantics, label rules and PromQL](metrics.md)

Account for compiled tool failures before Skill helpers turn them into result messages. Finish accounting in `finally` and exclude wrapped tools from kernel telemetry. Client transports own their typed outcomes. Keep live-event eviction atomic in the channel and count failed command `trySend` calls directly. [Event bus](../src/main/kotlin/ru/souz/backend/events/bus/AgentEventBus.kt)

For runtime changes, run `./gradlew :backend:test :agent:test souzGateFast` with Docker and `./gradlew souzDuplicationCheck`. [Production scrape test](../src/test/kotlin/ru/souz/backend/e2e/BackendMetricsE2eTest.kt)
