package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.CardChoice
import dev.aragos.aor.CardCommand
import dev.aragos.aor.CardKind
import dev.aragos.aor.TextCodec

/** Selected leader-play quote. Gross rebate and protection escrow are deliberately separate. */
internal data class JointLeaderReceipt(
    val cardDefinition: String,
    val printedWordOwned: Boolean,
    val quotedGrossRebate: Int,
    val protectionCash: Int,
)

/** Authoritative selected-command quotes, not extra rewards or counterfactual hand-cost savings. */
internal data class JointDecisionEvidence(
    val number: Int,
    val turn: Int,
    val leader: JointLeaderReceipt? = null,
    val purchase: CardPurchaseAudit? = null,
    val ownLeaderDefinitions: Set<String> = emptySet(),
) {
    init {
        require(number >= 0 && turn >= 1 && (leader == null) != (purchase == null)) {
            "Economic evidence requires one selected quote and its actual decision and turn."
        }
    }

    companion object {
        /**
         * Validate linkage and quoted data shape; economic amounts still come from the engine
         * owner.
         */
        fun validate(node: JsonNode, rows: List<JsonNode>) {
            val byNumber = rows.associateBy { it.required("number").intValue() }
            val evidence = PurchaseJson.array(node, "joint evidence")
            val numbers = evidence.map { integer(it, "number") }
            val expected =
                rows
                    .filter { row ->
                        row.required("decision").asText() != "play-cards" ||
                            selected(row).required("features")["cardKind.LEADER"]?.doubleValue() ==
                                1.0
                    }
                    .map { integer(it, "number") }
            require(numbers == expected) {
                "Economic evidence must cover exactly the selected leaders and all controlled purchases."
            }
            evidence.forEach { quote ->
                PurchaseJson.fields(
                    quote,
                    setOf("number", "turn", "leader", "purchase", "ownLeaderDefinitions"),
                )
                val row =
                    requireNotNull(byNumber[integer(quote, "number")]) {
                        "Quote origin is required."
                    }
                require(integer(quote, "turn") == integer(row, "turn")) {
                    "Quote turn must match its recorded actor row."
                }
                val leaders =
                    PurchaseJson.strings(quote.required("ownLeaderDefinitions"), "own leaders")
                require(leaders == leaders.distinct()) { "Own leader definitions must be unique." }
                if (row.required("decision").asText() == "play-cards") {
                    require(quote.required("purchase").isNull && leaders.isEmpty()) {
                        "A leader play has no purchase receipt."
                    }
                    val leader = quote.required("leader")
                    PurchaseJson.fields(
                        leader,
                        setOf(
                            "cardDefinition",
                            "printedWordOwned",
                            "quotedGrossRebate",
                            "protectionCash",
                        ),
                    )
                    val definition =
                        PurchaseJson.text(leader.required("cardDefinition"), "leader definition")
                    require(
                        leader.required("printedWordOwned").isBoolean &&
                            selected(row).required("features")["card.$definition"]?.doubleValue() ==
                                1.0 &&
                            integer(leader, "quotedGrossRebate") >= 0 &&
                            integer(leader, "protectionCash") >= 0
                    ) {
                        "Leader receipt must identify its selected card and separate nonnegative quoted amounts."
                    }
                } else {
                    require(quote.required("leader").isNull) {
                        "A purchase has no leader-play receipt."
                    }
                    val purchase = quote.required("purchase")
                    PurchaseJson.fields(
                        purchase,
                        setOf(
                            "number",
                            "turn",
                            "decision",
                            "command",
                            "quotedPrice",
                            "leaderCredits",
                        ),
                    )
                    require(
                        integer(purchase, "number") == integer(row, "number") &&
                            integer(purchase, "turn") == integer(row, "turn") &&
                            purchase.required("decision") == row.required("decision") &&
                            purchase.required("command") == selected(row).required("command") &&
                            integer(purchase, "quotedPrice") >= 0
                    ) {
                        "Purchase receipt must bind its actual selected command and quote."
                    }
                    PurchaseJson.array(purchase.required("leaderCredits"), "leader credits")
                        .forEach { credit ->
                            PurchaseJson.fields(
                                credit,
                                setOf(
                                    "leaderCardId",
                                    "baseAmount",
                                    "writtenRecordBonus",
                                    "marginalPriceSaving",
                                ),
                            )
                            PurchaseJson.text(credit.required("leaderCardId"), "leader card id")
                            require(
                                listOf("baseAmount", "writtenRecordBonus", "marginalPriceSaving")
                                    .all { integer(credit, it) >= 0 }
                            ) {
                                "Each separate leader credit requires nonnegative quoted amounts."
                            }
                        }
                }
            }
        }

        private fun integer(node: JsonNode, name: String): Int =
            PurchaseJson.integer(node.required(name), name)

        private fun selected(row: JsonNode): JsonNode =
            row.required("observation").required("actions")[integer(row, "selectedIndex")]

        fun from(decision: SimulationDecision, command: String): JointDecisionEvidence? {
            val view = decision.view
            val actor = view.players.single { it.id.value == decision.observation.playerId }
            val turn =
                requireNotNull(view.turnNumber) {
                    "Selected economic evidence requires its actual turn."
                }
            if (decision.observation.decision in setOf("purchase-advances", "purchase-shipping")) {
                return JointDecisionEvidence(
                    decision.number,
                    turn,
                    purchase = CardPurchaseAudit.from(decision, command),
                    ownLeaderDefinitions =
                        view.effects.leaders
                            .filter { it.owner == actor.id }
                            .map { it.leader.cardId }
                            .toSet(),
                )
            }
            if (decision.observation.decision != "play-cards") return null
            val play = TextCodec.parseCommand(command) as? CardCommand.PlayCard ?: return null
            val card = view.hand.single { it.reference == play.reference }
            if (card.definition.kind != CardKind.LEADER) return null
            val offer = view.cardOffers.single { it.reference == play.reference }
            val plain =
                requireNotNull(
                    offer.choices.firstOrNull { it.command == "play ${play.reference}" }
                ) {
                    "A selected leader requires its authoritative ordinary-play quote."
                }
            return JointDecisionEvidence(
                decision.number,
                turn,
                leader =
                    JointLeaderReceipt(
                        card.definition.id,
                        AdvanceId.PRINTED_WORD in actor.advances,
                        plain.cashChanges
                            .filter { it.player == actor.id }
                            .sumOf { it.after - it.before },
                        (play.choice as? CardChoice.LeaderProtection)?.cash ?: 0,
                    ),
            )
        }
    }
}
