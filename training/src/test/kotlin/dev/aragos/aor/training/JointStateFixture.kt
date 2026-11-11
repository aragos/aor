package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.aragos.aor.AdvanceAcquisition
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.BoardState
import dev.aragos.aor.CardData
import dev.aragos.aor.CardLeaderInPlay
import dev.aragos.aor.CardLeaderRole
import dev.aragos.aor.CardTurnEffects
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.LeaderId
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerAdvances
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import dev.aragos.aor.ai.BotModels

/** Synthetic role differences; no qualified model bytes or complete games are required. */
internal class JointStateFixture : AutoCloseable {
    val base = JointCollectionFixture()
    val ordinary =
        BotModels.default.copy(weights = BotModels.default.weights + ("points" to -100.0))

    init {
        base.artifacts.model(ordinary, "ordinary.json")
        base.artifacts.model(
            ordinary.copy(weights = ordinary.weights + ("tokens" to 2.0)),
            "expansion.json",
        )
        edit {
            put("baseline", "ordinary.json")
            put("jointStateChronology", JointEcology.VERSION)
            put("sharedPurchaseBaseline", "baseline.json")
            put("continuationProtocol", PurchaseEcology.PROTOCOL)
            set<JsonNode>(
                "expansionImitation",
                PurchaseJson.mapper.valueToTree(
                    mapOf(
                        "candidateModel" to "expansion.json",
                        "opponentModels" to listOf("expansion.json"),
                    )
                ),
            )
            set<JsonNode>(
                "tokenOrder",
                PurchaseJson.mapper.valueToTree(
                    mapOf(
                        "candidateSelector" to "FIXED_16_TO_15",
                        "opponentSelectors" to listOf("FIXED_16_TO_15"),
                    )
                ),
            )
        }
    }

    val input: JointCollectionInput
        get() = base.input

    fun game(): JointGameTrajectory =
        base
            .syntheticGame(input)
            .copy(
                jointEvidence =
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
            )

    fun edit(change: ObjectNode.() -> Unit) {
        val node = PurchaseJson.read(base.source) as ObjectNode
        node.change()
        base.artifacts.write("source.json", node)
    }

    override fun close() = base.close()
}

internal fun jointStateFixture(printedWord: Boolean = false): GameState {
    val actor = PlayerId("p1")
    val players = (1..4).map { Player(PlayerId("p$it"), "Player $it") }
    val capitals = listOf("venice", "genoa", "barcelona", "paris")
    val board =
        players.zip(capitals).fold(BoardState()) { current, (player, capital) ->
            current.placeMarker(ProvinceId(capital), player.id).board
        }
    val owned =
        if (printedWord) {
            setOf(AdvanceId.WRITTEN_RECORD, AdvanceId.HUMAN_BODY, AdvanceId.PRINTED_WORD)
        } else {
            emptySet()
        }
    return GameState(
        header = GameHeader(players, actor),
        stage = Stage.PLAY_CARDS,
        turnNumber = 2,
        board = board,
        turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
        players =
            players
                .mapIndexed { index, player ->
                    player.id to
                        PlayerState(
                            player,
                            capital = capitals[index],
                            cash = 300,
                            advances =
                                PlayerAdvances(
                                    if (index == 0) {
                                        owned.map { AdvanceAcquisition(it, 1) }
                                    } else {
                                        emptyList()
                                    }
                                ),
                            hand =
                                if (index == 0) {
                                    listOf(
                                        HandCard("held", CardData.definition("leonardo-da-vinci"))
                                    )
                                } else {
                                    emptyList()
                                },
                        )
                }
                .toMap(),
        effects =
            CardTurnEffects(
                leaders =
                    listOf(
                        CardLeaderInPlay(
                            LeaderId.ST_BENEDICT,
                            PlayerId("p2"),
                            CardLeaderRole.DISPLAY,
                        )
                    )
            ),
        flow = TurnFlow(actor = actor, phaseStarted = true, phaseBookkeepingApplied = true),
    )
}
