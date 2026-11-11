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

/** Fixed rival controls are indexed by the source opponent model, never by its rotating seat. */
internal enum class ArenaTokenOpponentSelector {
    ORDINARY,
    FIXED_16_TO_15,
    /** Subtract two from the actual ordinary bid only when that exact command is offered. */
    ORDINARY_MINUS_TWO,
}

/** Explicit evaluated-seat roles and an optional policy for each ordinary opponent model. */
class ArenaTokenOrderPolicy
private constructor(
    private val roles: Map<ArenaSide, ArenaTokenRole>,
    private val opponentSelectors: List<ArenaTokenOpponentSelector>?,
) {
    /** Root collection keeps the controlled seat's actual ordinary token-head reference. */
    internal fun validateCollectionContinuation() {
        require(roles.isEmpty()) {
            "Token collection cannot replace the controlled seat's ordinary token reference."
        }
        require(
            opponentSelectors?.all {
                it == ArenaTokenOpponentSelector.ORDINARY ||
                    it == ArenaTokenOpponentSelector.FIXED_16_TO_15
            } == true
        ) {
            "Token collection requires explicit ordinary or fixed token rival selectors."
        }
    }

    internal fun validate(configurations: List<TrainingConfiguration>, opponentCount: Int) {
        require(opponentSelectors == null || opponentSelectors.size == opponentCount) {
            "Token opponent selectors must contain exactly one entry per ordinary opponent model."
        }
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
        val evaluated =
            if (legacy) {
                prefix +
                    roles.entries.joinToString(":") { (side, role) ->
                        "$side=${role.input?.sha256}"
                    }
            } else {
                "token-order-arena-selectors-1:$prefix" +
                    roles.entries.joinToString(":") { (side, role) ->
                        "$side=${role.selector}:${role.input?.sha256 ?: "none"}"
                    }
            }
        return evaluated +
            (opponentSelectors?.joinToString(
                prefix = ":opponent-selectors-1:",
                separator = ",",
            ) {
                it.name
            } ?: "")
    }

    internal fun opponentControllers(
        playerCount: Int,
        controlledSeat: Int,
        opponentIndices: List<Int>?,
        opponents: List<BotModel>,
    ): Map<Int, SimulationController> {
        val selectors = opponentSelectors ?: return emptyMap()
        val indices =
            requireNotNull(opponentIndices) {
                "Token opponent selectors require the arena's explicit opponent-index lineup."
            }
        require(indices.size == playerCount && indices.all { it in opponents.indices }) {
            "A token opponent lineup must map every registered seat to an ordinary opponent model."
        }
        return indices
            .mapIndexedNotNull { seat, index ->
                if (seat == controlledSeat) return@mapIndexedNotNull null
                val controller =
                    when (selectors[index]) {
                        ArenaTokenOpponentSelector.ORDINARY -> null
                        ArenaTokenOpponentSelector.FIXED_16_TO_15 ->
                            TokenOrderController(
                                opponents[index],
                                ArenaTokenRole(ArenaTokenSelector.FIXED_16_TO_15, null),
                            )
                        ArenaTokenOpponentSelector.ORDINARY_MINUS_TWO ->
                            TokenMinusTwoController(opponents[index])
                    }
                controller?.let { seat to it }
            }
            .toMap()
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
                    opponentSelectors?.let {
                        put("opponentSelectors", it.map { selector -> selector.name })
                    }
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
                ArenaSide.entries.flatMap { listOf(modelField(it), selectorField(it)) }.toSet() +
                    "opponentSelectors",
            )
            val roles =
                ArenaSide.entries
                    .mapNotNull { side -> readRole(source, node, side)?.let { side to it } }
                    .toMap()
            val opponents =
                node["opponentSelectors"]?.let { values ->
                    require(values.isArray && !values.isEmpty) {
                        "Token opponent selectors must be a nonempty array."
                    }
                    values.map { value ->
                        val name = PurchaseJson.text(value, "opponent selector")
                        requireNotNull(
                            ArenaTokenOpponentSelector.entries.singleOrNull { it.name == name }
                        ) {
                            "Token opponent selector must be ORDINARY, FIXED_16_TO_15 or ORDINARY_MINUS_TWO."
                        }
                    }
                }
            require(roles.isNotEmpty() || opponents != null) {
                "A token-order arena overlay requires at least one explicit selector or model role."
            }
            return ArenaTokenOrderPolicy(roles, opponents)
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

/** Diagnostic rival only; uses its own ordinary head and never clips to an unoffered amount. */
private class TokenMinusTwoController(private val baseline: BotModel) : SimulationController {
    override fun controls(decision: String): Boolean = decision == "buy-tokens"

    override fun choose(decision: SimulationDecision): String {
        val ordinary =
            BotPolicy.choose(decision.observation, BotDifficulty.HARD, decision.seed, baseline)
        val amount = ordinary.substringAfter("buy-tokens ").toLong() - 2L
        val alternative = "buy-tokens $amount"
        return if (decision.observation.candidates.any { it.command == alternative }) alternative
        else ordinary
    }
}
