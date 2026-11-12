package dev.aragos.aor.training

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.aragos.aor.BoardState
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.ShippingCapability
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import dev.aragos.aor.ai.BotModels

/** Synthetic models and prospective integer streams only; never a retained game or trained head. */
internal class ExpansionPairedFixture : AutoCloseable {
    val base =
        JointCollectionFixture(
            configuration =
                TrainingMatrix.configurations.single {
                    it.id == "players-4/standard/no-crusades/deck-true/art-true"
                }
        )
    val files = base.artifacts
    val source = files.directory.resolve("expansion-source.json")
    val arenaSource = files.directory.resolve("arena.json")

    init {
        files.model(BotModels.default, "ordinary.json")
        files.model(
            BotModels.default.copy(weights = BotModels.default.weights + ("tokens" to 2.0)),
            "expansion.json",
        )
        files.write(
            "arena.json",
            mapOf(
                "schemaVersion" to 1,
                "purpose" to "SCREENING",
                "configurations" to listOf(base.configuration.id),
                "candidate" to "ordinary.json",
                "incumbent" to "ordinary.json",
                "opponents" to listOf("ordinary.json"),
                "pairsPerConfiguration" to 1,
                "seed" to 71,
                "jointPolicy" to
                    mapOf(
                        "baseline" to "baseline.json",
                        "sharedBundle" to "bundle.json",
                        "candidateBundle" to "bundle.json",
                        "incumbentBundle" to "bundle.json",
                    ),
                "tokenOrder" to
                    mapOf(
                        "candidateSelector" to "FIXED_16_TO_15",
                        "incumbentSelector" to "FIXED_16_TO_15",
                        "opponentSelectors" to listOf("FIXED_16_TO_15"),
                    ),
                "expansionImitation" to
                    mapOf(
                        "candidateModel" to "expansion.json",
                        "incumbentModel" to "expansion.json",
                        "opponentModels" to listOf("expansion.json"),
                    ),
            ),
        )
        files.write(
            "expansion-source.json",
            mapOf(
                "kind" to "expansion-paired-data-1",
                "arena" to "arena.json",
                "origins" to
                    (0 until 1024).map { index ->
                        ExpansionPairedSeeds(
                            index,
                            10000L + index,
                            listOf(20000L + index, 30000L + index),
                        )
                    },
            ),
        )
    }

    val input: ExpansionPairedInput
        get() = ExpansionPairedInput.read(source)

    fun editArena(change: ObjectNode.() -> Unit) {
        val node = PurchaseJson.read(arenaSource) as ObjectNode
        node.change()
        files.write("arena.json", node)
    }

    override fun close() = base.close()
}

internal fun expansionPairedState(): GameState {
    val players = (1..4).map { Player(PlayerId("p$it"), "Player $it") }
    val capitals = listOf("venice", "genoa", "barcelona", "paris")
    return GameState(
        header = GameHeader(players, players.first().id),
        stage = Stage.EXPANSION,
        turnNumber = 2,
        board =
            players.zip(capitals).fold(BoardState()) { board, (player, capital) ->
                board.placeMarker(ProvinceId(capital), player.id).board
            },
        turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
        players =
            players
                .mapIndexed { index, player ->
                    player.id to
                        PlayerState(
                            player,
                            capital = capitals[index],
                            cash = 100,
                            stockTokens = if (index == 0) 0 else 36,
                            poolTokens = if (index == 0) 36 else 0,
                            ships =
                                if (index == 0) ShippingCapability.SEAWORTHY_10
                                else ShippingCapability.NONE,
                        )
                }
                .toMap(),
        flow =
            TurnFlow(
                actor = players.first().id,
                phaseStarted = true,
                phaseBookkeepingApplied = true,
            ),
    )
}
