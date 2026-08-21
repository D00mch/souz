# Build logic

Before changing this included build, read its [README](README.md) and the
[quality-gate documentation](../docs/quality-gates.md).

## Purpose and boundaries

- `build-logic` owns the Souz-specific quality plugin and stays outside the product module graph.
- Keep the implementation direct. Do not introduce a generic gate framework without another concrete consumer.
- Use public Gradle APIs and inspect declared `ProjectDependency` relationships without resolving configurations.
- Treat unclassified project-dependency scopes as failures. Exclude a test scope only when it does not feed a production configuration.
- Keep check IDs and report contracts stable. Internal checker failures are `error`, and reports are written before the task fails.
- Reports must be deterministic and repository-relative, without source bodies, secrets, or absolute user paths.

## Verification

Run from the repository root:

```bash
./gradlew :build-logic:check
./gradlew souzGateFast --configuration-cache --configuration-cache-problems=fail
```
