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

/** Native experiment selectors; none changes the browser policy or the learned model bytes. */
internal enum class ArenaTokenSelector {
    /** Original full-proposal Q selection, including its fixed advantage margin. */
    MODEL,
    /** Retain the original Q choice only for a one-token change; never reselect a neighbor. */
    ADJACENT_VETO,
    /** Replace the actual ordinary bid of 16 with an offered 15; otherwise retain it exactly. */
    FIXED_16_TO_15,
}

/** A fixed control has no unused model; both learned selectors require their frozen Q model. */
internal data class ArenaTokenRole(
    val selector: ArenaTokenSelector,
    val input: TerritoryValueInput?,
) {
    init {
        require((selector == ArenaTokenSelector.FIXED_16_TO_15) == (input == null)) {
            "Fixed token control must omit its model; learned token selectors require a model."
        }
    }
}

/** Explicit evaluated-seat token roles; every rival retains its ordinary token policy. */
class ArenaTokenOrderPolicy private constructor(private val roles: Map<ArenaSide, ArenaTokenRole>) {
    internal fun validate(configurations: List<TrainingConfiguration>) {
        val models = roles.values.mapNotNull { it.input?.model }
        if (models.isEmpty()) return
        require(
            configurations.all { configuration ->
                val dictionary =
                    TokenObservationEncoder.dictionary(
                        PublicCatalog.forPlayers(configuration.playerCount, configuration.rules),
                        TerritoryEncoder.TOKEN_2,
                    )
                models.all { model ->
                    model.encoderVersion == TerritoryEncoder.TOKEN_2.version &&
                        model.stateFeatureNames == dictionary.stateFeatureNames &&
                        model.actionFeatureNames == dictionary.actionFeatureNames
                }
            }
        ) {
            "Token-order arena models require complete token version 2 dictionaries."
        }
    }

    internal fun identity(): String {
        val legacy = roles.values.all { it.selector == ArenaTokenSelector.MODEL }
        val prefix = "${TokenOrderPolicy.SELECTOR_VERSION}:${TokenOrderPolicy.PROPOSAL_VERSION}:"
        return if (legacy) {
            prefix +
                roles.entries.joinToString(":") { (side, role) -> "$side=${role.input?.sha256}" }
        } else {
            "token-order-arena-selectors-1:$prefix" +
                roles.entries.joinToString(":") { (side, role) ->
                    "$side=${role.selector}:${role.input?.sha256 ?: "none"}"
                }
        }
    }

    internal fun controller(side: ArenaSide?, baseline: BotModel): SimulationController? {
        require(side != null) {
            "Token-order roles require an explicit candidate or incumbent side."
        }
        return roles[side]?.let { TokenOrderController(baseline, it) }
    }

    internal fun metadata(): Map<String, Any> =
        mapOf(
            "tokenOrder" to
                buildMap {
                    roles.forEach { (side, role) ->
                        role.input?.let { input ->
                            put(
                                modelField(side),
                                mapOf(
                                    "path" to input.path.toString(),
                                    "sha256" to input.sha256,
                                    "snapshot" to snapshotName(side),
                                ),
                            )
                        }
                        if (role.selector != ArenaTokenSelector.MODEL) {
                            put(selectorField(side), role.selector.name)
                        }
                    }
                }
        )

    internal fun snapshot(artifacts: TrainingArtifacts) {
        roles.forEach { (side, role) ->
            role.input?.let { input ->
                artifacts.ensureFileSnapshot(input.path, input.sha256, snapshotName(side))
            }
        }
    }

    companion object {
        private fun modelField(side: ArenaSide) = "${side.name.lowercase()}Model"

        private fun selectorField(side: ArenaSide) = "${side.name.lowercase()}Selector"

        private fun snapshotName(side: ArenaSide) =
            "token-order-${side.name.lowercase()}-model.json"

        internal fun read(source: Path, node: JsonNode): ArenaTokenOrderPolicy {
            PurchaseJson.fields(
                node,
                emptySet(),
                ArenaSide.entries.flatMap { listOf(modelField(it), selectorField(it)) }.toSet(),
            )
            val roles =
                ArenaSide.entries
                    .mapNotNull { side -> readRole(source, node, side)?.let { side to it } }
                    .toMap()
            require(roles.isNotEmpty()) {
                "A token-order arena overlay requires at least one explicit selector or model role."
            }
            return ArenaTokenOrderPolicy(roles)
        }

        private fun readRole(source: Path, node: JsonNode, side: ArenaSide): ArenaTokenRole? {
            val model = node[modelField(side)]
            val selector = node[selectorField(side)]
            if (model == null && selector == null) return null
            val selected =
                selector?.let { value ->
                    val name = PurchaseJson.text(value, selectorField(side))
                    requireNotNull(ArenaTokenSelector.entries.singleOrNull { it.name == name }) {
                        "Token selector must be MODEL, ADJACENT_VETO or FIXED_16_TO_15."
                    }
                } ?: ArenaTokenSelector.MODEL
            return ArenaTokenRole(selected, model?.let { readModel(source, it, modelField(side)) })
        }

        private fun readModel(source: Path, value: JsonNode, field: String): TerritoryValueInput {
            val path =
                source.toAbsolutePath().parent.resolve(PurchaseJson.text(value, field)).toRealPath()
            val hash = PurchaseJson.hash(path)
            val model = TerritoryValuePolicyInput.read(path)
            require(PurchaseJson.hash(path) == hash) {
                "A token-order model changed while reading."
            }
            return TerritoryValueInput(path, hash, model)
        }
    }
}

/** Ordinary and Purchase priors are intentionally separate; this controller touches only tokens. */
internal class TokenOrderController(
    private val baseline: BotModel,
    private val role: ArenaTokenRole,
) : SimulationController {
    override fun controls(decision: String): Boolean = decision == "buy-tokens"

    override fun choose(decision: SimulationDecision): String {
        val ordinary =
            BotPolicy.choose(decision.observation, BotDifficulty.HARD, decision.seed, baseline)
        if (role.selector == ArenaTokenSelector.FIXED_16_TO_15) {
            return if (
                ordinary == "buy-tokens 16" &&
                    decision.observation.candidates.any { it.command == "buy-tokens 15" }
            ) {
                "buy-tokens 15"
            } else ordinary
        }
        val input = requireNotNull(role.input) { "A learned token role retains its Q model." }
        val observation =
            TokenObservationEncoder.from(
                decision.view,
                PlayerId(decision.observation.playerId),
                decision.observation,
                ordinary,
                TerritoryEncoder.TOKEN_2,
            )
        val selected = TokenOrderPolicy.choose(decision.observation, observation, input.model)
        if (role.selector == ArenaTokenSelector.MODEL || selected == ordinary) return selected
        val amount = selected.substringAfter("buy-tokens ").toLong()
        val baselineAmount = ordinary.substringAfter("buy-tokens ").toLong()
        return if (amount - baselineAmount in listOf(-1L, 1L)) selected else ordinary
    }
}
