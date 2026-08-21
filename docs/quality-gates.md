# Quality gates

Souz keeps repository quality checks in the included `build-logic` build. Run
the fast gate from the repository root:

```bash
./gradlew souzGateFast
```

The fast lane is local-safe: its results remain meaningful in a dirty
checkout. Its checks are blocking locally and report-only in pull-request CI.
CI still fails the quality job when the gate cannot produce both required
reports, so checkout, build, or reporting failures do not look like policy
results.

## Checks

| ID | Contract | Remediation |
| --- | --- | --- |
| `repository-contracts` | The Gradle project set, root Module Map, module policies, pain-point indexes, local policy links, and registered check policy paths agree. | Repair the reported repository-relative policy path or update the owning policy with the reviewed module change. |
| `module-boundaries` | Direct production `ProjectDependency` edges match the explicit module and KMP source-set allowlist. Test dependencies are excluded. | Remove the edge or update the owning module policy and allowlist together when the architecture change is intentional. |

Both checks have `local-safe` authority and `blocking` enforcement. An
unexpected checker failure is reported as `error`, not as a pass or policy
failure.

Project dependencies declared in an unclassified configuration fail closed.
Test-only edges should use a standard test source-set configuration so the
gate can exclude them explicitly.

Local-link checks validate filesystem targets. Markdown fragment identifiers
are not part of the version 1 repository contract.

## Reports

Each run writes:

```text
build/reports/souz-quality/fast/gate-summary-v1.json
build/reports/souz-quality/fast/gate-summary.md
```

The JSON contract is defined by
[`config/quality/gate-summary-v1.schema.json`](../config/quality/gate-summary-v1.schema.json).
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
./gradlew souzGateFast
```
