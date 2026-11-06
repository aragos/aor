package dev.aragos.aor.ai

import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BotTargetTieBreakExportsTest {
    @Test
    fun exportedTargetChoiceMatchesJvmSeedGoldens() {
        val seeds = listOf(0, 1, 2, 17, -1, Int.MIN_VALUE, Int.MAX_VALUE, -979738860)
        val targets = listOf("p1", "p1", "p2", "p3", "p1", "p2", "p3", "p2")

        val choices = seeds.map { chooseBotMove(observation(), model(), "hard", it) }

        assertEquals(targets.map(::command), choices)
    }

    @Test
    fun reversingWireCandidateOrderPreservesSeededTargets() {
        val choices =
            listOf(0, 2, 17).map { seed ->
                chooseBotMove(observation(reverse = true), model(), "hard", seed)
            }

        assertEquals(listOf("p1", "p2", "p3").map(::command), choices)
    }

    @Test
    fun theSameSeedAndWirePayloadReproduceTheTarget() {
        val choices = List(10) { chooseBotMove(observation(), model(), "hard", 17) }

        assertEquals(List(10) { command("p3") }, choices)
    }

    @Test
    fun differentSeedsDistributeEqualTargetsAcrossOpponents() {
        val choices = (0 until 300).map { chooseBotMove(observation(), model(), "hard", it) }
        val counts = choices.groupingBy { it }.eachCount()

        assertEquals(setOf("p1", "p2", "p3").map(::command).toSet(), counts.keys)
        assertTrue(counts.values.all { it in 70..130 })
    }

    @Test
    fun disabledGatePreservesLexicalTargetChoice() {
        val selected = chooseBotMove(observation(), model(gate = 0.0), "hard", 17)

        assertEquals(command("p1"), selected)
    }

    @Test
    fun absentGatePreservesLexicalTargetChoice() {
        val selected = chooseBotMove(observation(), "", "hard", 17)

        assertEquals(command("p1"), selected)
    }

    @Test
    fun absentTargetMetadataPreservesLegacyObservationBehavior() {
        val selected = chooseBotMove(observation(targetJson = { null }), model(), "hard", 17)

        assertEquals(command("p1"), selected)
    }

    @Test
    fun nullTargetMetadataPreservesLegacyObservationBehavior() {
        val selected = chooseBotMove(observation(targetJson = { "null" }), model(), "hard", 17)

        assertEquals(command("p1"), selected)
    }

    @Test
    fun normalDifficultyKeepsItsExistingTargetSelection() {
        val selected = chooseBotMove(observation(), model(), "normal", 17)

        assertEquals(command("p1"), selected)
    }

    @Test
    fun nonStringTargetMetadataIsRejectedBeforeInference() {
        for (target in listOf("1", "true", "{}", "[]", "{\"value\":\"p1\"}")) {
            assertFailsWith<IllegalArgumentException> {
                chooseBotMove(observation(targetJson = { target }), model(), "hard", 17)
            }
        }
    }

    @Test
    fun malformedPlayerIdentifiersAreRejectedBeforeInference() {
        for (target in listOf("\"\"", "\"p0\"", "\"p7\"", "\" p1\"")) {
            assertFailsWith<IllegalArgumentException> {
                chooseBotMove(observation(targetJson = { target }), model(), "hard", 17)
            }
        }
    }

    @Test
    fun metadataMustMatchTheCanonicalCommandTarget() {
        assertFailsWith<IllegalArgumentException> {
            chooseBotMove(observation(targetJson = { "\"p2\"" }), model(), "hard", 17)
        }
    }

    private fun command(target: String): String = "play held-war player $target"

    private fun model(gate: Double = 1.0): String =
        """{"schemaVersion":1,"weights":{"planning.targetTieBreak":$gate}}"""

    private fun observation(
        reverse: Boolean = false,
        targetJson: (String) -> String? = { "\"$it\"" },
    ): String {
        val targets = listOf("p1", "p2", "p3").let { if (reverse) it.reversed() else it }
        val candidates =
            targets.joinToString(",") { target ->
                val metadata = targetJson(target)?.let { ",\"playerTarget\":$it" }.orEmpty()
                """{
                "command":"${command(target)}","features":{"cash":1},
                "cashCost":0,"tokenCost":0,"group":"card.held-war","family":"play"$metadata
            }"""
            }
        return """{
            "schemaVersion":1,"playerId":"p4","decision":"play-cards","context":{},
            "availableCash":30,"availableTokens":10,
            "rulesVersion":"$RULES_VERSION","dataVersion":"$DATA_VERSION",
            "candidates":[$candidates]
        }"""
    }
}
