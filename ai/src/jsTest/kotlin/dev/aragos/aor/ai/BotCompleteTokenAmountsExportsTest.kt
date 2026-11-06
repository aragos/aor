package dev.aragos.aor.ai

import dev.aragos.aor.BoardState
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnFlow
import kotlin.test.Test
import kotlin.test.assertEquals

class BotCompleteTokenAmountsExportsTest {
    @Test
    fun exportedPolicySelectsTheSameExactPeacefulBudgetAsTheJvmFixture() {
        val choice = chooseBotMove(observation(), model(completeGate = 1), "hard", 0)

        assertEquals("buy-tokens 16", choice)
    }

    @Test
    fun missingZeroAndOtherGateValuesRetainTheSixProposalChoice() {
        val input = observation()

        val choices =
            listOf(null, 0, 2).map {
                chooseBotMove(input, model(completeGate = it), "hard", 0)
            }

        assertEquals(List(3) { "buy-tokens 10" }, choices)
    }

    @Test
    fun completeAmountsCannotEnableADisabledHeadAcrossJson() {
        val choice =
            chooseBotMove(
                observation(),
                model(completeGate = 1, headGate = 0, peacefulMarginGate = 1),
                "hard",
                0,
            )

        assertEquals("buy-tokens 10", choice)
    }

    @Test
    fun incompleteFeaturesKeepTheBaselineDespiteTheCompleteAmountGate() {
        val choice =
            chooseBotMove(
                observation(complete = false),
                model(completeGate = 1, peacefulMarginGate = 1),
                "hard",
                0,
            )

        assertEquals("buy-tokens 10", choice)
    }

    @Test
    fun completeAmountsDoNotChangeNormalDifficulty() {
        val choice = chooseBotMove(observation(), model(completeGate = 1), "normal", 0)

        assertEquals("buy-tokens 10", choice)
    }

    @Test
    fun peacefulMarginSelectsTheSameSmallPositiveInvestmentAsTheJvmFixture() {
        val input = observation(cash = 33, occupiedNeighbours = listOf("dijon", "bordeaux"))

        val choice =
            chooseBotMove(input, model(completeGate = 1, peacefulMarginGate = 1), "hard", 0)

        assertEquals("buy-tokens 9", choice)
    }

    @Test
    fun missingZeroAndOtherMarginGateValuesRetainTheLegacyMargin() {
        val input = observation(cash = 33, occupiedNeighbours = listOf("dijon", "bordeaux"))

        val choices =
            listOf(null, 0, 2).map {
                chooseBotMove(input, model(completeGate = 1, peacefulMarginGate = it), "hard", 0)
            }

        assertEquals(List(3) { "buy-tokens 8" }, choices)
    }

    @Test
    fun peacefulMarginDoesNotEnableCompleteAmountsAcrossJson() {
        val input = observation(cash = 33, occupiedNeighbours = listOf("dijon", "bordeaux"))

        val choice =
            chooseBotMove(input, model(completeGate = 0, peacefulMarginGate = 1), "hard", 0)

        assertEquals("buy-tokens 8", choice)
    }

    @Test
    fun zeroAdvantageStillRetainsTheBaselineWithBothGatesEnabled() {
        val choice =
            chooseBotMove(
                observation(),
                model(completeGate = 1, peacefulMarginGate = 1, neutralHead = true),
                "hard",
                0,
            )

        assertEquals("buy-tokens 10", choice)
    }

    @Test
    fun exportedContestedOnlyGainKeepsTheOrdinaryMargin() {
        val input =
            observation(
                occupiedNeighbours = listOf("bruges", "strasbourg", "st-malo", "dijon", "bordeaux")
            )

        val choice =
            chooseBotMove(
                input,
                model(
                    completeGate = 1,
                    peacefulMarginGate = 1,
                    neutralHead = true,
                    contestedWeight = 0.02,
                ),
                "hard",
                0,
            )

        assertEquals("buy-tokens 10", choice)
    }

    @Test
    fun exportedPolicyCanSelectAQualifyingSecondBestPeacefulGain() {
        val input =
            observation(
                cash = 33,
                occupiedNeighbours = listOf("dijon", "bordeaux"),
                contestedAlternative = 16,
            )

        val choice =
            chooseBotMove(
                input,
                model(completeGate = 1, peacefulMarginGate = 1, contestedWeight = 0.24),
                "hard",
                0,
            )

        assertEquals("buy-tokens 9", choice)
    }

    private fun model(
        completeGate: Int?,
        headGate: Int = 1,
        peacefulMarginGate: Int? = null,
        neutralHead: Boolean = false,
        contestedWeight: Double = 0.0,
    ): String {
        val weights =
            mapOf(
                "tokenPreference" to 1.0,
                BotTokenValue.GATE to headGate.toDouble(),
                "planning.tokenValue.v1.costFraction" to if (neutralHead) 0.0 else -0.05,
                BotTokenPlanFeature.INCOME.key to if (neutralHead) 0.0 else 0.32,
                BotTokenPlanFeature.UNCOMMITTED_TOKENS.key to if (neutralHead) 0.0 else -0.05,
                "planning.tokenValue.v1.affordableContested" to contestedWeight,
            ) +
                listOfNotNull(
                        completeGate?.let { BotTokenValue.COMPLETE_AMOUNTS_GATE to it.toDouble() },
                        peacefulMarginGate?.let {
                            BotTokenValue.PEACEFUL_MARGIN_GATE to it.toDouble()
                        },
                    )
                    .toMap()
        val serialized = BotModels.serializedWeights(BotModel(weights = weights))
        return """{"schemaVersion":1,"weights":{${numbers(serialized)}}}"""
    }

    private fun observation(
        complete: Boolean = true,
        cash: Int = 40,
        occupiedNeighbours: List<String> = emptyList(),
        contestedAlternative: Int? = null,
    ): String {
        val player = PlayerId("p1")
        val ids = (1..4).map { PlayerId("p$it") }
        val capitals = listOf("paris", "genoa", "barcelona", "venice")
        val capitalsBoard =
            ids.zip(capitals).fold(BoardState()) { board, (id, capital) ->
                board.placeMarker(ProvinceId(capital), id).board
            }
        val state =
            GameState(
                header = GameHeader(ids.map { Player(it, it.value) }, player),
                stage = Stage.TOKEN_PURCHASE,
                turnNumber = 1,
                board =
                    occupiedNeighbours.fold(capitalsBoard) { board, province ->
                        board.placeMarker(ProvinceId(province), PlayerId("p2")).board
                    },
                players =
                    ids.mapIndexed { index, id ->
                            id to
                                PlayerState(
                                    player = Player(id, id.value),
                                    capital = capitals[index],
                                    cash = if (id == player) cash else 40,
                                )
                        }
                        .toMap(),
                flow = TurnFlow(phaseStarted = true, phaseBookkeepingApplied = true),
            )
        val observation = BotObservations.from(state, player)
        val candidates =
            observation.candidates.joinToString(",") { candidate ->
                val completeFeatures =
                    if (contestedAlternative == null) {
                        candidate.features
                    } else {
                        candidate.features +
                            ("planning.tokenValue.v1.affordableContested" to
                                if (candidate.command == "buy-tokens $contestedAlternative") 0.1
                                else 0.0)
                    }
                val features =
                    if (complete) {
                        completeFeatures
                    } else {
                        completeFeatures - BotTokenPlanFeature.INCOME.key
                    }
                """{
                    "command":"${candidate.command}","features":{${numbers(features)}},
                    "cashCost":${candidate.cashCost},"tokenCost":${candidate.tokenCost},
                    "group":"${candidate.group}","family":"${candidate.family}"
                }"""
            }
        return """{
            "schemaVersion":1,"playerId":"p1","decision":"buy-tokens",
            "context":{${numbers(observation.context)}},"availableCash":$cash,"availableTokens":0,
            "rulesVersion":"${observation.rulesVersion}","dataVersion":"${observation.dataVersion}",
            "candidates":[$candidates]
        }"""
    }

    private fun numbers(values: Map<String, Double>): String =
        values.entries.joinToString(",") { (key, value) -> "\"$key\":$value" }
}
