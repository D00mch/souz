# Quality gates

Souz keeps repository quality checks in the included `build-logic` build. Run
the fast gate from the repository root:

```bash
./gradlew souzGateFast
```

The fast lane is local-safe: its results remain meaningful in a dirty
checkout. Repository, module, and source-set contracts are blocking locally
and in pull-request CI. Coroutine analysis is advisory. CI publishes the
quality summary and report artifacts even when a blocking check fails.

## Checks

| ID | Contract | Remediation |
| --- | --- | --- |
| `git-metadata` | The gate can identify the tested commit and worktree state. | Run the gate from the repository checkout and remove Git routing overrides that point outside it. |
| `repository-contracts` | The Gradle project set, root Module Map, module policies, pain-point indexes, local policy links, and registered check policy paths agree. | Repair the reported repository-relative policy path or update the owning policy with the reviewed module change. |
| `module-boundaries` | Direct production `ProjectDependency` edges match the explicit module and KMP source-set allowlist. Test dependencies are excluded. | Remove the edge or update the owning module policy and allowlist together when the architecture change is intentional. |
| `source-set-boundaries` | Production package declarations and references keep portable, core, backend, and shared UI source sets behind their reviewed host boundaries. Test source sets are excluded. | Move host-specific code to the owning platform or composition-root source set and expose a contract or host port where the inward module needs one. |
| `cancellation-propagation` | Suspend paths do not swallow `CancellationException`, including through `runCatching`. | Catch the expected exception type or rethrow cancellation immediately. |
| `coroutine-thread-local` | Every JVM `ThreadLocal` state declaration is reviewed explicitly. | Move the state into coroutine context, or suppress the reviewed declaration and propagate coroutine access with `asContextElement`. |
| `coroutine-monitor-use` | `synchronized`, `@Synchronized`, and `Collections.synchronized*` use inside suspend execution is reported for review. | Prefer `Mutex` inside suspend execution or keep monitor coordination behind an explicit non-suspending JVM boundary. |

All checks have `local-safe` authority. The three coroutine checks are advisory
and produce warnings; the other checks are blocking. An unexpected checker
failure is reported as `error`, not as a pass or policy failure.

Project dependencies declared in an unclassified configuration fail closed.
Test-only edges should use a standard test source-set configuration so the
gate can exclude them explicitly.

Local-link checks validate filesystem targets. Markdown fragment identifiers
are not part of the version 1 repository contract.

## Source-set analysis

The custom `SourceSetBoundaries` rule inspects production package declarations,
imports, and fully qualified references and enforces these boundaries:

Gradle supplies the rule with each configured Kotlin source root, source-set
owner, and main-compilation membership. Custom source-set names and directories
are classified by the build model; roots with overlapping ownership are rejected
as ambiguous.

- `commonMain` rejects JVM, Android, native-platform, Skiko/JNA, desktop-window,
  and Souz host-implementation references. AndroidX references fail closed:
  portable symbols are reviewed individually, and wildcard imports are rejected.
- Shared `:sharedUI` production source sets, including `commonJvmMain`, reject
  AWT/Swing, Skiko/JNA, desktop-only Compose window APIs, native model
  implementations, backend code, and desktop service or tool implementations.
  Portable `Dialog` and `Popup` APIs and ordinary JVM APIs remain allowed.
- `:graph-engine`, `:llms`, `:agent`, and `:skill-oauth-api` production code
  rejects Compose, UI, backend, native-model, host-DI, and host-service references.
- `:backend` production code rejects Compose, UI, host-DI, AWT/Swing, Skiko/JNA,
  and desktop service or tool references. Its reviewed `:native` dependency remains
  available.

JVM-only packages are denied from portable source sets. JVM and desktop
implementations in packages shared with portable contracts are reviewed as exact
symbols, so the shared contracts remain available without exposing host code.

The rule reports each forbidden reference's repository-relative path and line.
Gradle project-dependency checks remain the primary module authority; this rule
adds source-level diagnostics and protects boundaries inside allowed dependency
graphs.

## Coroutine analysis

Detekt runs one type-resolved analysis task for every JVM production and test
compilation. General built-in rule sets are disabled in
[`quality/detekt.yml`](../quality/detekt.yml); the Souz architecture and
coroutine rule sets are enabled. Detekt analysis has no baseline, so every
blocking or advisory finding remains visible.

The enabled built-in rule is `SuspendFunSwallowedCancellation`.

The custom `ThreadLocalInCoroutineCode` rule resolves declaration types and
requires a reviewed `@Suppress("ThreadLocalInCoroutineCode")` on each property,
parameter, local variable, or subclass that owns JVM thread-local state. When
that state is accessed from a coroutine, the reviewed implementation must also
propagate it with `asContextElement`.

The custom `MonitorInsideSuspendContext` rule resolves callable identity before
reporting `kotlin.synchronized`, `java.util.Collections.synchronized*`, or
`kotlin.jvm.Synchronized` inside suspend functions and suspend-typed lambdas.
Unrelated APIs with the same short names are ignored. Atomics, volatile fields,
and monitor coordination at non-suspending JVM or native boundaries are not
prohibited.

## Reports

Each run writes:

```text
build/reports/souz-quality/fast/gate-summary-v1.json
build/reports/souz-quality/fast/gate-summary.md
```

The JSON contract is defined by
[`quality/gate-summary-v1.schema.json`](../quality/gate-summary-v1.schema.json).
It records check versions, authority, enforcement, status,
repository-relative diagnostics, tested Git identity, pull-request SHAs when
supplied, dirty-worktree state, duration, and normalized SHA-256 evidence.
Each check's evidence path addresses its JSON subtree; its hash excludes the
duration and evidence fields to avoid self-reference and timing churn.

Normalized hashes exclude duration and contain no timestamps. Reports contain
no source bodies, stack traces, arbitrary environment values, or absolute
repository paths. The JSON and Markdown files are written before a failing
gate raises its Gradle error.

When changing the quality implementation, run:

```bash
./gradlew :build-logic:check
./gradlew souzGateFast --configuration-cache --configuration-cache-problems=fail
```
