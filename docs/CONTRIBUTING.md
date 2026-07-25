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

Use each documentation surface for one purpose:

- [AGENTS.md](../AGENTS.md) files contain durable engineering instructions, ownership boundaries, and verification commands.
- [Pain-point topics](pain-points.md) contain non-obvious invariants, failure modes, and safe-change guidance.
- README files contain human-facing architecture and usage descriptions.
- Source code and generated API documentation are authoritative for exact routes, configuration keys, constants, and file inventories.

Keep documentation current-state only and update the smallest owning document instead of copying the same fact into several files.

## Process

If you want to contribute a feature, please, create an issue with the description of what you want to do and/or discuss it first with Artur Dumchev:

- arturdumchev@gmail.com
- https://t.me/dumch
