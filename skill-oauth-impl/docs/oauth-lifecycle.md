# OAuth lifecycle and concurrency

Credentials are shared per `(userId, provider)`. Authorization and reconnect links include both
the credential's granted scopes and the caller's required scopes. Durable requested-scope
tracking also includes overlapping authorizations, even after their pending states are consumed.
Tracking untouched before the active cutoff is ignored; consuming a valid state refreshes its
timestamp so an in-flight code exchange is included in that union.

`ensureAuthorized` reuses a live link covering the requested scopes without changing its state,
generation, expiry, or tracking timestamp. Force bypasses connection checks and link reuse.
Expiry equality is valid for pending states, but expired for access tokens.

Pending-state creation and consumption use one transaction each. Both lock requested-scope
tracking before touching the pending row. Reuse decisions and scope/generation updates must stay
inside that lock; splitting the transaction can invalidate links or introduce deadlocks.
Credential upsert and deletion are single atomic SQL statements on autocommit connections.
Generation rejects older authorizations; revision rejects stale refreshes at the same generation.
Provider-reported scopes cannot order writes because the token response may omit them.

Connection checks use actual token expiry. Proactive refresh applies the safety margin only when
a refresh token exists. Only `invalid_grant` clears that token and produces a reconnect outcome;
other provider errors propagate without changing stored credentials. If clearing loses a compare-
and-set race, read the winning credential and retry. A successful refresh whose write loses the
race still returns its issued access token to that call.

Validate HTTPS and the provider's exact host allowlist before attaching an access token.
Caller headers cannot override authorization. Ktor takes content type from the request body's
metadata, so explicit content types belong on `TextContent`.

Keep the provider-neutral API, callback route, provider catalog, token payload format, and schema
compatible during internal refactors. Verify with:

```bash
./gradlew :skill-oauth-api:test :skill-oauth-impl:test
```

`SkillOAuthFlowTest` exercises real token exchange, encrypted persistence, and refresh through a
mock HTTP engine and PostgreSQL. Repository tests cover generation/revision rejection, link
reuse, scope widening, and concurrent creation/consumption without deadlock.
