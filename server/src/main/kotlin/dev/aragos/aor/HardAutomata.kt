package dev.aragos.aor

import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.BotPurchaseFeature
import dev.aragos.aor.ai.PersistentLowBidResponse
import dev.aragos.aor.ai.PhaseInitiativePolicy
import java.security.MessageDigest
import java.util.HexFormat

/** Keep this version's weights and policy settings available for saved automated seats. */
internal const val HARD_AUTOMATA_VERSION = "aor-hard-2026-11-20-1"

internal object HardAutomata {
    private const val MAX_MODEL_BYTES = 150_000
    private val finish = FinalAdvanceGuard(currentLead = 100, projectedLead = 250)

    private enum class Asset(file: String, expectedHash: String) {
        THREE_PLAYER(
            "three-player-continuation-model.json",
            "7446542a3e85f3db8b31d0fda632ae0d4f1ff809188b68cfda5899841ff57624",
        ),
        FOUR_AND_FIVE_PLAYER(
            "continuation-model.json",
            "956b39695f744ce835a72af6452e33d061b74479a8b13938e3b968f18ce4556e",
        ),
        SIX_PLAYER(
            "broad-model.json",
            "a479db4b3c7f580ce9979049fe407cbde4db22bf75b76d3cc54ac6f2039ce500",
        );

        val model by lazy {
            val bytes =
                checkNotNull(HardAutomata::class.java.getResourceAsStream("/automata/$file")) {
                        "The packaged automata model is missing: $file."
                    }
                    .use { it.readNBytes(MAX_MODEL_BYTES + 1) }
            check(bytes.size in 1..MAX_MODEL_BYTES) {
                "The automata model exceeds its byte budget."
            }
            val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
            check(hash == expectedHash) {
                "The automata model must match the evaluated checkpoint."
            }
            val root = json.readTree(bytes)
            check(
                root["rulesVersion"]?.asText() == RULES_VERSION &&
                    root["dataVersion"]?.asText() == DATA_VERSION
            ) {
                "The automata model requires the current rules and catalog."
            }
            BotModels.fromSerializedWeights(
                root.field("weights").properties().associate { (name, value) ->
                    name to value.doubleValue()
                },
                schemaVersion = root.field("schemaVersion").intValue(),
            )
        }
    }

    /** Each player count compiles its adjusted weights on its first automated decision. */
    private enum class Table(val players: Int, asset: Asset) {
        THREE(3, Asset.THREE_PLAYER),
        FOUR(4, Asset.FOUR_AND_FIVE_PLAYER),
        FIVE(5, Asset.FOUR_AND_FIVE_PLAYER),
        SIX(6, Asset.SIX_PLAYER);

        val model: BotModel by lazy {
            val baseline = asset.model
            baseline.copy(
                weights =
                    baseline.weights +
                        buildMap {
                            put(BotPurchaseFeature.INDULGENCE_SWING.key, 6.0)
                            put(BotPurchaseFeature.INDULGENCE_COUNTER.key, 8.0)
                            put(BotPurchaseFeature.PATRONAGE_ACCESS.key, 20.0)
                            put(BotPurchaseFeature.RELIEF_TIER.key, 20.0)
                            if (players == 5) put(BotPurchaseFeature.OVERSEAS_ACCESS.key, .6)
                        }
            )
        }

        val initiative =
            when (players) {
                4,
                6 -> PhaseInitiativePolicy.response(maximumTokenLoss = 8, earlyBias = .48)
                5 ->
                    PhaseInitiativePolicy.response(
                        maximumTokenLoss = 6,
                        earlyBias = .30,
                        lowBidResponse = PersistentLowBidResponse(tokenMargin = 1, earlyBias = .48),
                    )
                else -> PhaseInitiativePolicy.response(maximumTokenLoss = 6, earlyBias = .30)
            }
    }

    fun choose(state: GameState, seat: PlayerId, seed: Int): String {
        val table = Table.entries.single { it.players == state.playerIds.size }
        val view = state.viewForPlayer(seat)
        val observation = BotObservations.from(state, seat)
        val ordinary = BotPolicy.choose(observation, BotDifficulty.HARD, seed, table.model)
        val selected =
            when (observation.decision) {
                "bid-capital" ->
                    if (table == Table.FIVE) {
                        PhaseInitiativePolicy.varyCapitalBid(observation, ordinary, seed)
                    } else ordinary
                "buy-tokens" ->
                    PhaseInitiativePolicy.bid(view, seat, ordinary, seed, table.initiative)
                "purchase-advances" ->
                    finish.choose(state, view, seat, ordinary)?.takeIf { command ->
                        observation.candidates.any { it.command == command }
                    } ?: ordinary
                else -> ordinary
            }
        if (observation.candidates.none { it.command == selected }) {
            throw ServiceFailure(503, "BOT_POLICY_FAILED")
        }
        return selected
    }
}
