package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.aragos.aor.CardData
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.Stage
import dev.aragos.aor.ai.BotObservations

/** Invented public positions and locally constructed weights only; never a scientific input. */
internal class CpePairedFixture(nonzeroCardPurchase: Boolean = false) : AutoCloseable {
    val prior = ExpansionOnPolicyFixture()
    val files = prior.files
    val source = files.directory.resolve("cpe-source.json")
    val bundle = files.directory.resolve("cpe-bundle.json")

    init {
        if (nonzeroCardPurchase) {
            for (name in listOf("card-policy.json", "purchase-policy.json")) {
                val model = PurchaseJson.read(files.directory.resolve(name)) as ObjectNode
                model.set<JsonNode>(
                    "actorWeights",
                    PurchaseJson.mapper.valueToTree(List(64) { .125 }),
                )
                model.set<JsonNode>(
                    "stateBias",
                    PurchaseJson.mapper.valueToTree(List(64) { .25 }),
                )
                files.write(name, model)
            }
            prior.prior.base.writeBundle("bundle.json")
        }
        val dictionaries = CpePairedDictionary.forConfiguration(prior.prior.base.configuration)
        files.write(
            "cpe-bundle.json",
            mapOf(
                "kind" to CpePairedBundle.VERSION,
                "heads" to
                    CpeHead.entries.associate { head ->
                        val file =
                            when (head) {
                                CpeHead.CARD -> "card-policy.json"
                                CpeHead.PURCHASE -> "purchase-policy.json"
                                CpeHead.EXPANSION -> "expansion-actor.json"
                            }
                        head.name to
                            mapOf(
                                "path" to file,
                                "sha256" to PurchaseJson.hash(files.directory.resolve(file)),
                                "dictionarySha256" to
                                    requireNotNull(dictionaries[head]) { "Fixture dictionary." }
                                        .sha256(),
                            )
                    },
            ),
        )
        source()
    }

    fun source(head: CpeHead = CpeHead.EXPANSION, start: Int = 0) {
        val bundleSubject =
            mapOf("path" to "cpe-bundle.json", "sha256" to PurchaseJson.hash(bundle))
        files.write(
            "cpe-source.json",
            mapOf(
                "kind" to CpePairedInput.VERSION,
                "iteration" to 1,
                "head" to head,
                "globalStart" to start,
                "count" to 64,
                "arena" to
                    mapOf(
                        "path" to "arena.json",
                        "sha256" to PurchaseJson.hash(prior.prior.arenaSource),
                    ),
                "currentBundle" to bundleSubject,
                "originalBundle" to bundleSubject,
                "origins" to
                    List(640) {
                        CpePairedSeeds(
                            it,
                            10000L + it,
                            20000L + it,
                            listOf(30000L + it, 40000L + it),
                        )
                    },
                "replayIndices" to listOf(0, 213, 426, 639),
            ),
        )
    }

    val input: CpePairedInput
        get() = CpePairedInput.read(source)

    fun state(head: CpeHead): GameState {
        val expansion = expansionPairedState()
        return when (head) {
            CpeHead.EXPANSION -> expansion
            CpeHead.PURCHASE -> expansion.copy(stage = Stage.PURCHASE)
            CpeHead.CARD ->
                expansion.copy(
                    stage = Stage.PLAY_CARDS,
                    deck = listOf("stone-1", "wool-1"),
                    players =
                        expansion.players.mapValues { (id, player) ->
                            player.copy(
                                hand =
                                    listOf(
                                        HandCard(
                                            "held",
                                            CardData.definition(
                                                if (id == expansion.playerIds.first()) "wool-1"
                                                else "stone-1"
                                            ),
                                        )
                                    )
                            )
                        },
                )
        }
    }

    fun decision(
        head: CpeHead,
        seed: Int = 71,
        state: GameState = state(head),
    ): SimulationDecision {
        val actor = state.playerIds.first()
        return SimulationDecision(
            19,
            0,
            seed,
            state.viewForPlayer(actor),
            BotObservations.from(state, actor),
            19,
        )
    }

    override fun close() = prior.close()
}
