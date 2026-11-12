# Known own Chaos support

`known-own-chaos-support-1` is an experimental inference behavior, separate from the original
full-support card and purchase policies. It uses the unchanged actor observation, weights, prior,
value and uniform draw. The common owner is `KnownOwnChaosSupport`.

The card condition uses the authoritative `effect.role.0.chaos` action feature only when the own
`miseryUncertain` feature is absent or zero. Other seats' consequences and unknown outcomes do not
remove actions. The purchase condition uses the authoritative advance `chaos` action feature.
These features describe immediate public consequences, not expected future survival.

If at least one offered action is not known to cause own Chaos, known own-Chaos actions receive
zero probability. Each remaining probability is its original learned probability divided by the
remaining mass. Sampling consumes one uniform draw, preserves original indices and command order,
and uses the last eligible action for cumulative rounding fallback. The reported log probability
is the natural logarithm of the conditional probability. With no exclusions, including a compulsory
all-known-zero set, the original sampler and log probability are used exactly.

Native arena sources opt in with `jointPolicy.candidateSupport: "KNOWN_OWN_CHAOS_1"`. The profile
and version enter the arena identity and binding independently of immutable bundle hashes. It
applies only to the controlled candidate seat; incumbent and shared rivals retain the original
samplers. Token and expansion composition is unchanged. Omission preserves the old identity,
metadata and behavior. Both candidate learned heads are required.

This profile is inference-only. Existing recording callbacks are rejected before a choice, and
the original collection source schema rejects the profile. Original evaluation/choice types and
trajectory validators retain their full-support contracts. Training with conditional support would
require a separate explicitly versioned collection, reader and objective contract. This source
does not activate a browser/server policy or establish playing strength.
