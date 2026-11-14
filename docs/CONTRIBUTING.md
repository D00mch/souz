# Contributing

## Environment Requirements

- Java Development Kit (JDK) **21**
- Use the project Gradle Wrapper (`./gradlew`)

## Code Style and Principles

- Default [Kotlin Coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Avoid typical Kotlin pitfalls ([ru Habr 1](https://habr.com/ru/articles/874610/))
- Avoid complexity ([ru Habr 2](https://habr.com/ru/articles/878620/))

## Documentation

Implementations overview:

- [Agent module](../agent/README.md)
- [Shared runtime and MCP integration](../sharedLogic/README.md)
- [MCP transport details](../sharedLogic/src/jvmMain/kotlin/ru/souz/service/mcp/README.md)
- [Public client contract](public-souz-contract/README.md)

- [AGENTS.md](../AGENTS.md) files contain durable engineering instructions, ownership boundaries, and verification commands.
- Document intent, invariants, restrictions, failure modes, and focused verification in the owning module's `docs/`. Index each topic under `Pain points` in its `AGENTS.md`.
- Keep setup and runnable examples in usage guides. Link to source for implementation and generated API documentation for wire details.

Keep documentation current-state only and update the smallest owning document instead of copying the same fact into several files.

## Process

If you want to contribute a feature, please, create an issue with the description of what you want to do and/or discuss it first with Artur Dumchev:

- arturdumchev@gmail.com
- https://t.me/dumch
