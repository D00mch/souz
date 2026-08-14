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
    /** The revision this credential was read at (or `0` for a brand-new authorization). Passed
     *  back into [SkillOAuthCredentialRepository.upsert] unchanged by a same-generation write (a
     *  refresh) as the optimistic-concurrency token proving no other write landed in between. */
    val revision: Long = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
)

interface SkillOAuthCredentialRepository {
    suspend fun find(userId: String, provider: String): SkillOAuthCredential?

    /**
     * Stores [credential]'s token material as given (last write wins — there is only ever one
     * real access token on file per `(userId, provider)`), but rejects the write outright — returns
     * `null`, no-op — if [SkillOAuthCredential.generation] is older than what's already stored, or
     * if it's equal but [SkillOAuthCredential.revision] no longer matches the stored row's revision.
     *
     * This guards against three distinct hazards, none of which can be resolved by comparing
     * `grantedScopes` between writes: the OAuth token response's `scope` field is OPTIONAL when it
     * matches what was requested (RFC 6749), so a "narrower-looking" write might just be one the
     * provider didn't bother to echo back, not an actually-narrower grant. `generation` sidesteps
     * that ambiguity entirely — it's a fact this service controls, not something inferred from the
     * provider's response: (1) a callback whose own pending state was already superseded by a
     * fresher authorization for the same `(userId, provider)` must not clobber that fresher one's
     * credential just because its network round-trip happened to finish later; (2) a background
     * token refresh (which never bumps generation — see [SkillOAuthGatewayImpl.ensureFreshAccessToken])
     * must not silently undo a broader authorization the user completed while the refresh was in
     * flight; (3) two token refreshes racing for the same `(userId, provider)` share one
     * generation, so `generation` alone can't order them — without `revision`, the `>=` guard lets
     * both writes through and whichever happens to commit last wins, even if it's carrying an
     * already-stale refresh token (providers that rotate refresh tokens on each use would then
     * leave every subsequent refresh failing with `invalid_grant`). `revision` is bumped by exactly
     * one on every successful write, so the loser of such a race gets its write cleanly rejected
     * instead of silently corrupting the row.
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
    /** Snapshot of the durable requested-scope tracking's generation counter (see
     *  [SkillOAuthPendingStateRepository.beginAuthorization]) at the moment this state was created —
     *  carried into [SkillOAuthCredential.generation] once this flow's callback saves. */
    val generation: Long,
    val expiresAt: Instant,
)

interface SkillOAuthPendingStateRepository {
    /**
     * Starts, reuses, or supersedes the one live authorization attempt for `(userId, provider)`,
     * atomically:
     * 0. if [reuseExisting] and a still-live (not expired as of [now]), not-yet-superseded pending
     *    state already exists whose [SkillOAuthPendingState.requestedScopes] covers all of [scopes],
     *    returns it completely unchanged — no write at all, so its `expiresAt` is never extended and
     *    its `state`/generation never change. This is what makes `ensureAuthorized`
     *    (`:skill-oauth-api`) genuinely idempotent: a retry or an overlapping call asking for
     *    scopes an already-issued, unconsumed link already covers must not invalidate that link —
     *    the user could already have it open in a browser tab. The check has to happen inside the
     *    same lock as any write below (not as a separate read beforehand) — otherwise two
     *    concurrent callers could each see "nothing to reuse" and race to create their own state,
     *    with the loser's link silently invalidating the winner's.
     * 1. otherwise, folds [scopes] into the durable, per-`(userId, provider)` requested-scope
     *    tracking — which, unlike a pending state, is never deleted just because a callback consumed
     *    its state, so a second, unrelated authorization still widens on top of a first one that's
     *    mid-exchange — treating a row untouched since before [activeSince] as absent (a
     *    long-abandoned request doesn't get silently resurrected into an unrelated, much later
     *    authorization);
     * 2. bumps that pair's monotonic generation counter;
     * 3. supersedes any existing pending state for the same pair with a fresh one ([state]) carrying
     *    the merged scopes and new generation — the old `state`, if opened afterwards, fails cleanly
     *    as invalid/expired rather than corrupting anything.
     *
     * Every step happens under one row lock (`select ... for update` on the requested-scope tracking
     * row, held for the whole transaction) rather than as separate round-trips — a concurrent call
     * for the same pair blocks here until this one fully commits, including its pending-state read
     * and write. That matters: splitting this into separate transactions (check reuse, *then*
     * separately bump/write) leaves a window where an older call, paused in between, can still
     * unconditionally overwrite a newer call's already-written pending state with its own stale one
     * — a single lock spanning every step closes that window instead of just narrowing it.
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
     * Atomically deletes and returns the pending state if present and not expired as of [now].
     *
     * Locks the same `(userId, provider)` requested-scope tracking row [beginAuthorization] locks
     * first, before touching the pending-state row itself. Implementations must preserve that lock
     * order — locking the pending-state row first (e.g. via an unconditional delete) lets a
     * concurrent [beginAuthorization] for the same pair deadlock with this method, since it would
     * then acquire the two locks in the opposite order.
     */
    suspend fun consume(state: String, now: Instant): SkillOAuthPendingState?
}
