# Quality tooling

This directory stores repository-owned quality-tool configuration, reviewed
baselines, schemas, and report helpers. Blocking repository quality gates are
implemented in Gradle build logic and documented in
[`docs/quality-gates.md`](../docs/quality-gates.md).

## Contents

- `detekt.yml` enables coroutine and code-review analysis for the fast gate.
  `detekt-baselines/` stores reviewed coroutine findings only; code-review
  exceptions use explicit rule-specific suppressions.
- `jscpd.json`, `package.json`, `package-lock.json`, and
  `duplication-baseline.json` support the duplicate-code ratchet.
- `coverage-baseline.json` stores reviewed Kover coverage comparison values.
- `gate-summary-v1.schema.json` defines the shared JSON summary contract.
- `repowise_*.py` and `repowise-requirements.txt` support pull-request and
  maintenance RepoWise reports.
- `qodana.yaml` configures advisory Qodana analysis; behavior is documented in
  [`docs/quality-gates.md`](../docs/quality-gates.md).
