# Quality gates

Run the fast gate from the repository root:

```bash
./gradlew souzGateFast
```

The fast gate has `local-safe` authority, including in dirty checkouts.
Git metadata, repository contracts, production module boundaries, and
temporary collection membership are blocking. Coroutine, getter-computation,
and layered-mutability findings are advisory warnings. Checker failures,
including missing or malformed Detekt reports, are errors even for advisory
checks. See [check definitions](../build-logic/src/main/kotlin/ru/souz/build/quality/QualityModel.kt).

## Repository and module policy

Keep the root `Module Map` in sync with Gradle projects. Each module policy
needs a `Pain points` heading and a link there to every Markdown topic
directly under its `docs/`. The v1 local-link check validates filesystem
targets from policy files and Markdown files directly under their adjacent
`docs/` directories.
See [RepositoryContracts](../build-logic/src/main/kotlin/ru/souz/build/quality/RepositoryContracts.kt).

Direct production dependencies must follow the [module allowlist](../AGENTS.md#production-dependency-boundaries).
Unclassified project-dependency configurations fail closed. Use standard
test configurations for test-only dependencies.

## Kotlin analysis

[Detekt configuration](../quality/detekt.yml) selects type-resolved checks
for JVM production and test compilations. See the
[custom rules](../build-logic/detekt-rules/src/main/kotlin/ru/souz/build/quality/detekt)
for exact matches.

- Propagate cancellation, review JVM `ThreadLocal` declarations explicitly,
  and use `asContextElement` for coroutine access to reviewed thread-local
  state. Prefer `Mutex` within suspend execution and keep JVM monitors at
  explicit non-suspending boundaries.
- When replacing temporary constant collections used for membership, preserve
  candidate evaluation count, null handling, and equality semantics.
- Review computed getters for repeated work and stored mutable collections for
  ownership. Preserve evaluation timing and coordination when changing them.

Review changes to the module- and analysis-scoped
[coroutine baselines](../quality/detekt-baselines) after resolving or accepting
findings. Code-review findings are ineligible for baselining. Use a
rule-specific suppression with a nearby rationale for an intentional exception.

```bash
./gradlew updateSouzCoroutineBaseline
```

## Duplicate code

```bash
npm ci --prefix quality
./gradlew souzDuplicationCheck
```

The blocking duplication ratchet has `ci-exact-checkout` authority in a clean
GitHub Actions checkout whose `HEAD` matches `GITHUB_SHA`. Local runs compare
the same inputs and mark the preflight `not_authoritative`. Both increases
and decreases in duplicated-token totals require review. Regenerate the
[production and test baselines](../quality/duplication-baseline.json) with
`./gradlew updateSouzDuplicationBaseline`. See the
[ratchet](../build-logic/src/main/kotlin/ru/souz/build/quality/DuplicationRatchet.kt)
for thresholds and the
[duplication task](../build-logic/src/main/kotlin/ru/souz/build/quality/SouzDuplicationTask.kt)
for report locations. Keep HTML reports local because they contain source
fragments.

## Coverage

```bash
./gradlew test :koverXmlReport :koverHtmlReport koverLog -Psouz.coverage --no-parallel
```

PR CI requires JVM tests and both Kover reports. The
[coverage baseline](../quality/coverage-baseline.json) is a comparison
reference without a minimum threshold. The aggregate includes coverage across
module boundaries. Module rows measure each module's classes with its own
tests. `-Psouz.coverage` enables instrumentation. Integration-bearing test
tasks are untracked and execute whenever selected.

## PR advisory analysis

[PR CI](../.github/workflows/ci.yml) runs RepoWise on the base and
squash-equivalent merge tree using deterministic indexes without an LLM,
saved credentials, editor integration, or telemetry. KPI regressions and
change risk are advisory. Repository-wide refactoring and performance
recommendations are published separately by
[RepoWise Maintenance](../.github/workflows/repowise-maintenance.yml).

[Qodana Community](../.github/workflows/qodana.yml) runs on PRs, main pushes,
and manual dispatch without a cloud token. Findings are advisory and the
workflow disables direct comments, annotations, and automatic fixes.
[Qodana configuration](../quality/qodana.yaml) relies on `.gitignore` for
ignored paths. Reserve additional exclusions for tracked generated or
vendored files. For both analyzers, execution and report failures can fail
the job.

## Pull-request advisory publishing

The [publisher workflow](../.github/workflows/quality-advisory.yml) must
exist on the default branch. It runs after PR CI completes, including
failed runs, and updates one bot-owned comment and a `Quality advisory`
check. Missing or malformed inputs are shown as unavailable. Annotations
are limited to changed files, and the advisory leaves required CI checks
independent. See the [publisher](../quality/pr_advisory.cjs) for rendering
and output limits.

Analysis jobs have read-only repository access. The publisher executes
trusted default-branch code and reads untrusted artifacts as data outside
the checkout. Keep permissions scoped to repository and Actions reads,
plus checks and PR writes. The publisher resolves PR identities through
GitHub, supports forks, skips closed PRs, outdated heads, and older runs,
and escapes artifact text before rendering it.

## Reports

Both gate lanes write `gate-summary-v1.json` and `gate-summary.md` under
`build/reports/souz-quality/{fast,expensive}/` before raising a gate failure.
[CI](../.github/workflows/ci.yml) publishes the gate evidence even on failure.
Keep check IDs stable and preserve the
[v1 schema](../quality/gate-summary-v1.schema.json). Diagnostics and evidence
paths are repository-relative. Normalized SHA-256 hashes exclude durations
and evidence fields. Keep timestamps, source bodies, stack traces, arbitrary
environment values, and absolute paths out of the gate reports.
See the [report writer](../build-logic/src/main/kotlin/ru/souz/build/quality/QualityReport.kt).

## Verification

When changing quality tooling, run the affected checks above and the
[build-logic verification](../build-logic/AGENTS.md#verification).
For publisher changes, run `node --test quality/pr_advisory_test.cjs`.
