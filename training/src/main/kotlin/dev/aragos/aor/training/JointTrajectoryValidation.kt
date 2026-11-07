package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode

/** Validates one common origin before either typed phase can be reused for fitting. */
internal class JointTrajectoryValidation(
    private val input: JointCollectionInput,
    private val signature: String,
) {
    fun validate(node: JsonNode, index: Int) {
        require(index in input.seeds.indices) {
            "A joint game index must belong to its fixed bank."
        }
        PurchaseJson.fields(
            node,
            setOf(
                "schemaVersion",
                "signature",
                "index",
                "configuration",
                "gameSeed",
                "controlledSeat",
                "opponentIndices",
                "bundleSha256",
                "cardModelSha256",
                "purchaseModelSha256",
                "cardDecisions",
                "purchaseDecisions",
                "terminal",
            ),
        )
        require(
            integer(node, "schemaVersion") == 1 &&
                node.required("signature").asText() == signature &&
                integer(node, "index") == index &&
                node.required("configuration").asText() == input.configuration.id &&
                integer(node, "gameSeed") == input.seeds[index] &&
                integer(node, "controlledSeat") == input.seat(index) &&
                PurchaseJson.array(node.required("opponentIndices"), "opponentIndices").map {
                    PurchaseJson.integer(it, "opponent index")
                } == input.opponentIndices(index)
        ) {
            "A joint trajectory must preserve its exact origin, role and frozen rival schedule."
        }
        require(
            node.required("bundleSha256").asText() == input.bundle.sha256 &&
                node.required("cardModelSha256").asText() == input.bundle.card?.sha256 &&
                node.required("purchaseModelSha256").asText() == input.bundle.purchase.sha256
        ) {
            "Both joint phase models must belong to the same frozen behavior bundle."
        }
        val seat = input.seat(index)
        val steps =
            PurchaseTrajectoryValidation.terminal(
                node.required("terminal"),
                seat,
                input.configuration.playerCount,
            )
        val cards = PurchaseJson.array(node.required("cardDecisions"), "cardDecisions")
        val purchases = PurchaseJson.array(node.required("purchaseDecisions"), "purchaseDecisions")
        val cardNumbers = numbers(cards, steps)
        val purchaseNumbers = numbers(purchases, steps)
        require(cardNumbers.none { it in purchaseNumbers }) {
            "Joint card and purchase rows must have disjoint global decision numbers."
        }
        cards.forEach {
            CardTrajectoryValidation.decision(
                it,
                input.seeds[index],
                seat,
                input.bundle.cardDictionary,
                input.bundle.card.model,
            )
        }
        purchases.forEach {
            PurchaseTrajectoryValidation.decision(
                it,
                input.seeds[index],
                seat,
                input.bundle.purchaseDictionary,
                input.bundle.purchase.model,
            )
        }
        val ordered = (cards + purchases).sortedBy { integer(it, "number") }
        require(
            ordered.zipWithNext().all { (first, second) ->
                integer(first, "turn") <= integer(second, "turn")
            }
        ) {
            "Reconstructed joint phase interleaving must preserve increasing public turns."
        }
    }

    private fun numbers(rows: List<JsonNode>, steps: Int): List<Int> =
        rows
            .map { integer(it, "number") }
            .also { numbers ->
                require(
                    numbers == numbers.distinct().sorted() && numbers.all { it in 0 until steps }
                ) {
                    "Joint phase rows require distinct increasing absolute decision numbers."
                }
            }

    private fun integer(node: JsonNode, name: String): Int =
        PurchaseJson.integer(node.required(name), name)
}
