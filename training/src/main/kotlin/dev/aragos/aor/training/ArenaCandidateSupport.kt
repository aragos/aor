package dev.aragos.aor.training

import dev.aragos.aor.ai.KnownOwnChaosSupport

/** Explicit inference-only behavior; legacy sampled trajectory collectors do not support it. */
internal enum class ArenaCandidateSupport(val version: String) {
    KNOWN_OWN_CHAOS_1(KnownOwnChaosSupport.VERSION)
}
