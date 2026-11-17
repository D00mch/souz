# State retention

The desktop composition root starts `DesktopStateCleanup` in the application scope and cancels it on disposal. Its filesystem work runs on `Dispatchers.IO`; process shutdown joins application work before closing other resources.

Desktop graph sessions and similarity vectors use JDBC SQLite in `~/.local/state/souz/desktop.db`. Session payloads retain the graph trace JSON format inside SQLite. Similarity search scans stored float vectors, retains the requested top results, and ranks them by `(1 + cosine) / 2`, with a default score threshold of 0.65 and at most 1,024 dimensions. Existing JSON sessions and Lucene indexes are unused, are not imported, and are left untouched. The SQLite index has separate refresh settings so legacy index metadata cannot suppress its initial rebuild. Shared SQLDelight persistence is separate.

Cleanup deletes only regular files directly in `~/.local/state/souz/logs` ending in `.log` and expired SQLite graph-session rows. Logs use last-modified time and expire strictly after seven days; graph sessions use their most recent save time and expire strictly after thirty days. Items at the cutoff remain. Symlinks and subdirectories are skipped; vectors, memory, models, Skills, Knowledge, and other state are outside this policy.

`.desktop-cleanup` stores the last cleanup attempt as epoch milliseconds. A file lock coordinates desktop processes. The timestamp is flushed before deletion, so partial failures do not trigger repeated scans on relaunch. Cleanup runs immediately when due, then at intervals of at least 24 hours, including across restarts. A relaunch waits only for the remaining interval. Individual scan/deletion failures are logged and retried on the next daily attempt.

Desktop Logback rollover retains 168 hourly general-log periods and seven daily telemetry periods, with the existing telemetry size cap. Log destination overrides keep their existing Logback behavior; the daily cleanup only visits the default desktop state root. Backend retention is independent.

Settings → Support → Clear local caches requests confirmation before emptying local `.log` files and deleting SQLite graph-session rows regardless of age. Manual cleanup follows the support-log directory configuration, including log destination overrides. Logs are truncated in place so open logging streams remain usable. New activity can write logs and save graph sessions again. Only direct regular log files and graph-session rows are cleared; legacy JSON sessions, Lucene indexes, symlinks, subdirectories and other application state are preserved. Missing directories need no cleanup. A partial failure is reported in settings and does not prevent attempting the remaining files.

Run `./gradlew :desktopApp:test --tests 'ru.souz.service.maintenance.DesktopStateCleanupTest'` for age boundaries, restart/running cadence, cancellation, and filesystem isolation.

Run `./gradlew :desktopApp:test --tests 'ru.souz.service.maintenance.DesktopLocalCacheCleanerTest'` for manual cleanup, configured log paths, open-appender continuity, partial failure, and filesystem isolation. Settings confirmation, cancellation, busy state, and retry are covered by `./gradlew :sharedUI:jvmTest`.
