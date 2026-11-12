package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Path

/** Every model, observation and terminal below is constructed; this fixture never simulates. */
internal class TripleCollectionFixture : AutoCloseable {
    val expansion = ExpansionOnPolicyFixture()
    val base = expansion.prior.base
    val files = base.artifacts
    val source = files.directory.resolve("triple-source.json")

    init {
        base.writeBundle("rival.json")
        val joint = PurchaseJson.read(base.source) as ObjectNode
        joint.put("games", 2)
        joint.put("baseline", "ordinary.json")
        joint.put("jointStateChronology", JointEcology.VERSION)
        joint.put("sharedPurchaseBaseline", "baseline.json")
        joint.put("continuationProtocol", PurchaseEcology.PROTOCOL)
        joint.set<JsonNode>(
            "experimentalRulesTransfer",
            PurchaseJson.mapper.valueToTree(
                mapOf(
                    "sourceRulesVersion" to "ah1996-three-epoch-4",
                    "targetRulesVersion" to "ah1996-three-epoch-5",
                )
            ),
        )
        joint.set<JsonNode>(
            "expansionImitation",
            PurchaseJson.mapper.valueToTree(
                mapOf(
                    "candidateModel" to "expansion.json",
                    "opponentModels" to listOf("expansion.json"),
                )
            ),
        )
        joint.set<JsonNode>(
            "tokenOrder",
            PurchaseJson.mapper.valueToTree(
                mapOf(
                    "candidateSelector" to "FIXED_16_TO_15",
                    "opponentSelectors" to listOf("FIXED_16_TO_15"),
                )
            ),
        )
        files.write("source.json", joint)
        refresh()
    }

    val input: TripleCollectionInput
        get() = TripleCollectionInput.read(source)

    fun refresh() {
        files.write(
            "triple-source.json",
            mapOf(
                "kind" to TripleCollectionInput.VERSION,
                "jointSource" to
                    mapOf("path" to "source.json", "sha256" to PurchaseJson.hash(base.source)),
                "expansion" to
                    mapOf(
                        "path" to "expansion-actor.json",
                        "sha256" to PurchaseJson.hash(expansion.actorPath),
                    ),
            ),
        )
    }

    fun rows(input: TripleCollectionInput, number: Int = 7): List<TerritoryDecisionRecord> {
        val rows = mutableListOf<TerritoryDecisionRecord>()
        val collector = JointCollector(input.joint, input.signature, Long.MAX_VALUE)
        val actors =
            collector.controllers(0, {}, {}, {}, JointCollector.TripleRecording(input, rows))
        requireNotNull(actors[0]) { "The constructed controlled seat is required." }
            .choose(expansion.decision(number = number))
        return rows
    }

    fun game(
        input: TripleCollectionInput,
        rows: List<TerritoryDecisionRecord>,
        index: Int = 0,
    ): TripleGameTrajectory {
        val joint =
            base
                .syntheticGame(input.joint)
                .copy(
                    signature = input.signature,
                    index = index,
                    gameSeed = input.joint.seeds[index],
                    controlledSeat = input.joint.seat(index),
                    opponentIndices = input.joint.opponentIndices(index),
                    cardDecisions =
                        if (index == 0) base.syntheticGame(input.joint).cardDecisions
                        else emptyList(),
                    purchaseDecisions =
                        if (index == 0) base.syntheticGame(input.joint).purchaseDecisions
                        else emptyList(),
                    jointEvidence =
                        if (index == 0)
                            listOf(
                                JointDecisionEvidence(
                                    1,
                                    1,
                                    purchase =
                                        CardPurchaseAudit(
                                            1,
                                            1,
                                            "purchase-advances",
                                            "pass",
                                            0,
                                            emptyList(),
                                        ),
                                )
                            )
                        else emptyList(),
                    terminal = expansion.game(expansion.input, emptyList(), index).terminal,
                )
        return TripleGameTrajectory(
            expansionModelSha256 = input.expansion.sha256,
            expansionDecisions = rows,
            joint = joint,
        )
    }

    fun store(
        input: TripleCollectionInput = this.input,
        output: Path = base.output,
    ): TripleCollectionStore = TripleCollectionStore(input, output)

    override fun close() = expansion.close()
}
