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
    /** Authorization generation, preserved across token refreshes. */
    val generation: Long,
    /** Compare-and-set token: the stored revision at read time, incremented by each accepted write. */
    val revision: Long = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
)

interface SkillOAuthCredentialRepository {
    suspend fun find(userId: String, provider: String): SkillOAuthCredential?

    /**
     * Accepts a newer generation, or the same generation with a matching revision; returns the
     * stored row, or null for a rejected stale write. Preserves createdAt on replacement.
     * Generation orders authorizations; revision prevents racing refreshes from overwriting a
     * rotated refresh token. Provider-reported scopes cannot order writes because scope is optional.
     */
    suspend fun upsert(credential: SkillOAuthCredential): SkillOAuthCredential?

    suspend fun delete(userId: String, provider: String)
}

/** A single-use, short-lived CSRF token for one in-flight authorization attempt. */
data class SkillOAuthPendingState(
    val state: String,
    val userId: String,
    val skillId: String,
    val provider: String,
    val requestedScopes: List<String>,
    /** Requested-scope tracking generation at creation, carried into the saved credential. */
    val generation: Long,
    val expiresAt: Instant,
)

interface SkillOAuthPendingStateRepository {
    /**
     * Under one per-user/provider lock, reuses a live covering state when [reuseExisting], or:
     * - unions [scopes] with durable requested scopes, ignoring tracking older than [activeSince];
     * - increments the generation and replaces the pair's pending state with [state].
     *
     * Reuse leaves state, expiry, generation, and tracking untouched. Durable scopes outlive consumed
     * states so an authorization starting during a code exchange still includes that flow's scopes.
     * The reuse decision and all writes share one transaction; separate reads allow retries to
     * invalidate an already-issued link or an older caller to overwrite a newer pending state.
     */
    suspend fun beginAuthorization(
        state: String,
        userId: String,
        skillId: String,
        provider: String,
        scopes: List<String>,
        now: Instant,
        activeSince: Instant,
        expiresAt: Instant,
        reuseExisting: Boolean = true,
    ): SkillOAuthPendingState

    /**
     * Deletes and returns a state valid as of [now]; expired states are deleted and return null.
     * Locks requested-scope tracking before the pending row, matching [beginAuthorization]'s lock
     * order to prevent deadlocks. A valid consumption refreshes tracking's timestamp so the ensuing
     * code exchange is included in overlapping authorizations' scope unions.
     */
    suspend fun consume(state: String, now: Instant): SkillOAuthPendingState?
}
