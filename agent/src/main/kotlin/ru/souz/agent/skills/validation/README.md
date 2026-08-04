# Skill Validation

This package is the approval boundary for file-backed Skill bundles.

Discovery and execution load a `SkillBundle` first, then call `SkillApprovalGate` before exposing
`SKILL.md` or running bundled commands. The gate hashes the bundle, checks the exact validation
cache, runs validators on a miss, stores the result, and returns either an approved bundle or a
local rejection.

```mermaid
flowchart TD
    A["GetSkillByName or RunSkillCommand"] --> B["Load SkillBundle"]
    B --> C["SkillApprovalGate.ensureApproved"]
    C --> D["Hash bundle"]
    D --> E{"Exact cache record?"}
    E -->|"Approved"| F["Return approved bundle"]
    E -->|"Rejected"| G["Return rejection"]
    E -->|"Missing"| H["Run validators"]
    H --> I["Save SkillValidationRecord"]
    I --> J{"Any ERROR finding?"}
    J -->|"No"| F
    J -->|"Yes"| G
```

## Validator Chain

Validators run in a fixed order. Later validators receive earlier findings. The chain stops after
the first `ERROR`, so expensive checks do not run after a hard failure.

```mermaid
flowchart LR
    A["Structural checks"] --> B{"ERROR?"}
    B -->|"Yes"| E["Reject and cache"]
    B -->|"No"| C["Static checks"]
    C --> D{"ERROR?"}
    D -->|"Yes"| E
    D -->|"No"| F["Bounded LLM checks"]
    F --> G{"ERROR?"}
    G -->|"Yes"| E
    G -->|"No"| H["Approve and cache"]
```

## Rules

- Validation cache identity is `userId`, `skillId`, `bundleHash`, and `policyVersion`.
- A changed bundle or policy is a cache miss.
- Validators return `SkillValidationFinding` values.
- Any `ERROR` finding rejects the bundle.
- `SkillApprovalGate` caches both approvals and rejections for the exact identity.
- Validation order is structural checks, static checks, then bounded LLM checks.
- Validator failures fail closed.
- Coroutine cancellation is rethrown.
- Bundle discovery and loading stay outside this package.
