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
    suspend fun create(pending: SkillOAuthPendingState)

    /** Atomically deletes and returns the pending state if present and not expired as of [now]. */
    suspend fun consume(state: String, now: Instant): SkillOAuthPendingState?

    /**
     * Atomically deletes and returns any still-unexpired pending state(s) for this
     * `(userId, provider)` pair as of [now]. Used by [SkillOAuthApiImpl.startAuthorization] to
     * collapse two overlapping connect attempts into one active flow — without this, starting a
     * second authorization while a first one is still pending would leave two live `state` tokens
     * for the same pair, and whichever callback lands last would silently overwrite the scopes the
     * other one just granted.
     */
    suspend fun consumeActiveForUserAndProvider(userId: String, provider: String, now: Instant): List<SkillOAuthPendingState>
}
