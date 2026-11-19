package dev.aragos.aor.ai

import dev.aragos.aor.ExpansionAction
import dev.aragos.aor.ExpansionOfferView
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.MarkerView
import dev.aragos.aor.PieceSide
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.ProvinceView
import dev.aragos.aor.ScoreView
import dev.aragos.aor.Stage
import kotlin.test.Test
import kotlin.test.assertEquals

class FinalExpansionPolicyTest {
    private val players = (1..4).map { Player(PlayerId("p$it"), "Player $it") }
    private val actor = players.first().id
    private val leader = ProvinceId("genoa")
    private val rival = ProvinceId("barcelona")
    private val observation =
        TerritoryObservation(
            encoderVersion = TerritoryEncoder.EXPANSION_2.version,
            stateFeatures = emptyMap(),
            actions =
                listOf("attack barcelona", "attack genoa", "pass").map {
                    TerritoryAction(it, emptyMap())
                },
            baselineIndex = 2,
        )
    private val original = IncumbentResidualChoice(List(3) { 0.0 }, List(3) { 0.0 }, 2, "pass")
    private val config = FinalExpansionConfig(30.0, 5.0, 0.0, 0.0)

    private val view =
        GameState(GameHeader(players, actor)).viewForPlayer(actor).let { initial ->
            initial.copy(
                stage = Stage.EXPANSION,
                endingTriggered = true,
                players = initial.players.map { it.copy(boardMarkers = 5) },
                board =
                    listOf(
                        ProvinceView(leader, MarkerView(players[1].id, PieceSide.COLOURED)),
                        ProvinceView(rival, MarkerView(players[2].id, PieceSide.COLOURED)),
                    ),
                scores =
                    players.zip(listOf(100, 125, 90, 80)).map { (player, cash) ->
                        ScoreView(player.id, 0, cash, 0, cash, false)
                    },
                expansionOffers =
                    listOf(leader, rival).map { province ->
                        ExpansionOfferView(
                            destination = province,
                            primaryAction = ExpansionAction.ATTACK,
                            requiredTokens = 5,
                            availablePool = 10,
                            resultingPool = 5,
                            establishesMarker = true,
                            uncertain = true,
                            blockers = emptyList(),
                            partialPlacements = emptyList(),
                            shipping = null,
                            incomeBefore = null,
                            incomeAfter = null,
                            reachable = true,
                            competitionWinProbability = 0.6,
                        )
                    },
            )
        }

    @Test
    fun prefersRemovingIncomeFromTheRivalWithTheStrongestFinalScore() {
        assertEquals(
            "attack genoa",
            FinalExpansionPolicy.choose(view, actor, observation, original, config).command,
        )
    }

    @Test
    fun preservesTheRegularPolicyBeforeTheFinalTurn() {
        assertEquals(
            original,
            FinalExpansionPolicy.choose(
                view.copy(endingTriggered = false),
                actor,
                observation,
                original,
                config,
            ),
        )
    }
}
