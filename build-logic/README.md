# Souz build logic

This included Gradle build contains the `souz.quality` plugin used by the root
project. It owns repository-contract checks, production module-boundary checks,
and versioned quality reports; it is not a product module.

See [Quality gates](../docs/quality-gates.md) for tasks, check contracts, report
locations, CI authority, and remediation guidance.

Contributor rules are in [AGENTS.md](AGENTS.md).
