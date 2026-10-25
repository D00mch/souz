# Quality gates

Souz keeps repository quality checks in the included `build-logic` build. Run
the fast gate from the repository root:

```bash
./gradlew souzGateFast
```

The fast lane is local-safe: its results remain meaningful in a dirty
checkout. Repository and module contracts are blocking locally and in
pull-request CI. Constant-membership allocation checks are blocking; coroutine,
getter-computation, and layered-mutability analysis are advisory. CI publishes the quality
summary and report artifacts even when a blocking check fails.

## Checks

| ID | Contract | Remediation |
| --- | --- | --- |
| `git-metadata` | The gate can identify the tested commit and worktree state. | Run the gate from the repository checkout and remove Git routing overrides that point outside it. |
| `repository-contracts` | The Gradle project set, root Module Map, module policies, pain-point sections, local policy links, and registered check policy paths agree. | Repair the reported repository-relative policy path or update the owning policy with the reviewed module change. |
| `module-boundaries` | Direct production `ProjectDependency` edges match the explicit module and KMP source-set allowlist. Test dependencies are excluded. | Remove the edge or update the owning module policy and allowlist together when the architecture change is intentional. |
| `cancellation-propagation` | Suspend paths do not swallow `CancellationException`, including through `runCatching`. | Catch the expected exception type or rethrow cancellation immediately. |
| `coroutine-thread-local` | Every JVM `ThreadLocal` state declaration is reviewed explicitly. | Move the state into coroutine context, or suppress the reviewed declaration and propagate coroutine access with `asContextElement`. |
| `coroutine-monitor-use` | `synchronized`, `@Synchronized`, and `Collections.synchronized*` use inside suspend execution is reported for review. | Prefer `Mutex` inside suspend execution or keep monitor coordination behind an explicit non-suspending JVM boundary. |
| `temporary-collection-membership` | Constant collections are not constructed only for immediate membership in executable bodies. | Use comparisons, `when`, or a stored immutable collection, preserving equality and evaluation semantics. |
| `repeated-getter-computation` | Known allocations and collection traversal in computed getters are reported for review. | Review the caller or evaluation strategy; caching can change timing and identity. |
| `layered-mutability` | Stored mutable collections containing mutable records or collections are reported for ownership review. | Consider immutable entry replacement while preserving existing coordination. |
| `ci-exact-checkout` | The expensive lane is authoritative only for a clean GitHub Actions checkout whose `HEAD` matches `GITHUB_SHA`. | Remove checkout mutations and ensure the workflow tests the recorded SHA. |
| `duplicate-code` | Production and test duplicated-token totals match their reviewed jscpd baselines. | Remove the new duplication, or run the explicit baseline update task and review the baseline change. |

Fast checks have `local-safe` authority. The three coroutine checks and the
getter-computation and layered-mutability checks are advisory and produce
warnings; the other fast checks are blocking. Duplicate
code is blocking with `ci-exact-checkout` authority. An unexpected checker
failure is reported as `error`, not as a pass or policy failure.
RepoWise pull-request quality is advisory and compares the base with the
squash-equivalent PR head.

Project dependencies declared in an unclassified configuration fail closed.
Test-only edges should use a standard test source-set configuration so the
gate can exclude them explicitly.

Local-link checks validate filesystem targets. Markdown fragment identifiers
are not part of the version 1 repository contract.

The root Module Map indexes module policies. Each module policy indexes
pain-point topics in a required `Pain points` section of `AGENTS.md`, with
topic files directly under the module's `docs/`. Every Markdown file directly
under a module's `docs/` must have a link in that module policy's `Pain points`
section; images and links in other sections do not satisfy this contract.
Local-link checks cover `AGENTS.md` and Markdown files directly under `docs/`.

## Coroutine analysis

Detekt runs one type-resolved analysis task for every JVM production and test
compilation. Only the selected coroutine and `souz-review` rules are enabled in
[`quality/detekt.yml`](../quality/detekt.yml). Reviewed coroutine findings live in
module- and analysis-scoped files under `quality/detekt-baselines/`; they are
suppressed so the gate summary shows only new findings. Refresh the reviewed
baseline explicitly after resolving or accepting debt:

```bash
./gradlew updateSouzCoroutineBaseline
```

This task filters out code-review findings. It cannot baseline the blocking
membership rule or hide advisory getter and mutability findings.

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

## Code-review analysis

The `souz-review` rules resolve callable and type identities, including import
aliases and inferred types. Findings are explicitly routed into the fast-gate
summary because Detekt itself runs with `ignoreFailures=true`. Missing or
malformed reports produce errors even for advisory checks. The summary-v1
contract and existing coroutine check IDs remain unchanged.

`TemporaryCollectionForMembership` reports standard `setOf`, `listOf`, and
`arrayOf` calls immediately consumed by standard `in`, `!in`, or `contains`,
including `when` conditions. Any nonempty number of literal, enum-entry, or
`const val` arguments qualifies. Functions, getters, and callbacks are covered;
stored collections, ranges, spreads, dynamic arguments, unrelated APIs, and
one-time initializers outside callbacks are excluded. Diagnostics describe the
temporary construction without asserting how frequently the code executes.
Manual fixes must preserve candidate evaluation count, null handling, and
collection equality semantics; stored collections suit cases where comparisons
would change semantics or obscure a larger lookup table.

`RepeatedComputationInGetter` reports known tuple/collection-factory allocations
over stable scalar inputs and standard collection transformations or predicate
scans over stable read-only references. Supported operations are `Pair`,
`Triple`, `to`, the three membership factories, `map`, `mapNotNull`, `flatMap`,
`filter`, `filterNot`, `associate`, `associateBy`, `associateWith`, `groupBy`, and
predicate forms of `count`, `any`, `all`, `none`, `first`, and `firstOrNull`.
Mutable inputs, arrays, explicitly mutable collection types, delegates, custom
getters, unknown calls, and external-state reads are excluded, as are cheap forwarding
and arithmetic. Returning a callback does not execute its body during getter
access. Read-only references do not establish deep immutability. A finding
does not prove frequent access or that caching is safe: caching changes
evaluation and exception timing, identity, and work for unused properties.

`LayeredMutability` examines stored mutable lists, sets, and maps, including
constructor properties, Kotlin mutable interfaces, and supported standard JVM
implementations such as `LinkedHashMap` and `ConcurrentHashMap`. It reports
data-class values/elements with `var` properties and known nested mutable
collections, including through read-only collection type arguments. Map values
are inspected; keys, unknown types, read-only outer collections, and local
builders are excluded. This targeted rule is used instead of
`DataClassShouldBeImmutable`: the built-in rule flags every data-class `var`
without connecting it to collection ownership and misses nested collections.

Code-review checks have no reviewed baseline. Intentional exceptions use a
rule-specific suppression, for example `@Suppress("LayeredMutability")`, with
a nearby ownership explanation. Resource registries and buffers may retain
their existing design after explicit review. A finding is not proof of a race
or a performance improvement; it does not justify replacing a coroutine mutex
with a concurrent map. These rules provide no automatic corrections.

Code-review findings are neither baselined nor automatically rewritten.
Collection scans use explicit calls where evaluation must stay live. Client
runtime records, quota windows, graph transitions, streaming response state and
event-subscriber membership use entry replacement under their existing ownership
and coordination boundaries. Staged-file state and the memory UI model lookup
remain advisory candidates for ownership review.

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

The expensive-lane summary shows the reviewed baseline, current duplicated-token
total, and signed delta for production and tests.

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

## RepoWise pull-request quality

Pull-request CI installs the version of RepoWise pinned in
[`quality/repowise-requirements.txt`](../quality/repowise-requirements.txt),
checks out full Git history, and builds deterministic indexes for the PR base
and head without an LLM, saved credentials, editor integration, or telemetry.

Pull requests are squash-merged. CI checks out GitHub's PR merge commit, uses its
first parent as the base, verifies its second parent against the event's PR head,
then grafts it onto the base as one commit. The merge tree includes base changes
missing from a stale PR branch, while intermediate PR commits do not affect
RepoWise health scores.

The advisory comparison reports base-to-head changes in average and hotspot
defect health, worst-performer health, average and hotspot maintainability,
and average and hotspot performance. KPI regressions appear in the job summary
and uploaded artifacts but do not fail CI. Only execution errors, invalid
reports, or missing or empty artifacts fail the RepoWise job.

The same job runs `repowise risk` over the grafted base-to-merge revision range
and publishes the PR's change-risk classification, percentile, size, spread,
and main risk drivers. Change risk is advisory. The risk model keeps a
200-commit baseline. Global refactoring targets are intentionally excluded
from PR runs.

## Qodana advisory analysis

Qodana Community provides an independent advisory analyzer alongside the
repository's blocking quality gates. Its configuration lives at
[`quality/qodana.yaml`](../quality/qodana.yaml), and GitHub Actions runs it
through the reusable [`Qodana`](../.github/workflows/qodana.yml) workflow.

[`CI-Tests`](../.github/workflows/ci.yml) calls Qodana for pull requests, placing
its built-in summary and full report artifact on the same Actions run page.
Qodana also runs on pushes to `main` and manual dispatch. It uses the JVM
Community linter with JDK 21, pull-request mode, `--config quality/qodana.yaml`,
and GitHub caches. Comments and API annotations are disabled; reporting uses
read-only repository permissions and supports fork PRs after any required
workflow approval.

Qodana findings are advisory. The configuration has no baseline,
`failureConditions`, fail threshold, or aggregate quality-score gate. Analyzer
failures, invalid configuration, infrastructure failures, and missing or empty
SARIF reports still fail the workflow. Missing reports are noted in the run
summary. The workflow does not apply or push automatic fixes.

Qodana does not require a `QODANA_TOKEN`, Qodana Cloud account, or paid
features. It relies on the repository `.gitignore` for generated and ignored
files; add Qodana exclusions only for tracked generated or vendored paths that
the analyzer must skip.

## RepoWise maintenance guidance

The [`RepoWise Maintenance`](../.github/workflows/repowise-maintenance.yml)
workflow runs weekly on the default branch and can also be started manually.
Its advisory `RepoWise Refactoring and Performance` job builds one full-history
index and publishes a downloadable maintenance artifact rather than adding
repository-wide advice to every pull request.

The artifact contains a detailed Markdown report with ranked targets, concrete
plans, evidence, blast radius, inferred validation commands and tests, and
causal production/tooling performance opportunities. The full structured JSON
includes every refactoring plan and performance opportunity, including
test-only findings. RepoWise optimization strategies and safety rationales are
reported when the analyzer can infer them; opportunities without a safe plan
remain explicit investigation targets.

An indexed local checkout can produce the source JSON with:

```bash
repowise health . --no-workspace --format json
repowise health . --no-workspace --refactoring-targets --format json
```

## Reports

Each run writes:

```text
build/reports/souz-quality/fast/gate-summary-v1.json
build/reports/souz-quality/fast/gate-summary.md
```

The duplication lane writes the same v1 contract under
`build/reports/souz-quality/expensive/`. Kover writes XML and HTML under
`build/reports/kover/`. Pull-request RepoWise reports are under
`build/reports/repowise/`. Scheduled/manual maintenance reports are under
`build/reports/repowise-maintenance/`.

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
