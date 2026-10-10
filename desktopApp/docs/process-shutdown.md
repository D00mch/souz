# Process shutdown

## Invariants

Desktop shutdown has one coroutine-coordinated completion shared by concurrent and repeated callers. Once a caller owns shutdown, the body is non-cancellable. It cancels and joins application work before closing the local runtime, MCP manager, Telegram controller, shared provider clients, Giga client, and shutdown observer in order. Close failures are accumulated in execution order.

`AutoCloseable.close()` is only a blocking adapter for process and UI boundaries that cannot call the suspending API.

## Verification

Run `./gradlew :desktopApp:test --tests 'ru.souz.DesktopProcessResourcesTest'` and cover ordering, repeated shutdown, and failure aggregation.
