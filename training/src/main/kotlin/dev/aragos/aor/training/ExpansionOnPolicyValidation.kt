package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode

/** New typed trajectory boundary; no existing token/expansion-v1 reader is relaxed. */
internal class ExpansionOnPolicyValidation(private val input: ExpansionOnPolicyInput) {
    fun validate(node: JsonNode, index: Int) {
        PurchaseJson.fields(
            node,
            setOf(
                "schemaVersion",
                "kind",
                "signature",
                "index",
                "configuration",
                "gameSeed",
                "controlledSeat",
                "opponentIndices",
                "actorModelSha256",
                "frozenCardPurchaseBundleSha256",
                "referenceExpansionModelSha256",
                "arenaSourceSha256",
                "expansionDecisions",
                "terminal",
            ),
        )
        require(
            index in input.seeds.indices &&
                integer(node, "schemaVersion") == 1 &&
                node["kind"].asText() == "expansion-on-policy-trajectory-1" &&
                node["signature"].asText() == input.signature &&
                integer(node, "index") == index &&
                node["configuration"].asText() == input.configuration.id &&
                integer(node, "gameSeed") == input.seeds[index] &&
                integer(node, "controlledSeat") == input.seat(index) &&
                PurchaseJson.array(node["opponentIndices"], "opponentIndices").map {
                    PurchaseJson.integer(it, "opponent index")
                } == List(4) { 0 }
        ) {
            "Expansion trajectory must retain its exact origin, setup and role schedule."
        }
        require(
            node["actorModelSha256"].asText() == input.actor.sha256 &&
                node["frozenCardPurchaseBundleSha256"].asText() ==
                    input.frozenCardPurchaseBundleSha256 &&
                node["referenceExpansionModelSha256"].asText() ==
                    input.referenceExpansionModelSha256 &&
                node["arenaSourceSha256"].asText() == input.arenaSourceSha256
        ) {
            "Expansion behavior and every frozen continuation/reference identity must remain exact."
        }
        val steps = PurchaseTrajectoryValidation.terminal(node["terminal"], input.seat(index), 4)
        val rows = PurchaseJson.array(node["expansionDecisions"], "expansionDecisions")
        val numbers = rows.map { integer(it, "number") }
        require(numbers == numbers.distinct().sorted() && numbers.all { it in 0 until steps }) {
            "Expansion rows require distinct chronological absolute PLAYER numbers."
        }
        require(
            rows.zipWithNext().all { (first, next) ->
                integer(first, "turn") <= integer(next, "turn")
            }
        ) {
            "Expansion rows must preserve public turn order."
        }
        rows.forEach { decision(it, index) }
    }

    internal fun decision(node: JsonNode, index: Int) {
        ExpansionBehaviorValidation.validate(
            node,
            input.seeds[index],
            input.seat(index),
            input.actor.model,
        )
    }

    private fun integer(node: JsonNode, name: String): Int =
        PurchaseJson.integer(node.required(name), name)
}
