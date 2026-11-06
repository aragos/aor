package dev.aragos.aor.ai

import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class BotLeaderExportsTest {
    @Test
    fun exportedPlannerRetainsAnUnusedLeaderOption() {
        val selected = chooseBotMove(observation(), model(), "hard", 0)

        assertEquals("pass", selected)
    }

    @Test
    fun exportedPlannerUsesACreditThatUnlocksItsPreferredAdvance() {
        val input = observation(with = listOf(advance("N", cost = 10, value = 15.0)))

        val selected = chooseBotMove(input, model(), "hard", 0)

        assertEquals("play hand-1", selected)
    }

    @Test
    fun anAffordableCreditHasNoValueWhenBothPlansPreferAnotherAdvance() {
        val preferred = advance("R", cost = 40, value = 30.0)
        val input =
            observation(
                without = listOf(preferred, advance("N", cost = 30, value = 2.0)),
                with = listOf(preferred, advance("N", cost = 10, value = 12.0)),
                cash = 45,
            )

        val selected = chooseBotMove(input, model(), "hard", 0)

        assertEquals("pass", selected)
    }

    @Test
    fun zeroRetentionPreservesTheImmediateHandReductionBenefit() {
        val selected = chooseBotMove(observation(), model(retention = 0.0), "hard", 0)

        assertEquals("play hand-1", selected)
    }

    @Test
    fun worthwhileCashCardsArePlayedBeforeCommittingTheLeader() {
        val input =
            observation(
                with = listOf(advance("N", cost = 10, value = 40.0)),
                cashCardValue = 5.0,
            )

        val selected = chooseBotMove(input, model(), "hard", 0)

        assertEquals("play hand-2 commodity stone", selected)
    }

    @Test
    fun anUnwantedCashCardDoesNotDelayAUsefulLeader() {
        val input =
            observation(
                with = listOf(advance("N", cost = 10, value = 40.0)),
                cashCardValue = -5.0,
            )

        val selected = chooseBotMove(input, model(), "hard", 0)

        assertEquals("play hand-1", selected)
    }

    @Test
    fun missingZeroAndNonUnitGatesPreserveLegacyChoicesAcrossDifficulties() {
        val input = observation(cashCardValue = 5.0)
        val legacy = observation(cashCardValue = 5.0, includePlanning = false)

        for (gate in listOf(null, 0.0, 0.5, 2.0)) {
            for (difficulty in listOf("easy", "normal", "hard")) {
                assertEquals(
                    chooseBotMove(legacy, model(gate = gate), difficulty, 17),
                    chooseBotMove(input, model(gate = gate), difficulty, 17),
                )
            }
        }
    }

    @Test
    fun missingOptionalMetadataPreservesTheLegacyLeaderChoice() {
        val selected =
            chooseBotMove(
                observation(includePlanning = false),
                model(),
                "hard",
                0,
            )

        assertEquals("play hand-1", selected)
    }

    @Test
    fun activeLeaderTimingDoesNotChangeNormalDifficulty() {
        val input = observation(cashCardValue = 5.0)
        val legacy = observation(cashCardValue = 5.0, includePlanning = false)

        assertEquals(
            chooseBotMove(legacy, model(), "normal", 17),
            chooseBotMove(input, model(), "normal", 17),
        )
    }

    @Test
    fun invalidCandidatePoolIndicesAreRejectedAtTheJsonBoundary() {
        val input = observation().replace("\"candidates\":[0]", "\"candidates\":[42]")

        assertFails { chooseBotMove(input, model(), "hard", 0) }
    }

    @Test
    fun fractionalCandidatePoolIndicesAreRejectedAtTheJsonBoundary() {
        val input = observation().replace("\"candidates\":[0]", "\"candidates\":[0.5]")

        assertFails { chooseBotMove(input, model(), "hard", 0) }
    }

    @Test
    fun aContinuationCannotReferToTheWrongForecastStage() {
        val input = observation().replace("\"shipping\":0", "\"shipping\":1")

        assertFails { chooseBotMove(input, model(), "hard", 0) }
    }

    private fun model(gate: Double? = 1.0, retention: Double = 0.5): String =
        """{
        "schemaVersion":1,"weights":{
            "discount":1,"cash":1,"utility":1,"planning.retainedLeader":$retention
            ${gate?.let { ",\"planning.leaderTiming\":$it" }.orEmpty()}
        }
    }"""

    private fun candidate(
        command: String,
        features: String = "{}",
        cost: Int = 0,
        family: String = "pass",
        group: String = command,
    ): String =
        """{"command":"$command","features":$features,"cashCost":$cost,
        "tokenCost":0,"group":"$group","family":"$family"}"""

    private fun advance(letter: String, cost: Int, value: Double): String =
        candidate(
            command = "buy-advance $letter",
            features = """{"utility":$value}""",
            cost = cost,
            family = "advance",
            group = "advance.$letter",
        )

    private fun observation(
        without: List<String> = emptyList(),
        with: List<String> = emptyList(),
        cash: Int = 20,
        cashCardValue: Double? = null,
        includePlanning: Boolean = true,
    ): String {
        val pass = candidate("pass")
        val leader =
            candidate(
                command = "play hand-1",
                features = """{"discount":20,"cash":2}""",
                family = "play",
                group = "card.hand-1",
            )
        val cashCard = cashCardValue?.let {
            candidate(
                command = "play hand-2 commodity stone",
                features = """{"utility":$it}""",
                family = "play",
                group = "card.hand-2",
            )
        }
        val pool = (listOf(pass) + without + with).joinToString(",")
        val withoutIndices = (listOf(0) + without.indices.map { it + 1 }).joinToString(",")
        val withIndices = (listOf(0) + with.indices.map { it + without.size + 1 }).joinToString(",")
        val cashCards = if (cashCard == null) "[]" else """["play hand-2 commodity stone"]"""
        val planning =
            if (includePlanning) {
                """"leaderPlanning":{
                "candidates":[$pool],
                "forecasts":[
                    {"stage":"SHIPPING","cash":$cash,"stabilization":0,"candidates":[0]},
                    {"stage":"ADVANCES","cash":$cash,"stabilization":0,"candidates":[$withoutIndices]},
                    {"stage":"ADVANCES","cash":$cash,"stabilization":0,"candidates":[$withIndices]}
                ],
                "leaders":{"play hand-1":{
                    "withoutLeader":{"shipping":0,"advances":{"pass":1}},
                    "withLeader":{"shipping":0,"advances":{"pass":2}},
                    "retainedDiscount":20
                }},
                "cashCards":$cashCards
            },"""
            } else ""
        val offered = listOfNotNull(pass, leader, cashCard).joinToString(",")
        return """{
            "schemaVersion":1,"playerId":"p1","decision":"play-cards",
            "context":{},"availableCash":$cash,"availableTokens":0,
            "rulesVersion":"$RULES_VERSION","dataVersion":"$DATA_VERSION",
            $planning
            "candidates":[$offered]
        }"""
    }
}
