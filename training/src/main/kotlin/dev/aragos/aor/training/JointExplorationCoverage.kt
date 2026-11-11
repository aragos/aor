package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.CardCommand
import dev.aragos.aor.TextCodec
import dev.aragos.aor.TurnCommand
import dev.aragos.aor.ai.CardPolicy
import dev.aragos.aor.ai.CardPolicyModel

internal data class JointPhysicalCardCoverage(
    val reference: String,
    val variants: Int,
    val probability: Double,
    val selected: Boolean,
    val recommended: Boolean,
    val leader: Boolean,
)

internal data class JointCardCoverage(
    val number: Int,
    val turn: Int,
    val offeredCommands: Int,
    val selectedDifferentCommand: Boolean,
    val selectedDifferentPhysicalGroup: Boolean,
    val recommendationProbability: Double,
    val groups: List<JointPhysicalCardCoverage>,
) {
    val offeredPhysicalCards: Int
        get() = groups.count { it.reference != "pass" }

    val passOffered: Boolean
        get() = groups.any { it.reference == "pass" }

    val passSelected: Boolean
        get() = groups.any { it.reference == "pass" && it.selected }
}

/**
 * One origin's coverage. References, probabilities and selected-command receipts are diagnostics.
 */
internal object JointExplorationCoverage {
    fun project(game: JsonNode, model: CardPolicyModel): Map<String, Any> {
        val rows =
            PurchaseJson.array(game.required("cardDecisions"), "card decisions").map { row ->
                val observation = CardTrajectoryValidation.observation(row.required("observation"))
                val selected =
                    PurchaseJson.integer(row.required("selectedIndex"), "selected card index")
                val probabilities = CardPolicy.evaluate(observation, model).probabilities
                val groups =
                    observation.actions.indices.groupBy { group(observation.actions[it].command) }
                val selectedGroup = group(observation.actions[selected].command)
                val baselineGroup = group(observation.actions[observation.baselineIndex].command)
                JointCardCoverage(
                    number = PurchaseJson.integer(row.required("number"), "card decision number"),
                    turn = PurchaseJson.integer(row.required("turn"), "card decision turn"),
                    offeredCommands = observation.actions.size,
                    selectedDifferentCommand = selected != observation.baselineIndex,
                    selectedDifferentPhysicalGroup = selectedGroup != baselineGroup,
                    recommendationProbability = probabilities[observation.baselineIndex],
                    groups =
                        groups.map { (reference, indices) ->
                            JointPhysicalCardCoverage(
                                reference = reference,
                                variants = indices.size,
                                probability = indices.sumOf { probabilities[it] },
                                selected = reference == selectedGroup,
                                recommended = reference == baselineGroup,
                                leader =
                                    indices.any {
                                        observation.actions[it].features["cardKind.LEADER"] == 1.0
                                    },
                            )
                        },
                )
            }
        return mapOf(
            "originIndex" to PurchaseJson.integer(game.required("index"), "origin index"),
            "independentWorlds" to 1,
            "cardDecisions" to rows.size,
            "purchaseDecisions" to game.required("purchaseDecisions").size(),
            "worldCoverage" to
                mapOf(
                    "zeroCardDecisions" to rows.isEmpty(),
                    "zeroPurchaseDecisions" to game.required("purchaseDecisions").isEmpty,
                    "anyPhysicalDeviation" to
                        rows.any(JointCardCoverage::selectedDifferentPhysicalGroup),
                    "anyPass" to rows.any(JointCardCoverage::passSelected),
                    "anyLeaderOffered" to
                        rows.any { row -> row.groups.any(JointPhysicalCardCoverage::leader) },
                    "anyLeaderSelected" to
                        rows.any { row -> row.groups.any { it.leader && it.selected } },
                ),
            "rows" to rows,
            "observedSelectedCommandQuotes" to
                (game["jointEvidence"] ?: PurchaseJson.mapper.createArrayNode()),
            "creditAttribution" to
                "Each leader marginal is conditional on other credits; never sum overlapping marginals.",
            "cashAttribution" to
                "Gross quoted rebate and protection escrow remain separate; no invented stabilization savings.",
        )
    }

    private fun group(command: String): String =
        when (val parsed = TextCodec.parseCommand(command)) {
            is CardCommand.PlayCard -> parsed.reference
            TurnCommand.Pass -> "pass"
            else -> error("Card coverage requires an offered play or pass.")
        }
}
