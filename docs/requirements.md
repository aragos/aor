# Product scope

AoR supports complete asynchronous three-epoch Age of Renaissance games for 3–6 seats, all 64 cards
and final scoring. The visual table supports ordinary play; canonical commands support expert input,
reproducible reports and authorized history edits.

Acceptance requires deterministic replay, playable existing saves, atomic durable writes, audience-safe
views/history, explicit plan authorization and accessible desktop/phone play that preserves valid
work. Setup is protected; history edits are auditable. Cancellation/abandonment stop play and retain
permitted inspection; player abandonment requires unanimous consent and reveals no private values.
Detailed contracts belong to [engine](engine.md), [architecture](design.md), [UI](ui-design.md),
[rules](rules.md) and [commands](commands.md).

Hosted play uses Firebase and PostgreSQL, with a target of hundreds of games per month within
$25/month; production measurements must establish capacity. An existing Squarespace subdomain can
serve the app. [Deployment](deployment.md) owns operations; [follow-ups](follow-ups.md) owns launch checks.

New online games require 3–6 registered humans. Local games offer Easy/Normal/Hard automated seats,
run by one worker in the creator's browser; they pause without a host. The host can inspect bot hands
and modify policy, so this mode is for solo/trusted casual play. Existing saves retain controllers.
Purchase and Buy Card support private activity-scoped plans. Future-phase scripts, midgame controller
replacement, competitive bot hosting and fully offline authority remain deferred.

Automata improvements emphasize offline training and compact browser inference. The
[full-game workflow](contributing.md#full-game-strategy-experiments) tunes economic evaluation and
bounded planning on one four-player rules configuration. The
[purchase learner](contributing.md#purchase-policy-learning) also learns repeated shipping and
advance choices from complete-game outcomes. Each candidate
requires independent complete-game confirmation and suitable rival-population checks before adoption
within its qualified setup. Separate experimental continuation learners and the
[commodity pilot](follow-ups.md#automata-training-pilot) retain their
own validation gates and do not automatically replace the application policy. Qualification on one
profile does not establish strength on other variants or against humans; human playtests remain
required. This work preserves ordinary commands, player information boundaries and saved games.
