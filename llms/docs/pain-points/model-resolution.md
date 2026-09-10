# Model resolution

## Invariants

The serialized default embeddings marker is exactly `Embeddings` and does not identify a provider. Public model selectors are trimmed and resolved at input boundaries into explicit resolved, unknown, unsupported-provider, or ambiguous outcomes. Backend turn inputs carry `LLMModel`. Agent execution requests carry a host-resolved raw model ID and explicit provider, including configured subagent models absent from the enum.

An ambiguous embeddings alias prefers the configured embeddings model only when that model is one of the matching candidates. Unsupported configured providers, including Giga on backend, remain explicit failures.

## Safe changes

- Keep enum names available as unambiguous selectors when aliases overlap.
- Add aliases through the shared resolvers and cover whitespace and case normalization.
- Do not replace unknown, ambiguous, or unsupported selections with an unrelated fallback.
- `LLMRequest.Chat.provider` is internal routing metadata excluded from JSON. When present, `model` is already the exact provider request ID; routers and adapters must not normalize it. Calls without routing metadata retain legacy model resolution. See [subagent configuration](../../../docs/subagents.md#model-configuration).

## Verification

Run `./gradlew :llms:test :backend:test` and cover every resolution outcome.
