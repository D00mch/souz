# Provider responses

## Invariants

- Provider adapters populate `LLMResponse.Message.functionCall` and its call ID without copying the serialized function-call envelope into `content`. Non-empty content is assistant text in the streaming runtime.
- OpenAI-compatible requests include an `items` schema for every array-valued tool property. The shared property contract does not describe element types, so adapters use an unconstrained item schema.
- Token accounting happens at the `LLMChatAPI` boundary, where usage is normalized. A logging failure must not turn a successful provider response into a failed execution.

## Why this is fragile

Duplicating a function call into message content exposes its JSON as transient assistant text. Dropping tool-schema metadata can make a provider reject a request before execution.

## Safe changes

- Preserve function-call names, arguments, IDs, and ordinary assistant text independently.
- Preserve declared property metadata when adding array item schemas.
- Keep logging sinks, retention, and metrics ownership in the backend.

## Verification

Run `./gradlew :sharedLogic:test :backend:test`. Cover provider serialization, streaming tool calls, usage accounting, and failure isolation.
