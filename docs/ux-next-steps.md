# UX review and remaining work

Current behavior is defined by [requirements](requirements.md), [UI principles](ui-design.md) and
[map design](map-design.md). [Decision coverage](ui-decision-coverage.md) distinguishes controls,
automated evidence and human review. This document tracks genuine remaining work rather than a
backlog of already implemented changes.

## Current baseline

The engine projects authoritative costs, eligibility and effects; the service owns authorization,
private audiences and revisions; visual controls submit canonical commands. Preserve those boundaries.

Creation now selects variants and starts setup automatically. The compact AoR table separates
inspection from active decisions; opening reference material preserves prepared targets. Location
cards activate map picking immediately. Expansion filters infeasible pool/shipping commitments,
keeps legal partial placements, shows white markers/military cards, and permits clearing results.
Relief remains explicit for advance tiers even when blocked. Waiting Urban Ascendancy owners can
queue one cancelable purchase in the open view without automatic retries.

Commodity adjustments, optional full payout boxes and public unplayed-leader counts support planning
without exposing hidden cards. Activity opens by default, labels dice colors, explains draws and
market rolls, records epoch/end triggers and cash changes, and omits continuation noise. Catalog 13
adds the reviewed Fez–Western Mediterranean contact to both board variants.

At the 2026-10-28 checkpoint, integrated Kotlin checks and targeted gameplay, market and contextual
browser regressions pass; both map variants pass geometry validation. The comprehensive browser
rerun is pending. Earlier evidence remains scoped in the coverage inventory.

## Remaining review

| Work | Acceptance |
| --- | --- |
| Full-session accessibility | Record keyboard/screen-reader play through setup, waiting, interruptions and error recovery; every action has a named, reachable control and announced state. |
| Physical-phone and browser-chrome zoom | Review real touch, scrolling, inspection/actions and 200% browser zoom alongside emulated coverage. Preserve prepared work and reachable commitment. |
| Visual states outside HTML contrast checks | Review SVG, gradients, opacity-composited emphasis and native controls. Automated text checks do not establish these contrasts. |
| Constrained map labels | Review Toulouse and Constantinople at Fit and practical phone zoom; retain readable inspection and all owner/age/count data. Existing collision tests establish containment, not human legibility. |
| Production responsiveness | Measure representative six-seat, conflict-heavy late games and isolated React commit cost before changing transport or caching. Compare against the baseline below. |
| Retired internal rule handlers | Remove only with separate review and replay/version checks; current configuration already rejects retired choices. |

Launch configuration, accounts, DNS and provider checks belong in [follow-ups](follow-ups.md) and
[deployment](deployment.md). Bots and persistent private planning remain deferred; the Urban
Ascendancy draft is deliberately limited to the open player view.

## Execution record, 2026-10-27

The earlier review delivered stable nested-decision identity, shared target limits, private-seat
recovery, readable event/advance references, contextual typed diagnostics and semantic visual tokens.
Joint geometry and rendered collision checks preserve all twelve owner/age groups; dense provinces
have a zoom/inspection fallback. The later Danish correction removed Copenhagen's label/piece
exceptions. These are implemented contracts, not open priorities.

The archived performance baseline used catalog `ah1996-catalog-2`, before later map corrections.
It reached epoch 3, turn 8, revision 234 through 278 actions and 363 history entries, with six active
seats holding 9–11 cards each. Ten concurrent six-viewer batches measured snapshot p50 **5.0 ms**,
p95 **7.2 ms**, maximum **145,955 bytes**. Eighteen workspace interactions measured p50 **66.1 ms**,
p95 **92.3 ms**, including driver overhead and two frames. Per-tab task CPU was 0.21–0.38 seconds;
layout was 0.004–0.022 seconds.

These are local Vite, low-conflict development measurements, not production latency or isolated
React timings. They did not justify transport changes. The generated bundle still has a size advisory.
Any optimization must preserve private updates that occur without a committed revision, viewer
permissions, rule versions and stale-request behavior, with before/after measurements.

Reproduce from `web`:

```sh
AOR_BROWSER_SUITE=performance.browser.mjs AOR_BROWSER_TIMEOUT_MS=300000 npm run test:browser
```

The runner retains `late-game-performance.json`, contrast reports and screenshots outside the
repository, then removes private game data and browser profiles. Keep historical runs distinct from
current regressions, and record actual stages visited by long scenarios.
