package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** New triple wire boundary; existing C/P and E likelihood owners keep their original guards. */
internal class TripleTrajectoryValidation(private val input: TripleCollectionInput) {
    fun validate(node: JsonNode, index: Int) {
        require(node.isObject) { "A triple trajectory must be an object." }
        require(index in input.joint.seeds.indices) {
            "Triple origin must belong to its frozen bank."
        }
        require(
            node["kind"]?.asText() == "triple-on-policy-trajectory-1" &&
                node["expansionModelSha256"]?.asText() == input.expansion.sha256
        ) {
            "Triple rows require their separate kind and exact expansion behavior."
        }
        val joint = node.deepCopy<ObjectNode>()
        joint.remove(listOf("kind", "expansionModelSha256", "expansionDecisions"))
        JointTrajectoryValidation(input.joint, input.signature).validate(joint, index)
        val expansions =
            PurchaseJson.array(node.required("expansionDecisions"), "expansionDecisions")
        val steps =
            PurchaseJson.integer(
                node.required("terminal").required("gameDecisions"),
                "gameDecisions",
            )
        val numbers = expansions.map { integer(it, "number") }
        require(numbers == numbers.distinct().sorted() && numbers.all { it in 0 until steps }) {
            "Expansion rows must retain increasing absolute PLAYER numbers within the complete world."
        }
        expansions.forEach {
            ExpansionBehaviorValidation.validate(
                it,
                input.joint.seeds[index],
                input.joint.seat(index),
                input.expansion.model,
            )
        }
        val ordered =
            (expansions +
                    joint.required("cardDecisions").toList() +
                    joint.required("purchaseDecisions").toList())
                .sortedBy { integer(it, "number") }
        require(ordered.map { integer(it, "number") }.distinct().size == ordered.size) {
            "All three phases must have disjoint absolute PLAYER numbers."
        }
        require(ordered.zipWithNext().all { (a, b) -> integer(a, "turn") <= integer(b, "turn") }) {
            "The merged three-phase chronology must preserve public turn order."
        }
    }

    private fun integer(node: JsonNode, name: String): Int =
        PurchaseJson.integer(node.required(name), name)
}
