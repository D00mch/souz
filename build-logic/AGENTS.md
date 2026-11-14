# Build logic

## Purpose and boundaries

- `build-logic` owns the [Souz quality plugin](README.md) and stays outside the product module graph.
- Keep the implementation direct. Do not introduce a generic gate framework without another concrete consumer.
- Use public Gradle APIs and inspect declared `ProjectDependency` relationships without resolving configurations.
- Treat unclassified project-dependency scopes as failures. Exclude a test scope only when it does not feed a production configuration.
- Keep check IDs and report contracts stable. Internal checker failures are `error`, and reports are written before the task fails.
- Reports must be deterministic and repository-relative, without source bodies, secrets, or absolute user paths.
- Resolve types and callable identities in custom review rules. Every enabled rule needs explicit fast-gate routing and enforcement tests; keep code-review findings out of coroutine baseline maintenance.

## Pain points

- [Quality gates](../docs/quality-gates.md) — repository contracts, module boundaries, coroutine analysis, baselines, and reports.

## Verification

Run from the repository root:

```bash
./gradlew :build-logic:check
./gradlew souzGateFast --configuration-cache --configuration-cache-problems=fail
```
