# Pain points

Read the topic files relevant to the code you plan to change in :agent. These notes document lasting constraints and common pitfalls, not a history of changes.

## Topics

- [Execution lifecycle](pain-points/execution-lifecycle.md) — stateful facade execution, cancellation, session ownership, and request-scoped kernels.
- [Skill activation](pain-points/skill-activation.md) — turn ordering, bundle loading, validation caching, and command exposure.
- [Skills-oriented graph](pain-points/skills-oriented-graph.md) — core-tool isolation, large-result offloading, and Knowledge lifetime.

When a new problem does not fit an existing topic, add a focused file under [`agent/docs/pain-points/`](pain-points/) and link it here.
