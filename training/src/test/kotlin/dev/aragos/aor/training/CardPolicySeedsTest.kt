package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.Stage
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CardPolicySeedsTest {
    @Test
    fun sha256AddressesMatchIndependentSignedBigEndianGoldens() {
        data class Case(
            val world: Int,
            val replicate: Int,
            val batch: Int,
            val turn: Int,
            val stage: String,
            val decision: String,
            val seat: Int,
            val occurrence: Int,
            val global: Int,
            val semantic: Int,
        )
        val cases =
            listOf(
                Case(71, 0, 17, 1, "PURCHASE", "purchase-advances", 0, 0, 1869720876, -1186629484),
                Case(71, 7, 19, 2, "PLAY_CARDS", "play-cards", 3, 4, 2036998466, 1687019922),
                Case(
                    -250000000,
                    3,
                    555,
                    12,
                    "PURCHASE",
                    "purchase-shipping",
                    2,
                    0,
                    190528078,
                    976665787,
                ),
                Case(
                    Int.MIN_VALUE,
                    4,
                    11999,
                    99,
                    "FINAL_CARD_PLAY",
                    "play-cards",
                    1,
                    17,
                    -1045132172,
                    -1932423310,
                ),
            )
        for (case in cases) {
            val master = CardPolicySeeds.master(case.world, case.replicate)
            val actual =
                listOf(CardPolicySchedule.G, CardPolicySchedule.S).map { schedule ->
                    CardPolicySeeds.hashedSeed(
                        master,
                        schedule,
                        case.batch,
                        case.turn,
                        case.stage,
                        case.decision,
                        case.seat,
                        case.occurrence,
                    )
                }
            assertThat(actual).containsExactly(case.global, case.semantic).inOrder()
        }
    }

    @Test
    fun forcedRootRecordsActualLegacySeedForEverySchedule() {
        for (schedule in CardPolicySchedule.entries) {
            val seeds = CardPolicySeeds(-71, 7, schedule)
            seeds.reserveRoot(130, 4, 2)

            assertThat(seeds.trace.single())
                .isEqualTo(
                    CardPolicySeedTrace(
                        130,
                        130,
                        4,
                        "PLAY_CARDS",
                        "play-cards",
                        2,
                        0,
                        CardAdvantageExecutor.policySeed(-71, 130, 2),
                        true,
                    )
                )
        }
    }

    @Test
    fun extraCardDecisionDoesNotShiftSemanticPurchaseOrNextTurnSeeds() {
        val ordinary = CardPolicySeeds(71, 2, CardPolicySchedule.S)
        val extra = CardPolicySeeds(71, 2, CardPolicySchedule.S)
        ordinary.reserveRoot(10, 1, 0)
        extra.reserveRoot(10, 1, 0)
        extra.seed(11, 11, 1, Stage.PLAY_CARDS, "play-cards", 0)
        val ordinaryShipping = ordinary.seed(11, 11, 1, Stage.PURCHASE, "purchase-shipping", 0)
        val extraShipping = extra.seed(12, 12, 1, Stage.PURCHASE, "purchase-shipping", 0)
        val ordinaryNextTurn = ordinary.seed(12, 12, 2, Stage.PLAY_CARDS, "play-cards", 0)
        val extraNextTurn = extra.seed(13, 13, 2, Stage.PLAY_CARDS, "play-cards", 0)

        assertThat(extraShipping).isEqualTo(ordinaryShipping)
        assertThat(extraNextTurn).isEqualTo(ordinaryNextTurn)
        assertThat(extra.trace.map { it.occurrence }).containsExactly(0, 1, 0, 0).inOrder()
    }

    @Test
    fun globalScheduleRetainsCommandCountDrift() {
        val ordinary = CardPolicySeeds(71, 2, CardPolicySchedule.G)
        val extra = CardPolicySeeds(71, 2, CardPolicySchedule.G)
        ordinary.reserveRoot(10, 1, 0)
        extra.reserveRoot(10, 1, 0)
        extra.seed(11, 11, 1, Stage.PLAY_CARDS, "play-cards", 0)

        val left = ordinary.seed(11, 11, 1, Stage.PURCHASE, "purchase-shipping", 0)
        val right = extra.seed(12, 12, 1, Stage.PURCHASE, "purchase-shipping", 0)

        assertThat(left).isNotEqualTo(right)
    }

    @Test
    fun nestedAndFinalDecisionsDoNotReuseOrdinaryCardBuckets() {
        val seeds = CardPolicySeeds(71, 0, CardPolicySchedule.S)
        seeds.reserveRoot(10, 1, 0)
        seeds.seed(11, 11, 1, Stage.PLAY_CARDS, "prepare-war", 0)
        seeds.seed(12, 12, 1, Stage.PLAY_CARDS, "play-cards", 0)
        seeds.seed(13, 13, 1, Stage.FINAL_CARD_PLAY, "play-cards", 0)

        assertThat(seeds.trace.map { it.occurrence }).containsExactly(0, 0, 1, 0).inOrder()
        assertThat(seeds.trace.map { it.seed }.distinct()).hasSize(4)
    }

    @Test
    fun sealedBatchRetainsCommonIndexAndSeparateActorAddresses() {
        val seeds = CardPolicySeeds(Int.MIN_VALUE, 1, CardPolicySchedule.L)
        seeds.reserveRoot(10, 1, 0)
        seeds.seed(11, 11, 2, Stage.TOKEN_PURCHASE, "buy-tokens", 0)
        seeds.seed(12, 11, 2, Stage.TOKEN_PURCHASE, "buy-tokens", 2)

        assertThat(seeds.trace.drop(1).map { it.batchStart }).containsExactly(11, 11)
        assertThat(seeds.trace.drop(1).map { it.seed })
            .containsExactly(
                CardAdvantageExecutor.policySeed(Int.MIN_VALUE, 11, 0),
                CardAdvantageExecutor.policySeed(Int.MIN_VALUE, 11, 2),
            )
            .inOrder()
    }

    @Test
    fun sealedBatchCannotChangeItsPreStateContext() {
        val seeds = CardPolicySeeds(71, 0, CardPolicySchedule.S)
        seeds.reserveRoot(10, 1, 0)
        seeds.seed(11, 11, 2, Stage.TOKEN_PURCHASE, "buy-tokens", 0)

        assertFailsWith<IllegalArgumentException> {
            seeds.seed(12, 11, 3, Stage.TOKEN_PURCHASE, "buy-tokens", 2)
        }
    }

    @Test
    fun traceCannotSkipAbsoluteCommands() {
        val seeds = CardPolicySeeds(71, 0, CardPolicySchedule.S)
        seeds.reserveRoot(10, 1, 0)

        assertFailsWith<IllegalArgumentException> {
            seeds.seed(12, 12, 1, Stage.PURCHASE, "purchase-shipping", 0)
        }
    }

    @Test
    fun eightChanceStartsPreserveHistoricalFirstFourWithoutCrossWorldOverlap() {
        val worlds = listOf(Int.MIN_VALUE, -71, 0, 71, Int.MAX_VALUE)
        val starts = worlds.flatMap { world ->
            (0..7).map { CardPolicySeeds.chanceSeed(world, it) }
        }

        assertThat(starts.distinct()).hasSize(40)
        for (world in worlds) {
            assertThat((0..3).map { CardPolicySeeds.chanceSeed(world, it) })
                .isEqualTo((0..3).map { CardAdvantageExecutor.chanceSeed(world, it) })
        }
    }
}
