package ru.souz.skilloauth.impl

import java.time.Instant

/** A stored, encrypted OAuth connection for one `(userId, provider)` pair, shared across skills. */
data class SkillOAuthCredential(
    val userId: String,
    val provider: String,
    val accessTokenEncrypted: String,
    val refreshTokenEncrypted: String?,
    val grantedScopes: List<String>,
    val expiresAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

interface SkillOAuthCredentialRepository {
    suspend fun find(userId: String, provider: String): SkillOAuthCredential?

    /**
     * Stores [credential]'s token material as given (last write wins — there is only ever one
     * real access token on file per `(userId, provider)`), but merges `grantedScopes` with
     * whatever is already stored rather than replacing it outright: two separate, legitimately
     * completed authorization flows for the same `(userId, provider)` — e.g. one callback's token
     * exchange still in flight when a second, unrelated flow starts and finishes first — must not
     * let whichever one saves last erase the scopes the other one just had the user grant. A
     * mismatch between the merged `grantedScopes` bookkeeping and what the one stored token can
     * literally do at the provider surfaces as a clean 401/403 at call time rather than silently
     * using the wrong permissions; see [SkillOAuthApiImpl]'s usable-credential check for the
     * recovery path (status reports not-connected, prompting reconnect).
     */
    suspend fun upsert(credential: SkillOAuthCredential): SkillOAuthCredential

    suspend fun delete(userId: String, provider: String)
}

/** A single-use, short-lived CSRF token for one in-flight authorization attempt. */
data class SkillOAuthPendingState(
    val state: String,
    val userId: String,
    val skillId: String,
    val provider: String,
    val requestedScopes: List<String>,
    val expiresAt: Instant,
)

interface SkillOAuthPendingStateRepository {
    /**
     * Creates [pending], or — if a still-live pending state already exists for the same
     * `(userId, provider)` — supersedes it in place: the old `state` becomes invalid, and the
     * stored `requestedScopes` become the union of the old and new requests. Backed by a single
     * DB upsert (`insert ... on conflict (user_id, provider) do update`, guarded by a unique index
     * on that pair), not a separate read-then-write — two concurrent calls for the same
     * `(userId, provider)` can therefore never both "win" and leave two live pending states, which
     * a check-then-act sequence could otherwise allow. Returns the row as stored (with the merged
     * `requestedScopes`), since the caller needs it to build the authorize URL.
     */
    suspend fun upsertSupersedingByUserAndProvider(pending: SkillOAuthPendingState): SkillOAuthPendingState

    /** Atomically deletes and returns the pending state if present and not expired as of [now]. */
    suspend fun consume(state: String, now: Instant): SkillOAuthPendingState?
}
