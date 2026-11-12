# Commodity cancellation representation

The current ordinary evaluator cannot learn the direction of a cancellation from its numeric
inputs. [The adapter](../ai/src/commonMain/kotlin/dev/aragos/aor/ai/BotObservations.kt#L576)
adds the quoted cash cost and the actor's affected market count, with the same contextual copies
for shortage and surplus. [Scoring](../ai/src/commonMain/kotlin/dev/aragos/aor/ai/BotPolicy.kt#L10)
uses those features; bounded planning only extends advance and expansion decisions. Command and
group strings still name the adjustment, so this is a numeric-feature alias, not identical wire
observations.

The read-only [audit helper](../tools/AdjustmentAliasAudit.java) replays the 256 explicitly pinned,
completed family55 synthetic backbones. At their first branching cancellation window (origin 0,
entry 140, turn 3, p1), it mirrors every marker in each affected commodity's queue while preserving
all other state components. Gold and Ivory are always mirrored together when affected. These are
clearly labeled synthetic fixtures; they do not assert that the mirrored state follows the same
history. No hidden authority facts are exported.

| Cancellation | Cost | Current own public payout before → after | M8 score relative to PASS |
| --- | ---: | ---: | ---: |
| Cloth surplus | 5 | 5 → 20 | +3.2928533 |
| Mirrored cloth shortage | 5 | 45 → 20 | +3.2928533 |
| Grain shortage | 4 | 16 → 4 | +1.3380324 |
| Mirrored grain surplus | 4 | 0 → 4 | +1.3380324 |

Each pair has exactly equal numeric candidate features and context. Payouts come from Core's
[public projection](../core/src/commonMain/kotlin/dev/aragos/aor/EconomyProjection.kt#L35), before
and after one legal cancellation; no chance event or continuation is executed. The forecast is
conditional on a future commodity payout, not realized cash or evidence of optimal play. Rival
payouts also move in opposite directions.
[Only the first queued adjustment affects payment](../core/src/commonMain/kotlin/dev/aragos/aor/CardEffects.kt#L759),
so cancelling a later queued
marker can have a different immediate effect even for the same adjustment kind.

The retained games contain 4,676 cancellation windows across 256 independent origins: 1,335 recorded
surplus cancellations, 1,242 shortage cancellations and 2,099 passes. There are 2,047 windows with
one cancellation plus PASS, and 2,629 with two cancellations plus PASS. Repeated windows within a
game are dependent. These counts establish decision coverage, not cancellation quality.

A small next mechanism would extend the authoritative typed cancellation offer with adjustment
kind, affected queues and current public payout changes by relative seat after the exact removal.
Reuse [the cancellation owner](../core/src/commonMain/kotlin/dev/aragos/aor/AdjustmentCancellation.kt)
and existing payout rules; do not recreate payout arithmetic in AI. Expose signed own/rival payout
changes and queue position as explicit opt-in features, leaving old model defaults unchanged.

After mirrored, later-queue and paired Gold/Ivory tests, a bounded 256-origin data pilot could retain
PASS and every offered cancellation (at most two in this retained coverage), with two paired
continuation streams and exact frozen G2/E/T/M8 continuation. Keep all action returns, missing
roots and independent-world identities. A public-input learner and fresh whole-game evaluation
would decide whether paying for cancellation helps; no blanket cancel/retain rule follows from
this counterexample. The current audit contains zero new games, tails or fits.
