# Quality gates

Souz keeps repository quality checks in the included `build-logic` build. Run
the fast gate from the repository root:

```bash
./gradlew souzGateFast
```

The fast lane is local-safe: its results remain meaningful in a dirty
checkout. Its checks are blocking locally and in pull-request CI. CI publishes
the quality summary and report artifacts even when the gate fails.

## Checks

| ID | Contract | Remediation |
| --- | --- | --- |
| `git-metadata` | The gate can identify the tested commit and worktree state. | Run the gate from the repository checkout and remove Git routing overrides that point outside it. |
| `repository-contracts` | The Gradle project set, root Module Map, module policies, pain-point indexes, local policy links, and registered check policy paths agree. | Repair the reported repository-relative policy path or update the owning policy with the reviewed module change. |
| `module-boundaries` | Direct production `ProjectDependency` edges match the explicit module and KMP source-set allowlist. Test dependencies are excluded. | Remove the edge or update the owning module policy and allowlist together when the architecture change is intentional. |
| `cancellation-propagation` | Suspend paths do not swallow `CancellationException`, including through `runCatching`. | Catch the expected exception type or rethrow cancellation immediately. |
| `coroutine-thread-local` | Coroutine-owned `ThreadLocal` state requires reviewed `asContextElement` propagation. | Move the state into coroutine context, or propagate every coroutine use and explicitly suppress the reviewed declaration. |
| `coroutine-monitor-use` | Direct `synchronized`, `@Synchronized`, and `Collections.synchronized*` use inside suspend functions and coroutine builders is reported for review. | Prefer `Mutex` inside suspend execution or keep monitor coordination behind an explicit non-suspending JVM boundary. |
| `coroutine-safety` | Coroutine code follows structured execution, cleanup, Flow-signature, delay, scope-receiver, and test-lifecycle rules. | Apply the Detekt diagnostic at the reported repository-relative location. |

All checks have `local-safe` authority. `coroutine-monitor-use` is advisory and
produces warnings; the other checks are blocking. An unexpected checker failure
is reported as `error`, not as a pass or policy failure.

Project dependencies declared in an unclassified configuration fail closed.
Test-only edges should use a standard test source-set configuration so the
gate can exclude them explicitly.

Local-link checks validate filesystem targets. Markdown fragment identifiers
are not part of the version 1 repository contract.

## Coroutine analysis

Detekt runs type-resolved analysis for every JVM production and test
compilation. Non-coroutine rule sets are disabled in
[`quality/detekt.yml`](../quality/detekt.yml).

The enabled built-in rules are:

- `CoroutineLaunchedInTestWithoutRunTest`
- `GlobalCoroutineUsage`
- `SleepInsteadOfDelay`
- `SuspendFunInFinallySection`
- `SuspendFunSwallowedCancellation`
- `SuspendFunWithCoroutineScopeReceiver`
- `SuspendFunWithFlowReturnType`

`InjectDispatcher` and `RedundantSuspendModifier` remain disabled because they
enforce testability or style rather than coroutine correctness. The blocking
custom `ThreadLocalInCoroutineCode` rule requires a reviewed suppression when
thread-local propagation is deliberately retained. The advisory
`MonitorInsideSuspendContext` rule reports monitor
coordination directly inside suspend functions and coroutine builders. Atomics,
volatile fields, and monitor coordination at non-suspending JVM or native
boundaries are not prohibited.

Existing findings live in module-scoped files under `quality/detekt-baselines/`,
preventing a finding in one project from suppressing an identical Detekt ID in
another.
Advisory findings are excluded from the baseline so they remain visible.
After reviewing a deliberate debt change, regenerate it explicitly:

```bash
./gradlew updateSouzCoroutineBaseline
```

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
