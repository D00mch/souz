# Runtime

The `:runtime` module contains shared JVM runtime pieces used by both desktop (`:composeApp`) and backend (`:backend`):

- secure config/settings access (`ConfigStore`, `SettingsProvider`, `SettingsProviderImpl`);
- provider chat clients (Giga, Qwen, AiTunnel, Anthropic, OpenAI);
- shared LLM routing/classification (`LLMFactory`, `ApiClassifier`);
- runtime resources required by shared clients (for example Giga trust certificates).

## Notes

- `:runtime` is JVM-only.
- Backend no longer depends on `:composeApp`; both backend and desktop reuse these classes from `:runtime`.
