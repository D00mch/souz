# Quality gates

Souz keeps repository quality checks in the included `build-logic` build. Run
the fast gate from the repository root:

```bash
./gradlew souzGateFast
```

The fast lane is local-safe: its results remain meaningful in a dirty
checkout. Repository and module contracts are blocking locally and in
pull-request CI. Coroutine analysis is advisory. CI publishes the quality
summary and report artifacts even when a blocking check fails.

## Checks

| ID | Contract | Remediation |
| --- | --- | --- |
| `git-metadata` | The gate can identify the tested commit and worktree state. | Run the gate from the repository checkout and remove Git routing overrides that point outside it. |
| `repository-contracts` | The Gradle project set, root Module Map, module policies, pain-point indexes, local policy links, and registered check policy paths agree. | Repair the reported repository-relative policy path or update the owning policy with the reviewed module change. |
| `module-boundaries` | Direct production `ProjectDependency` edges match the explicit module and KMP source-set allowlist. Test dependencies are excluded. | Remove the edge or update the owning module policy and allowlist together when the architecture change is intentional. |
| `cancellation-propagation` | Suspend paths do not swallow `CancellationException`, including through `runCatching`. | Catch the expected exception type or rethrow cancellation immediately. |
| `coroutine-thread-local` | Every JVM `ThreadLocal` state declaration is reviewed explicitly. | Move the state into coroutine context, or suppress the reviewed declaration and propagate coroutine access with `asContextElement`. |
| `coroutine-monitor-use` | `synchronized`, `@Synchronized`, and `Collections.synchronized*` use inside suspend execution is reported for review. | Prefer `Mutex` inside suspend execution or keep monitor coordination behind an explicit non-suspending JVM boundary. |
| `ci-exact-checkout` | The expensive lane is authoritative only for a clean GitHub Actions checkout whose `HEAD` matches `GITHUB_SHA`. | Remove checkout mutations and ensure the workflow tests the recorded SHA. |
| `duplicate-code` | Production and test duplicated-token totals match their reviewed jscpd baselines. | Remove the new duplication, or run the explicit baseline update task and review the baseline change. |

Fast checks have `local-safe` authority. The three coroutine checks are
advisory and produce warnings; the other fast checks are blocking. Duplicate
code is blocking with `ci-exact-checkout` authority. An unexpected checker
failure is reported as `error`, not as a pass or policy failure.

Project dependencies declared in an unclassified configuration fail closed.
Test-only edges should use a standard test source-set configuration so the
gate can exclude them explicitly.

Local-link checks validate filesystem targets. Markdown fragment identifiers
are not part of the version 1 repository contract.

## Coroutine analysis

Detekt runs one type-resolved analysis task for every JVM production and test
compilation. Non-coroutine rule sets are disabled in
[`quality/detekt.yml`](../quality/detekt.yml). Reviewed findings live in
module- and analysis-scoped files under `quality/detekt-baselines/`; they are
suppressed so the gate summary shows only new findings. Refresh the reviewed
baseline explicitly after resolving or accepting debt:

```bash
./gradlew updateSouzCoroutineBaseline
```

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

## Duplicate code

jscpd `5.0.16` is pinned by `quality/package-lock.json`. Install it and run the
production/test ratchet with:

```bash
npm ci --prefix quality
./gradlew souzDuplicationCheck
```

Production clones require at least 15 lines and 100 tokens. Test clones require
at least 20 lines and 120 tokens. The reviewed thresholds in
[`quality/duplication-baseline.json`](../quality/duplication-baseline.json) store
duplicated-token totals separately for both scopes. Token totals are insensitive
to whitespace and line-number drift. Growth and stale reductions both require a
reviewed baseline update:

```bash
./gradlew updateSouzDuplicationBaseline
```

The local task compares the same inputs but reports `not_authoritative` for the
exact-checkout preflight. Pull-request CI requires a clean checkout whose
`HEAD` matches `GITHUB_SHA`. Local HTML reports are written to
`build/tmp/souzDuplicationCheck/{production,tests}/jscpd-report.html`. They
contain source fragments and are not uploaded as quality evidence.

## Coverage

Kover JVM coverage reports are generated in the same Gradle task graph as the
required JVM test suite:

```bash
./gradlew test :sharedLogic:allTests :sharedUI:allTests \
  :koverXmlReport :koverHtmlReport koverLog -Psouz.coverage --no-parallel
```

Report generation and the presence of `build/reports/kover/report.xml` and
`build/reports/kover/html/index.html` are blocking pull-request requirements.
CI compares the root aggregate and module-local line coverage with the reviewed
values in [`quality/coverage-baseline.json`](../quality/coverage-baseline.json)
and publishes baseline, current, and percentage-point delta columns in the job
summary. Generated resource classes matching
`*.generated.resources.*` are excluded from every report. No Kover verification
rule or minimum coverage threshold is configured; the baseline is a comparison
reference. Kover is activated only with
`-Psouz.coverage`, so `souzGateFast` and ordinary local builds are not
instrumented.

The root report merges every product module. Kover covers common and JVM source
sets; non-JVM targets are outside this report. Each module row covers that
module's classes using its own JVM test tasks, while the root aggregate also
includes coverage produced across module boundaries.

## Reports

Each run writes:

```text
build/reports/souz-quality/fast/gate-summary-v1.json
build/reports/souz-quality/fast/gate-summary.md
```

The duplication lane writes the same v1 contract under
`build/reports/souz-quality/expensive/`. Kover writes XML and HTML under
`build/reports/kover/`.

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
npm ci --prefix quality
./gradlew souzDuplicationCheck
./gradlew test :sharedLogic:allTests :sharedUI:allTests :koverXmlReport :koverHtmlReport :koverLog -Psouz.coverage --no-parallel
```
