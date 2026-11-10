package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TokenObservationEncoder
import dev.aragos.aor.ai.TokenOrderPolicy
import java.nio.file.Path

/** Explicit evaluated-seat token roles; every rival retains its ordinary token policy. */
class ArenaTokenOrderPolicy
private constructor(private val roles: Map<ArenaSide, TerritoryValueInput>) {
    internal fun validate(configurations: List<TrainingConfiguration>) {
        require(
            configurations.all { configuration ->
                val dictionary =
                    TokenObservationEncoder.dictionary(
                        PublicCatalog.forPlayers(configuration.playerCount, configuration.rules),
                        TerritoryEncoder.TOKEN_2,
                    )
                roles.values.all { input ->
                    input.model.encoderVersion == TerritoryEncoder.TOKEN_2.version &&
                        input.model.stateFeatureNames == dictionary.stateFeatureNames &&
                        input.model.actionFeatureNames == dictionary.actionFeatureNames
                }
            }
        ) {
            "Token-order arena models require complete token version 2 dictionaries."
        }
    }

    internal fun identity(): String =
        "${TokenOrderPolicy.SELECTOR_VERSION}:${TokenOrderPolicy.PROPOSAL_VERSION}:" +
            roles.entries.joinToString(":") { (side, input) -> "$side=${input.sha256}" }

    internal fun controller(side: ArenaSide?, baseline: BotModel): SimulationController? {
        require(side != null) {
            "Token-order roles require an explicit candidate or incumbent side."
        }
        return roles[side]?.let { TokenOrderController(baseline, it) }
    }

    internal fun metadata(): Map<String, Any> =
        mapOf(
            "tokenOrder" to
                roles.entries.associate { (side, input) ->
                    field(side) to
                        mapOf(
                            "path" to input.path.toString(),
                            "sha256" to input.sha256,
                            "snapshot" to snapshotName(side),
                        )
                }
        )

    internal fun snapshot(artifacts: TrainingArtifacts) {
        roles.forEach { (side, input) ->
            artifacts.ensureFileSnapshot(input.path, input.sha256, snapshotName(side))
        }
    }

    companion object {
        private fun field(side: ArenaSide) = "${side.name.lowercase()}Model"

        private fun snapshotName(side: ArenaSide) =
            "token-order-${side.name.lowercase()}-model.json"

        internal fun read(source: Path, node: JsonNode): ArenaTokenOrderPolicy {
            PurchaseJson.fields(node, emptySet(), ArenaSide.entries.map(::field).toSet())
            val roles =
                ArenaSide.entries
                    .mapNotNull { side ->
                        node[field(side)]?.let { value ->
                            val path =
                                source
                                    .toAbsolutePath()
                                    .parent
                                    .resolve(PurchaseJson.text(value, field(side)))
                                    .toRealPath()
                            val hash = PurchaseJson.hash(path)
                            val model = TerritoryValuePolicyInput.read(path)
                            require(PurchaseJson.hash(path) == hash) {
                                "A token-order model changed while reading."
                            }
                            side to TerritoryValueInput(path, hash, model)
                        }
                    }
                    .toMap()
            require(roles.isNotEmpty()) {
                "A token-order arena overlay requires at least one model role."
            }
            return ArenaTokenOrderPolicy(roles)
        }
    }
}

/** Ordinary and Purchase priors are intentionally separate; this controller touches only tokens. */
internal class TokenOrderController(
    private val baseline: BotModel,
    private val input: TerritoryValueInput,
) : SimulationController {
    override fun controls(decision: String): Boolean = decision == "buy-tokens"

    override fun choose(decision: SimulationDecision): String {
        val ordinary =
            BotPolicy.choose(decision.observation, BotDifficulty.HARD, decision.seed, baseline)
        val observation =
            TokenObservationEncoder.from(
                decision.view,
                PlayerId(decision.observation.playerId),
                decision.observation,
                ordinary,
                TerritoryEncoder.TOKEN_2,
            )
        return TokenOrderPolicy.choose(decision.observation, observation, input.model)
    }
}
