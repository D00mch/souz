# Skill validation

[SkillApprovalGate](SkillApprovalGate.kt) checks an already loaded file-backed bundle. It caches approvals and rejections by user, canonical Skill ID, bundle hash and policy version. Bump the version when validation rules change.

Run structural, static and bounded LLM validation in that order. An `ERROR` rejects the bundle and stops the chain. Validator and cache failures fail closed, and coroutine cancellation propagates. [Approval integration](../../../../../../../../docs/skill-activation.md)
