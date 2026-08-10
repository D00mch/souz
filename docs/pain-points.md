# Pain points

Pain-point topics document non-obvious invariants, common failure modes, and the safe way to change fragile areas. They are current-state maintenance guides, not feature overviews or change history.

Before editing a module, open its index below and read only the topics related to the code you plan to change.

## Module indexes

- [`:agent`](../agent/docs/pain-points.md)
- [`:graph-engine`](../graph-engine/docs/pain-points.md)
- [`:llms`](../llms/docs/pain-points.md)
- [`:native`](../native/docs/pain-points.md)
- [`:ambientAgent`](../ambientAgent/docs/pain-points.md)
- [`:sharedLogic`](../sharedLogic/docs/pain-points.md)
- [`:sharedUI`](../sharedUI/docs/pain-points.md)
- [`:desktopApp`](../desktopApp/docs/pain-points.md)
- [`:backend`](../backend/docs/pain-points.md)

## Topic format

Each topic should state:

1. The invariant that must remain true.
2. Why the area is fragile or costly to break.
3. The safe path for changing it.
4. Focused verification commands or scenarios.

Keep exact constants, route inventories, file trees, and historical narratives in their canonical source rather than duplicating them here.

## Repository-wide topics

No repository-wide pain-point topics are currently recorded. Add one under [`docs/pain-points/`](pain-points/) only when the constraint genuinely spans modules and is not clearer in a single module index.
