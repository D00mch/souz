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
    /** Carried forward from the [SkillOAuthPendingState] that produced this save (or from the
     *  previous credential row, for a token refresh) — see [SkillOAuthCredentialRepository.upsert]. */
    val generation: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

interface SkillOAuthCredentialRepository {
    suspend fun find(userId: String, provider: String): SkillOAuthCredential?

    /**
     * Stores [credential]'s token material as given (last write wins — there is only ever one
     * real access token on file per `(userId, provider)`), but rejects the write outright — returns
     * `null`, no-op — if [SkillOAuthCredential.generation] is older than what's already stored.
     *
     * This guards against two distinct hazards, neither of which can be resolved by comparing
     * `grantedScopes` between writes: the OAuth token response's `scope` field is OPTIONAL when it
     * matches what was requested (RFC 6749), so a "narrower-looking" write might just be one the
     * provider didn't bother to echo back, not an actually-narrower grant. `generation` sidesteps
     * that ambiguity entirely — it's a fact this service controls, not something inferred from the
     * provider's response: (1) a callback whose own pending state was already superseded by a
     * fresher authorization for the same `(userId, provider)` must not clobber that fresher one's
     * credential just because its network round-trip happened to finish later; (2) a background
     * token refresh (which never bumps generation — see [SkillOAuthApiImpl.ensureFreshAccessToken])
     * must not silently undo a broader authorization the user completed while the refresh was in
     * flight.
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
    /** Snapshot of [SkillOAuthRequestedScopesRepository]'s generation at the moment this state was
     *  created — carried into [SkillOAuthCredential.generation] once this flow's callback saves. */
    val generation: Long,
    val expiresAt: Instant,
)

interface SkillOAuthPendingStateRepository {
    /**
     * Creates [pending], or — if a still-live pending state already exists for the same
     * `(userId, provider)` — supersedes it in place: the old `state` becomes invalid. Backed by a
     * single DB upsert (`insert ... on conflict (user_id, provider) do update`, guarded by a unique
     * index on that pair), not a separate read-then-write — two concurrent calls for the same
     * `(userId, provider)` can therefore never both "win" and leave two live pending states. Unlike
     * an earlier version of this method, it does *not* merge `requestedScopes` itself — the caller
     * (`startAuthorization`) is expected to have already computed the right value via
     * [SkillOAuthRequestedScopesRepository], which survives past this state being consumed and is
     * therefore the actual source of truth for "what's been requested so far".
     */
    suspend fun upsertSupersedingByUserAndProvider(pending: SkillOAuthPendingState): SkillOAuthPendingState

    /** Atomically deletes and returns the pending state if present and not expired as of [now]. */
    suspend fun consume(state: String, now: Instant): SkillOAuthPendingState?
}

/** The cumulative union of every scope ever requested for one `(userId, provider)` pair, plus a
 *  monotonic [generation] counter bumped alongside it — see [SkillOAuthRequestedScopesRepository]. */
data class SkillOAuthRequestedScopesState(
    val requestedScopes: List<String>,
    val generation: Long,
)

interface SkillOAuthRequestedScopesRepository {
    /**
     * Atomically folds [scopes] into whatever's already on file for `(userId, provider)` and bumps
     * the generation counter, under a single row lock (`select ... for update`) — a concurrent call
     * for the same pair blocks until this one commits, then sees its result rather than racing it.
     *
     * This is what actually closes the race a unique index on the (transient) pending-states table
     * alone cannot: by the time a callback consumes its own pending state (the first thing
     * `handleCallback` does), that row is gone, so a second, unrelated `startAuthorization` call has
     * nothing left to supersede *there*. This table's row, unlike a pending state, is never deleted
     * except on an explicit disconnect — so the second call still sees, and widens on top of,
     * everything requested by the first, in-flight one. In the common case both flows converge on
     * the same (unioned) request before either is completed by the user, so neither one's real grant
     * ends up narrower than the other's.
     *
     * A row last touched before [activeSince] is treated as if it didn't exist — its scopes are
     * *not* folded in, only [scopes] is used as the new baseline (though [generation] still keeps
     * increasing regardless) — so a long-abandoned request (its own authorize link expired, user
     * never completed it) doesn't get silently resurrected into an unrelated, much later
     * authorization.
     */
    suspend fun mergeAndBump(
        userId: String,
        provider: String,
        scopes: List<String>,
        now: Instant,
        activeSince: Instant,
    ): SkillOAuthRequestedScopesState
}
