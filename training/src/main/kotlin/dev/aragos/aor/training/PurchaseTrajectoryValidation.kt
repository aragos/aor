package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.ai.PurchaseAction
import dev.aragos.aor.ai.PurchaseObservation
import dev.aragos.aor.ai.PurchaseObservationEncoder
import dev.aragos.aor.ai.PurchasePolicy
import kotlin.math.abs

/** Cached trajectories must retain exact origin/policy identity and recomputable behavior facts. */
internal class PurchaseTrajectoryValidation(
    private val input: PurchaseCollectionInput,
    private val signature: String,
) {
    fun validate(node: JsonNode, index: Int) {
        require(index in input.seeds.indices) {
            "A purchase game index must belong to its frozen seed bank."
        }
        PurchaseJson.fields(
            node,
            setOf(
                "schemaVersion",
                "signature",
                "mode",
                "encoderVersion",
                "actorVersion",
                "modelSha256",
                "index",
                "configuration",
                "gameSeed",
                "controlledSeat",
                "opponentIndices",
                "decisions",
                "terminal",
            ),
        )
        require(
            node.required("signature").asText() == signature &&
                integer(node, "schemaVersion") == 1 &&
                integer(node, "index") == index &&
                node.required("configuration").asText() == input.configuration.id &&
                integer(node, "gameSeed") == input.seeds[index] &&
                integer(node, "controlledSeat") == input.seat(index) &&
                PurchaseJson.array(node.required("opponentIndices"), "opponentIndices").map {
                    PurchaseJson.integer(it, "opponent index")
                } == input.opponentIndices(index)
        ) {
            "A cached purchase game must preserve its exact signature, seed, controlled seat and lineup."
        }
        require(
            node.required("mode").asText() == input.mode &&
                node.required("encoderVersion").asText() ==
                    PurchaseObservationEncoder.ENCODER_VERSION &&
                node.required("actorVersion").asText() == PurchasePolicy.ACTOR_VERSION &&
                node.required("modelSha256").let {
                    if (input.modelSha256 == null) it.isNull else it.asText() == input.modelSha256
                }
        ) {
            "A cached purchase game must preserve its encoder, actor and exact behavior model."
        }
        val terminalNode = node.required("terminal")
        val steps = terminal(terminalNode, input.seat(index))
        val decisions = PurchaseJson.array(node.required("decisions"), "decisions")
        require(decisions.size <= steps) {
            "A purchase trajectory cannot contain more records than game decisions."
        }
        val numbers = decisions.map { integer(it, "number") }
        require(numbers == numbers.distinct().sorted() && numbers.all { it in 0 until steps }) {
            "Purchase trajectory decisions must have distinct increasing game command indices."
        }
        decisions.forEach { decision(it, index) }
    }

    private fun decision(node: JsonNode, gameIndex: Int) {
        PurchaseJson.fields(
            node,
            setOf(
                "number",
                "actorSeed",
                "decision",
                "turn",
                "observation",
                "selectedIndex",
                "behaviorLogProbability",
                "behaviorValue",
            ),
        )
        require(
            node.required("decision").asText() in setOf("purchase-shipping", "purchase-advances")
        ) {
            "Only shipping and advance decisions belong to a purchase trajectory."
        }
        require(integer(node, "turn") > 0) {
            "A collected purchase decision requires its actual game turn."
        }
        val seed = integer(node, "actorSeed")
        val baselineSeed =
            input.seeds[gameIndex] xor
                (integer(node, "number") * 65537 + input.seat(gameIndex) * 8191)
        require(seed == PurchasePolicy.actorSeed(baselineSeed)) {
            "A purchase record must preserve its independently derived actor sampling seed."
        }
        val observation = observation(node.required("observation"))
        require(
            observation.stateFeatures.keys.all { it in input.dictionary.stateFeatureNames } &&
                observation.actions.all { action ->
                    action.features.keys.all { it in input.dictionary.actionFeatureNames }
                }
        ) {
            "A purchase record contains a feature outside its frozen complete dictionary."
        }
        val selected = integer(node, "selectedIndex")
        require(selected in observation.actions.indices) {
            "A purchase action index must reference an offered command."
        }
        val policy = input.policy
        if (policy == null) {
            require(
                selected == observation.baselineIndex &&
                    node.required("behaviorLogProbability").isNull &&
                    node.required("behaviorValue").isNull
            ) {
                "Baseline imitation must select its exact recommendation without invented behavior likelihoods."
            }
            return
        }
        val expected = PurchasePolicy.choose(observation, policy, seed)
        require(selected == expected.index) {
            "A cached purchase action must match the frozen actor and sampling seed."
        }
        close(
            PurchaseJson.number(node.required("behaviorLogProbability"), "behaviorLogProbability"),
            expected.logProbability,
            "behavior log probability",
        )
        close(
            PurchaseJson.number(node.required("behaviorValue"), "behaviorValue"),
            expected.value,
            "behavior value",
        )
    }

    private fun terminal(node: JsonNode, seat: Int): Int {
        PurchaseJson.fields(
            node,
            setOf("reward", "winShare", "rankReward", "scores", "gameDecisions"),
        )
        val scores = PurchaseJson.array(node.required("scores"), "scores")
        require(scores.size == input.configuration.playerCount) {
            "Terminal scores must contain every registered seat."
        }
        scores.forEachIndexed { index, score ->
            PurchaseJson.fields(
                score,
                setOf("player", "advancePoints", "cash", "misery", "total", "winner"),
            )
            require(
                score.required("player").asText() == "p${index + 1}" &&
                    score.required("winner").isBoolean
            ) {
                "Terminal purchase labels must preserve registration identities and explicit winner flags."
            }
            val misery = nullableInteger(score.required("misery"), "misery")
            val total = nullableInteger(score.required("total"), "total")
            val cash = integer(score, "cash")
            val advancePoints = integer(score, "advancePoints")
            require(total == misery?.let { advancePoints + cash - it }) {
                "Terminal score totals must equal public advance points plus cash minus misery."
            }
        }
        val totals = scores.map { nullableInteger(it.required("total"), "total") }
        val maximum = totals.filterNotNull().maxOrNull()
        val winners = scores.indices.filter { totals[it] != null && totals[it] == maximum }
        require(
            scores.indices.all { scores[it].required("winner").booleanValue() == (it in winners) }
        ) {
            "Terminal winner flags must agree with public scores, including ties and Chaos."
        }
        val win = if (seat in winners) 1.0 / winners.size else 0.0
        val own = totals[seat]
        val rank =
            if (own == null) 0.0
            else
                totals
                    .filterIndexed { index, _ -> index != seat }
                    .sumOf { other ->
                        when {
                            other == null || own > other -> 1.0
                            own == other -> 0.5
                            else -> 0.0
                        }
                    } / (totals.size - 1)
        close(PurchaseJson.number(node.required("winShare"), "winShare"), win, "win share")
        close(PurchaseJson.number(node.required("rankReward"), "rankReward"), rank, "rank reward")
        close(
            PurchaseJson.number(node.required("reward"), "reward"),
            .8 * win + .2 * rank,
            "terminal reward",
        )
        return integer(node, "gameDecisions").also {
            require(it in 1..12000) {
                "A completed purchase game must respect the simulator's decision bound."
            }
        }
    }

    companion object {
        internal fun observation(node: JsonNode): PurchaseObservation {
            PurchaseJson.fields(
                node,
                setOf("encoderVersion", "stateFeatures", "actions", "baselineIndex"),
            )
            return PurchaseObservation(
                PurchaseJson.text(node.required("encoderVersion"), "encoderVersion"),
                features(node.required("stateFeatures")),
                PurchaseJson.array(node.required("actions"), "actions").map { action ->
                    PurchaseJson.fields(action, setOf("command", "features"))
                    PurchaseAction(
                        PurchaseJson.text(action.required("command"), "command"),
                        features(action.required("features")),
                    )
                },
                integer(node, "baselineIndex"),
            )
        }

        private fun features(node: JsonNode): Map<String, Double> {
            require(node.isObject) { "Sparse purchase features must be a JSON object." }
            return node.properties().associate { (key, value) ->
                key to PurchaseJson.number(value, key)
            }
        }

        private fun integer(node: JsonNode, name: String): Int =
            PurchaseJson.integer(node.required(name), name)

        private fun nullableInteger(node: JsonNode, name: String): Int? =
            if (node.isNull) null else PurchaseJson.integer(node, name)

        private fun close(actual: Double, expected: Double, name: String) {
            require(abs(actual - expected) <= 1e-12) {
                "Cached purchase $name does not match its recomputed value."
            }
        }
    }
}
