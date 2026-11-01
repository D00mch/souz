# Scenario Skill fixtures

Source: [Souz Skill export](https://disk.yandex.ru/d/2r6zoI_x33TFaA), snapshot 2026-10-29.

The export contains 30 Skill IDs and 37 variants. `index.json` pins one variant per ID:
the variant with the most deployments, then the lexicographically smallest full SHA-256
on a tie. All 108 selected files retain their source bytes. User identities and deployment
records are not included. No credentials or local Skill registry are needed.

The index records the source variant hash and each file's SHA-256 and byte count.
`ScenarioSkillFixtures` checks files against the index and uses the production bundle
parser and hasher. Inventory reads the index and frontmatter; supporting files load on
exact bundle lookup. The approval cache accepts only the pinned bundle hash in tests.

Every scenario can discover these bundles and the full production compiled-tool catalog.
Scenario-specific mocks supply tool results. Unconfigured tools return
`scenario_tool_not_configured`; file-backed commands return a simulated nonzero exit with
`scenario_skill_not_configured`. Bundled scripts are never executed. These fixtures test
discovery and tool selection, not external service availability or Skill authorization.

When refreshing the fixture set, apply the same variant selection, copy complete bundles,
update `index.json`, and run the deterministic checks in
[the scenario guide](../../../../../docs/agent-scenario-tests.md). Treat results from a
different catalog or fixture revision as separate measurements.
