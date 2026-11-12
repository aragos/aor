# Phase-specific turn-order learning prerequisite

Renaissance is a promising untrained decision family. It can change who draws, plays cards,
purchases, or expands first, and using it consumes the turn's opportunity. The current ordinary
policy scores a swap by the movement toward earlier order, without a phase-specific or
opponent-specific action feature. See [the feature owner](../ai/src/commonMain/kotlin/dev/aragos/aor/ai/BotObservations.kt#L345),
[legal windows](../core/src/commonMain/kotlin/dev/aragos/aor/TurnEngine.kt#L267), and
[the swap transition](../core/src/commonMain/kotlin/dev/aragos/aor/TurnCommand.kt#L55).
[Purchase](../training/src/main/kotlin/dev/aragos/aor/training/PurchaseActorController.kt#L21)
and [card](../training/src/main/kotlin/dev/aragos/aor/training/CardActorController.kt#L32)
learners leave these decisions to the ordinary policy.

Other distinct opportunities are adjustment cancellation, Wind/Watermill exchanges, and
expansion thresholds that award cards. Cancellation currently uses cost and own market count
without encoding the adjustment's direction in its ordinary candidate features; see
[cancellation features](../ai/src/commonMain/kotlin/dev/aragos/aor/ai/BotObservations.kt#L576).
Wind/Watermill uses a fixed received-card proxy and values the offered card mainly by immediate
own cash; see [trade features](../ai/src/commonMain/kotlin/dev/aragos/aor/ai/BotObservations.kt#L550)
and [card valuation](../ai/src/commonMain/kotlin/dev/aragos/aor/ai/BotObservations.kt#L701).
Commodity [expansion interactions](../ai/src/commonMain/kotlin/dev/aragos/aor/ai/BotExpansionPlanning.kt)
explicitly exclude future card ownership and payout ordering. Majority shortage cards and
new-marker bonuses therefore deserve separate attention from buying a card with cash or tokens.
These are modeling gaps, not proofs that a particular observed action was wrong.

The first prerequisite is a read-only coverage pass over completed synthetic canonical histories.
Count independent worlds, controlled-seat ownership, actual offered windows by phase and turn,
pass/swap behavior, and forced versus branching choices. Keep worlds with no opportunity. Retain
all-seat counts only as descriptive context; seats and multiple windows in one world are not new
independent samples. Reconstruct actual owner choices before each recorded command; do not call
a policy, sample a future, evaluate an alternative, or use terminal scores to select windows.

If coverage supports it, a later bounded diagnostic could retain at most 256 prospective roots,
all offered pass/adjacent-swap commands (at most three), and two fresh paired continuations per
command: at most 1,536 new terminal tails when reusing completed origins. Existing
[session forks](../training/src/main/kotlin/dev/aragos/aor/training/ExperimentSession.kt) and
[absolute policy offsets](../training/src/main/kotlin/dev/aragos/aor/training/FrozenPolicyContinuation.kt)
support this without another simulation framework. Preserve every action return; do not label a
privileged best action from one hidden world. Seeds do not resample the retained hands or deck.
The phase distribution and missing roots must be reviewed before deciding that this would test
substantive timing rather than almost exclusively the first Draw Card window.

Token-neighbor experiments, leader retention/discount commitment diagnostics, and joint
card/purchase credit learning do not directly learn Renaissance control. Broad ordinary-weight
searches may have changed generic order preferences, so this is not a claim that order was never
touched. The completed acquisition probe did not establish useful conditional prediction; it
does not authorize another parameter sweep here. Any public student produced by a future
experiment needs a fresh strength test. This proposal authorizes no tails, fit, or activation.
