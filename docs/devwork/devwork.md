# Souz — Q3 2026 time estimate

Analyzed on 29 September 2026. Period: 1 July–29 September 2026 to date, Europe/Moscow. The latest counted original commit is 28 September at 22:05 Moscow time.

**Estimate: 433 hours (432.53 before rounding), approximately 54 eight-hour days.**

| Period | Estimated hours | Counted commit records |
|---|---:|---:|
| July | 53.92 | 72 |
| August | 194.85 | 223 |
| September to date | 183.76 | 211 |
| Total | 432.53 | 506 |

## Coverage and calculation

- Combined the three approved identities: arturdumchev@yandex.ru, ardumchev@sberbank.ru, and adumchev@sberdevices.ru.
- Reviewed metadata for all 510 GitHub PRs and retrieved the original commit lists for all 166 PRs active in Q3. All commit lists were complete; no pagination remained.
- Included 130 PRs containing your original Q3 work: 112 merged, 4 open, and 14 closed without merging. PR counts describe current status; commit author timestamps determine the quarter.
- Combined PR commits with available local and remote branch history, including local work without a matching GitHub PR. Recovered 43 non-merge commits missing from the local branch histories.
- Removed 109 Q3 integration/squash records whose work is represented by original PR commits. Consolidated 22 rebase copies sharing author email, original author timestamp, and subject. Shared commits across PRs were counted once.
- Used author timestamps to preserve the original timing through rebases. All work is combined into one timeline, so overlapping PRs do not accumulate separate session allowances.
- Kept 45 ordinary branch merge commits, consistent with git-hours including merges by default. Excluding those produces 409 hours.
- Used the upstream git-hours estimation function with its default 120-minute gap threshold and 120-minute initial-work allowance. The upstream implementation omits an allowance for the earliest session; this result preserves that behavior. The Python calculation was independently checked against the unmodified JavaScript function.
- Monthly hours allocate the intervals counted in the total across calendar-month boundaries, rather than restarting the calculation for each month. Rounded monthly values can differ from the rounded total by 0.01 hour.

## Comparisons

| Calculation | Hours |
|---|---:|
| Main history alone, original method | 171 |
| Previous local/remote branch method, restricted to Q3 | 443 |
| Corrected PR + branch timeline | 433 |
| Corrected timeline excluding ordinary branch merges | 409 |
| Corrected timeline excluding commits found only in unmerged PRs | 399 |

Unmerged-only commits increase the combined estimate by 33.16 hours. This is a marginal contribution, not a separate total of PR estimates. Restoring missing originals increases coverage, while removing duplicate integration records reduces it; a corrected estimate need not be larger than the previous all-branch estimate.

## Sensitivity and limits

Keeping the two-hour session gap but changing the assumed initial-work allowance to one hour produces 303 hours; three hours produces 563 hours. These are scenario estimates, not confidence bounds.

Commit history does not measure active human effort or distinguish AI execution from personal work. Deleted or force-pushed commits absent from both retained local history and current PR commit lists cannot be reconstructed. Available local and remote branch history was included; PR metadata came from D00mch/souz on GitHub.

## Unmerged PRs containing your Q3 work

- [#563](https://github.com/D00mch/souz/pull/563) — open
- [#573](https://github.com/D00mch/souz/pull/573) — closed
- [#583](https://github.com/D00mch/souz/pull/583) — closed
- [#613](https://github.com/D00mch/souz/pull/613) — closed
- [#622](https://github.com/D00mch/souz/pull/622) — closed
- [#631](https://github.com/D00mch/souz/pull/631) — closed
- [#636](https://github.com/D00mch/souz/pull/636) — closed
- [#647](https://github.com/D00mch/souz/pull/647) — closed
- [#774](https://github.com/D00mch/souz/pull/774) — open
- [#786](https://github.com/D00mch/souz/pull/786) — closed
- [#790](https://github.com/D00mch/souz/pull/790) — closed
- [#791](https://github.com/D00mch/souz/pull/791) — closed
- [#795](https://github.com/D00mch/souz/pull/795) — open
- [#812](https://github.com/D00mch/souz/pull/812) — closed
- [#813](https://github.com/D00mch/souz/pull/813) — closed
- [#815](https://github.com/D00mch/souz/pull/815) — closed
- [#816](https://github.com/D00mch/souz/pull/816) — closed
- [#828](https://github.com/D00mch/souz/pull/828) — open

## Sources

- [git-hours source revision used](https://github.com/kimmobrunfeldt/git-hours/blob/c589eea5174ee6439b68b4b437e443e7b6e4bf7b/src/index.js)
- [Souz pull requests](https://github.com/D00mch/souz/pulls)
- [GitHub explanation of squash and merge](https://docs.github.com/en/pull-requests/reference/pull-request-merges)
