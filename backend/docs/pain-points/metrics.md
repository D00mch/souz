# Metrics accounting

Repository commits own execution outcomes. Observe successful active-to-terminal writes inside the JDBC dispatcher after commit; observing service responses can double-count idempotent retries or lose cancellation finalization. Runtime state and durable replay remain independent of meters.

One backend registry owns process counters, JVM binders and pool instrumentation. Configure Hikari's tracker before constructing its datasource, and close meters after application work and storage. Active execution gauges read shared PostgreSQL state; aggregate replicas with `max`. Other counters and work gauges belong to one instance.

Keep timing monotonic and process-local. Exclude the union of client-result waits and option waits from processing, retaining a separate option clock through concurrent client waits. Preserve stream usage high-water marks across empty/repeated/decreasing snapshots. Finish tool accounting in `finally`; typed client outcomes belong to their transport rather than generic helper telemetry.

Keep channel eviction atomic through `DROP_OLDEST` and its undelivered callback. Only actual public live events count as losses; replay/control events and bot observers do not. Bound model, route, category and exception labels as described in the [metric reference](../metrics.md).

Verify with `./gradlew :backend:test :agent:test souzGateFast` and `./gradlew souzDuplicationCheck`. Existing execution, option, provider, client-tool and event-bus tests cover accounting at their normal boundaries; `BackendMetricsE2eTest` checks the production scrape and shared load.
