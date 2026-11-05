package dev.aragos.aor.ai

import dev.aragos.aor.AdvanceData
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.MiseryStep
import dev.aragos.aor.PlayerEconomy
import dev.aragos.aor.PlayerState
import dev.aragos.aor.PurchasePlanningPosition

/**
 * Bounded beam search over actual Purchase transitions. Every compared position has completed
 * stabilization and relief. Actions unlock and reprice their successors through the core engine;
 * the returned first action remains advisory and is replanned from the next live observation.
 */
object BotPurchasePlanner {
    private data class Node(val position: PurchasePlanningPosition, val first: String? = null)

    private data class Evaluated(val node: Node, val value: Double)

    fun choose(
        observation: BotObservation,
        difficulty: BotDifficulty,
        model: BotModel,
    ): BotCandidate {
        val snapshot =
            requireNotNull(observation.purchase) { "Purchase planning requires its snapshot." }
        val initial = snapshot.position()
        val allowed = observation.candidates.associateBy(BotCandidate::command)
        val evaluator = PositionEvaluator(observation, initial.player, model)
        val width = if (difficulty == BotDifficulty.HARD) 12 else 6
        val depth = if (difficulty == BotDifficulty.HARD) 6 else 4
        val budget = if (difficulty == BotDifficulty.HARD) 1800 else 700
        var transitions = 0

        fun step(node: Node, command: String): Node {
            transitions++
            return Node(node.position.step(command), node.first ?: command)
        }

        // Complete the remaining mandatory payments for every search prefix. Passing the advances
        // step is a real alternative; cash and misery payment remain separate continuations.
        fun finish(node: Node): List<Node> {
            if (node.position.finished) return listOf(node)
            val commands =
                when (node.position.decision) {
                    "purchase-shipping",
                    "purchase-advances" -> listOf("pass")
                    "immediate-master-art-discard" -> discardChoices(node.position, snapshot)
                    else -> node.position.commands
                }
            return commands.flatMap { command -> finish(step(node, command)) }
        }

        fun evaluate(node: Node): Evaluated =
            finish(node)
                .filter { it.first in allowed }
                .map { completed ->
                    Evaluated(completed, evaluator.value(completed.position.player))
                }
                .maxWithOrNull(compareBy<Evaluated> { it.value }.thenByDescending { it.node.first })
                ?: error("A purchase continuation requires a currently offered first action.")

        var best = evaluate(Node(initial))
        var frontier = listOf(Node(initial))
        repeat(depth) {
            if (frontier.isEmpty() || transitions + 48 >= budget) return@repeat
            val children = buildList {
                for (node in frontier) {
                    val candidates =
                        (if (node.position.decision == "immediate-master-art-discard") {
                                discardChoices(node.position, snapshot)
                            } else {
                                node.position.commands
                            })
                            .filter { command ->
                                (node.first != null || command in allowed) &&
                                    (command != "pass" ||
                                        node.position.decision != "purchase-advances")
                            }
                    for (command in candidates) {
                        // The terminal suffix has at most one discard, two stabilization choices
                        // and five relief choices. Reserve its full bound before opening a node.
                        if (transitions + 48 >= budget) break
                        val child = step(node, command)
                        val evaluated = evaluate(child)
                        if (
                            evaluated.value > best.value ||
                                (evaluated.value == best.value &&
                                    requireNotNull(evaluated.node.first) {
                                        "A plan requires its first action."
                                    } <
                                        requireNotNull(best.node.first) {
                                            "The best plan requires its first action."
                                        })
                        ) {
                            best = evaluated
                        }
                        if (
                            child.position.decision in
                                setOf("purchase-advances", "immediate-master-art-discard")
                        ) {
                            add(child to evaluated.value)
                        }
                    }
                }
            }
            frontier =
                children
                    .sortedWith(
                        compareByDescending<Pair<Node, Double>> { it.second }
                            .thenBy { it.first.first }
                    )
                    .distinctBy { (node, _) ->
                        val player = node.position.player
                        listOf(
                            node.first,
                            node.position.decision,
                            player.cash,
                            player.ships,
                            player.misery,
                            player.advances.owned,
                            player.hand,
                            node.position.leaders,
                        )
                    }
                    .take(width)
                    .map { it.first }
        }
        return requireNotNull(allowed[best.node.first]) {
            "The chosen purchase must be a live offer."
        }
    }

    private fun discardChoices(
        position: PurchasePlanningPosition,
        snapshot: BotPurchaseSnapshot,
    ): List<String> {
        val references = position.player.hand.map { it.reference }.toSet()
        val leastValuable =
            snapshot.hand
                .filter { it.reference in references }
                .minWithOrNull(
                    compareBy<BotPurchaseCard> { if (it.burden) -1.0 else it.value }
                        .thenBy { it.reference }
                )
        // Each discard changes the same hand-count payment. Keep the best retained hand alongside
        // passing, rather than multiplying every tail by up to 64 interchangeable payment states.
        return listOfNotNull("pass", leastValuable?.let { "discard-card ${it.reference}" })
    }

    private class PositionEvaluator(
        private val observation: BotObservation,
        private val initial: PlayerState,
        private val model: BotModel,
    ) {
        private val endgame = observation.context["endgame"] ?: 0.0
        private val playerCount =
            requireNotNull(observation.purchase) {
                    "Purchase evaluation requires its seat facts."
                }
                .seats
                .size
        private val markers = (observation.context["markers"] ?: 0.0).toInt()
        private val initialIncome =
            PlayerEconomy.incomeQuote(initial, playerCount, markers).totalCashGain
        private val conditions =
            listOf(
                "count.${(observation.context["playerCount"] ?: 0.0).toInt()}",
                "epoch.${(observation.context["epoch"] ?: 0.0).toInt()}",
                "map.${if (observation.context["european"] == 1.0) "european" else "standard"}",
            ) +
                listOfNotNull(
                    "deckModified".takeIf { observation.context["deckModified"] == 1.0 },
                    "immediateArt".takeIf { observation.context["immediateArt"] == 1.0 },
                    observation.candidates
                        .asSequence()
                        .flatMap { it.features.keys.asSequence() }
                        .firstOrNull { it.startsWith("initial.") }
                        ?.split('.')
                        ?.take(2)
                        ?.joinToString("."),
                )

        fun value(player: PlayerState): Double {
            if (player.misery == MiseryStep.CHAOS) return -1.0e12
            val acquired = player.advances.owned - initial.advances.owned
            val features = buildMap {
                put("cash", (player.cash - initial.cash).toDouble())
                put("points", (player.advances.score - initial.advances.score).toDouble())
                put(
                    "misery",
                    ((player.misery.value ?: 1000) - (initial.misery.value ?: 1000)).toDouble(),
                )
                val discarded =
                    requireNotNull(observation.purchase) {
                            "Purchase evaluation requires its snapshot."
                        }
                        .hand
                        .filter { card -> player.hand.none { it.reference == card.reference } }
                put("cards", -discarded.sumOf { it.value } / 7.5)
                put("burden", -discarded.count { it.burden }.toDouble())
                put(
                    "discount",
                    acquired.sumOf { AdvanceData.definition(it).credit }.toDouble() *
                        (1.0 - endgame) *
                        0.8,
                )
                put(
                    "income",
                    (PlayerEconomy.incomeQuote(player, playerCount, markers).totalCashGain -
                            initialIncome)
                        .toDouble(),
                )
                put("reach", acquired.sumOf(::advanceReach) + retainedShipUpgrade(player, acquired))
                put(
                    "military",
                    acquired
                        .count {
                            it in
                                setOf(
                                    AdvanceId.PROSELYTISM,
                                    AdvanceId.CATHEDRAL,
                                    AdvanceId.NATIONALISM,
                                )
                        }
                        .toDouble(),
                )
                for (advance in acquired) {
                    put("advance.${advance.letter}", 1.0)
                }
            }
            return features.entries.sumOf { (feature, amount) ->
                amount *
                    ((model.weights[feature] ?: 0.0) +
                        endgame * (model.weights["endgame.$feature"] ?: 0.0) +
                        conditions.sumOf { model.weights["$it.$feature"] ?: 0.0 })
            }
        }

        // Paid shipping has value only if the resulting fleet survives this purchase activity.
        // Advance-driven resets are evaluated as abilities; their superseded paid level is absent.
        private fun retainedShipUpgrade(player: PlayerState, acquired: Set<AdvanceId>): Double =
            if (
                player.ships != initial.ships &&
                    AdvanceId.SEAWORTHY_VESSELS !in acquired &&
                    AdvanceId.OCEAN_NAVIGATION !in acquired
            ) {
                6.0
            } else {
                0.0
            }

        private fun advanceReach(advance: AdvanceId): Double =
            when (advance) {
                AdvanceId.THE_HEAVENS,
                AdvanceId.CARAVAN -> 3.0
                AdvanceId.OVERLAND_EAST,
                AdvanceId.SEAWORTHY_VESSELS -> 7.0
                AdvanceId.OCEAN_NAVIGATION,
                AdvanceId.NEW_WORLD -> 12.0
                else -> 0.0
            }
    }
}
