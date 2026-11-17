# State retention

The desktop composition root starts `DesktopStateCleanup` in the application scope and cancels it on disposal. Its filesystem work runs on `Dispatchers.IO`; process shutdown joins application work before closing other resources.

Cleanup deletes only regular files directly in `~/.local/state/souz/logs` ending in `.log` and `sessions` ending in `.json`. Retention uses last-modified time: logs strictly older than seven days and graph sessions strictly older than thirty days expire. Files at the cutoff remain. Symlinks and subdirectories are skipped; models, databases, indexes, Skills, Knowledge, and other state are outside this policy.

`.desktop-cleanup` stores the last cleanup attempt as epoch milliseconds. A file lock coordinates desktop processes. The timestamp is flushed before deletion, so partial failures do not trigger repeated scans on relaunch. Cleanup runs immediately when due, then at intervals of at least 24 hours, including across restarts. A relaunch waits only for the remaining interval. Individual scan/deletion failures are logged and retried on the next daily attempt.

Desktop Logback rollover retains 168 hourly general-log periods and seven daily telemetry periods, with the existing telemetry size cap. Log destination overrides keep their existing Logback behavior; the daily cleanup only visits the default desktop state root. Backend retention is independent.

Settings → Support → Clear local caches requests confirmation before emptying local `.log` files and deleting saved graph-session `.json` files regardless of age. Manual cleanup follows the support-log directory configuration, including log destination overrides. Logs are truncated in place so open logging streams remain usable. New activity can write logs and save graph sessions again. Only direct regular files are cleared; symlinks, subdirectories and other application state are preserved. Missing directories need no cleanup. A partial failure is reported in settings and does not prevent attempting the remaining files.

Run `./gradlew :desktopApp:test --tests 'ru.souz.service.maintenance.DesktopStateCleanupTest'` for age boundaries, restart/running cadence, cancellation, and filesystem isolation.

Run `./gradlew :desktopApp:test --tests 'ru.souz.service.maintenance.DesktopLocalCacheCleanerTest'` for manual cleanup, configured log paths, open-appender continuity, partial failure, and filesystem isolation. Settings confirmation, cancellation, busy state, and retry are covered by `./gradlew :sharedUI:jvmTest`.
