# Skills

This document describes how Souz discovers, ranks, loads, and installs agent skills.

## Overview

Souz skills are markdown packages with a `SKILL.md` file at the root of the skill directory.

Souz currently supports two skill sources:

- workspace skills from `./skills`
- managed skills from `~/.local/state/souz/skills`

Workspace skills override managed skills when both expose the same skill name.

The runtime discovers skill summaries from frontmatter, embeds only the summary text, and stores those vectors in a dedicated Lucene index under `~/.local/state/souz/index/skills`. Full `SKILL.md` bodies stay on disk and are loaded lazily only after a skill has been selected.

## Directory Layout

Each skill must live in its own directory and include `SKILL.md`.

Example:

```text
skills/
└── weather/
    └── SKILL.md
```

Souz resolves skill locations with `SkillDirectories`:

- workspace root: auto-detected by walking ancestors until a `skills/` directory is found
- workspace override: `souz.workspaceRoot` system property or `SOUZ_WORKSPACE_ROOT`
- managed skills root: `~/.local/state/souz/skills`

## `SKILL.md` Shape

Souz reads YAML-like frontmatter from `SKILL.md` to build `AgentSkillSummary`.

Recognized summary fields:

- `name`
- `description`
- `when_to_use`
- `disable-model-invocation`
- `user-invocable`
- `allowed-tools`
- `metadata.openclaw.requires.bins`
- `metadata.openclaw.os`

Minimal example:

```md
---
name: weather
description: Weather and forecast helper
when_to_use: Use for forecast, temperature, and rain questions.
allowed-tools: [Bash]
metadata:
  openclaw:
    requires:
      bins:
        - curl
---
Use weather APIs to answer forecast questions.
```

## Retrieval Flow

Skill search is implemented in `EmbeddingSkillSearch`.

1. Discover skill summaries from the workspace and managed directories.
2. Build a catalog fingerprint from the summary metadata.
3. Compare that fingerprint plus the current embeddings model fingerprint with the persisted index metadata.
4. Rebuild the skills index only when the fingerprint is stale.
5. Embed the user query and run nearest-neighbor search against the skills index.
6. Load the full `SKILL.md` only for the chosen skill.

Important details:

- only summary text is embedded: `name`, `description`, and `when_to_use`
- skill bodies are intentionally excluded from the index
- retrieval uses a dedicated score floor to avoid weak nearest-neighbor matches
- slash activation such as `/weather Moscow` still resolves by exact skill name from disk

## Index Storage

The skills vector index is separate from the desktop information index.

- skills index directory: `~/.local/state/souz/index/skills`
- metadata file: `skills-index-metadata.json`

The stored metadata tracks:

- embeddings model fingerprint
- skill catalog fingerprint

This keeps skill indexing out of the graph-session hot path. Sessions search the existing index; they do not rescan and re-embed all skills on every turn.

## ClawHub Integration

Souz has minimal ClawHub install, update, and list support in `ClawHubManager`.

Current flow:

1. Resolve a remote skill by `sourceId` from `https://clawhub.ai/api/skills/{sourceId}`.
2. Download the returned archive URL.
3. Extract the ZIP into a temporary directory under the managed skills root.
4. Require exactly one extracted skill directory containing `SKILL.md`.
5. Parse the skill, move it into `~/.local/state/souz/skills/<folder>`, and update `.clawhub/lock.json`.

Safety checks in the installer:

- rejects absolute ZIP paths
- rejects ZIP entries that escape the extraction root
- rejects final managed-skill paths that escape the managed skills directory

Lockfile data stored in `.clawhub/lock.json`:

- `skillName`
- `folderName`
- `sourceId`
- `version`
- `installedAt`

## ClawHub Catalog Notes

The public ClawHub skills catalog is available at [clawhub.ai/skills](https://clawhub.ai/skills).

As of April 30, 2026, the public catalog page exposes:

- browsing for `Skills` and `Plugins`
- sorting such as featured, downloads, stars, installs, updated, newest, and name
- category filters such as MCP, tools, prompts, workflows, dev tools, data and APIs, security, automation, and other
- a `Hide suspicious` filter

The public publish flow currently routes to [clawhub.ai/publish-skill](https://clawhub.ai/publish-skill), which requires GitHub sign-in before publishing.

ClawHub also publishes moderation boundaries at [clawhub.ai/about](https://clawhub.ai/about). The page explicitly says ClawHub is intended for useful agent tooling and rejects workflows centered on abuse, fraud, privacy invasion, non-consensual impersonation, explicit sexual content, or hidden/misleading execution.

When adding ClawHub support in Souz, treat the public site as a human-facing catalog first. Do not hardcode catalog UI options into product logic without verifying the current site behavior.

## Development Notes

- Keep skill discovery and `SKILL.md` loading in `FilesystemSkillCatalog`.
- Keep retrieval and index freshness logic in `EmbeddingSkillSearch`.
- Keep Lucene storage details in `SkillVectorIndex`.
- Avoid per-turn reindexing.
- Prefer updating this document when the on-disk skill format, retrieval strategy, or ClawHub integration changes.
