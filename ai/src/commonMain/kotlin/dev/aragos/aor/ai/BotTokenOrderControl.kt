package dev.aragos.aor.ai

/**
 * Optional, separately qualified token control applied after the ordinary token heads. Exactly one
 * enables the fixed 16-to-15 experiment; all other values retain the incumbent. The model's
 * version/configuration routing owns its qualified scope. This control observes only the actual
 * incumbent command and offered alternatives, never sealed rival purchases.
 */
object BotTokenOrderControl {
    const val FIXED_16_TO_15 = "planning.tokenOrderControl.fixed16To15"
    private const val PREFIX = "planning.tokenOrderControl."

    internal fun reserved(name: String): Boolean = name.startsWith(PREFIX)

    internal fun choose(
        observation: BotObservation,
        incumbent: BotCandidate,
        model: BotModel,
    ): BotCandidate {
        if (model.weights[FIXED_16_TO_15] != 1.0 || incumbent.command != "buy-tokens 16") {
            return incumbent
        }
        return observation.candidates.firstOrNull { it.command == "buy-tokens 15" } ?: incumbent
    }
}
