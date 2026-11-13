# Skill OAuth API

## Purpose and boundaries

- `:skill-oauth-api` owns provider-neutral Skill OAuth contracts.
- Keep host, HTTP transport, provider implementation, and persistence dependencies outside this module.

## Pain points

No module-specific pain-point topics are currently recorded.

## Verification

Run `./gradlew :skill-oauth-api:test`.
